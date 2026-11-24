package com.dpad.messaging.activities

import com.dpad.messaging.models.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadMessageMergeTest {

    @Test
    fun freshProviderBodyReplacesBlankCachedBody() {
        val cachedBlank = message(1, "", date = 20)
        val older = message(0, "old", date = 10)
        val fresh = message(1, "new text", date = 20)
        val newest = message(2, "latest", date = 30)

        val merged = ThreadActivity.mergeMessagePage(listOf(older, cachedBlank), listOf(fresh, newest))

        assertEquals(listOf(0L, 1L, 2L), merged.map { it.id })
        assertEquals("new text", merged[1].body)
    }

    @Test
    fun smsAndMmsWithSameProviderIdRemainSeparate() {
        val sms = message(7, "sms", isMms = false)
        val mms = message(7, "mms", isMms = true)

        val merged = ThreadActivity.mergeMessagePage(listOf(sms), listOf(mms))

        assertEquals(2, merged.size)
        assertEquals(setOf(false, true), merged.map { it.isMms }.toSet())
    }

    private fun message(id: Long, body: String, date: Long = 0, isMms: Boolean = false) = Message(
        id = id,
        threadId = 1,
        body = body,
        type = Message.TYPE_INBOX,
        date = date,
        isMms = isMms
    )
}
