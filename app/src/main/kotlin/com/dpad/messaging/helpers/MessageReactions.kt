package com.dpad.messaging.helpers

import com.dpad.messaging.models.Message
import com.dpad.messaging.models.MessageReaction
import com.dpad.messaging.models.MmsAttachmentJson

/**
 * Turns reaction fallback texts ("Liked “…”", "👍 to “…”") into reactions on
 * the message they refer to.
 *
 * This is display-only: nothing is changed in the SMS/MMS provider, so turning
 * the setting off shows every original message again. A fallback text is only
 * hidden when its target was actually found in the thread.
 */
object MessageReactions {

    /** Identifies a message; SMS and MMS ids come from different tables. */
    data class Key(val id: Long, val isMms: Boolean)

    class Result(
        /** Reaction fallback messages that are now shown as reactions instead. */
        val hidden: Set<Key>,
        /** Reactions per target message, oldest first. */
        val reactions: Map<Key, List<MessageReaction>>,
        /** "Edited to “…”" messages, mapped to the new text. */
        val edits: Map<Key, String>
    ) {
        companion object {
            val EMPTY = Result(emptySet(), emptyMap(), emptyMap())
        }
    }

    fun keyOf(message: Message) = Key(message.id, message.isMms)

    /** [messages] must be in chronological order (oldest first). */
    fun resolve(messages: List<Message>): Result {
        val hidden = HashSet<Key>()
        val edits = HashMap<Key, String>()
        // target -> (sender -> reaction); each sender holds one reaction per message.
        val byTarget = HashMap<Key, LinkedHashMap<String, MessageReaction>>()

        for ((index, message) in messages.withIndex()) {
            if (message.type != Message.TYPE_INBOX && message.type != Message.TYPE_SENT) continue
            if (message.body.isBlank() || hasAttachments(message)) continue
            val parsed = ReactionParser.parse(message.body) ?: continue

            if (parsed.kind == ReactionParser.Kind.EDIT) {
                if (message.isIncoming) edits[keyOf(message)] = parsed.quotedText
                continue
            }

            val target = findTarget(messages, index, parsed, hidden, edits) ?: continue
            val senders = byTarget.getOrPut(keyOf(target)) { LinkedHashMap() }
            val sender = senderKey(message)
            senders.remove(sender)
            if (parsed.kind == ReactionParser.Kind.ADD) {
                senders[sender] = MessageReaction(
                    emoji = parsed.emoji,
                    senderName = message.senderName.ifBlank { message.address },
                    isMine = message.isOutgoing,
                    date = message.date
                )
            }
            hidden.add(keyOf(message))
        }

        if (hidden.isEmpty() && edits.isEmpty()) return Result.EMPTY
        val reactions = byTarget
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.values.toList() }
        return Result(hidden, reactions, edits)
    }

    private fun findTarget(
        messages: List<Message>,
        reactionIndex: Int,
        parsed: ReactionParser.Parsed,
        hidden: Set<Key>,
        edits: Map<Key, String>
    ): Message? {
        val quote = normalize(if (parsed.isTruncated) parsed.quotePrefix else parsed.quotedText)
        for (i in reactionIndex - 1 downTo 0) {
            val candidate = messages[i]
            val key = keyOf(candidate)
            if (key in hidden) continue
            val matches = if (parsed.media != ReactionParser.Media.NONE) {
                matchesMedia(candidate, parsed.media)
            } else {
                val body = normalize(edits[key] ?: candidate.body)
                when {
                    quote.isEmpty() || body.isEmpty() -> false
                    parsed.isTruncated -> body.startsWith(quote)
                    else -> body == quote
                }
            }
            if (matches) return candidate
        }
        return null
    }

    private fun matchesMedia(message: Message, media: ReactionParser.Media): Boolean {
        if (!hasAttachments(message)) return false
        if (media == ReactionParser.Media.ANY) return true
        val prefix = when (media) {
            ReactionParser.Media.IMAGE -> "image/"
            ReactionParser.Media.VIDEO -> "video/"
            else -> "audio/"
        }
        return MmsAttachmentJson.decode(message.attachmentsJson).any { it.mimeType.startsWith(prefix) }
    }

    private fun hasAttachments(message: Message): Boolean {
        val json = message.attachmentsJson.trim()
        return json.isNotEmpty() && json != "[]"
    }

    private fun senderKey(message: Message): String {
        if (message.isOutgoing) return "me"
        val digits = message.address.filter(Char::isDigit)
        return if (digits.length >= 7) digits.takeLast(10) else message.address.trim().lowercase()
    }

    private val whitespace = Regex("\\s+")

    /** Makes quote comparison tolerant of smart punctuation and whitespace differences. */
    private fun normalize(text: String): String = text
        .replace('’', '\'').replace('‘', '\'')
        .replace('“', '"').replace('”', '"')
        .replace(' ', ' ')
        .replace(whitespace, " ")
        .trim()
}
