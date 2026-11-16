package com.dpad.messaging.helpers

import com.dpad.messaging.models.Message
import com.dpad.messaging.models.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReactionsTest {

    private var nextId = 1L

    private fun sent(body: String) = Message(
        id = nextId, threadId = 1, body = body, type = Message.TYPE_SENT, date = nextId++ * 1000
    )

    private fun received(body: String, address: String = "+15551234567", name: String = "Alice") = Message(
        id = nextId, threadId = 1, body = body, type = Message.TYPE_INBOX, date = nextId++ * 1000,
        address = address, senderName = name
    )

    private fun image(incoming: Boolean) = Message(
        id = nextId, threadId = 1, type = if (incoming) Message.TYPE_INBOX else Message.TYPE_SENT,
        date = nextId++ * 1000, isMms = true,
        attachmentsJson = """[{"uri":"content://mms/part/1","mimeType":"image/jpeg","fileName":"a.jpg"}]"""
    )

    private fun bodies(items: List<ThreadItem>) = items.mapNotNull {
        when (it) {
            is ThreadItem.SentMessage -> it.message.body
            is ThreadItem.ReceivedMessage -> it.message.body
            else -> null
        }
    }

    @Test
    fun `reaction is attached to the quoted message and hidden`() {
        val target = sent("See you at 5")
        val reaction = received("Liked “See you at 5”")
        val result = MessageReactions.resolve(listOf(target, reaction))

        assertEquals(setOf(MessageReactions.keyOf(reaction)), result.hidden)
        val reactions = result.reactions.getValue(MessageReactions.keyOf(target))
        assertEquals(listOf("👍"), reactions.map { it.emoji })
        assertEquals("Alice", reactions.single().senderName)
    }

    @Test
    fun `reaction without a matching message stays a normal message`() {
        val messages = listOf(sent("Hello"), received("Liked “Something else”"))
        val result = MessageReactions.resolve(messages)
        assertTrue(result.hidden.isEmpty())
        assertTrue(result.reactions.isEmpty())
    }

    @Test
    fun `most recent matching message is the target`() {
        val first = sent("ok")
        val second = sent("ok")
        val result = MessageReactions.resolve(listOf(first, second, received("👍 to “ok”")))
        assertEquals(setOf(MessageReactions.keyOf(second)), result.reactions.keys)
    }

    @Test
    fun `a new reaction from the same sender replaces the old one`() {
        val target = sent("Dinner?")
        val result = MessageReactions.resolve(
            listOf(target, received("Liked “Dinner?”"), received("Loved “Dinner?”"))
        )
        assertEquals(listOf("❤️"), result.reactions.getValue(MessageReactions.keyOf(target)).map { it.emoji })
        assertEquals(2, result.hidden.size)
    }

    @Test
    fun `removal clears the reaction and both texts are hidden`() {
        val target = sent("Dinner?")
        val result = MessageReactions.resolve(
            listOf(target, received("Liked “Dinner?”"), received("Removed a like from “Dinner?”"))
        )
        assertTrue(result.reactions.isEmpty())
        assertEquals(2, result.hidden.size)
    }

    @Test
    fun `group reactions from different senders are all kept`() {
        val target = sent("Party at 8")
        val result = MessageReactions.resolve(
            listOf(
                target,
                received("Liked “Party at 8”", "+15551110000", "Alice"),
                received("👍 to “Party at 8”", "+15552220000", "Bob"),
                received("Reacted 🎉 to “Party at 8”", "+15553330000", "Cara")
            )
        )
        val reactions = result.reactions.getValue(MessageReactions.keyOf(target))
        assertEquals(listOf("👍", "👍", "🎉"), reactions.map { it.emoji })
        assertEquals(listOf("Alice", "Bob", "Cara"), reactions.map { it.senderName })
    }

    @Test
    fun `truncated quote matches by prefix and smart punctuation is ignored`() {
        val long = sent("This is a very long message that the other phone shortened")
        val apostrophe = sent("I'm here")
        val result = MessageReactions.resolve(
            listOf(long, apostrophe, received("Liked “This is a very long…”"), received("Loved “I’m here”"))
        )
        assertEquals(
            setOf(MessageReactions.keyOf(long), MessageReactions.keyOf(apostrophe)),
            result.reactions.keys
        )
    }

    @Test
    fun `image reaction targets the latest image`() {
        val photo = image(incoming = false)
        val result = MessageReactions.resolve(listOf(sent("look"), photo, sent("nice?"), received("Loved an image")))
        assertEquals(setOf(MessageReactions.keyOf(photo)), result.reactions.keys)
    }

    @Test
    fun `thread items hide reactions only when conversion is on`() {
        val messages = listOf(sent("See you at 5"), received("Liked “See you at 5”"))

        val plain = ThreadItem.fromMessages(messages, convertReactions = false)
        assertEquals(listOf("See you at 5", "Liked “See you at 5”"), bodies(plain))

        val converted = ThreadItem.fromMessages(messages, convertReactions = true)
        assertEquals(listOf("See you at 5"), bodies(converted))
        val item = converted.filterIsInstance<ThreadItem.SentMessage>().single()
        assertEquals("👍", item.reactions.single().emoji)
    }

    @Test
    fun `edited message shows the new text`() {
        val items = ThreadItem.fromMessages(
            listOf(received("See you at 5"), received("Edited to “See you at 6”")),
            convertReactions = true
        )
        val received = items.filterIsInstance<ThreadItem.ReceivedMessage>()
        assertEquals(listOf("See you at 5", "See you at 6"), received.map { it.message.body })
        assertEquals(listOf(false, true), received.map { it.edited })
    }

    @Test
    fun `my own sent reaction shows on the received message and can be removed`() {
        val target = received("Dinner at 7?")
        val mine = sent(ReactionParser.compose("👍", target.body))
        val added = MessageReactions.resolve(listOf(target, mine))
        assertTrue(added.reactions.getValue(MessageReactions.keyOf(target)).single().isMine)

        val removed = MessageReactions.resolve(
            listOf(target, mine, sent(ReactionParser.compose("👍", target.body, remove = true)))
        )
        assertTrue(removed.reactions.isEmpty())
        assertEquals(2, removed.hidden.size)
    }
}
