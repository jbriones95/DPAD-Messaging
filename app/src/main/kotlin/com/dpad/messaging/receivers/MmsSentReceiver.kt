package com.dpad.messaging.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.models.Message
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.MmsSender
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * Receives the result PendingIntent fired by mmslib Transaction via SmsManager.sendMultimediaMessage().
 *
 * With mmslib:
 *  - mmslib handles all provider persistence (inserts, updates to sent/failed)
 *  - We receive this callback to refresh the UI
 *  - resultCode = RESULT_OK (-1) = sent, otherwise = failed
 *  - threadId is provided for UI refresh targeting
 *
 * This receiver logs the result and posts EventBus events for UI refresh.
 */
class MmsSentReceiver : BroadcastReceiver() {
    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_ORIGINAL_RESEND_ID = "original_resent_message_id"
        private const val EXTRA_ORIGINAL_MESSAGE_ID = "original_message_id"
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
        val targetMsgBox = if (isSuccess) 2 else 5 // 2=sent, 5=failed
        val scheduledMessageId = intent.getLongExtra(EXTRA_SCHEDULED_MESSAGE_ID, -1L)

        val contentUri = extractContentUri(intent)
        if (contentUri != null) {
            updateMsgBoxByUri(context, contentUri, targetMsgBox)
        } else if (threadId > 0) {
            updateLatestOutboxForThread(context, threadId, targetMsgBox)
        }

        cleanupTempPduFile(intent)
        deleteOriginalResentMessage(context, intent)

        val extras = intent.extras?.keySet()?.sorted()?.joinToString() ?: "<none>"
        Log.d(
            "DPAD_MSG",
            "MmsSentReceiver.onReceive() threadId=$threadId hasImage=$hasImage resultCode=$resultCode isSuccess=$isSuccess extras=$extras"
        )

        // mmslib has already updated the provider (inserted and updated msg_box)
        // Our job is to refresh the UI so the message appears with correct status
        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0) {
            EventBus.getDefault().post(RefreshMessages(threadId))
            Log.d("DPAD_MSG", "MmsSentReceiver: posted refresh events for threadId=$threadId")
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
                ContentValues().apply { put("msg_box", msgBox) },
                null,
                null
            )
            Log.d("DPAD_MSG", "MmsSentReceiver: updated uri=$uri msg_box=$msgBox")
        } catch (e: Exception) {
            Log.w("DPAD_MSG", "MmsSentReceiver: failed to update uri=$uri", e)
        }
    }

    private fun updateLatestOutboxForThread(context: Context, threadId: Long, msgBox: Int) {
        val mmsUri = Uri.parse("content://mms")
        try {
            // Update ALL outbox rows for the thread so multi-attachment sends
            // don't leave extra rows stuck in msg_box=4.
            context.contentResolver.update(
                mmsUri,
                ContentValues().apply { put("msg_box", msgBox) },
                "thread_id = ? AND msg_box = 4",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.w("DPAD_MSG", "MmsSentReceiver: failed fallback update for threadId=$threadId", e)
        }
    }

    private fun cleanupTempPduFile(intent: Intent) {
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (filePath.isBlank()) return

        runCatching {
            val deleted = File(filePath).delete()
            Log.d("DPAD_MSG", "MmsSentReceiver: temp file cleanup path=$filePath deleted=$deleted")
        }.onFailure { e ->
            Log.w("DPAD_MSG", "MmsSentReceiver: temp file cleanup failed path=$filePath", e)
        }
    }

    private fun deleteOriginalResentMessage(context: Context, intent: Intent) {
        val originalId = when {
            intent.hasExtra(EXTRA_ORIGINAL_RESEND_ID) -> intent.getLongExtra(EXTRA_ORIGINAL_RESEND_ID, -1L)
            else -> intent.getLongExtra(EXTRA_ORIGINAL_MESSAGE_ID, -1L)
        }
        if (originalId <= 0) return

        runCatching {
            val deleted = context.contentResolver.delete(Uri.parse("content://mms/$originalId"), null, null)
            Log.d("DPAD_MSG", "MmsSentReceiver: removed original resent mms id=$originalId deletedRows=$deleted")
        }.onFailure { e ->
            Log.w("DPAD_MSG", "MmsSentReceiver: failed removing original resent mms id=$originalId", e)
        }
    }
}
