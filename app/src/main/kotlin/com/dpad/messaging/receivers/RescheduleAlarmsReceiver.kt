package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dpad.messaging.App
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.ScheduledMessageScheduler
import kotlinx.coroutines.launch

/**
 * Re-registers all pending scheduled-message alarms after boot,
 * time-zone changes, or app updates — because AlarmManager alarms
 * do not survive a device reboot.
 *
 * Phase 2: query Room for all scheduled messages with future dates
 * and re-register alarms with exact/inexact fallback handling.
 */
class RescheduleAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) com.dpad.messaging.dumber.DumberSyncService.ensureStarted(context)
        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                reschedulePending(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        suspend fun reschedulePending(context: Context) {
            val now = System.currentTimeMillis()
            val dao = App.get().database.messagesDao()
            val pending = dao.getScheduledMessagesAfter(now)
            pending.forEach { message ->
                val triggerAt = message.scheduledDate ?: return@forEach
                ScheduledMessageScheduler.scheduleAndPersistStatus(
                    context = context,
                    message = message,
                    triggerAtMillis = triggerAt
                )
            }
        }
    }
}
