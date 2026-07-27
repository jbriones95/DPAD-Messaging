package com.dpad.messaging.helpers

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.models.Message
import com.dpad.messaging.receivers.SmsStatusDeliveredReceiver
import com.dpad.messaging.receivers.SmsStatusSentReceiver
import com.klinker.android.send_message.Message as KlinkerMessage
import com.klinker.android.send_message.Settings as KlinkerSettings
import com.klinker.android.send_message.Transaction as KlinkerTransaction
import org.json.JSONArray
import org.greenrobot.eventbus.EventBus

/**
 * Unified send abstraction for SMS + MMS.
 *
 * Phase 1 goal: introduce an adapter layer without changing behavior.
 * Current implementation delegates to existing SmsSender/MmsSender.
 *
 * MDM filter enforcement: every outgoing send call funnels through this
 * interface (from ThreadActivity, retry, HeadlessSmsSendService, and
 * ScheduledMessageReceiver), so the MDM whitelist/blocklist check lives
 * inside each interface method's implementation. This is the authoritative
 * outgoing security boundary — UI-layer checks elsewhere are pre-validation
 * for UX only.
 */
interface UnifiedMessageSender {
    suspend fun sendSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int = -1,
        scheduledMessageId: Long? = null
    )

    suspend fun sendMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        attachmentUris: List<Uri> = emptyList(),
        threadId: Long,
        subscriptionId: Int = -1,
        scheduledMessageId: Long? = null
    )

    suspend fun sendGroupSmsFanout(
        context: Context,
        recipients: List<String>,
        body: String,
        fallbackThreadId: Long,
        subscriptionId: Int = -1
    )
}

/**
 * Shared MDM outgoing filter check used by all UnifiedMessageSender impls.
 *
 * Returns the list of recipients that are NOT allowed by the current policy.
 * Empty list means the send may proceed. Any non-empty list means the send
 * MUST be aborted by the caller — there is no "partial send" semantics here,
 * since allowing some recipients but not others on a group message would
 * leak the policy state to the user.
 *
 * If a scheduledMessageId is supplied the failed-send is also recorded in
 * the messages DB so the user sees the outcome in the thread.
 */
internal object OutgoingFilter {
    private const val TAG = "DPAD_MSG"

    suspend fun checkAndRecord(
        context: Context,
        recipients: List<String>,
        scheduledMessageId: Long?
    ): List<String> {
        val blocked = recipients.filter { recipient ->
            !SmsWhitelistManager.check(context, recipient).allowed
        }
        if (blocked.isNotEmpty()) {
            Log.i(TAG, "OutgoingFilter: blocked send — recipients not permitted: $blocked")
            if (scheduledMessageId != null) {
                runCatching {
                    val dao = App.get().database.messagesDao()
                    val msg = dao.getMessage(scheduledMessageId)
                    if (msg != null) {
                        dao.updateMessage(
                            msg.copy(
                                type = Message.TYPE_FAILED,
                                status = Message.STATUS_FAILED,
                                isScheduled = false
                            )
                        )
                        EventBus.getDefault().post(RefreshConversations())
                        if (msg.threadId > 0L) {
                            EventBus.getDefault().post(RefreshMessages(msg.threadId))
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "OutgoingFilter: failed to record blocked scheduled message status", e)
                }
            }
        }
        return blocked
    }
}

/**
 * Legacy adapter preserving existing behavior.
 */
object LegacyUnifiedMessageSender : UnifiedMessageSender {
    override suspend fun sendSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int,
        scheduledMessageId: Long?
    ) {
        // MDM outgoing filter
        if (OutgoingFilter.checkAndRecord(context, listOf(phoneNumber), scheduledMessageId).isNotEmpty()) return

        SmsSender.send(
            context = context,
            phoneNumber = phoneNumber,
            body = body,
            threadId = threadId,
            subscriptionId = subscriptionId,
            scheduledMessageId = scheduledMessageId
        )
    }

