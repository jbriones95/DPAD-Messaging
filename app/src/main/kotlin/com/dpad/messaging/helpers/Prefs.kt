package com.dpad.messaging.helpers

import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Thin SharedPreferences wrapper for user-configurable settings.
 *
 * Initialised once via [init] in App.onCreate(); accessed anywhere via [get].
 * Thread-safe: double-checked locking via @Volatile + synchronized.
 */
class Prefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dpad_prefs", Context.MODE_PRIVATE)

    // ── Keys ──────────────────────────────────────────────────────────────────

    companion object {
        private const val KEY_DELIVERY_REPORTS    = "delivery_reports"
        private const val KEY_SEND_ON_ENTER       = "send_on_enter"
        private const val KEY_CHARACTER_COUNTER   = "character_counter"
        private const val KEY_SEND_GROUP_MESSAGE_MMS = "send_group_message_mms"
        private const val KEY_USE_LIBRARY_SMS_SENDING = "use_library_sms_sending"
        private const val KEY_LOCK_SCREEN_PRIVACY = "lock_screen_privacy"
        private const val KEY_RECYCLE_BIN_ENABLED = "recycle_bin_enabled"
        private const val KEY_MUTED_THREADS       = "muted_threads"
        private const val KEY_PINNED_THREADS      = "pinned_threads"
        private const val KEY_ARCHIVED_THREADS    = "archived_threads"
        private const val KEY_APP_THEME_MODE      = "app_theme_mode"
        private const val KEY_APP_ACCENT          = "app_accent"
        private const val KEY_DATE_FORMAT         = "date_format"
        private const val KEY_TIME_FORMAT         = "time_format"
        private const val KEY_UI_SCALE            = "ui_scale"
        private const val KEY_DEFAULT_SMS_DISMISSED = "default_sms_dismissed"
        private const val KEY_CONTACT_COLOR_PREFIX = "contact_color_"

        const val PRIVACY_FULL        = "full"
        const val PRIVACY_SENDER_ONLY = "sender_only"
        const val THEME_SYSTEM        = "system"
        const val THEME_LIGHT         = "light"
        const val THEME_DARK          = "dark"
        const val ACCENT_BLUE         = "blue"
        const val ACCENT_GREEN        = "green"
        const val ACCENT_ORANGE       = "orange"
        const val ACCENT_ROSE         = "rose"
        const val DATE_FORMAT_MDY     = "MDY"   // MM/dd/yy  (American)
        const val DATE_FORMAT_DMY     = "DMY"   // dd/MM/yy  (European)
        const val TIME_FORMAT_12H     = "12h"   // h:mm a
        const val TIME_FORMAT_24H     = "24h"   // HH:mm
        const val UI_SCALE_COMPACT    = "compact"   // 0.85x
        const val UI_SCALE_NORMAL     = "normal"    // 1.0x  (default)
        const val UI_SCALE_LARGE      = "large"     // 1.25x
        const val UI_SCALE_XLARGE     = "xlarge"    // 1.5x

        private const val TAG = "Prefs"

        @Volatile private var instance: Prefs? = null

        fun init(context: Context): Prefs {
            val prefs = instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
            return prefs
        }

        fun get(): Prefs = checkNotNull(instance) { "Prefs.init() must be called before Prefs.get()" }
    }

    // ── Properties ────────────────────────────────────────────────────────────

    /** Show a "Delivered" status tick after the system confirms delivery. Default: false. */
    var deliveryReports: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY_REPORTS, false)
        set(v) = prefs.edit().putBoolean(KEY_DELIVERY_REPORTS, v).apply()

    /** Press Enter (not Shift+Enter) to send the message. Default: true. */
    var sendOnEnter: Boolean
        get() = prefs.getBoolean(KEY_SEND_ON_ENTER, true)
        set(v) = prefs.edit().putBoolean(KEY_SEND_ON_ENTER, v).apply()

    /** Show remaining characters and segment count in the compose bar. Default: false. */
    var characterCounter: Boolean
        get() = prefs.getBoolean(KEY_CHARACTER_COUNTER, false)
        set(v) = prefs.edit().putBoolean(KEY_CHARACTER_COUNTER, v).apply()

    /**
     * Keep text-only group replies in a single thread by sending them as group MMS.
     * When disabled, text-only group replies are sent as individual SMS for compatibility.
     * Default: false.
     */
    var sendGroupMessageMms: Boolean
        get() = prefs.getBoolean(KEY_SEND_GROUP_MESSAGE_MMS, true)
        set(v) = prefs.edit().putBoolean(KEY_SEND_GROUP_MESSAGE_MMS, v).apply()

    /**
     * Phase 2 rollout flag for library-backed SMS sending.
     * true = route SMS_SINGLE through mmslib transaction path.
     * false = use legacy SmsManager sender.
     */
    var useLibrarySmsSending: Boolean
        get() = prefs.getBoolean(KEY_USE_LIBRARY_SMS_SENDING, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_LIBRARY_SMS_SENDING, v).apply()

    /**
     * Controls what is shown on the lock screen notification.
     * [PRIVACY_FULL] = show sender and message body.
     * [PRIVACY_SENDER_ONLY] = show sender only; body is hidden.
     * Default: [PRIVACY_FULL].
     */
    var lockScreenPrivacy: String
        get() = prefs.getString(KEY_LOCK_SCREEN_PRIVACY, PRIVACY_SENDER_ONLY) ?: PRIVACY_SENDER_ONLY
        set(v) = prefs.edit().putString(KEY_LOCK_SCREEN_PRIVACY, v).apply()

    /** Move deleted messages to the recycle bin instead of hard-deleting. Default: false. */
    var recycleBinEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECYCLE_BIN_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_RECYCLE_BIN_ENABLED, v).apply()

    /** App-wide theme mode: system/light/dark. Default: system. */
    var appThemeMode: String
        get() = prefs.getString(KEY_APP_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = prefs.edit().putString(KEY_APP_THEME_MODE, v).apply()

    /** App-wide accent color choice. Default: blue. */
    var appAccent: String
        get() = prefs.getString(KEY_APP_ACCENT, ACCENT_BLUE) ?: ACCENT_BLUE
        set(v) = prefs.edit().putString(KEY_APP_ACCENT, v).apply()

    /** Date display format. [DATE_FORMAT_MDY] = MM/dd/yy; [DATE_FORMAT_DMY] = dd/MM/yy. Default: MDY (American). */
    var dateFormat: String
        get() = prefs.getString(KEY_DATE_FORMAT, DATE_FORMAT_MDY) ?: DATE_FORMAT_MDY
        set(v) = prefs.edit().putString(KEY_DATE_FORMAT, v).apply()

    /** Time display format. [TIME_FORMAT_12H] = 12-hour; [TIME_FORMAT_24H] = 24-hour. Default: 12h. */
    var timeFormat: String
        get() = prefs.getString(KEY_TIME_FORMAT, TIME_FORMAT_12H) ?: TIME_FORMAT_12H
        set(v) = prefs.edit().putString(KEY_TIME_FORMAT, v).apply()

    /** Returns true when notifications are muted for [threadId]. */
    fun isThreadMuted(threadId: Long): Boolean {
        val muted = prefs.getStringSet(KEY_MUTED_THREADS, emptySet()) ?: emptySet()
        return muted.contains(threadId.toString())
    }

    /** Returns the set of all muted thread IDs. */
    fun getMutedThreadIds(): Set<Long> {
        return (prefs.getStringSet(KEY_MUTED_THREADS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()
    }

    /** Enables or disables notifications for [threadId]. */
    fun setThreadMuted(threadId: Long, muted: Boolean) {
        val current = prefs.getStringSet(KEY_MUTED_THREADS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val key = threadId.toString()
        if (muted) current.add(key) else current.remove(key)
        prefs.edit().putStringSet(KEY_MUTED_THREADS, current).apply()
    }

    /** Returns true when [threadId] is pinned to the top of the conversation list. */
    fun isThreadPinned(threadId: Long): Boolean {
        val pinned = prefs.getStringSet(KEY_PINNED_THREADS, emptySet()) ?: emptySet()
        return pinned.contains(threadId.toString())
    }

    /** Pins or unpins [threadId]. */
    fun setThreadPinned(threadId: Long, pinned: Boolean) {
        val current = prefs.getStringSet(KEY_PINNED_THREADS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val key = threadId.toString()
        if (pinned) current.add(key) else current.remove(key)
        prefs.edit().putStringSet(KEY_PINNED_THREADS, current).apply()
    }

    /** Returns the full set of pinned thread IDs. */
    fun getPinnedThreadIds(): Set<Long> {
        return (prefs.getStringSet(KEY_PINNED_THREADS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()
    }

    /** Returns true when [threadId] is archived. */
    fun isThreadArchived(threadId: Long): Boolean {
        val archived = prefs.getStringSet(KEY_ARCHIVED_THREADS, emptySet()) ?: emptySet()
        return archived.contains(threadId.toString())
    }

    /** Archives or un-archives [threadId]. */
    fun setThreadArchived(threadId: Long, archived: Boolean) {
        val current = prefs.getStringSet(KEY_ARCHIVED_THREADS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val key = threadId.toString()
        if (archived) current.add(key) else current.remove(key)
        prefs.edit().putStringSet(KEY_ARCHIVED_THREADS, current).apply()
    }

    /** Returns the full set of archived thread IDs. */
    fun getArchivedThreadIds(): Set<Long> {
        return (prefs.getStringSet(KEY_ARCHIVED_THREADS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()
    }

    /** UI scale preference. One of [UI_SCALE_COMPACT], [UI_SCALE_NORMAL], [UI_SCALE_LARGE], [UI_SCALE_XLARGE]. */
    var uiScale: String
        get() = prefs.getString(KEY_UI_SCALE, UI_SCALE_NORMAL) ?: UI_SCALE_NORMAL
        set(v) = prefs.edit().putString(KEY_UI_SCALE, v).apply()

    /** UI scale as a float multiplier applied to the system font scale. */
    val uiScaleFactor: Float get() = when (uiScale) {
        UI_SCALE_COMPACT -> 0.85f
        UI_SCALE_LARGE   -> 1.25f
        UI_SCALE_XLARGE  -> 1.5f
        else             -> 1.0f
    }

    /** User declined the "set as default SMS app" prompt — don't ask again. */
    var defaultSmsDismissed: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_SMS_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(KEY_DEFAULT_SMS_DISMISSED, v).apply()

    /** Contact color for a normalized phone number, or null if using the default. */
    fun getContactColor(normalizedNumber: String): Int? {
        val raw = prefs.getString(KEY_CONTACT_COLOR_PREFIX + normalizedNumber, null) ?: return null
        return raw.toIntOrNull()
    }

    /** Persists (or clears, when [color] is null) the color for a phone number. */
    fun setContactColor(normalizedNumber: String, color: Int?) {
        prefs.edit().apply {
            if (color == null) {
                remove(KEY_CONTACT_COLOR_PREFIX + normalizedNumber)
            } else {
                putString(KEY_CONTACT_COLOR_PREFIX + normalizedNumber, color.toString())
            }
        }.apply()
    }
}
