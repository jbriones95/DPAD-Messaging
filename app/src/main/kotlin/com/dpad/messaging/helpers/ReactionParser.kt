package com.dpad.messaging.helpers

/**
 * Recognises the plain-text fallbacks that iPhones and Google Messages send
 * over SMS/MMS when someone reacts to (or edits) a message, for example:
 *
 *   Liked “See you at 5”            (iOS tapback)
 *   Removed a heart from “…”        (iOS tapback removed)
 *   Reacted 🎉 to “…”               (iOS emoji tapback)
 *   👍 to “See you at 5”            (Google Messages)
 *   Laughed at an image             (reaction to an attachment)
 *   Edited to “See you at 6”        (iOS edited message)
 *
 * Parsing alone only says a body *might* be a reaction. [MessageReactions]
 * additionally requires the quoted text to match an earlier message in the
 * thread before anything is hidden, so ordinary texts are never swallowed.
 */
object ReactionParser {

    enum class Kind { ADD, REMOVE, EDIT }

    /** What the reaction points at when it does not quote message text. */
    enum class Media { NONE, IMAGE, VIDEO, AUDIO, ANY }

    data class Parsed(
        val kind: Kind,
        /** Reaction emoji; blank for [Kind.EDIT]. */
        val emoji: String,
        /** Quoted text of the target message (new text for [Kind.EDIT]); blank for media targets. */
        val quotedText: String,
        val media: Media = Media.NONE
    ) {
        /** True when the sender's app shortened the quote ("Liked “Long text…”"). */
        val isTruncated: Boolean
            get() = quotedText.endsWith("…") || quotedText.endsWith("...")

        /** [quotedText] without a trailing ellipsis. */
        val quotePrefix: String
            get() = quotedText.removeSuffix("…").removeSuffix("...").trimEnd()
    }

    private class Rule(val prefix: String, val kind: Kind, val emoji: String)

    private const val LIKE = "👍"
    private const val DISLIKE = "👎"
    private const val LOVE = "❤️"
    private const val LAUGH = "😂"
    private const val EMPHASIS = "‼️"
    private const val QUESTION = "❓"

    private val rules = listOf(
        // iOS, English
        Rule("Liked", Kind.ADD, LIKE),
        Rule("Disliked", Kind.ADD, DISLIKE),
        Rule("Loved", Kind.ADD, LOVE),
        Rule("Laughed at", Kind.ADD, LAUGH),
        Rule("Emphasized", Kind.ADD, EMPHASIS),
        Rule("Emphasised", Kind.ADD, EMPHASIS),
        Rule("Questioned", Kind.ADD, QUESTION),
        Rule("Removed a like from", Kind.REMOVE, LIKE),
        Rule("Removed a dislike from", Kind.REMOVE, DISLIKE),
        Rule("Removed a heart from", Kind.REMOVE, LOVE),
        Rule("Removed a laugh from", Kind.REMOVE, LAUGH),
        Rule("Removed an exclamation from", Kind.REMOVE, EMPHASIS),
        Rule("Removed a question mark from", Kind.REMOVE, QUESTION),
        Rule("Edited to", Kind.EDIT, ""),
        // iOS, Spanish
        Rule("Le gustó", Kind.ADD, LIKE),
        Rule("No le gustó", Kind.ADD, DISLIKE),
        Rule("Le encantó", Kind.ADD, LOVE),
        Rule("Se rió de", Kind.ADD, LAUGH),
        Rule("Se rio de", Kind.ADD, LAUGH),
        Rule("Enfatizó", Kind.ADD, EMPHASIS),
        Rule("Cuestionó", Kind.ADD, QUESTION),
        // iOS, German
        Rule("Gefällt", Kind.ADD, LIKE),
        Rule("Gefällt nicht", Kind.ADD, DISLIKE),
        Rule("Liebt", Kind.ADD, LOVE),
        Rule("Lachte über", Kind.ADD, LAUGH)
    )

    private const val OPEN_QUOTES = "“\"„«‘"
    private const val CLOSE_QUOTES = "”\"“»’"

    private val mediaPhrases = mapOf(
        "an image" to Media.IMAGE,
        "a photo" to Media.IMAGE,
        "a picture" to Media.IMAGE,
        "a movie" to Media.VIDEO,
        "a video" to Media.VIDEO,
        "an audio message" to Media.AUDIO,
        "a voice message" to Media.AUDIO,
        "an audio clip" to Media.AUDIO,
        "an attachment" to Media.ANY,
        "a sticker" to Media.ANY,
        "a gif" to Media.IMAGE
    )

