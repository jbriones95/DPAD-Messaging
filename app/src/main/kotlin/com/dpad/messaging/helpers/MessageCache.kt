package com.dpad.messaging.helpers

import com.dpad.messaging.models.Message

/**
 * Simple in-memory LRU cache for message lists keyed by thread ID.
 *
 * Stores the last Telephony query result so that re-entering a conversation
 * shows messages instantly while a background refresh runs.
 */
object MessageCache {

    private const val MAX_ENTRIES = 5

    private val cache = object : LinkedHashMap<Long, List<Message>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Message>>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(threadId: Long): List<Message>? = cache[threadId]

    @Synchronized
    fun put(threadId: Long, messages: List<Message>) {
        cache[threadId] = messages
    }

    @Synchronized
    fun invalidate(threadId: Long) {
        cache.remove(threadId)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
