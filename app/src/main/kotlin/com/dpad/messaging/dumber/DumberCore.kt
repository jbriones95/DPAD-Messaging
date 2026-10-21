package com.dpad.messaging.dumber

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import chat.dumber.app.NativeCore
import org.json.JSONObject
import java.io.File
import java.util.Locale

object DumberCore {
    const val SERVICE = "https://nokings.chat"
    fun available(): Boolean = try { Class.forName("chat.dumber.app.NativeCore"); true } catch (_: LinkageError) { false } catch (_: Exception) { false }
    fun request(context: Context, command: String, account: String? = null): JSONObject {
        val state = if (account == null) DumberAccount.read(context) else DumberAccount.state(context, account)
        return JSONObject().put("command", command).put("service", SERVICE)
            .put("store_path", File(context.noBackupFilesDir, if (account == null) "dumber-crypto" else DumberAccount.storeName(context, account)).absolutePath)
            .put("store_password", DumberAccount.password(context, account))
            .apply { state.optJSONObject("session")?.let { put("session", it) } }
    }
    fun call(request: JSONObject): JSONObject {
        val response = JSONObject(NativeCore.invoke(request.toString()))
        if (!response.optBoolean("ok")) throw DumberException(response.optString("error", "dumber.chat unavailable"),
            if (response.has("http_status")) response.getInt("http_status") else null,
            if (response.has("retry_after_seconds")) response.getLong("retry_after_seconds") else null,
            response.optString("errcode").takeIf { it.isNotBlank() })
        return response.getJSONObject("data")
    }
    fun international(context: Context, number: String, subscriptionId: Int = -1): String? {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        if (Regex("\\+[1-9][0-9]{7,14}").matches(normalized)) return normalized
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val sim = if (android.os.Build.VERSION.SDK_INT >= 24 && subscriptionId >= 0) manager.createForSubscriptionId(subscriptionId) else manager
        val region = runCatching { sim.simCountryIso.ifBlank { sim.networkCountryIso } }.getOrDefault("")
        if (region.isBlank()) return null // Never guess an ambiguous national number.
        return PhoneNumberUtils.formatNumberToE164(number, region.uppercase(Locale.ROOT))
    }
}
class DumberException(message: String, val httpStatus: Int? = null, val retryAfterSeconds: Long? = null, val errorCode: String? = null): Exception(message) {
    val recipientUnavailable: Boolean get() = httpStatus == 403 && errorCode == "DUMBER_RECIPIENT_UNAVAILABLE"
}
