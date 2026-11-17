package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Session and crypto-store password stay in Keystore-encrypted, no-backup storage. */
object DumberAccount {
    private var cached: JSONObject? = null
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "dpad-dumber-account"
        if (!store.containsAlias(alias)) KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey(alias, null) as SecretKey
    }
    fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).toString()
    }
    fun open(text: String): String {
        val value = JSONObject(text)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(value.getString("iv"), Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(value.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8)
    }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "dumber-account"))
    @Synchronized fun read(context: Context): JSONObject {
        if (cached == null) {
            val file = file(context)
            cached = if (!file.baseFile.exists()) JSONObject() else {
                val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
                JSONObject(String(cipher.doFinal(Base64.decode(data.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
            }
        }
        return JSONObject(cached.toString())
    }
    @Synchronized fun update(context: Context, change: (JSONObject) -> Unit) {
        val state = read(context); change(state)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val target = file(context); val output = target.startWrite()
        try { output.write(encrypted.toString().toByteArray()); target.finishWrite(output); cached = state }
        catch (e: Exception) { target.failWrite(output); throw e }
    }
    fun ids(context: Context): List<String> = read(context).optJSONObject("accounts")?.keys()?.asSequence().orEmpty().toList()
    fun state(context: Context, account: String): JSONObject =
        read(context).optJSONObject("accounts")?.optJSONObject(account) ?: JSONObject()
    fun updateAccount(context: Context, account: String, change: (JSONObject) -> Unit) = update(context) { root ->
        root.optJSONObject("accounts")?.optJSONObject(account)?.let(change)
    }
    internal fun storedSession(state: JSONObject): Session? = state.optString("session").takeIf { it.isNotBlank() }?.let(ChatStorage::decodeSession)
    fun session(context: Context, account: String): Session? = storedSession(state(context, account))
    fun connected(context: Context) = runCatching { ids(context).isNotEmpty() }.getOrDefault(false)
    fun enabled(context: Context, account: String? = null): Boolean = if (account == null) ids(context).any { enabled(context, it) }
        else state(context, account).let { it.has("session") && it.optBoolean("enabled", true) }
    fun forSim(context: Context, sim: DumberSubscriptions.Sim?): String? {
        if (sim == null) return null
        val binding = read(context).optJSONObject("sim_bindings")?.optJSONObject(sim.id.toString()) ?: return null
        if (sim.cardId >= 0 && binding.optInt("card_id", -1) >= 0 && sim.cardId != binding.optInt("card_id")) return null
        val account = binding.optString("account")
        if (!enabled(context, account) || (sim.number != null && binding.optString("reported_number").isNotBlank() && sim.number != binding.optString("reported_number"))) return null
        return account
    }
    fun forSubscription(context: Context, subscription: Int): String? = forSim(context, DumberSubscriptions.selected(context, subscription))
    fun subscription(context: Context, account: String): Int = DumberSubscriptions.active(context).firstOrNull { forSim(context, it) == account }?.id ?: -1
    fun acceptVerification(context: Context, nonce: String, session: Session, sim: DumberSubscriptions.Sim): Boolean {
        var accepted = false
        update(context) { root ->
            val pending = root.optJSONObject("verification") ?: return@update
            if (pending.optString("nonce") != nonce || pending.optInt("subscription", -1) != sim.id) return@update
            if (pending.optInt("card_id", -1) >= 0 && sim.cardId >= 0 && pending.optInt("card_id") != sim.cardId) return@update
            val phone = pending.getString("phone")
            if (System.currentTimeMillis() > pending.optLong("expires")) return@update
            val account = session.accountId
            val accounts = root.optJSONObject("accounts") ?: JSONObject().also { root.put("accounts", it) }
            // Verification installs the new server-issued main session; local history is retained.
            val existing = accounts.optJSONObject(account)
            if (existing == null) accounts.put(account, JSONObject().put("session", ChatStorage.encodeSession(session)).put("phone", phone)
                .put("connected_at", System.currentTimeMillis()).put("store_name", "dumber-" + UUID.randomUUID()))
            else if (existing.optString("phone") != phone) return@update
            else {
                // Every verification replaces the main session on the server.
                // Keep local history, but never reuse another device's crypto database.
                if (storedSession(existing)?.deviceId != session.deviceId) {
                    existing.put("store_name", "dumber-" + UUID.randomUUID())
                    existing.remove("store_password")
                    existing.remove("since")
                }
                existing.put("session", ChatStorage.encodeSession(session)).put("enabled", true).put("connected_at", System.currentTimeMillis())
                existing.remove("sim_removed")
                existing.remove("sim_choice")
                existing.remove("logged_out")
                existing.remove("session_revoked")
            }
            val bindings = root.optJSONObject("sim_bindings") ?: JSONObject().also { root.put("sim_bindings", it) }
            bindings.keys().asSequence().toList().filter { bindings.getJSONObject(it).optString("account") == account }.forEach { bindings.remove(it) }
            bindings.put(sim.id.toString(), JSONObject().put("account", account).put("card_id", sim.cardId).put("label", sim.label).put("reported_number", sim.number))
            root.remove("verification"); root.remove("error"); accepted = true
        }
        if (accepted) SimRemovalUi.refresh(context)
        return accepted
    }
    /** Ignore stale in-flight responses from a previous main session. */
    fun revoked(context: Context, account: String, token: String) {
        updateAccount(context, account) { state ->
            if (storedSession(state)?.credentials == token) {
                state.put("enabled", false).put("session_revoked", true)
                state.remove("sim_choice")
            }
        }
        SimRemovalUi.refresh(context)
    }
    /** Revoke only this phone's session, retaining account identity and local history. */
    fun logout(context: Context, account: String): Boolean {
        var changed = false
        update(context) { root ->
            val state = root.optJSONObject("accounts")?.optJSONObject(account) ?: return@update
            if (!state.has("session") || !state.optBoolean("enabled", true)) return@update
            val pending = root.optJSONObject("pending_logouts") ?: JSONObject().also { root.put("pending_logouts", it) }
            pending.put(UUID.randomUUID().toString(), JSONObject().put("session", state.getString("session")))
            state.put("enabled", false).put("logged_out", true)
            state.remove("sim_removed")
            state.remove("sim_choice")
            if (root.optJSONObject("verification")?.optString("phone") == state.optString("phone")) {
                root.remove("verification")
                root.remove("error")
            }
            changed = true
        }
        if (changed) SimRemovalUi.refresh(context)
        return changed
    }
    fun logoutPending(context: Context, account: String): Boolean {
        val pending = read(context).optJSONObject("pending_logouts") ?: return false
        return pending.keys().asSequence().any {
            storedSession(pending.getJSONObject(it))?.accountId == account
        }
    }
    /** Record the choice without disabling reception or queuing a logout. */
    @Synchronized fun reviewMissing(context: Context, active: Set<Int>): Boolean {
        val root = read(context)
        val bindings = root.optJSONObject("sim_bindings") ?: JSONObject()
        val accounts = root.optJSONObject("accounts") ?: return false
        val missing = accounts.keys().asSequence().associateWith { account ->
            val state = accounts.getJSONObject(account)
            state.has("session") && state.optBoolean("enabled", true) &&
                bindings.keys().asSequence().none { it.toIntOrNull() in active &&
                    bindings.getJSONObject(it).optString("account") == account }
        }
        val changes = missing.filter { (id, absent) ->
            absent != accounts.getJSONObject(id).has("sim_choice")
        }
        if (changes.isEmpty()) return false
        update(context) { next ->
            for ((id, absent) in changes) {
                val state = next.getJSONObject("accounts").getJSONObject(id)
                if (absent) state.put("sim_choice", JSONObject()
                    .put("nonce", UUID.randomUUID().toString()).put("keep", false))
                else state.remove("sim_choice")
            }
        }
        return true
    }
    fun pendingRemovals(context: Context): List<RemovalPrompt> {
        val accounts = read(context).optJSONObject("accounts") ?: return emptyList()
        return accounts.keys().asSequence().mapNotNull { id ->
            val state = accounts.getJSONObject(id)
            val choice = state.optJSONObject("sim_choice")
            if (state.has("session") && state.optBoolean("enabled", true) &&
                choice != null && !choice.optBoolean("keep"))
                RemovalPrompt(id, choice.getString("nonce"), state.optString("phone"))
            else null
        }.toList()
    }
    @Synchronized fun chooseRemoval(context: Context, prompt: RemovalPrompt, keep: Boolean) {
        val state = state(context, prompt.account)
        val choice = state.optJSONObject("sim_choice") ?: return
        if (!state.optBoolean("enabled", true) || choice.optString("nonce") != prompt.nonce ||
            choice.optBoolean("keep")) return
        if (keep) updateAccount(context, prompt.account) {
            it.getJSONObject("sim_choice").put("keep", true)
        } else logout(context, prompt.account)
    }
    fun removalWarnings(context: Context): List<String> = pendingRemovals(context).map {
        "SIM unavailable for ${it.phone}. Choose whether to keep dumber.chat connected."
    }
    fun storeName(context: Context, account: String): String = state(context, account).getString("store_name")
    @Synchronized fun password(context: Context, account: String): String {
        check(state(context, account).has("session")) { "Account unavailable" }
        if (!state(context, account).has("store_password")) updateAccount(context, account) { it.put("store_password", UUID.randomUUID().toString() + UUID.randomUUID()) }
        return state(context, account).getString("store_password")
    }
}
