package com.dpad.messaging.dumber

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
    // Legacy account remains in its original file/store; additional accounts are separate entries.
    fun ids(context: Context): List<String> {
        val root = read(context)
        return (listOfNotNull(root.optJSONObject("session")?.optString("user_id")?.takeIf { it.isNotBlank() }) +
            root.optJSONObject("accounts")?.keys()?.asSequence().orEmpty().toList()).distinct()
    }
    fun state(context: Context, account: String): JSONObject {
        val root = read(context)
        return if (root.optJSONObject("session")?.optString("user_id") == account) root
        else root.optJSONObject("accounts")?.optJSONObject(account) ?: JSONObject()
    }
    fun updateAccount(context: Context, account: String, change: (JSONObject) -> Unit) = update(context) { root ->
        if (root.optJSONObject("session")?.optString("user_id") == account) change(root)
        else root.optJSONObject("accounts")?.optJSONObject(account)?.let(change)
    }
    fun connected(context: Context) = runCatching { ids(context).isNotEmpty() }.getOrDefault(false)
    fun enabled(context: Context, account: String? = null): Boolean = if (account == null) ids(context).any { enabled(context, it) }
        else state(context, account).let { it.has("session") && it.optBoolean("enabled", true) }
    fun needsLegacyLink(context: Context): Boolean {
        val root = read(context)
        val legacy = root.optJSONObject("session")?.optString("user_id") ?: return false
        val bindings = root.optJSONObject("sim_bindings") ?: return true
        return bindings.keys().asSequence().none { bindings.getJSONObject(it).optString("account") == legacy }
    }
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
    fun acceptVerification(context: Context, nonce: String, session: JSONObject, sim: DumberSubscriptions.Sim): Boolean {
        var accepted = false
        update(context) { root ->
            val pending = root.optJSONObject("verification") ?: return@update
            if (pending.optString("nonce") != nonce || pending.optInt("subscription", -1) != sim.id) return@update
            if (pending.optInt("card_id", -1) >= 0 && sim.cardId >= 0 && pending.optInt("card_id") != sim.cardId) return@update
            val phone = pending.getString("phone")
            if (System.currentTimeMillis() > pending.optLong("expires")) return@update
            val account = session.getString("user_id")
            val accounts = root.optJSONObject("accounts") ?: JSONObject().also { root.put("accounts", it) }
            // Verification installs the new server-issued main session; local history is retained.
            val existing = if (root.optJSONObject("session")?.optString("user_id") == account) root else accounts.optJSONObject(account)
            if (existing == null) accounts.put(account, JSONObject().put("session", session).put("phone", phone)
                .put("connected_at", System.currentTimeMillis()).put("store_name", "dumber-" + UUID.randomUUID()))
            else if (existing.optString("phone") != phone) return@update
            else {
                // Every verification replaces the main session on the server.
                // Keep local history, but never reuse another device's crypto database.
                if (existing.optJSONObject("session")?.optString("device_id") != session.optString("device_id")) {
                    existing.put("store_name", "dumber-" + UUID.randomUUID())
                    existing.remove("store_password")
                    existing.remove("since")
                }
                existing.put("session", session).put("enabled", true).put("connected_at", System.currentTimeMillis())
                existing.remove("sim_removed")
                existing.remove("logged_out")
                existing.remove("session_revoked")
            }
            val bindings = root.optJSONObject("sim_bindings") ?: JSONObject().also { root.put("sim_bindings", it) }
            bindings.keys().asSequence().toList().filter { bindings.getJSONObject(it).optString("account") == account }.forEach { bindings.remove(it) }
            bindings.put(sim.id.toString(), JSONObject().put("account", account).put("card_id", sim.cardId).put("label", sim.label).put("reported_number", sim.number))
            root.remove("verification"); root.remove("error"); accepted = true
        }
        return accepted
    }
    /** Ignore stale in-flight responses from a previous main session. */
    fun revoked(context: Context, account: String, token: String) {
        updateAccount(context, account) { state ->
            if (state.optJSONObject("session")?.optString("access_token") == token) {
                state.put("enabled", false).put("session_revoked", true)
            }
        }
    }
    /** Revoke only this phone's session, retaining account identity and local history. */
    fun logout(context: Context, account: String): Boolean {
        var changed = false
        update(context) { root ->
            val state = if (root.optJSONObject("session")?.optString("user_id") == account) root
                else root.optJSONObject("accounts")?.optJSONObject(account) ?: return@update
            if (!state.has("session") || !state.optBoolean("enabled", true)) return@update
            val pending = root.optJSONObject("pending_logouts") ?: JSONObject().also { root.put("pending_logouts", it) }
            pending.put(UUID.randomUUID().toString(), JSONObject().put("session", JSONObject(state.getJSONObject("session").toString())))
            state.put("enabled", false).put("logged_out", true)
            state.remove("sim_removed")
            if (root.optJSONObject("verification")?.optString("phone") == state.optString("phone")) {
                root.remove("verification")
                root.remove("error")
            }
            changed = true
        }
        return changed
    }
    fun logoutPending(context: Context, account: String): Boolean {
        val pending = read(context).optJSONObject("pending_logouts") ?: return false
        return pending.keys().asSequence().any {
            pending.getJSONObject(it).optJSONObject("session")?.optString("user_id") == account
        }
    }
    /** Disable first, keeping history and an encrypted logout request for offline retries. */
    fun disconnectMissing(context: Context, active: Set<Int>): List<String> {
        val removed = mutableListOf<String>()
        update(context) { root ->
            val bindings = root.optJSONObject("sim_bindings") ?: JSONObject()
            val pending = root.optJSONObject("pending_logouts") ?: JSONObject()
            val accounts = (listOfNotNull(root.optJSONObject("session")?.optString("user_id")) +
                root.optJSONObject("accounts")?.keys()?.asSequence().orEmpty().toList()).distinct()
            for (account in accounts) {
                val linked = bindings.keys().asSequence().filter { bindings.getJSONObject(it).optString("account") == account }.toList()
                if (linked.any { it.toIntOrNull() in active }) continue
                val binding = linked.firstOrNull()?.let { bindings.getJSONObject(it) }
                val state = if (root.optJSONObject("session")?.optString("user_id") == account) root
                    else root.optJSONObject("accounts")?.optJSONObject(account) ?: continue
                if (!state.optBoolean("enabled", true) || !state.has("session")) continue
                val label = binding?.optString("label")?.takeIf { it.isNotBlank() } ?: "Number"
                val warning = "$label (${state.optString("phone")}) was disconnected from dumber.chat because no active SIM is linked to it. Linked browsers will also be signed out when the server receives the logout. Insert or enable the SIM and verify its number to reconnect."
                state.put("enabled", false).put("sim_removed", warning)
                pending.put(UUID.randomUUID().toString(), JSONObject().put("session", state.getJSONObject("session")))
                removed.add(warning)
            }
            if (pending.length() > 0) root.put("pending_logouts", pending)
        }
        return removed
    }
    fun removalWarnings(context: Context): List<String> = ids(context).mapNotNull {
        state(context, it).optString("sim_removed").takeIf(String::isNotBlank)
    }
    fun storeName(context: Context, account: String): String = state(context, account).optString("store_name", "dumber-crypto")
    @Synchronized fun password(context: Context, account: String? = null): String {
        if (account == null) {
            if (!read(context).has("store_password")) update(context) { it.put("store_password", UUID.randomUUID().toString() + UUID.randomUUID()) }
            return read(context).getString("store_password")
        }
        check(state(context, account).has("session")) { "Account unavailable" }
        if (!state(context, account).has("store_password")) updateAccount(context, account) { it.put("store_password", UUID.randomUUID().toString() + UUID.randomUUID()) }
        return state(context, account).getString("store_password")
    }
}
