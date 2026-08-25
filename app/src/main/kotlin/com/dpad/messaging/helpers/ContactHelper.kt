package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.LruCache

/**
 * Resolves phone numbers to contact display names and photo URIs.
 * Uses an LruCache to avoid repeated ContentProvider queries.
 */
class ContactHelper(private val context: Context) {

    data class ContactInfo(
        val displayName: String,
        val photoUri: String = ""
    )

    private val cache = LruCache<String, ContactInfo>(256)
    private val misses = LruCache<String, Boolean>(256)

    /** Returns the best display name for a phone number, or the number itself if no contact found. */
    fun getDisplayName(phoneNumber: String): String {
        return resolve(phoneNumber)?.displayName ?: phoneNumber
    }

    /** Returns ContactInfo (name + photo URI) for a phone number, or null if unknown. */
    fun resolve(phoneNumber: String): ContactInfo? {
        if (phoneNumber.isBlank()) return null

        val cacheKey = phoneNumber.filter { it.isDigit() }.ifBlank { phoneNumber }
        val cached = cache.get(cacheKey)
        if (cached != null) return cached
        if (misses.get(cacheKey) == true) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(cacheKey)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
        )

        val result = try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: return@use null
                    val photo = cursor.getString(1) ?: ""
                    ContactInfo(name, photo).also { cache.put(cacheKey, it) }
                } else null
            }
        } catch (e: Exception) {
            null
        }
        if (result == null) misses.put(cacheKey, true)
        return result
    }

    fun clearCache() {
        cache.evictAll()
        misses.evictAll()
    }

    data class ContactSuggestion(
        val displayName: String,
        val phoneNumber: String
    )

    /**
     * Returns up to [limit] contacts whose name or number contains [query].
     * Intended for type-ahead suggestions; does NOT cache results.
     */
    fun search(query: String, limit: Int = 5): List<ContactSuggestion> {
        if (query.isBlank()) return emptyList()
        val likeQuery = "%$query%"
        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)
        val selection = "${Phone.DISPLAY_NAME} LIKE ? OR ${Phone.NUMBER} LIKE ?"
        val selectionArgs = arrayOf(likeQuery, likeQuery)
        val sortOrder = "${Phone.DISPLAY_NAME} ASC"
        return try {
            context.contentResolver.query(
                Phone.CONTENT_URI, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val results = mutableListOf<ContactSuggestion>()
                val seenNumbers = mutableSetOf<String>()
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1)?.replace("\\s".toRegex(), "") ?: continue
                    if (seenNumbers.add(number)) {
                        results.add(ContactSuggestion(name, number))
                    }
                }
                results
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
