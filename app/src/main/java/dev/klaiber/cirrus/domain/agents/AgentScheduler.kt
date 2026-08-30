package dev.klaiber.cirrus.domain.agents

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts agents on the clock.
 *
 * Each agent is booked as an alarm at its next due time, and re-books itself after it runs.
 *
 * The alarm is [AgentAlarms]; this class owns *when*, and it owns the queue matching the store.
 * WorkManager still runs the generation once the alarm has fired, because it has the retry chain,
 * the network constraint and the Hilt worker factory — but it is no longer the clock. It was, and
 * it was the reason agents did not fire: WorkManager defers under Doze, a phone left alone
 * overnight is in Doze at 07:30 by definition, and the run arrived whenever the phone was next
 * picked up. Periodic work would have been worse still, with a fifteen-minute floor and drift
 * against the wall clock.
 */
@Singleton
class AgentScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agents: AgentRepository,
    private val alarms: AgentAlarms,
) {

    /**
     * Called at startup and whenever an agent changes, so the queue always matches the store.
     *
     * It also cancels what should no longer fire. `pruneWork` only discards work that has already
     * finished, so an agent switched off while the app was dead used to keep its booking and run
     * anyway — the one failure mode where a switch marked "off" does the thing anyway.
     */
    suspend fun syncAll() {
        val manager = WorkManager.getInstance(context)
        agents.all().forEach { agent ->
            if (agent.isScheduled) {
                schedule(agent)
            } else {
                alarms.cancel(agent.id)
                manager.cancelUniqueWork(workName(agent.id))
            }
        }
        // Runs that were killed rather than finished — by a reboot, or by the platform reclaiming
        // the app mid-generation — are still marked as in progress. Close them out, or the agents
        // screen shows a spinner for something that stopped days ago.
        agents.failInterruptedRuns(System.currentTimeMillis() - STALE_RUN_MS)
        manager.pruneWork()
    }

    fun schedule(agent: Agent) {
        if (!agent.isScheduled) {
            cancel(agent.id)
            return
        }

        val dueAt = nextRunAt(agent)
        if (dueAt == null) {
            cancel(agent.id)
            return
        }

        // Only the alarm is set here. Enqueuing the work as well — which is what this used to do,
        // with a delay and `ExistingWorkPolicy.REPLACE` — had a second problem beyond Doze: the
        // worker re-books itself at the end of its own run, under the same unique name it is
        // running as, and REPLACE cancels running work. The worker was cancelling itself on its
        // way out. With the alarm as the clock, nothing enqueues delayed work at all.
        alarms.set(agent.id, dueAt)
    }

    fun cancel(agentId: String) {
        alarms.cancel(agentId)
        WorkManager.getInstance(context).cancelUniqueWork(workName(agentId))
    }

    /** Runs an agent now, outside its schedule, without disturbing the next scheduled run. */
    fun runNow(agentId: String) {
        val request = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_AGENT_ID, agentId)
                    .putBoolean(KEY_MANUAL, true)
                    .build(),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            // KEEP, not REPLACE: tapping "run now" twice means someone is impatient, not that they
            // want the first run cancelled halfway and started again.
            .enqueueUniqueWork(manualName(agentId), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val KEY_AGENT_ID = "agentId"
        const val KEY_MANUAL = "manual"
        const val TAG = "cirrus-agent"

        /** Two quick attempts is the whole retry budget; see [AgentWorker]. */
        const val MAX_ATTEMPTS = 3
        private const val BACKOFF_SECONDS = 30L

        /** Longer than any run can legitimately take, including its own timeout and retries. */
        private const val STALE_RUN_MS = 30L * 60 * 1000

        fun workName(agentId: String) = "agent-$agentId"

        private fun manualName(agentId: String) = "agent-now-$agentId"

        /**
         * Milliseconds until this agent is next due.
         *
         * Walks forward a day at a time from today, which handles "later today", "tomorrow" and
         * "not until next Tuesday" with the same three lines — and, because it works in
         * [LocalDateTime], gets daylight saving right by construction: 07:30 stays 07:30.
         */
        fun delayUntilNextRun(
            agent: Agent,
            now: LocalDateTime = LocalDateTime.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long {
            if (agent.days.isEmpty()) return Long.MAX_VALUE
            val time = now.toLocalDate().atStartOfDay().plusMinutes(agent.minuteOfDay.toLong())

            for (offset in 0..DAYS_AHEAD) {
                val candidate = time.plusDays(offset.toLong())
                val day = DayOfWeek.of(candidate.dayOfWeek.value)
                if (day in agent.days && candidate.isAfter(now)) {
                    return candidate.atZone(zone).toInstant().toEpochMilli() -
                        now.atZone(zone).toInstant().toEpochMilli()
                }
            }
            return Long.MAX_VALUE
        }

        /** When this agent next runs, as a wall-clock instant, or null if it never does. */
        fun nextRunAt(
            agent: Agent,
            now: LocalDateTime = LocalDateTime.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long? {
            if (!agent.isScheduled) return null
            val delay = delayUntilNextRun(agent, now, zone)
            if (delay == Long.MAX_VALUE) return null
            return now.atZone(zone).toInstant().toEpochMilli() + delay
        }

        private const val DAYS_AHEAD = 8
    }
}

/**
 * The scheduled run itself, enqueued by [AgentAlarmReceiver] once the alarm has gone off.
 *
 * Re-books before returning, so the chain continues: the alarm that started this one is spent, and
 * nothing else will set tomorrow's. That makes the `finally`-shaped guarantee below the important
 * part of this class — an agent that stops being booked because of one bad morning is the failure
 * the whole file exists to avoid.
 */
@HiltWorker
class AgentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: AgentRunner,
    private val agents: AgentRepository,
    private val scheduler: AgentScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val agentId = inputData.getString(AgentScheduler.KEY_AGENT_ID) ?: return Result.failure()
        val manual = inputData.getBoolean(AgentScheduler.KEY_MANUAL, false)

        val outcome = try {
            runner.run(
                agentId = agentId,
                trigger = if (manual) AgentRunTrigger.MANUAL else AgentRunTrigger.SCHEDULED,
            )
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (error: Throwable) {
            // Nothing should reach here — the runner records its own failures — but an exception
            // escaping this method skips the re-booking below, and an agent that stops being
            // scheduled because of one bad morning is the failure this whole file exists to avoid.
            AgentRunner.Outcome.Failed(error.message ?: "The run failed unexpectedly.")
        }

        // A dropped socket at 07:30 used to mean no briefing that day: every failure was final.
        // Two more attempts, thirty seconds apart, costs nothing when the network is simply back —
        // and a rejected key is deliberately *not* retryable, because burning three generations to
        // rediscover that the key is still wrong helps nobody.
        val retrying = outcome is AgentRunner.Outcome.Retryable &&
            runAttemptCount < AgentScheduler.MAX_ATTEMPTS - 1

        // Still not while retrying: `schedule` now sets an alarm rather than enqueuing work, so it
        // no longer cancels the retry — but booking tomorrow's run before today's has finished
        // retrying would be booking it from a state that may still change.
        if (!manual && !retrying) {
            agents.byId(agentId)?.let(scheduler::schedule)
        }

        return when {
            retrying -> Result.retry()
            outcome is AgentRunner.Outcome.Finished -> Result.success()
            // The failure is already recorded on the agent, and on the run, for the user to see.
            else -> Result.failure()
        }
    }
}

/**
 * Puts every agent's alarm back after a reboot or an update.
 *
 * Alarms do not survive either, where WorkManager's queue did — so without this a phone restarted
 * overnight has nothing booked until somebody opens Cirrus, which is the same silent stop the alarm
 * clock was introduced to fix, arriving by a different route.
 *
 * A worker rather than work done in `BootReceiver` itself: this reads the agent store, and a
 * broadcast receiver has about ten seconds and no business touching a database.
 */
@HiltWorker
class ScheduleSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: AgentScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        scheduler.syncAll()
        return Result.success()
    }
}
