package com.dpad.messaging.helpers

import com.dpad.messaging.models.Conversation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCacheTest {

    @After
    fun tearDown() {
        ConversationCache.invalidate()
    }

    @Test
    fun markThreadReadKeepsResolvedNamesAndOtherRows() {
        val cached = listOf(
            Conversation(
                threadId = 1,
                phoneNumber = "15551234567",
                title = "Alex",
                read = false,
                unreadCount = 3
            ),
            Conversation(
                threadId = 2,
                phoneNumber = "15557654321",
                title = "Sam",
                read = false,
                unreadCount = 1
            )
        )
        ConversationCache.put(cached)

        ConversationCache.markThreadRead(1)

        val updated = ConversationCache.get().orEmpty()
        assertEquals("Alex", updated[0].title)
        assertTrue(updated[0].read)
        assertEquals(0, updated[0].unreadCount)
        assertFalse(updated[1].read)
        assertEquals(1, updated[1].unreadCount)
    }
}
