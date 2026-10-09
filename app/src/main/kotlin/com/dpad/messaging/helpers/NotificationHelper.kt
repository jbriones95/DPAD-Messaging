package com.dpad.messaging.helpers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.activities.ThreadActivity
import com.dpad.messaging.receivers.DirectReplyReceiver
import com.dpad.messaging.receivers.MarkAsReadReceiver

/**
 * Builds and posts notifications for incoming messages.
 *
 * One notification per thread with a stable, positive integer identifier.
 * Each notification has:
 *   • Tap           → opens ThreadActivity
 *   • Reply action  → DirectReplyReceiver (inline reply via RemoteInput)
 *   • Mark as Read  → MarkAsReadReceiver
 */
object NotificationHelper {

    const val REPLY_KEY = "reply_key"
    const val EXTRA_PHONE_NUMBER = "extra_phone_number"
    private const val FAILURE_NOTIFICATION_OFFSET = 1_000_000_000
    private const val PENDING_MMS_NOTIFICATION_OFFSET = 2_100_000_000

    fun threadNotificationId(threadId: Long): Int {
        val mixed = threadId xor (threadId ushr 32)
        return (mixed.toInt() and 0x7FFFFFFF).coerceAtLeast(1)
    }

    fun failureNotificationId(messageId: Long): Int {
        val mixed = messageId xor (messageId ushr 32)
        return FAILURE_NOTIFICATION_OFFSET + (mixed.toInt() and 0x3FFFFFFF)
    }

    fun showIncomingNotification(
        context: Context,
        threadId: Long,
        senderName: String,
        phoneNumber: String,
        body: String
    ) {
        if (Prefs.get().isThreadMuted(threadId)) return

        val notifId = threadNotificationId(threadId)

        // ── Tap → ThreadActivity ──────────────────────────────────────────────
        val openPI = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, ThreadActivity::class.java).apply {
                putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                putExtra(ThreadActivity.EXTRA_THREAD_TITLE, senderName)
                putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Reply action ──────────────────────────────────────────────────────
        val remoteInput = RemoteInput.Builder(REPLY_KEY)
            .setLabel(context.getString(R.string.reply))
            .build()

        val replyPI = PendingIntent.getBroadcast(
            context,
            notifId,
            Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(MarkAsReadReceiver.EXTRA_THREAD_ID, threadId)
                putExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            },
            // MUTABLE is required for RemoteInput on API 31+
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send,
            context.getString(R.string.reply),
            replyPI
        ).addRemoteInput(remoteInput).build()

        // ── Mark as Read action ───────────────────────────────────────────────
        val markReadPI = PendingIntent.getBroadcast(
            context,
            notifId + 10_000,
            Intent(context, MarkAsReadReceiver::class.java).apply {
                putExtra(MarkAsReadReceiver.EXTRA_THREAD_ID, threadId)
                putExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadAction = NotificationCompat.Action(
            0,
            context.getString(R.string.mark_as_read),
            markReadPI
        )

        // ── Build & post ──────────────────────────────────────────────────────
        val accentColor = ThemeManager.accentColor(context)
        val sender = Person.Builder().setName(senderName).build()
        val silentUnknownSender = Prefs.get().silentUnknownSenders &&
            (senderName.isBlank() || senderName.trim() == phoneNumber.trim())
        val messageStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(body, System.currentTimeMillis(), sender)
        val builder = NotificationCompat.Builder(context, App.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(accentColor)
            .setColorized(true)
            .setContentTitle(senderName)
            .setContentText(body)
            .setStyle(messageStyle)
            .setContentIntent(openPI)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setSilent(silentUnknownSender)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .addAction(markReadAction)

        // Android O moved alert behavior to notification channels. Older devices
        // need the defaults explicitly set on each notification.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O &&
            !silentUnknownSender
        ) {
            builder.setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        }

        // Apply lock screen privacy setting.
        when (Prefs.get().lockScreenPrivacy) {
            Prefs.PRIVACY_SENDER_ONLY -> {
                // Show only sender on lock screen; body is hidden.
                val publicNotif = NotificationCompat.Builder(context, App.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_new_message)
                    .setColor(accentColor)
                    .setColorized(true)
                    .setContentTitle(senderName)
                    .setContentText(context.getString(R.string.new_message))
                    .build()
                builder
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicNotif)
            }
            Prefs.PRIVACY_NONE -> {
                val publicNotif = NotificationCompat.Builder(context, App.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_new_message)
                    .setColor(accentColor)
                    .setColorized(true)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.new_message))
                    .build()
                builder
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicNotif)
            }
            else -> {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }
        }

        context.getSystemService(NotificationManager::class.java)
            .notify(notifId, builder.build())
    }

    fun cancelNotification(context: Context, notifId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notifId)
    }

    fun pendingMmsNotificationId(messageId: Long): Int {
        val mixed = messageId xor (messageId ushr 32)
        return PENDING_MMS_NOTIFICATION_OFFSET + (mixed.toInt() and 0x00FFFFFF)
    }

    fun showPendingMmsNotification(context: Context, messageId: Long, subscriptionId: Int) {
        if (messageId <= 0L) return
        val notificationId = pendingMmsNotificationId(messageId)
        val downloadIntent = MmsDownloadPolicy.pendingDownloadIntent(context, messageId, subscriptionId)
        val downloadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, App.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(ThemeManager.accentColor(context))
            .setContentTitle(context.getString(R.string.mms_download_pending_title))
            .setContentText(context.getString(R.string.mms_download_pending_text))
            .setContentIntent(downloadPendingIntent)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.download_mms), downloadPendingIntent)

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            builder.setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        }
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, builder.build())
    }

    fun cancelPendingMmsNotification(context: Context, messageId: Long) {
        if (messageId > 0L) cancelNotification(context, pendingMmsNotificationId(messageId))
    }

    fun cancelFailureNotification(context: Context, messageId: Long) {
        cancelNotification(context, failureNotificationId(messageId))
    }

    fun showSendFailureNotification(
        context: Context,
        messageId: Long,
        threadId: Long,
        phoneNumber: String,
        reason: String
    ) {
        if (messageId <= 0L || threadId <= 0L) return

        val failureId = failureNotificationId(messageId)
        val openIntent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
            putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, phoneNumber)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            failureId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retryIntent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
            putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(ThreadActivity.EXTRA_RETRY_MESSAGE_ID, messageId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val retryPendingIntent = PendingIntent.getActivity(
            context,
            failureId + 1,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accentColor = ThemeManager.accentColor(context)
        val builder = NotificationCompat.Builder(context, App.CHANNEL_SEND_FAILURE)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(accentColor)
            .setContentTitle(context.getString(R.string.message_failed))
            .setContentText(reason)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.retry_send), retryPendingIntent)

        context.getSystemService(NotificationManager::class.java)
            .notify(failureId, builder.build())
    }
}
