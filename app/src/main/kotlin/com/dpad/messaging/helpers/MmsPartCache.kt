package com.dpad.messaging.helpers

import android.util.LruCache

/**
 * Tiny in-memory cache for MMS part lookups.
 *
 * Avoids repeated ContentProvider queries for the same message while
 * browsing threads.
 */
object MmsPartCache {

    data class CachedParts(
        val textBody: String,
        val imagePartUris: List<String>,
        val attachmentLabel: String
    )

    private val cache = LruCache<Long, CachedParts>(256)

    @Synchronized
    fun get(msgId: Long): CachedParts? = cache.get(msgId)

    @Synchronized
    fun put(msgId: Long, value: CachedParts) {
        cache.put(msgId, value)
    }

    @Synchronized
    fun remove(msgId: Long) {
        cache.remove(msgId)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }
}
