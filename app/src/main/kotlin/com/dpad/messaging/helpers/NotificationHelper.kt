package com.dpad.messaging.helpers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.activities.ThreadActivity
import com.dpad.messaging.receivers.DirectReplyReceiver
import com.dpad.messaging.receivers.MarkAsReadReceiver

/**
 * Builds and posts notifications for incoming messages.
 *
 * One notification per thread (keyed by threadId.toInt()).
 * Each notification has:
 *   • Tap           → opens ThreadActivity
 *   • Reply action  → DirectReplyReceiver (inline reply via RemoteInput)
 *   • Mark as Read  → MarkAsReadReceiver
 */
object NotificationHelper {

    const val REPLY_KEY = "reply_key"
    const val EXTRA_PHONE_NUMBER = "extra_phone_number"

    fun showIncomingNotification(
        context: Context,
        threadId: Long,
        senderName: String,
        phoneNumber: String,
        body: String
    ) {
        if (Prefs.get().isThreadMuted(threadId)) return

        val notifId = threadId.toInt()

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
        val builder = NotificationCompat.Builder(context, App.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(accentColor)
            .setColorized(true)
            .setContentTitle(senderName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPI)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .addAction(markReadAction)

        // Apply lock screen privacy setting.
        if (Prefs.get().lockScreenPrivacy == Prefs.PRIVACY_SENDER_ONLY) {
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
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        context.getSystemService(NotificationManager::class.java)
            .notify(notifId, builder.build())
    }

    fun cancelNotification(context: Context, notifId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notifId)
    }
}