    override suspend fun sendMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        attachmentUris: List<Uri>,
        threadId: Long,
        subscriptionId: Int,
        scheduledMessageId: Long?
    ) {
        // MDM outgoing filter
        if (OutgoingFilter.checkAndRecord(context, recipients, scheduledMessageId).isNotEmpty()) return

        MmsSender.send(
            context = context,
            recipients = recipients,
            body = body,
            attachmentUri = attachmentUri,
            attachmentUris = attachmentUris,
            threadId = threadId,
            subscriptionId = subscriptionId,
            scheduledMessageId = scheduledMessageId
        )
    }

    override suspend fun sendGroupSmsFanout(
        context: Context,
        recipients: List<String>,
        body: String,
        fallbackThreadId: Long,
        subscriptionId: Int
    ) {
        // MDM outgoing filter — entire fanout aborts if any recipient is blocked.
        if (OutgoingFilter.checkAndRecord(context, recipients, null).isNotEmpty()) return

        for (recipient in recipients) {
            val recipientThreadId = try {
                Telephony.Threads.getOrCreateThreadId(context, recipient)
            } catch (e: Exception) {
                Log.w("DPAD_MSG", "UnifiedSender: failed to resolve threadId for $recipient", e)
                fallbackThreadId
            }

            SmsSender.send(
                context = context,
                phoneNumber = recipient,
                body = body,
                threadId = recipientThreadId,
                subscriptionId = subscriptionId
            )
        }

        if (fallbackThreadId > 0 && recipients.isNotEmpty()) {
            runCatching {
                context.contentResolver.insert(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, recipients.joinToString("|"))
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                        put(Telephony.Sms.THREAD_ID, fallbackThreadId)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                    }
                )
            }.onFailure { e ->
                Log.w("DPAD_MSG", "UnifiedSender: failed to insert group fanout broadcast row", e)
            }
        }
    }
}

/**
 * Phase 2 pilot sender:
 *  - SMS single-recipient is sent via mmslib transaction path.
 *  - MMS and fanout group SMS keep legacy behavior for stability.
 */
object LibraryUnifiedMessageSender : UnifiedMessageSender {
    private const val TAG = "DPAD_MSG"

    override suspend fun sendSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        subscriptionId: Int,
        scheduledMessageId: Long?
    ) {
        // MDM outgoing filter
        if (OutgoingFilter.checkAndRecord(context, listOf(phoneNumber), scheduledMessageId).isNotEmpty()) return

        val settings = KlinkerSettings().apply {
            setUseSystemSending(true)
            setGroup(false)
            setDeliveryReports(Prefs.get().deliveryReports)
            if (subscriptionId >= 0) setSubscriptionId(subscriptionId)
        }

        val transaction = KlinkerTransaction(context, settings)
            .setExplicitBroadcastForSentSms(
                Intent(context, SmsStatusSentReceiver::class.java).apply {
                    putExtra(SmsSender.EXTRA_THREAD_ID, threadId)
                    if (scheduledMessageId != null) {
                        putExtra(SmsSender.EXTRA_SCHEDULED_MESSAGE_ID, scheduledMessageId)
                    }
                }
            )
            .setExplicitBroadcastForDeliveredSms(
                Intent(context, SmsStatusDeliveredReceiver::class.java).apply {
                    putExtra(SmsSender.EXTRA_THREAD_ID, threadId)
                    if (scheduledMessageId != null) {
                        putExtra(SmsSender.EXTRA_SCHEDULED_MESSAGE_ID, scheduledMessageId)
                    }
                }
            )
        val message = KlinkerMessage(body, phoneNumber)

        try {
            Log.d(TAG, "LibraryUnifiedMessageSender: SMS via mmslib phone=$phoneNumber threadId=$threadId subId=$subscriptionId")
            transaction.sendNewMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "LibraryUnifiedMessageSender: SMS library send failed, falling back to legacy", e)
            // SmsSender.send() contains the authoritative MDM filter check.
            SmsSender.send(
                context = context,
                phoneNumber = phoneNumber,
                body = body,
                threadId = threadId,
                subscriptionId = subscriptionId,
                scheduledMessageId = scheduledMessageId
            )
        }
    }

    override suspend fun sendMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        attachmentUris: List<Uri>,
        threadId: Long,
        subscriptionId: Int,
        scheduledMessageId: Long?
    ) {
        // MDM outgoing filter
        if (OutgoingFilter.checkAndRecord(context, recipients, scheduledMessageId).isNotEmpty()) return

        // Keep existing MMS path unchanged for phase 2.
        MmsSender.send(
            context = context,
            recipients = recipients,
            body = body,
            attachmentUri = attachmentUri,
            attachmentUris = attachmentUris,
            threadId = threadId,
            subscriptionId = subscriptionId,
            scheduledMessageId = scheduledMessageId
        )
    }

    override suspend fun sendGroupSmsFanout(
        context: Context,
        recipients: List<String>,
        body: String,
        fallbackThreadId: Long,
        subscriptionId: Int
    ) {
        // Keep existing fanout path unchanged for phase 2.
        // The filter check happens inside LegacyUnifiedMessageSender.sendGroupSmsFanout.
        LegacyUnifiedMessageSender.sendGroupSmsFanout(
            context = context,
            recipients = recipients,
            body = body,
            fallbackThreadId = fallbackThreadId,
            subscriptionId = subscriptionId
        )
    }
}

