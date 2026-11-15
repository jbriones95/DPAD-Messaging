package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.io.File
import java.util.Locale

object DumberCore {
    const val SERVICE = "https://nokings.chat"
    fun available(): Boolean = ChatClient.isAvailable()
    internal var factory: (ChatConfig) -> ChatApi = { ChatClient(it) }
    internal fun config(context: Context, account: String? = null): ChatConfig = ChatConfig(SERVICE,
        account?.let { requireNotNull(DumberAccount.session(context, it)) },
        account?.let { CryptoStore(File(context.noBackupFilesDir, DumberAccount.storeName(context, it)).absolutePath, DumberAccount.password(context, it)) })
    fun client(context: Context, account: String? = null): ChatApi = factory(config(context, account))
    fun client(session: Session): ChatApi = factory(ChatConfig(SERVICE, session))
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