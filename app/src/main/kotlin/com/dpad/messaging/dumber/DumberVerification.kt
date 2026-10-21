package com.dpad.messaging.dumber

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DumberVerification {
    suspend fun begin(context: Context, phone: String, subscription: Int) = withContext(Dispatchers.IO) {
        val sim = DumberSubscriptions.selected(context, subscription)
        require(sim != null) { "Select an active SIM first" }
        val nonce = UUID.randomUUID().toString()
        DumberAccount.update(context) {
            it.put("verification", JSONObject().put("phone", phone).put("nonce", nonce).put("subscription", sim.id)
                .put("card_id", sim.cardId).put("label", sim.label)
                .put("started", System.currentTimeMillis()).put("expires", System.currentTimeMillis() + 300_000))
            it.remove("error")
        }
        try { DumberCore.call(DumberCore.request(context, "request_code").put("phone", phone)) }
        catch (e: Exception) {
            DumberAccount.update(context) { if (it.optJSONObject("verification")?.optString("nonce") == nonce) it.remove("verification") }
            throw e
        }
    }
    fun receive(context: Context, body: String, timestamp: Long, subscription: Int) {
        val pending = DumberAccount.read(context).optJSONObject("verification") ?: return
        // Missing subscription metadata requires manual entry; never attribute a code to the wrong SIM.
        if (subscription < 0 || pending.optInt("subscription", -1) != subscription) return
        if (timestamp < pending.optLong("started") - 30_000 || System.currentTimeMillis() > pending.optLong("expires")) return
        val code = DumberProtocol.verificationCode(body) ?: return
        submit(context, code, subscription)
    }
    fun submit(context: Context, code: String, subscription: Int) {
        if (!Regex("[0-9]{6}").matches(code)) return
        var nonce: String? = null
        DumberAccount.update(context) { state ->
            val pending = state.optJSONObject("verification") ?: return@update
            if (subscription < 0 || pending.optInt("subscription", -1) != subscription) return@update
            if (System.currentTimeMillis() > pending.optLong("expires") || pending.has("code")) return@update
            if (pending.optString("tried").split(',').contains(code)) return@update
            pending.put("code", code)
            state.remove("error")
            pending.put("tried", pending.optString("tried") + "," + code)
            nonce = pending.getString("nonce")
        }
        nonce?.let {
            WorkManager.getInstance(context).enqueueUniqueWork("dumber-verification", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DumberVerificationWorker>()
                    .setInputData(workDataOf("nonce" to it)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}

class DumberVerificationWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pending = DumberAccount.read(applicationContext).optJSONObject("verification") ?: return@withContext Result.success()
        val nonce = inputData.getString("nonce") ?: return@withContext Result.success()
        if (pending.optString("nonce") != nonce || System.currentTimeMillis() > pending.optLong("expires")) return@withContext Result.success()
        val code = pending.optString("code")
        if (code.isBlank()) return@withContext Result.success()
        var verified: JSONObject? = null
        try {
            check(DumberSubscriptions.selected(applicationContext, pending.getInt("subscription")) != null) { "SIM unavailable" }
            val session = DumberCore.call(DumberCore.request(applicationContext, "verify").put("phone", pending.getString("phone")).put("code", code))
            verified = session
            val sim = DumberSubscriptions.selected(applicationContext, pending.getInt("subscription"))
            check(sim != null && DumberAccount.acceptVerification(applicationContext, nonce, session, sim)) { "SIM changed during verification" }
            DumberSyncService.ensureStarted(applicationContext)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            DumberAccount.update(applicationContext) {
                if (it.optJSONObject("verification")?.optString("nonce") == nonce) {
                    it.getJSONObject("verification").remove("code")
                    it.put("error", "Verification failed. Check the selected SIM and code, or request a new code.")
                }
            }
        } finally {
            // Dispose of a verification session only if SIM validation prevented adopting it.
            verified?.let { session ->
                val retained = DumberAccount.state(applicationContext, session.getString("user_id")).optJSONObject("session")
                if (retained?.optString("access_token") != session.optString("access_token")) runCatching {
                    DumberCore.call(DumberCore.request(applicationContext, "logout").put("session", session))
                }
            }
        }
        Result.success()
    }
}
