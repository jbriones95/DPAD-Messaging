package com.dpad.messaging.dumber

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Discovery is called only by an open conversation. Sending only reads the cache. */
object DumberDirectory {
    private val lock = Mutex()
    const val NEGATIVE_TTL = 24 * 60 * 60 * 1000L
    data class Status(val peer: String? = null, val absent: Boolean = false, val reason: String = "", val retryAt: Long = 0)
    fun account(context: Context): String = DumberAccount.read(context).optJSONObject("session")?.optString("user_id").orEmpty()
    fun cached(context: Context, phone: String, now: Long = System.currentTimeMillis(), account: String = account(context)): Status {
        val state = DumberAccount.state(context, account).optJSONObject("directory") ?: return Status()
        if (state.optString("account") != account) return Status()
        val entry = state.optJSONObject("recipients")?.optJSONObject(phone) ?: return Status()
        val peer = entry.optString("peer").takeIf { it.isNotBlank() }
        // A negative routing decision remains SMS-only while revalidation is due/offline.
        return Status(peer, peer == null)
    }
    fun remember(context: Context, account: String, phone: String, peer: String?, authoritative: Boolean = false, expectedRevision: Long? = null): Boolean {
        var accepted = false
        DumberAccount.updateAccount(context, account) { state ->
            if (state.optJSONObject("session")?.optString("user_id") != account) return@updateAccount
            val directory = state.optJSONObject("directory")?.takeIf { it.optString("account") == account }
                ?: JSONObject().put("account", account).put("recipients", JSONObject())
            val recipients = directory.getJSONObject("recipients")
            val old = recipients.optJSONObject(phone)
            if (expectedRevision != null && (old?.optLong("revision") ?: 0) != expectedRevision) return@updateAccount
            // Reverse resolution/historical sync identifies senders, not current availability.
            if (peer != null && !authoritative && old != null && !old.has("peer")) return@updateAccount
            recipients.put(phone, JSONObject().put("checked", System.currentTimeMillis())
                .put("revision", (old?.optLong("revision") ?: 0) + 1)
                .apply { if (peer != null) put("peer", peer)
                    else old?.optString("peer")?.takeIf { it.isNotBlank() }?.let { put("known_peer", it) } })
            state.put("directory", directory)
            accepted = true
        }
        return accepted
    }
    suspend fun lookup(context: Context, phone: String, force: Boolean = false, fetch: (() -> String)? = null, account: String = account(context)): Status = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = cached(context, phone, account = account)
            val entry = DumberAccount.state(context, account).optJSONObject("directory")?.takeIf { it.optString("account") == account }?.optJSONObject("recipients")?.optJSONObject(phone)
            val revision = entry?.optLong("revision") ?: 0
            if (!force && (cached.peer != null || (cached.absent && (entry?.optLong("checked") ?: 0) + NEGATIVE_TTL > System.currentTimeMillis()))) return@withLock cached
            if (!force && (entry?.optLong("retry_at") ?: 0) > System.currentTimeMillis()) return@withLock cached.copy(reason = "Availability check will retry later.")
            val now = System.currentTimeMillis()
            val state = DumberAccount.state(context, account)
            val retryAt = if (state.optString("lookup_retry_account") == account) state.optLong("lookup_retry_at") else 0
            if (retryAt > now) return@withLock cached.copy(reason = "Phone lookup limit reached. Try again later.", retryAt = retryAt)
            try {
                val peer = fetch?.invoke() ?: DumberCore.call(DumberCore.request(context, "lookup", account).put("phone", phone)).getString("user_id")
                if (!DumberAccount.enabled(context, account)) return@withLock Status(reason = "Account changed")
                val current = DumberAccount.state(context, account).optJSONObject("directory")?.takeIf { it.optString("account") == account }?.optJSONObject("recipients")?.optJSONObject(phone)
                if ((current?.optLong("revision") ?: 0) != revision) return@withLock cached(context, phone, account = account)
                remember(context, account, phone, peer, authoritative = true, expectedRevision = revision)
                cached(context, phone, account = account)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!DumberAccount.enabled(context, account)) return@withLock Status(reason = "Account changed")
                val current = DumberAccount.state(context, account).optJSONObject("directory")?.takeIf { it.optString("account") == account }?.optJSONObject("recipients")?.optJSONObject(phone)
                if ((current?.optLong("revision") ?: 0) != revision) return@withLock cached(context, phone, account = account)
                if (e is DumberException && e.httpStatus == 404) {
                    remember(context, account, phone, null, expectedRevision = revision)
                    cached(context, phone, account = account)
                } else {
                    val retry = if (e is DumberException && e.httpStatus == 429) now + (e.retryAfterSeconds ?: 60).coerceIn(1, 86400) * 1000 else 0
                    if (retry > 0) DumberAccount.updateAccount(context, account) { it.put("lookup_retry_account", account).put("lookup_retry_at", retry) }
                    if (retry == 0L) DumberAccount.updateAccount(context, account) {
                        it.optJSONObject("directory")?.takeIf { it.optString("account") == account }?.optJSONObject("recipients")?.optJSONObject(phone)?.put("retry_at", now + 300_000)
                    }
                    cached.copy(reason = if (e is DumberException && e.httpStatus == 401) "Reconnect dumber.chat to check availability." else if (retry > 0) "Phone lookup limit reached. Try again later." else "Availability unknown. Check your connection.", retryAt = retry)
                }
            }
        }
    }
}
