package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.android.mms.transaction.DownloadManager
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.receivers.PendingMmsDownloadReceiver
import com.google.android.mms.pdu_alt.PduHeaders
import org.greenrobot.eventbus.EventBus

/** App-owned policy for when an incoming MMS may start its platform download. */
object MmsDownloadPolicy {
    private const val COLUMN_TRANSACTION_ID = "tr_id"
    private const val COLUMN_CONTENT_LOCATION = "ct_l"
    private const val COLUMN_MESSAGE_TYPE = "m_type"
    private const val COLUMN_MESSAGE_BOX = "msg_box"

    fun shouldAutoDownload(): Boolean = Prefs.get().autoDownloadMms

    fun defer(context: Context, messageUri: Uri, subscriptionId: Int) {
        val messageId = messageUri.lastPathSegment?.toLongOrNull() ?: return
        NotificationHelper.showPendingMmsNotification(context, messageId, subscriptionId)
        EventBus.getDefault().post(RefreshConversations())
    }

    fun downloadPending(context: Context, messageId: Long, fallbackSubscriptionId: Int = -1) {
        val uri = Uri.parse("content://mms/$messageId")
        val pending = context.contentResolver.query(
            uri,
            arrayOf(
                COLUMN_MESSAGE_TYPE,
                COLUMN_MESSAGE_BOX,
                COLUMN_CONTENT_LOCATION,
                COLUMN_TRANSACTION_ID,
                Telephony.Mms.SUBSCRIPTION_ID
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst() ||
                cursor.getInt(0) != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND ||
                cursor.getInt(1) != 1
            ) {
                return@use null
            }
            val location = cursor.getString(2).orEmpty()
            if (location.isBlank()) return@use null
            val subscriptionId = if (!cursor.isNull(4)) cursor.getInt(4) else fallbackSubscriptionId
            PendingDownload(
                location = location,
                transactionId = cursor.getString(3).orEmpty(),
                subscriptionId = subscriptionId
            )
        } ?: return

        DownloadManager.getInstance().downloadMultimediaMessage(
            context,
            pending.location,
            pending.transactionId,
            uri,
            true,
            pending.subscriptionId
        )
    }

    fun downloadPending(context: Context) {
        context.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", Telephony.Mms.SUBSCRIPTION_ID),
            "$COLUMN_MESSAGE_TYPE = ? AND $COLUMN_MESSAGE_BOX = ?",
            arrayOf(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND.toString(), "1"),
            "date ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val messageId = cursor.getLong(0)
                val subscriptionId = if (!cursor.isNull(1)) cursor.getInt(1) else -1
                downloadPending(context, messageId, subscriptionId)
            }
        }
    }

    fun cancelPendingNotification(context: Context, messageId: Long) {
        NotificationHelper.cancelPendingMmsNotification(context, messageId)
    }

    fun pendingDownloadIntent(context: Context, messageId: Long, subscriptionId: Int): android.content.Intent =
        android.content.Intent(context, PendingMmsDownloadReceiver::class.java).apply {
            putExtra(PendingMmsDownloadReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(PendingMmsDownloadReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }

    private data class PendingDownload(
        val location: String,
        val transactionId: String,
        val subscriptionId: Int
    )
}
