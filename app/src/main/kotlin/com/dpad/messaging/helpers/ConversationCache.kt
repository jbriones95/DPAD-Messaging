package com.dpad.messaging.helpers

import com.dpad.messaging.models.Conversation

/**
 * In-memory cache for conversation list data.
 *
 * Keeps the latest list so the UI can render instantly while a background
 * Telephony refresh runs.
 */
object ConversationCache {

    @Volatile
    private var cached: List<Conversation>? = null

    @Synchronized
    fun get(): List<Conversation>? = cached

    @Synchronized
    fun put(conversations: List<Conversation>) {
        cached = conversations
    }

    /** Keep resolved contact names while updating only the opened thread. */
    @Synchronized
    fun markThreadRead(threadId: Long) {
        cached = cached?.map { conversation ->
            if (conversation.threadId == threadId) {
                conversation.copy(read = true, unreadCount = 0)
            } else conversation
        }
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }
}