object MessageSenders {
    val unified: UnifiedMessageSender
        get() = if (Prefs.get().useLibrarySmsSending) {
            LibraryUnifiedMessageSender
        } else {
            LegacyUnifiedMessageSender
        }

    suspend fun scheduleSms(
        context: Context,
        phoneNumber: String,
        body: String,
        threadId: Long,
        scheduledDate: Long,
        subscriptionId: Int = -1
    ): Long {
        val messageId = generateScheduledMessageId()
        App.get().database.messagesDao().insertMessage(
            Message(
                id = messageId,
                threadId = threadId,
                body = body,
                type = Message.TYPE_QUEUED,
                date = scheduledDate,
                dateSent = 0L,
                read = true,
                address = phoneNumber,
                isMms = false,
                status = Message.STATUS_PENDING,
                subscriptionId = subscriptionId,
                isScheduled = true,
                scheduledDate = scheduledDate
            )
        )
        val inserted = App.get().database.messagesDao().getMessage(messageId)
        if (inserted != null) {
            ScheduledMessageScheduler.scheduleAndPersistStatus(
                context = context,
                message = inserted,
                triggerAtMillis = scheduledDate
            )
            val refreshed = App.get().database.messagesDao().getMessage(messageId)
            if (refreshed?.isScheduled == false && refreshed.type == Message.TYPE_FAILED) {
                EventBus.getDefault().post(RefreshConversations())
                if (threadId > 0L) EventBus.getDefault().post(RefreshMessages(threadId))
            }
        }
        return messageId
    }

    suspend fun scheduleMms(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUris: List<Uri>,
        threadId: Long,
        scheduledDate: Long,
        subscriptionId: Int = -1
    ): Long {
        val messageId = generateScheduledMessageId()
        val attachmentsJson = JSONArray(attachmentUris.map { it.toString() }).toString()
        val participantsJson = JSONArray(recipients).toString()

        App.get().database.messagesDao().insertMessage(
            Message(
                id = messageId,
                threadId = threadId,
                body = body,
                type = Message.TYPE_QUEUED,
                date = scheduledDate,
                dateSent = 0L,
                read = true,
                address = recipients.firstOrNull().orEmpty(),
                isMms = true,
                status = Message.STATUS_PENDING,
                subscriptionId = subscriptionId,
                isScheduled = true,
                scheduledDate = scheduledDate,
                attachmentsJson = attachmentsJson,
                participantsJson = participantsJson
            )
        )
        val inserted = App.get().database.messagesDao().getMessage(messageId)
        if (inserted != null) {
            ScheduledMessageScheduler.scheduleAndPersistStatus(
                context = context,
                message = inserted,
                triggerAtMillis = scheduledDate
            )
            val refreshed = App.get().database.messagesDao().getMessage(messageId)
            if (refreshed?.isScheduled == false && refreshed.type == Message.TYPE_FAILED) {
                EventBus.getDefault().post(RefreshConversations())
                if (threadId > 0L) EventBus.getDefault().post(RefreshMessages(threadId))
            }
        }
        return messageId
    }

    private fun generateScheduledMessageId(): Long {
        val now = System.currentTimeMillis()
        val salt = (System.nanoTime() and 0x3FF)
        return (now shl 10) or salt
    }
}
