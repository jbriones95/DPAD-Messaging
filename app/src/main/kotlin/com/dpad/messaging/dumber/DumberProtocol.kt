package com.dpad.messaging.dumber

/** Pure rules shared by the receiver, transport and UI. */
object DumberProtocol {
    const val MARKER = "dumber.chat:encrypted:"
    fun encryptedMarker(value: String?) = value?.startsWith(MARKER) == true && value.length > MARKER.length
    fun verificationCode(body: String): String? {
        if (!Regex("(?i)(verification|verify|code|kod)").containsMatchIn(body)) return null
        val codes = Regex("(?<![0-9])[0-9]{6}(?![0-9])").findAll(body).map { it.value }.toList()
        return codes.singleOrNull()
    }
}

/** Unknown availability requires a decision; network failures never select SMS. */
internal enum class DeliveryRoute { CARRIER, ENCRYPTED, CONFIRM_SMS }
internal fun deliveryRoute(enabled: Boolean, peer: String?, absent: Boolean): DeliveryRoute = when {
    !enabled -> DeliveryRoute.CARRIER
    peer != null -> DeliveryRoute.ENCRYPTED
    absent -> DeliveryRoute.CARRIER
    else -> DeliveryRoute.CONFIRM_SMS
}
