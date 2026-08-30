package dev.klaiber.cirrus.domain.agents

import dev.klaiber.cirrus.data.repository.AgentRepository
import dev.klaiber.cirrus.domain.model.Agent
import dev.klaiber.cirrus.domain.model.AgentRunTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts agents on the clock.
 *
 * Android books each agent as a one-shot WorkManager request that re-books itself, because periodic
 * work there has a fifteen-minute floor and drifts against the wall clock — useless for "07:30 on
 * weekdays". The desktop has no work manager to argue with, so the same shape is a coroutine per
 * agent that sleeps until the agent is due, runs it, and books the next one.
 *
 * The behavioural difference worth being explicit about: **agents only run while Cirrus is open.**
 * WorkManager persists its queue, so a phone that was asleep at 07:30 fires the run late; a desktop
 * app that was not running simply missed it, and the next occurrence is booked instead. Firing a
 * fortnight of missed briefings at launch would be worse than skipping them.
 *
 * A machine that was *asleep* rather than closed is the case in between, and it is handled: the
 * wait is against the wall clock rather than a duration (see [sleepUntil]), so a lid opened at
 * 07:45 still produces the 07:30 run, while anything older than [LATE_GRACE_MS] is skipped for the
 * same reason a fortnight of them would be.
 */
@Singleton
class AgentScheduler @Inject constructor(
    private val agents: AgentRepository,
    private val runner: AgentRunner,
    private val scope: CoroutineScope,
) {

    /** One sleeping coroutine per scheduled agent. Replacing a booking cancels the old one. */
    private val bookings = mutableMapOf<String, Job>()
    private val lock = Mutex()

    /**
     * Called at startup and whenever an agent changes, so the queue always matches the store.
     *
     * It also cancels what should no longer fire. An agent switched off has its booking dropped
     * here rather than left to notice at wake-up — the one failure mode where a switch marked
     * "off" does the thing anyway.
     */
    suspend fun syncAll() {
        agents.all().forEach { agent ->
            if (agent.isScheduled) schedule(agent) else cancel(agent.id)
        }
        // Runs that were killed rather than finished — a crash, a quit mid-generation, a machine
        // that slept through the run's own timeout — are still marked as in progress. Close them
        // out, or the agents screen shows a spinner for something that stopped days ago.
        agents.failInterruptedRuns(System.currentTimeMillis() - STALE_RUN_MS)
    }

    fun schedule(agent: Agent) {
        scope.launch {
            lock.withLock {
                bookings.remove(agent.id)?.cancel()
                if (!agent.isScheduled) return@withLock
                // An absolute instant, not a duration. See [sleepUntil] for why that distinction
                // is the difference between an agent that fires and one that does not.
                val dueAt = nextRunAt(agent) ?: return@withLock
                bookings[agent.id] = scope.launch { sleepThenRun(agent.id, dueAt) }
            }
        }
    }

    fun cancel(agentId: String) {
        scope.launch { lock.withLock { bookings.remove(agentId)?.cancel() } }
    }

    /** Runs an agent now, outside its schedule, without disturbing the next scheduled run. */
    fun runNow(agentId: String) {
        scope.launch { attempt(agentId, AgentRunTrigger.MANUAL) }
    }

    /**
     * Sleeps until due, runs, then books the next occurrence.
     *
     * The re-booking is in a `finally` so it survives the run throwing: an agent that stops being
     * scheduled because of one bad morning is the failure this whole file exists to avoid. It is
     * skipped only on cancellation, which is what `cancel` and a replaced booking both do.
     *
     * A run whose moment passed while the machine was asleep is taken if it is recent and dropped
     * if it is not. [LATE_GRACE_MS] is the line, and it is drawn where it is because the two cases
     * are genuinely different: a laptop shut overnight and opened at nine should still produce the
     * 07:30 briefing, and one opened after a fortnight away should not produce fourteen of them at
     * once — which is the behaviour the class comment promises.
     */
    private suspend fun sleepThenRun(agentId: String, dueAt: Long) {
        sleepUntil(dueAt)
        try {
            if (isStillWorthRunning(dueAt)) attempt(agentId, AgentRunTrigger.SCHEDULED)
        } finally {
            // Out of the map before re-booking: `schedule` cancels whatever booking it finds
            // there, and the one it would find here is this coroutine, in its own `finally`.
            lock.withLock { bookings.remove(agentId) }
            // Re-read rather than reuse: the agent may have been edited while this one slept.
            agents.byId(agentId)?.let(::schedule)
        }
    }

    /**
     * Waits until a wall-clock instant, rather than for a duration.
     *
     * This is the bug that stopped desktop agents firing, and it is invisible in a test that never
     * suspends the machine. `delay` is measured on a *monotonic* clock, which does not advance
     * while a laptop is asleep — so an agent booked at 23:00 to fire in eight and a half hours
     * still believed it had eight and a half hours to go when the lid opened at 07:45, and went off
     * some time that afternoon. Every desktop agent scheduled overnight, which is most of them, was
     * affected.
     *
     * Sleeping in slices and re-reading the wall clock each time fixes it in both directions: a
     * machine that slept through the moment notices as soon as it wakes, and one whose clock is
     * corrected — a timezone change, an NTP step — reconverges instead of firing an hour out.
     */
    private suspend fun sleepUntil(dueAt: Long) {
        while (true) {
            val remaining = dueAt - System.currentTimeMillis()
            if (remaining <= 0) return
            delay(remaining.coerceAtMost(MAX_SLICE_MS))
        }
    }

    /**
     * One run, with two more tries if the failure looks transient.
     *
     * A dropped socket at 07:30 used to mean no briefing that day: every failure was final. Two
     * more attempts, thirty seconds apart, costs nothing when the network is simply back — and a
     * rejected key is deliberately *not* retryable, because burning three generations to
     * rediscover that the key is still wrong helps nobody.
     */
    private suspend fun attempt(agentId: String, trigger: AgentRunTrigger) {
        repeat(MAX_ATTEMPTS) { attempt ->
            val outcome = try {
                runner.run(agentId = agentId, trigger = trigger)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (error: Throwable) {
                // Nothing should reach here — the runner records its own failures — but an
                // exception escaping would skip the re-booking in the caller's `finally`.
                AgentRunner.Outcome.Failed(error.message ?: "The run failed unexpectedly.")
            }

            if (outcome !is AgentRunner.Outcome.Retryable) return
            if (attempt == MAX_ATTEMPTS - 1) return
            delay(BACKOFF_MS shl attempt)
        }
    }

    companion object {

        /** Two quick attempts is the whole retry budget. */
        const val MAX_ATTEMPTS = 3

        /**
         * The longest a single sleep may be before the wall clock is consulted again.
         *
         * Short enough that a machine waking from suspend notices within a minute, long enough
         * that an agent booked six days out costs a few thousand no-op wake-ups rather than
         * anything measurable.
         */
        private const val MAX_SLICE_MS = 60_000L

        /**
         * How late a missed run may be and still be worth doing.
         *
         * Six hours: a briefing you were asleep for is still this morning's briefing, and one from
         * last week is not.
         */
        private const val LATE_GRACE_MS = 6L * 60 * 60 * 1000

        /**
         * Whether a run whose moment has already passed should still happen.
         *
         * Split out as a predicate because it is the one part of the sleep-and-wake path that can
         * be asserted without a clock: everything else is `delay`, and the bug it exists for —
         * a laptop suspended across the run's time — is not reproducible in a unit test.
         */
        fun isStillWorthRunning(dueAt: Long, now: Long = System.currentTimeMillis()): Boolean =
            now - dueAt <= LATE_GRACE_MS
        private const val BACKOFF_MS = 30_000L

        /** Longer than any run can legitimately take, including its own timeout and retries. */
        private const val STALE_RUN_MS = 30L * 60 * 1000

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
