package dev.klaiber.cirrus.domain.agents

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The clock agents actually run on.
 *
 * WorkManager used to be both the clock and the runner, and it is a poor clock. Its whole design is
 * to be a good citizen — it batches, it defers, and under Doze it will hold a job for hours to line
 * it up with a maintenance window. That is exactly right for syncing a mailbox and exactly wrong
 * for the one thing agents promise, which is "07:30 on weekdays". A phone left alone overnight is
 * in deep Doze at 07:30 by definition, so the case the feature exists for was the case most likely
 * to be deferred: the briefing arrived when the phone was next picked up, which for most people is
 * not the same thing at all and reads as the agent simply not having run.
 *
 * `AlarmManager` is the API for "at this time", and `setAndAllowWhileIdle` is the variant that Doze
 * does not hold. So the alarm is the clock and WorkManager stays the runner — it keeps the retry
 * chain, the network constraint and the Hilt worker factory, none of which is worth rebuilding.
 * The alarm fires, [AgentAlarmReceiver] enqueues the worker, and the worker books the next alarm.
 *
 * **The inexact variant is the contract, and no permission is asked for.**
 * `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM`, which since Android 12 the user has to
 * grant on a system screen and which app stores treat as reserved for alarm clocks and calendars.
 * So the exact call is used only where the platform already allows it — every version below 12, and
 * above it whoever has granted the permission for their own reasons — and the plain
 * `setAndAllowWhileIdle` is what everyone else gets. It needs nothing, is still exempt from Doze,
 * and lands within a few minutes. A briefing at 07:32 is the feature working; a briefing at 11:00
 * because the phone was idle is not, and that is the distance actually being closed here.
 *
 * Alarms do not survive a reboot, which WorkManager's queue did. `BootReceiver` puts them back.
 */
@Singleton
class AgentAlarms @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    /** Books [agentId] to fire at [triggerAtMillis], replacing whatever was booked for it. */
    fun set(agentId: String, triggerAtMillis: Long) {
        val alarms = manager ?: return
        val intent = pendingIntent(agentId, mutable = false)

        // Wall clock rather than elapsed time: an agent is due at a time of day, so a clock
        // correction or a timezone change should move it, which is what RTC means and what
        // ELAPSED_REALTIME would get wrong by exactly the size of the correction.
        //
        // Exact if the platform already permits it — below Android 12 it does, and above it some
        // users have granted it for other reasons — but never by asking for the permission. The
        // inexact variant is the contract: it escapes Doze, which is the whole of the bug, and
        // lands within a few minutes. `runCatching` because `canScheduleExactAlarms` can go from
        // true to false between the check and the call, and it throws when it does.
        runCatching {
            if (canBeExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            }
        }.onFailure {
            runCatching {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            }
        }
    }

    fun cancel(agentId: String) {
        manager?.cancel(pendingIntent(agentId, mutable = false))
    }

    /**
     * One `PendingIntent` per agent, distinguished by its request code and its data.
     *
     * The request code alone is not enough: `PendingIntent` compares intents ignoring their extras,
     * so two agents whose ids hash to the same code would silently share one booking and one of
     * them would never fire. The id goes in the *data* URI, which is compared.
     */
    private fun pendingIntent(agentId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, AgentAlarmReceiver::class.java).apply {
            action = ACTION_RUN_AGENT
            data = android.net.Uri.parse("cirrus-agent://$agentId")
            putExtra(AgentScheduler.KEY_AGENT_ID, agentId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, agentId.hashCode(), intent, flags)
    }

    companion object {
        const val ACTION_RUN_AGENT = "dev.klaiber.cirrus.RUN_AGENT"

        /** True when the platform would let us be exact, which is only ever a bonus here. */
        fun canBeExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }
}

/**
 * The alarm going off: hand the run to WorkManager and get out of the way.
 *
 * A receiver has about ten seconds before the system considers it hung, and a generation takes
 * minutes, so nothing is run here. Enqueuing is the whole job. It is expedited because the user
 * chose this minute and a queued-behind-something briefing is the problem this file is fixing, and
 * it keeps a unique name per agent so a duplicate alarm cannot produce two simultaneous runs — one
 * agent at a time is load-bearing, since `SendNotificationTool` carries the conversation its
 * notification should open.
 */
class AgentAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val agentId = intent.getStringExtra(AgentScheduler.KEY_AGENT_ID)
            ?: intent.data?.host
            ?: return

        val request = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(Data.Builder().putString(AgentScheduler.KEY_AGENT_ID, agentId).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(AgentScheduler.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            AgentScheduler.workName(agentId),
            // KEEP: if a run is somehow already going for this agent, the alarm has arrived on top
            // of it and starting a second would give two runs each other's notification thread.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

/**
 * Alarms are lost on reboot; the schedule is not.
 *
 * This is the part WorkManager gave for free and `AlarmManager` does not. Without it, a phone
 * restarted at 2am has no agents booked at all until somebody opens Cirrus — which is the same
 * silent stop this whole change is about, arriving by a different route.
 *
 * The re-booking goes through a worker rather than happening here, for the reason above: reading
 * the agent store is disk work, and a receiver is not the place for it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
                .addTag(AgentScheduler.TAG)
                .build(),
        )
    }

    companion object {
        const val SYNC_WORK_NAME = "cirrus-agent-sync"
    }
}
