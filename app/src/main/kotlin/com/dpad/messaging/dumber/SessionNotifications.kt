package com.dpad.messaging.dumber

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dpad.messaging.R

/** One dismissible notice per revoked session; never a notification for temporary outages. */
internal object SessionNotifications {
    internal const val TAG = "dumber_session_ended"
    internal const val ID = 8092
    private const val CHANNEL = "dumber_account_alerts"

    @Synchronized fun refresh(context: Context) {
        // Locked/unavailable storage or denied notification permission must not break reception.
        runCatching {
            val revoked = DumberAccount.revokedAccounts(DumberAccount.read(context))
            val manager = context.getSystemService(NotificationManager::class.java)
            if (revoked.isEmpty()) {
                manager.cancel(TAG, ID)
                return
            }
            val unseen = revoked.filterValues { !it.optBoolean("revocation_notified") }
            if (unseen.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.dumber_account_alerts), NotificationManager.IMPORTANCE_HIGH))
                if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val open = PendingIntent.getActivity(context, ID,
                Intent(context, DumberConnectActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(TAG, ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_new_message)
                .setContentTitle(context.getString(R.string.dumber_session_ended))
                .setContentText(context.getString(R.string.dumber_session_ended_detail))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.dumber_session_ended_detail)))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build())
            DumberAccount.update(context) { root ->
                val current = DumberAccount.revokedAccounts(root)
                for ((id, previous) in unseen) {
                    current[id]?.takeIf { it.optString("session") == previous.optString("session") }
                        ?.put("revocation_notified", true)
                }
            }
        }
    }
}
