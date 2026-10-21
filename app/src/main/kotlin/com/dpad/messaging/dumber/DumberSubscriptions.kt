package com.dpad.messaging.dumber

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Subscription IDs identify subscriptions, never SIM slot positions. Numbers are hints, not proof. */
object DumberSubscriptions {
    data class Sim(val id: Int, val label: String, val number: String?, val cardId: Int = -1)
    fun active(context: Context): List<Sim> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching { manager.activeSubscriptionInfoList.orEmpty().map { info ->
            val raw = runCatching {
                if (Build.VERSION.SDK_INT >= 33) manager.getPhoneNumber(info.subscriptionId)
                else if (Build.VERSION.SDK_INT >= 24) context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId).line1Number
                else info.number
            }.getOrNull()
            val number = raw?.takeIf { it.isNotBlank() }?.let { DumberCore.international(context, it, info.subscriptionId) }
            Sim(info.subscriptionId, info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${info.simSlotIndex + 1}", number,
                if (Build.VERSION.SDK_INT >= 29) info.cardId else -1)
        } }.getOrDefault(emptyList())
    }
    /** Null means telephony is not reliable enough to decide that a SIM disappeared. */
    fun stableActiveIds(context: Context): Set<Int>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        if (android.os.SystemClock.elapsedRealtime() < 60_000) return null
        if (android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0) return null
        return runCatching {
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
            // UNKNOWN, NOT_READY, PIN/PUK and radio transitions are not removal evidence.
            for (slot in 0 until telephony.phoneCount) {
                val state = if (Build.VERSION.SDK_INT >= 26) telephony.getSimState(slot) else telephony.simState
                if (state != TelephonyManager.SIM_STATE_ABSENT && state != TelephonyManager.SIM_STATE_READY) return null
            }
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val subscriptions = manager.activeSubscriptionInfoList.orEmpty()
            // A ready card with no subscription is still being initialized.
            for (slot in 0 until telephony.phoneCount) {
                val state = if (Build.VERSION.SDK_INT >= 26) telephony.getSimState(slot) else telephony.simState
                if (state == TelephonyManager.SIM_STATE_READY && subscriptions.none { it.simSlotIndex == slot }) return null
            }
            subscriptions.map { it.subscriptionId }.toSet()
        }.getOrNull()
    }
    fun selected(context: Context, subscription: Int): Sim? {
        val id = if (subscription >= 0) subscription else SubscriptionManager.getDefaultSmsSubscriptionId()
        return active(context).singleOrNull { it.id == id }
    }
    fun receivedSubscription(intent: android.content.Intent): Int = intent.getIntExtra(
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
}