    private val dotAll = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val reactedTo = Regex("""^Reacted\s+(\S{1,24})\s+to\s+(.+)$""", dotAll)
    private val removedFrom = Regex("""^Removed\s+(\S{1,24})\s+from\s+(.+)$""", dotAll)
    private val emojiTo = Regex("""^(\S{1,24})\s+to\s+(.+)$""", dotAll)

    /** Returns the reaction/edit described by [body], or null for an ordinary message. */
    fun parse(body: String): Parsed? {
        val text = body.trim()
        if (text.length < 3 || text.length > 2000) return null

        for (rule in rules) {
            if (!text.startsWith(rule.prefix, ignoreCase = true)) continue
            val rest = text.substring(rule.prefix.length)
            // "Likedness" must not match "Liked".
            if (rest.isEmpty() || !(rest[0].isWhitespace() || rest[0] in OPEN_QUOTES)) continue
            val target = parseTarget(rest.trim(), allowMedia = rule.kind != Kind.EDIT) ?: continue
            return Parsed(rule.kind, rule.emoji, target.first, target.second)
        }

        reactedTo.find(text)?.let { m -> emojiResult(Kind.ADD, m)?.let { return it } }
        removedFrom.find(text)?.let { m -> emojiResult(Kind.REMOVE, m)?.let { return it } }
        emojiTo.find(text)?.let { m -> emojiResult(Kind.ADD, m)?.let { return it } }
        return null
    }

    /**
     * Compact text for notifications and the conversation list:
     * "Liked “Hi”" becomes "👍 to “Hi”". Anything else is returned unchanged.
     */
    fun displayText(body: String): String {
        val parsed = parse(body) ?: return body
        if (parsed.kind != Kind.ADD || parsed.media != Media.NONE) return body
        return "${parsed.emoji} to “${parsed.quotedText}”"
    }

    /** Emoji offered when reacting from this app. */
    val PICKER_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "😡", "👎", "‼️", "❓")

    private const val MAX_QUOTE_LENGTH = 100

    /**
     * Builds the text sent to react to a message, in the Google Messages format
     * ("👍 to “Hi”"), or to take a reaction back ("Removed 👍 from “Hi”").
     * [mimeType] is used when the message has no text ("👍 to an image").
     */
    fun compose(emoji: String, targetBody: String, mimeType: String = "", remove: Boolean = false): String {
        val text = targetBody.trim()
        val target = when {
            text.isNotEmpty() -> "“${shorten(text)}”"
            mimeType.startsWith("image/") -> "an image"
            mimeType.startsWith("video/") -> "a video"
            mimeType.startsWith("audio/") -> "an audio message"
            else -> "an attachment"
        }
        return if (remove) "Removed $emoji from $target" else "$emoji to $target"
    }

    /** Keeps the reaction within one or two SMS segments for long messages. */
    private fun shorten(text: String): String {
        if (text.length <= MAX_QUOTE_LENGTH) return text
        var end = MAX_QUOTE_LENGTH
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun emojiResult(kind: Kind, match: MatchResult): Parsed? {
        val emoji = match.groupValues[1]
        if (!looksLikeEmoji(emoji)) return null
        val target = parseTarget(match.groupValues[2].trim(), allowMedia = true) ?: return null
        return Parsed(kind, emoji, target.first, target.second)
    }

    private fun parseTarget(rest: String, allowMedia: Boolean): Pair<String, Media>? {
        if (rest.length >= 3 && rest.first() in OPEN_QUOTES && rest.last() in CLOSE_QUOTES) {
            val quoted = rest.substring(1, rest.length - 1)
            return if (quoted.isBlank()) null else quoted to Media.NONE
        }
        if (!allowMedia) return null
        val media = mediaPhrases[rest.trimEnd('.').lowercase()] ?: return null
        return "" to media
    }

    /** True when [token] is made only of emoji-like code points (no letters or digits). */
    internal fun looksLikeEmoji(token: String): Boolean {
        var hasSymbol = false
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isLetterOrDigit(cp) -> return false
                // Variation selectors, zero-width joiner, keycap, skin tones.
                cp == 0x200D || cp == 0x20E3 || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF -> Unit
                cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp in 0x3000..0x303F ||
                    cp == 0x203C || cp == 0x2049 || cp == 0x00A9 || cp == 0x00AE ||
                    Character.getType(cp) == Character.OTHER_SYMBOL.toInt() -> hasSymbol = true
                else -> return false
            }
        }
        return hasSymbol
    }
}
