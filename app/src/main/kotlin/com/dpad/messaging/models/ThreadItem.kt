package com.dpad.messaging.models

import com.dpad.messaging.helpers.MessageReactions

/**
 * Sealed class representing the different item types in the message thread RecyclerView.
 */
sealed class ThreadItem {

    /** Date / time separator displayed between messages on different days. */
    data class DateHeader(
        val date: Long
    ) : ThreadItem()

    /** A message currently being sent (spinner shown). */
    data class SendingMessage(
        val message: Message
    ) : ThreadItem()

    /** An outgoing message that was sent (or delivered / failed). */
    data class SentMessage(
        val message: Message,
        val reactions: List<MessageReaction> = emptyList()
    ) : ThreadItem()

    /** An incoming message from a contact. */
    data class ReceivedMessage(
        val message: Message,
        val reactions: List<MessageReaction> = emptyList(),
        /** True when the body is the new text of an "Edited to “…”" message. */
        val edited: Boolean = false
    ) : ThreadItem()

    companion object {
        // Reusable calendar to avoid per-call allocations in dayKey()
        private val calendar = java.util.Calendar.getInstance()

        /**
         * Build a flat list of ThreadItems from raw messages, inserting date headers.
         *
         * With [convertReactions], reaction fallback texts from iPhones and Google
         * Messages are shown as reactions on the message they refer to instead of
         * as separate messages.
         */
        fun fromMessages(messages: List<Message>, convertReactions: Boolean = false): List<ThreadItem> {
            if (messages.isEmpty()) return emptyList()

            val resolved = if (convertReactions) {
                MessageReactions.resolve(messages)
            } else {
                MessageReactions.Result.EMPTY
            }
            val items = mutableListOf<ThreadItem>()
            var lastDateKey = -1L

            for (original in messages) {
                val key = MessageReactions.keyOf(original)
                if (key in resolved.hidden) continue
                val editedBody = resolved.edits[key]
                val message = if (editedBody != null) original.copy(body = editedBody) else original
                val reactions = resolved.reactions[key].orEmpty()
                val dayKey = dayKey(message.date)
                if (dayKey != lastDateKey) {
                    items.add(DateHeader(message.date))
                    lastDateKey = dayKey
                }
                items.add(
                    when {
                        message.type == Message.TYPE_OUTBOX ||
                        message.type == Message.TYPE_QUEUED -> SendingMessage(message)
                        message.isIncoming -> ReceivedMessage(message, reactions, editedBody != null)
                        else -> SentMessage(message, reactions)
                    }
                )
            }
            return items
        }

        private fun dayKey(timestamp: Long): Long {
            calendar.timeInMillis = timestamp
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
    }
}
