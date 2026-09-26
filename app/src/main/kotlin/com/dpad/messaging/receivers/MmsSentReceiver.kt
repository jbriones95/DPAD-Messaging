package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.models.Message
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.MmsSender
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * Receives the result PendingIntent fired by [com.dpad.messaging.helpers.MmsTransmitter]
 * after the platform reports the outcome of `SmsManager.sendMultimediaMessage()`.
 *
 * Each in-flight message gets a PendingIntent keyed on its provider row id, and the row
 * URI is carried as the intent's data, so concurrent sends are reported independently
 * and can be resolved exactly.
 *
 *  - resultCode = Activity.RESULT_OK (-1) means sent. Any other value is the
 *    `SmsManager.MMS_ERROR_*` code itself: the platform passes the error as the broadcast
 *    result code, not as an extra (see mmslib `MmsRequest.processResult`, which calls
 *    `pendingIntent.send(context, result, fillIn)`).
 *  - `SmsManager.EXTRA_MMS_HTTP_STATUS` is supplied only when the MMSC itself rejected
 *    the request with an HTTP error.
 *  - threadId is provided for UI refresh targeting.
 */
class MmsSentReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DPAD_MSG"
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_SCHEDULED_MESSAGE_ID = "extra_scheduled_message_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val receiverResultCode = resultCode
        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                processReceive(context, intent, receiverResultCode)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun processReceive(context: Context, intent: Intent, resultCode: Int) {
        val threadId = intent.getLongExtra(MmsSender.EXTRA_THREAD_ID, -1L)
        val hasImage = intent.getBooleanExtra("extra_has_image", false)
        val isSuccess = resultCode == Activity.RESULT_OK
        // The app owns msg_box on this path (mmslib's own system-send receiver does the
        // same), and the UI reads it to tell a pending bubble from a terminal one. We
        // therefore mark failures explicitly rather than leaving the row in the outbox,
        // which matches what the previous in-process transport did via SendRequest.
        val targetMsgBox = if (isSuccess) {
            Telephony.Mms.MESSAGE_BOX_SENT
        } else {
            Telephony.Mms.MESSAGE_BOX_FAILED
        }
        val scheduledMessageId = intent.getLongExtra(EXTRA_SCHEDULED_MESSAGE_ID, -1L)
        // The platform reports the MMS error code as the broadcast result code, and
        // supplies an HTTP status only when the MMSC itself rejected the request.
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, -1)

        val contentUri = extractContentUri(intent)
        if (contentUri != null) {
            updateMsgBoxByUri(context, contentUri, targetMsgBox)
        } else if (threadId > 0) {
            Log.w(TAG, "MmsSentReceiver: no message URI on callback; falling back to thread scan")
            updateLatestOutboxForThread(context, threadId, targetMsgBox)
        }

        cleanupTempPduFile(intent)

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "MmsSentReceiver: threadId=$threadId hasImage=$hasImage resultCode=$resultCode " +
                    "httpStatus=$httpStatus uri=$contentUri"
            )
        }

        if (!isSuccess) {
            Log.w(TAG, "MmsSentReceiver: MMS send failed resultCode=$resultCode httpStatus=$httpStatus")
        }

        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0) {
            EventBus.getDefault().post(RefreshMessages(threadId))
        }

        if (scheduledMessageId > 0L) {
            AppCoroutineScopes.io.launch {
                val dao = App.get().database.messagesDao()
                val scheduled = dao.getMessage(scheduledMessageId) ?: return@launch
                if (isSuccess) {
                    dao.deleteMessage(scheduledMessageId)
                } else {
                    dao.updateMessage(
                        scheduled.copy(
                            type = Message.TYPE_FAILED,
                            status = Message.STATUS_FAILED,
                            isScheduled = false,
                            scheduledDate = null,
                            dateSent = System.currentTimeMillis()
                        )
                    )
                }
                EventBus.getDefault().post(RefreshConversations())
                if (threadId > 0) EventBus.getDefault().post(RefreshMessages(threadId))
            }
        }
    }

    private fun extractContentUri(intent: Intent): Uri? {
        val directData = intent.data
        if (directData != null) return directData

        val asString = intent.getStringExtra("content_uri")
        if (!asString.isNullOrBlank()) return Uri.parse(asString)

        val asUriString = intent.getStringExtra("uri")
        if (!asUriString.isNullOrBlank()) return Uri.parse(asUriString)

        val parcelableContentUri = getUriExtra(intent, "content_uri")
        if (parcelableContentUri != null) return parcelableContentUri

        return getUriExtra(intent, "uri")
    }

    private fun getUriExtra(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    private fun updateMsgBoxByUri(context: Context, uri: Uri, msgBox: Int) {
        try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, msgBox) },
                null,
                null
            )
            Log.d(TAG, "MmsSentReceiver: updated uri=$uri msg_box=$msgBox")
        } catch (e: Exception) {
            Log.w(TAG, "MmsSentReceiver: failed to update uri=$uri", e)
        }
    }

    /**
     * Last-resort path for callbacks that arrive without row data. The new transmitter
     * always sets the row URI as the intent data, so this should be unreachable; it is
     * retained only so an unexpected callback cannot leave rows stranded in the outbox.
     */
    private fun updateLatestOutboxForThread(context: Context, threadId: Long, msgBox: Int) {
        val mmsUri = Uri.parse("content://mms")
        try {
            context.contentResolver.update(
                mmsUri,
                ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, msgBox) },
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?",
                arrayOf(threadId.toString(), Telephony.Mms.MESSAGE_BOX_OUTBOX.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "MmsSentReceiver: failed fallback update for threadId=$threadId", e)
        }
    }

    private fun cleanupTempPduFile(intent: Intent) {
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (filePath.isBlank()) return

        runCatching {
            val deleted = File(filePath).delete()
            Log.d(TAG, "MmsSentReceiver: temp file cleanup path=$filePath deleted=$deleted")
        }.onFailure { e ->
            Log.w(TAG, "MmsSentReceiver: temp file cleanup failed path=$filePath", e)
        }
    }
}
