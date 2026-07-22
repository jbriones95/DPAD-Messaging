package com.dpad.messaging.helpers

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.models.Message
import com.dpad.messaging.receivers.ScheduledMessageReceiver

object ScheduledMessageScheduler {

    sealed class ScheduleResult {
        data object ScheduledExact : ScheduleResult()
        data object ScheduledInexact : ScheduleResult()
        data class Failed(val reason: FailureReason) : ScheduleResult()
    }

    enum class FailureReason {
        ALARM_SERVICE_UNAVAILABLE,
        MISSING_EXACT_ALARM_PERMISSION,
        SECURITY_EXCEPTION,
        UNKNOWN
    }

    fun canScheduleExact(context: Context): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun scheduleMessage(context: Context, messageId: Long, triggerAtMillis: Long): ScheduleResult {
        if (messageId <= 0L) return ScheduleResult.Failed(FailureReason.UNKNOWN)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: return ScheduleResult.Failed(FailureReason.ALARM_SERVICE_UNAVAILABLE)
        val pendingIntent = scheduledMessagePendingIntent(context, messageId)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                ScheduleResult.ScheduledInexact
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                ScheduleResult.ScheduledExact
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                ScheduleResult.ScheduledExact
            }
        } catch (se: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    ScheduleResult.ScheduledInexact
                } catch (_: SecurityException) {
                    ScheduleResult.Failed(FailureReason.SECURITY_EXCEPTION)
                }
            } else {
                ScheduleResult.Failed(FailureReason.SECURITY_EXCEPTION)
            }
        } catch (_: Exception) {
            ScheduleResult.Failed(FailureReason.UNKNOWN)
        }
    }

    fun cancelMessage(context: Context, messageId: Long): Boolean {
        if (messageId <= 0L) return false
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            alarmManager.cancel(scheduledMessagePendingIntent(context, messageId))
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun scheduleAndPersistStatus(
        context: Context,
        message: Message,
        triggerAtMillis: Long,
        failedType: Int = Message.TYPE_FAILED,
        failedStatus: Int = Message.STATUS_FAILED
    ): ScheduleResult {
        val result = scheduleMessage(context, message.id, triggerAtMillis)
        val dao = com.dpad.messaging.App.get().database.messagesDao()
        when (result) {
            is ScheduleResult.Failed -> {
                dao.updateMessage(
                    message.copy(
                        type = failedType,
                        status = failedStatus,
                        isScheduled = false,
                        scheduledDate = null
                    )
                )
                showScheduleFailureNotification(context)
            }

            ScheduleResult.ScheduledInexact -> {
                showInexactScheduleNotification(context)
            }

            ScheduleResult.ScheduledExact -> Unit
        }
        return result
    }

    private fun showScheduleFailureNotification(context: Context) {
        val accent = ThemeManager.accentColor(context)
        val notification = NotificationCompat.Builder(context, App.CHANNEL_SEND_FAILURE)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(accent)
            .setColorized(true)
            .setContentTitle(context.getString(R.string.scheduled_send_failed_title))
            .setContentText(context.getString(R.string.scheduled_send_failed_exact_alarm_body))
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(80_001, notification)
    }

    private fun showInexactScheduleNotification(context: Context) {
        val accent = ThemeManager.accentColor(context)
        val notification = NotificationCompat.Builder(context, App.CHANNEL_SEND_FAILURE)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(accent)
            .setColorized(true)
            .setContentTitle(context.getString(R.string.scheduled_send_inexact_title))
            .setContentText(context.getString(R.string.scheduled_send_inexact_body))
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(80_002, notification)
    }

    internal fun scheduledMessagePendingIntent(context: Context, messageId: Long): PendingIntent {
        val requestCode = (messageId and 0x7FFFFFFF).toInt()
        val intent = Intent(context, ScheduledMessageReceiver::class.java).apply {
            putExtra(ScheduledMessageReceiver.EXTRA_SCHEDULED_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
