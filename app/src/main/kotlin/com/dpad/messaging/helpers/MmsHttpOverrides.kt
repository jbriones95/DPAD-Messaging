package com.dpad.messaging.helpers

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.dpad.messaging.BuildConfig
import com.klinker.android.send_message.MmsRequestOverrides

/**
 * Applies the tested Verizon handset identity to MMS requests when needed.
 * Verizon's MMSC uses the receiving device's UAProf to transcode audio;
 * Android's default profile can result in unsupported QCELP audio.
 */
object MmsHttpOverrides {
    private const val TAG = "DPAD_MSG"
    private const val VERIZON_CARRIER_ID = 1839
    private const val VERIZON_USER_AGENT = "T408DL-MMS/2.0"
    private const val VERIZON_UA_PROF =
        "http://uaprof.vtext.com/alcatel/wst408dl/wst408dl.xml"
    private val VERIZON_MCC_MNC = setOf(
        "310004", "310010", "310012", "310013",
        "311480", "311481", "311482", "311483", "311484",
        "311485", "311486", "311487", "311488", "311489"
    )

    fun install() {
        MmsRequestOverrides.setProvider(object : MmsRequestOverrides.Provider {
            override fun apply(context: Context, subId: Int, configOverrides: Bundle) {
                applyForMms(context, subId, configOverrides)
            }

            override fun appendTransactionId(context: Context, subId: Int): Boolean = false

            override fun shouldAutoDownload(context: Context, subId: Int): Boolean =
                MmsDownloadPolicy.shouldAutoDownload()

            override fun onDownloadDeferred(context: Context, messageUri: android.net.Uri, subId: Int) {
                MmsDownloadPolicy.defer(context, messageUri, subId)
            }
        })
    }

    internal fun isVerizonOperator(operator: String): Boolean =
        operator.filter { it.isDigit() } in VERIZON_MCC_MNC

    fun applyForMms(context: Context, subId: Int, configOverrides: Bundle) {
        if (!isVerizonSim(context, subId)) return
        configOverrides.putString(SmsManager.MMS_CONFIG_USER_AGENT, VERIZON_USER_AGENT)
        configOverrides.putString(SmsManager.MMS_CONFIG_UA_PROF_URL, VERIZON_UA_PROF)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Applied Verizon MMS UAProf for subId=$subId")
        }
    }

    private fun isVerizonSim(context: Context, subId: Int): Boolean {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return false
        val telephony = if (subId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
        } else {
            base
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching { telephony.simCarrierId }.getOrDefault(-1) == VERIZON_CARRIER_ID
        ) return true
        return isVerizonOperator(runCatching { telephony.simOperator }.getOrNull().orEmpty())
    }
}
