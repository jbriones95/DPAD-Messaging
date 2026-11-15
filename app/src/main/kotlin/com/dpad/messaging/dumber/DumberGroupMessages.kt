package com.dpad.messaging.dumber

import chat.dumber.sdk.Conversation
import chat.dumber.sdk.Message
import chat.dumber.sdk.MessageContent
import com.dpad.messaging.models.Message as DisplayMessage
import org.json.JSONObject

/** Adapts encrypted storage to the same timeline model used by carrier groups. */
internal object DumberGroupMessages {
    data class Row(
        val key: String,
        val outgoing: Boolean,
        val body: String?,
        val phone: String?,
        val timestamp: Long,
        val pendingId: String? = null,
        val error: String? = null,
        val encrypted: Boolean = false,
        val unsupported: Boolean = false,
        val senderId: String = ""
    ) {
        fun display(id: Long, senderName: String, unavailable: String, unsupportedText: String) = DisplayMessage(
            id = id, threadId = 0,
            body = body ?: if (unsupported) unsupportedText else unavailable,
            type = when {
                pendingId != null -> DisplayMessage.TYPE_FAILED
                outgoing -> DisplayMessage.TYPE_SENT
                else -> DisplayMessage.TYPE_INBOX
            },
            date = timestamp, address = phone.orEmpty(),
            senderName = if (outgoing) "" else senderName,
            // Pending text has not yet been confirmed encrypted by the transport.
            isEncrypted = encrypted && pendingId == null
        )
    }

    fun rows(account: String, info: Conversation, events: List<Message>, pending: List<JSONObject>): List<Row> {
        val people = info.members.associateBy { it.contact.id }
        val sentTransactions = events.filter { it.sender.id == account }.mapNotNull { it.transactionId }.toSet()
        return events.map { event ->
            Row("event:${event.eventId}", event.sender.id == account,
                (event.content as? MessageContent.Text)?.body,
                people[event.sender.id]?.contact?.phone ?: event.sender.phone, event.timestampMs,
                encrypted = event.encrypted, unsupported = event.content == MessageContent.Unsupported,
                senderId = event.sender.id)
        } + pending.filter { it.getString("id") !in sentTransactions }.map {
            Row("pending:${it.getString("id")}", true, it.getString("body"), null,
                it.optLong("timestamp", events.lastOrNull()?.timestampMs ?: System.currentTimeMillis()),
                it.getString("id"), it.optString("error").takeIf(String::isNotBlank))
        }
    }
}
