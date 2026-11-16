package com.dpad.messaging.helpers

import com.dpad.messaging.helpers.ReactionParser.Kind
import com.dpad.messaging.helpers.ReactionParser.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionParserTest {

    private fun parsed(body: String) = checkNotNull(ReactionParser.parse(body)) { "not parsed: $body" }

    @Test
    fun `iOS tapbacks are recognised`() {
        val expected = mapOf(
            "Liked “See you at 5”" to "👍",
            "Disliked “See you at 5”" to "👎",
            "Loved “See you at 5”" to "❤️",
            "Laughed at “See you at 5”" to "😂",
            "Emphasized “See you at 5”" to "‼️",
            "Questioned “See you at 5”" to "❓"
        )
        for ((body, emoji) in expected) {
            val p = parsed(body)
            assertEquals(Kind.ADD, p.kind)
            assertEquals(emoji, p.emoji)
            assertEquals("See you at 5", p.quotedText)
        }
    }

    @Test
    fun `iOS tapback removals are recognised`() {
        assertEquals(Kind.REMOVE, parsed("Removed a like from “ok”").kind)
        assertEquals(Kind.REMOVE, parsed("Removed a heart from “ok”").kind)
        assertEquals(Kind.REMOVE, parsed("Removed an exclamation from “ok”").kind)
        assertEquals(Kind.REMOVE, parsed("Removed a question mark from “ok”").kind)
        assertEquals("ok", parsed("Removed a laugh from “ok”").quotedText)
    }

    @Test
    fun `iOS emoji tapbacks are recognised`() {
        val add = parsed("Reacted 🎉 to “We won”")
        assertEquals(Kind.ADD, add.kind)
        assertEquals("🎉", add.emoji)
        assertEquals("We won", add.quotedText)

        val remove = parsed("Removed 🎉 from “We won”")
        assertEquals(Kind.REMOVE, remove.kind)
        assertEquals("🎉", remove.emoji)
    }

    @Test
    fun `Google Messages reactions are recognised`() {
        val p = parsed("👍 to “On my way”")
        assertEquals(Kind.ADD, p.kind)
        assertEquals("👍", p.emoji)
        assertEquals("On my way", p.quotedText)
        assertEquals("😂", parsed("😂 to \"straight quotes\"").emoji)
        // Skin tone and ZWJ sequences count as one emoji token.
        assertEquals("👍🏽", parsed("👍🏽 to “ok”").emoji)
        assertEquals("❤️", parsed("❤️ to “ok”").emoji)
    }

    @Test
    fun `quotes inside the quoted text and multi-line text are kept`() {
        assertEquals("He said “hi” to me", parsed("Liked “He said “hi” to me”").quotedText)
        assertEquals("line one\nline two", parsed("Loved “line one\nline two”").quotedText)
    }

    @Test
    fun `truncated quotes are flagged`() {
        val p = parsed("Liked “This is a very long mess…”")
        assertTrue(p.isTruncated)
        assertEquals("This is a very long mess", p.quotePrefix)
    }

    @Test
    fun `media reactions are recognised`() {
        assertEquals(Media.IMAGE, parsed("Loved an image").media)
        assertEquals(Media.VIDEO, parsed("Laughed at a movie").media)
        assertEquals(Media.AUDIO, parsed("Liked an audio message").media)
        assertEquals(Media.ANY, parsed("Emphasized an attachment").media)
        assertEquals(Media.IMAGE, parsed("👍 to an image").media)
    }

    @Test
    fun `edits are recognised`() {
        val p = parsed("Edited to “See you at 6”")
        assertEquals(Kind.EDIT, p.kind)
        assertEquals("See you at 6", p.quotedText)
    }

    @Test
    fun `ordinary messages are not reactions`() {
        val bodies = listOf(
            "Liked it a lot",
            "Loved the movie",
            "I liked “Dune”",
            "Likedness “x”",
            "Going to “the place”",
            "5 to “six”",
            "ok to “go”",
            "Removed the door from “the shed”",
            "Liked “”",
            "Liked “unterminated",
            "👍",
            "",
            "to “x”"
        )
        for (body in bodies) assertNull(body, ReactionParser.parse(body))
    }

    @Test
    fun `display text is compact for reactions only`() {
        assertEquals("👍 to “Hi”", ReactionParser.displayText("Liked “Hi”"))
        assertEquals("🎉 to “Hi”", ReactionParser.displayText("Reacted 🎉 to “Hi”"))
        assertEquals("Hello there", ReactionParser.displayText("Hello there"))
        assertEquals("Loved an image", ReactionParser.displayText("Loved an image"))
        assertEquals("Removed a like from “Hi”", ReactionParser.displayText("Removed a like from “Hi”"))
    }

    @Test
    fun `composed reactions parse back`() {
        assertEquals("👍 to “Hi”", ReactionParser.compose("👍", "Hi"))
        assertEquals("Removed 👍 from “Hi”", ReactionParser.compose("👍", "Hi", remove = true))
        assertEquals("❤️ to an image", ReactionParser.compose("❤️", "", "image/jpeg"))
        for (emoji in ReactionParser.PICKER_EMOJI) {
            val p = parsed(ReactionParser.compose(emoji, "See you at 5"))
            assertEquals(Kind.ADD, p.kind)
            assertEquals(emoji, p.emoji)
            assertEquals("See you at 5", p.quotedText)
            assertEquals(Kind.REMOVE, parsed(ReactionParser.compose(emoji, "x", remove = true)).kind)
        }
        val long = parsed(ReactionParser.compose("👍", "word ".repeat(60)))
        assertTrue(long.isTruncated)
        assertTrue("word ".repeat(60).startsWith(long.quotePrefix))
    }
}
