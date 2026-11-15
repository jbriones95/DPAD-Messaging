package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android presentation of the Rust-owned recipient discovery state. */
object DumberDirectory {
    data class Status(val peer: String? = null, val absent: Boolean = false, val reason: String = "", val retryAt: Long = 0)
    fun cached(context: Context, phone: String, account: String): Status {
        if(!DumberAccount.enabled(context,account)) return Status()
        val data = try { DumberCore.client(context,account).recipientStatus(phone) }
            catch(_: LinkageError) { return Status(reason="Encrypted messaging is unavailable") }
            catch(_: Exception) { return Status(reason="Availability unknown") }
        return Status(data.contact?.id, data.availability == Availability.UNAVAILABLE, retryAt=data.retryAtMs)
    }
    suspend fun lookup(context: Context, phone: String, force: Boolean = false, account: String): Status = withContext(Dispatchers.IO) {
        try {
            DumberCore.client(context,account).checkRecipient(phone,force)
            cached(context,phone,account=account)
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) {
            val previous = cached(context,phone,account=account)
            previous.copy(reason=when((e as? ChatException)?.kind) {
                ErrorKind.AUTHENTICATION_REQUIRED -> "Reconnect dumber.chat to check availability."
                ErrorKind.RATE_LIMITED -> "Phone lookup limit reached. Try again later."
                else -> "Availability unknown. Check your connection."
            })
        }
    }
}
