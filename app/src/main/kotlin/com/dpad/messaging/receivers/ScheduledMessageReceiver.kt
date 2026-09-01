package com.dpad.messaging.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.AttachmentPolicy
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.MessageSenders
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Message
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray

/**
 * Fires when a scheduled message alarm expires.
 *
 * Phase 2: retrieve the scheduled message from Room, send via SmsManager.
 */
class ScheduledMessageReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SCHEDULED_MESSAGE_ID = "extra_scheduled_message_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_SCHEDULED_MESSAGE_ID, -1L)
        if (messageId == -1L) return

        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                val dao = App.get().database.messagesDao()
                val message = dao.getMessage(messageId) ?: return@launch
                if (!message.isScheduled) return@launch

                dao.updateMessage(
                    message.copy(
                        type = Message.TYPE_OUTBOX,
                        status = Message.STATUS_PENDING,
                        dateSent = 0L
                    )
                )

                runCatching {
                    if (message.isMms) {
                        val recipients = parseJsonArray(message.participantsJson).ifEmpty {
                            listOf(message.address).filter { it.isNotBlank() }
                        }
                        val attachments = parseJsonArray(message.attachmentsJson)
                            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

                        val oversized = attachments.firstOrNull {
                            !AttachmentPolicy.isWithinMmsLimit(context, it)
                        }
                        if (oversized != null) {
                            throw IllegalStateException("Scheduled MMS attachment exceeds size policy")
                        }

                        MessageSenders.unified.sendMms(
                            context = context,
                            recipients = recipients,
                            body = message.body,
                            attachmentUri = attachments.firstOrNull(),
                            attachmentUris = attachments,
                            threadId = message.threadId,
                            subscriptionId = message.subscriptionId,
                            scheduledMessageId = message.id
                        )
                    } else {
                        MessageSenders.unified.sendSms(
                            context = context,
                            phoneNumber = message.address,
                            body = message.body,
                            threadId = message.threadId,
                            subscriptionId = message.subscriptionId,
                            scheduledMessageId = message.id
                        )
                    }
                }.onSuccess {
                    // Final success/failure is handled by SmsStatusSentReceiver / MmsSentReceiver.
                }.onFailure {
                    dao.updateMessage(
                        message.copy(
                            type = Message.TYPE_FAILED,
                            status = Message.STATUS_FAILED,
                            isScheduled = false,
                            scheduledDate = null
                        )
                    )
                    val telephonyType = if (message.isMms) {
                        Uri.parse("content://mms/$messageId") to "msg_box"
                    } else {
                        Uri.parse("content://sms/$messageId") to Telephony.Sms.TYPE
                    }
                    runCatching {
                        val cv = android.content.ContentValues().apply {
                            if (message.isMms) {
                                put(telephonyType.second, 5)
                            } else {
                                put(telephonyType.second, Telephony.Sms.MESSAGE_TYPE_FAILED)
                            }
                        }
                        context.contentResolver.update(telephonyType.first, cv, null, null)
                    }
                    showScheduledSendFailureNotification(context, message.threadId)
                }

                EventBus.getDefault().post(RefreshConversations())
                EventBus.getDefault().post(RefreshMessages(message.threadId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun parseJsonArray(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        if (!raw.trim().startsWith("[")) return listOf(raw).filter { it.isNotBlank() }
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i)
                    if (value.isNotBlank()) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun showScheduledSendFailureNotification(context: Context, threadId: Long) {
        val accent = ThemeManager.accentColor(context)
        val notification = NotificationCompat.Builder(context, App.CHANNEL_SEND_FAILURE)
            .setSmallIcon(R.drawable.ic_new_message)
            .setColor(accent)
            .setColorized(true)
            .setContentTitle(context.getString(R.string.scheduled_send_failed_title))
            .setContentText(context.getString(R.string.scheduled_send_failed_body))
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify((threadId and 0x7FFFFFFF).toInt() + 40_000, notification)
    }
}
