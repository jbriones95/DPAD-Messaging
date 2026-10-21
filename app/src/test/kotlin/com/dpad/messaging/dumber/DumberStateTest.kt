package com.dpad.messaging.dumber

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** JVM provider only: exercise actual AES-GCM files/SQLite without a device Keystore. */
class TestAndroidKeyStore : KeyStoreSpi() {
    companion object { val keys = mutableMapOf<String, Key>() }
    override fun engineGetKey(alias: String, password: CharArray?) = keys[alias]
    override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
    override fun engineAliases() = Collections.enumeration(keys.keys)
    override fun engineSize() = keys.size
    override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineGetCreationDate(alias: String) = Date()
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
class TestAndroidKeyGenerator : KeyGeneratorSpi() {
    private var alias = ""
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(size: Int, random: SecureRandom?) = Unit
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) { alias = (params as KeyGenParameterSpec).keystoreAlias }
    override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { TestAndroidKeyStore.keys[alias] = it }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DumberStateTest {
    private lateinit var context: Context
    private val self = "@self:example.test"
    private val ownPhone = "+15555550100"
    private val phone = "+15555550101"
    private val peer = "@peer:example.test"
    @Before fun setup() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Test provider") {
            init {
                put("KeyStore.AndroidKeyStore", TestAndroidKeyStore::class.java.name)
                put("KeyGenerator.AES", TestAndroidKeyGenerator::class.java.name)
            }
        })
        context = ApplicationProvider.getApplicationContext()
        DumberAccount.update(context) {
            it.keys().asSequence().toList().forEach { key -> it.remove(key) }
            it.put("session", JSONObject().put("user_id", self)).put("phone", ownPhone)
            it.remove("directory"); it.remove("lookup_retry_at"); it.remove("lookup_retry_account")
        }
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        sims(1)
        link(self, ownPhone, 1)
    }
    private fun sims(vararg ids: Int) {
        val manager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setActiveSubscriptionInfos(*ids.mapIndexed { slot, id ->
            org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                .setId(id).setSimSlotIndex(slot).setDisplayName("SIM $id").setCountryIso("us").buildSubscriptionInfo()
        }.toTypedArray())
        org.robolectric.shadows.ShadowSubscriptionManager.setDefaultSmsSubscriptionId(ids.firstOrNull() ?: -1)
    }
    private fun link(account: String, phone: String, subscription: Int, card: Int = -1) {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "test-$subscription")
            .put("phone", phone).put("subscription", subscription).put("card_id", card).put("expires", System.currentTimeMillis() + 60000)) }
        assertTrue(DumberAccount.acceptVerification(context, "test-$subscription", JSONObject().put("user_id", account),
            DumberSubscriptions.Sim(subscription, "SIM $subscription", null, card)))
    }
    @Test fun replacementAdoptsSessionAndChangesCryptoStoreWithoutDroppingAccountHistory() {
        DumberAccount.update(context) {
            it.getJSONObject("session").put("device_id", "old").put("access_token", "old-token")
            it.put("verification", JSONObject().put("nonce", "replace").put("phone", ownPhone)
                .put("subscription", 1).put("expires", System.currentTimeMillis() + 60000))
        }
        val oldStore = DumberAccount.storeName(context, self)
        val oldPassword = DumberAccount.password(context, self)
        assertTrue(DumberAccount.acceptVerification(context, "replace",
            JSONObject().put("user_id", self).put("device_id", "new").put("access_token", "new-token"),
            DumberSubscriptions.Sim(1, "SIM 1", null)))
        assertEquals("new-token", DumberAccount.state(context, self).getJSONObject("session").getString("access_token"))
        assertNotEquals(oldStore, DumberAccount.storeName(context, self))
        assertNotEquals(oldPassword, DumberAccount.password(context, self))
        assertTrue(DumberAccount.ids(context).contains(self))
        DumberAccount.revoked(context, self, "old-token")
        assertTrue(DumberAccount.enabled(context, self))
        DumberAccount.revoked(context, self, "new-token")
        assertFalse(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.state(context, self).optBoolean("session_revoked"))
    }
    @Test fun manualLogoutOnlyDisablesSelectedAccountAndPreservesItsStore() {
        val other = "@other:example.test"
        sims(1, 2)
        link(other, "+15555550102", 2)
        val store = DumberAccount.storeName(context, other)
        val password = DumberAccount.password(context, other)
        assertTrue(DumberAccount.logout(context, other))
        assertFalse(DumberAccount.enabled(context, other))
        assertTrue(DumberAccount.enabled(context, self))
        assertNull(DumberAccount.forSubscription(context, 2))
        assertEquals(store, DumberAccount.storeName(context, other))
        assertEquals(password, DumberAccount.password(context, other))
        assertTrue(DumberAccount.removalWarnings(context).isEmpty())
        assertFalse(DumberAccount.logout(context, other))
        assertEquals(1, DumberAccount.read(context).getJSONObject("pending_logouts").length())
        assertTrue(DumberAccount.logoutPending(context, other))
        assertFalse(DumberDisconnect.flush(context) { throw java.io.IOException("offline") })
        assertTrue(DumberAccount.logoutPending(context, other))
        assertTrue(DumberDisconnect.flush(context) {
            assertEquals(other, it.getJSONObject("session").getString("user_id"))
            JSONObject()
        })
        assertFalse(DumberAccount.logoutPending(context, other))
        assertTrue(DumberAccount.state(context, other).optBoolean("logged_out"))
        link(other, "+15555550102", 2)
        assertTrue(DumberAccount.enabled(context, other))
        assertFalse(DumberAccount.state(context, other).optBoolean("logged_out"))
    }
    @Test fun manualLogoutCancelsMatchingVerificationAndRetainsOldSessionForRetry() {
        DumberAccount.update(context) {
            it.getJSONObject("session").put("access_token", "old")
            it.put("verification", JSONObject().put("phone", ownPhone).put("nonce", "old-verification"))
        }
        assertTrue(DumberAccount.logout(context, self))
        assertFalse(DumberAccount.read(context).has("verification"))
        link(self, ownPhone, 1)
        assertTrue(DumberDisconnect.flush(context) {
            assertEquals("old", it.getJSONObject("session").getString("access_token"))
            JSONObject()
        })
        assertTrue(DumberAccount.enabled(context, self))
    }
    @Test fun manualLogoutPreservesOtherNumbersVerification() {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("phone", "+15555550102")) }
        assertTrue(DumberAccount.logout(context, self))
        assertTrue(DumberAccount.read(context).has("verification"))
    }
    @Test fun removedSimDisconnectsOnlyItsAccountAndKeepsHistoryIdentity() {
        val other = "@other:example.test"
        sims(1, 2)
        link(other, "+15555550102", 2)
        val store = DumberAccount.storeName(context, self)
        val warnings = DumberAccount.disconnectMissing(context, setOf(2))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(ownPhone))
        assertFalse(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.enabled(context, other))
        assertNull(DumberAccount.forSubscription(context, 1))
        assertEquals(other, DumberAccount.forSubscription(context, 2))
        assertEquals(store, DumberAccount.storeName(context, self))
        assertEquals(1, DumberAccount.read(context).getJSONObject("pending_logouts").length())
        assertTrue(DumberAccount.disconnectMissing(context, setOf(2)).isEmpty())
        assertEquals(1, DumberAccount.removalWarnings(context).size)
    }
    @Test fun unboundLegacyAccountIsDisconnectedWhileLinkedAccountStaysEnabled() {
        DumberAccount.update(context) { it.remove("sim_bindings") }
        link("@comviq:example.test", "+15555550102", 2)
        val warnings = DumberAccount.disconnectMissing(context, setOf(2))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(ownPhone))
        assertFalse(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.enabled(context, "@comviq:example.test"))
    }
    @Test fun removingLastSimDisablesAllReception() {
        DumberAccount.disconnectMissing(context, emptySet())
        assertFalse(DumberAccount.enabled(context))
    }
    @Test fun offlineLogoutRetriesAndDoesNotReenableAccount() {
        DumberAccount.disconnectMissing(context, emptySet())
        assertFalse(DumberDisconnect.flush(context) { throw java.io.IOException("offline") })
        assertFalse(DumberAccount.enabled(context, self))
        var calls = 0
        assertTrue(DumberDisconnect.flush(context) {
            calls++
            assertEquals(self, it.getJSONObject("session").getString("user_id"))
            assertEquals("logout", it.getString("command"))
            JSONObject()
        })
        assertEquals(1, calls)
        assertTrue(DumberDisconnect.flush(context) { error("already logged out") })
    }
    @Test fun revokedSessionCompletesLogout() {
        DumberAccount.disconnectMissing(context, emptySet())
        assertTrue(DumberDisconnect.flush(context) { throw DumberException("revoked", 401) })
    }
    @Test fun reconnectRequiresVerificationAndOldLogoutCannotRevokeNewSession() {
        DumberAccount.updateAccount(context, self) { it.getJSONObject("session").put("access_token", "old") }
        DumberAccount.disconnectMissing(context, emptySet())
        sims(1)
        assertNull(DumberAccount.forSubscription(context, 1))
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "reconnect")
            .put("phone", ownPhone).put("subscription", 1).put("expires", System.currentTimeMillis() + 60000)) }
        assertTrue(DumberAccount.acceptVerification(context, "reconnect",
            JSONObject().put("user_id", self).put("access_token", "new"), DumberSubscriptions.Sim(1, "SIM 1", null)))
        assertTrue(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.removalWarnings(context).isEmpty())
        assertTrue(DumberDisconnect.flush(context) {
            assertEquals("old", it.getJSONObject("session").getString("access_token")); JSONObject()
        })
        assertEquals("new", DumberAccount.state(context, self).getJSONObject("session").getString("access_token"))
    }
    @Test fun uncertainOrBrieflyMissingSubscriptionsDoNotDisconnect() {
        val monitor = DumberSimMonitor()
        monitor.check(context, emptySet(), 0)
        monitor.check(context, emptySet(), 4999)
        assertTrue(DumberAccount.enabled(context, self))
        monitor.check(context, null, 5000)
        monitor.check(context, emptySet(), 6000)
        monitor.check(context, emptySet(), 10999)
        assertTrue(DumberAccount.enabled(context, self))
        monitor.check(context, setOf(1), 11000)
        monitor.check(context, setOf(1), 20000)
        assertTrue(DumberAccount.enabled(context, self))
    }
    @Test fun unavailableRecipientDialogOffersSmsAndCancelKeepsMessage() {
        val controller = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = controller.setup().get()
        val message = DumberOutbox.message(self, phone, "keep this draft", 880, 1, null)
            .put("recipient_unavailable", true).put("reason", "Recipient no longer available")
        val id = message.getString("id")
        DumberDirectory.remember(context, self, phone, null)
        DumberOutbox(context).use { it.save(message) }
        try {
            val status = android.widget.Button(activity)
            val ui = DumberConversationUi(activity, status, android.widget.Button(activity), { phone }, { 880 }, { 1 }, { false }, { true })
            ui.render()
            assertTrue(status.text.toString().startsWith(activity.getString(com.dpad.messaging.R.string.dumber_status_sms)))
            ui.refreshPending(prompt = true)
            var dialog: androidx.appcompat.app.AlertDialog? = null
            repeat(200) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
                if (dialog == null) Thread.sleep(10)
            }
            val shown = requireNotNull(dialog)
            assertEquals(activity.getString(com.dpad.messaging.R.string.dumber_send_sms), shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text.toString())
            assertTrue(shown.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString().contains("Send this message as regular SMS?"))
            shown.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            DumberOutbox(context).use { assertNotNull(it.get(id)) }
        } finally {
            DumberOutbox(context).use { it.delete(id) }
            controller.pause().stop().destroy()
        }
    }
    @Test fun rejectionDemotesRecipientAndPreservesPendingMessageAndUncertainty() = runBlocking {
        for (uncertain in listOf(false, true)) {
            DumberDirectory.remember(context, self, phone, peer, authoritative = true)
            val message = DumberOutbox.message(self, phone, "keep rejected text", 778, 1, null)
                .put("peer", peer).put("uncertain", uncertain)
            val id = message.getString("id")
            DumberOutbox(context).use { it.save(message) }
            try {
                assertFalse(DumberDelivery.retry(context, id) { _, _ ->
                    throw DumberException("Recipient no longer available", 403, errorCode = "DUMBER_RECIPIENT_UNAVAILABLE")
                })
                val status = DumberDirectory.cached(context, phone, account = self)
                assertTrue(status.absent); assertNull(status.peer)
                DumberOutbox(context).use { queue ->
                    val saved = queue.get(id)!!
                    assertTrue(saved.getBoolean("recipient_unavailable"))
                    assertEquals(uncertain, saved.getBoolean("uncertain"))
                    assertEquals("keep rejected text", saved.getString("body"))
                    assertEquals(1, saved.getInt("subscription"))
                    assertFalse(saved.optBoolean("confirmed"))
                }
                assertTrue(DumberDelivery.send(context, phone, "next text", 778, 1, null))
                DumberOutbox(context).use { queue ->
                    val next = queue.list(self, 778).firstOrNull { it.optString("body") == "next text" }
                    assertNotNull(next)
                    assertTrue(next!!.optBoolean("recipient_unavailable"))
                }
            } finally {
                DumberOutbox(context).use { queue ->
                    queue.delete(id)
                    queue.list(self, 778).filter { it.optString("body") == "next text" }
                        .forEach { queue.delete(it.getString("id")) }
                }
            }
        }
    }
    @Test fun permissionAndNetworkFailuresNeverDemoteRecipient() = runBlocking {
        DumberDirectory.remember(context, self, phone, peer)
        for (error in listOf(DumberException("forbidden", 403), DumberException("offline", 502),
                DumberException("wrong status", 500, errorCode = "DUMBER_RECIPIENT_UNAVAILABLE"))) {
            val message = DumberOutbox.message(self, phone, "pending", 779, 1, null).put("peer", peer)
            val id = message.getString("id")
            DumberOutbox(context).use { it.save(message) }
            try {
                assertFalse(DumberDelivery.retry(context, id) { _, _ -> throw error })
                assertEquals(peer, DumberDirectory.cached(context, phone).peer)
                DumberOutbox(context).use { assertFalse(it.get(id)!!.optBoolean("recipient_unavailable")) }
            } finally { DumberOutbox(context).use { it.delete(id) } }
        }
    }
    @Test fun negativeRoutingSurvivesHistoricalResolutionAndFailedExpiredRecheck() = runBlocking {
        DumberDirectory.remember(context, self, phone, null)
        DumberDirectory.remember(context, self, phone, peer) // old sync/reverse resolution
        assertTrue(DumberDirectory.cached(context, phone).absent)
        DumberDirectory.lookup(context, phone, fetch = { error("24 hour cache should avoid lookup") })
        DumberAccount.updateAccount(context, self) { it.getJSONObject("directory").getJSONObject("recipients").getJSONObject(phone).put("checked", 0) }
        var calls = 0
        assertTrue(DumberDirectory.lookup(context, phone, fetch = { calls++; throw java.io.IOException("offline") }).absent)
        assertTrue(DumberDirectory.lookup(context, phone, fetch = { error("transient backoff") }).absent)
        assertEquals(1, calls)
        assertEquals(peer, DumberDirectory.lookup(context, phone, force = true, fetch = { peer }).peer)
    }
    @Test fun rejectedRecipientDoesNotSpendLookupQuotaOrAffectOtherSim() = runBlocking {
        sims(1, 2); val other = "@other:example.test"; link(other, "+15555550200", 2)
        DumberDirectory.remember(context, self, phone, peer)
        DumberDirectory.remember(context, other, phone, peer)
        DumberDirectory.remember(context, self, phone, null)
        assertEquals(peer, DumberDirectory.cached(context, phone, account = other).peer)
        assertTrue(DumberDirectory.lookup(context, phone, fetch = { error("no lookup after rejection") }).absent)
        DumberAccount.updateAccount(context, self) { it.getJSONObject("directory").getJSONObject("recipients").getJSONObject(phone).put("checked", 0) }
        assertTrue(DumberDirectory.lookup(context, phone, fetch = { throw DumberException("quota", 429, 120) }).absent)
        assertTrue(DumberDirectory.lookup(context, phone, force = true, fetch = { error("must honor account quota") }).absent)
    }
    @Test fun inflightLookupCannotUndoNewerSendRejection() = runBlocking {
        DumberDirectory.remember(context, self, phone, peer)
        val result = DumberDirectory.lookup(context, phone, force = true, fetch = {
            DumberDirectory.remember(context, self, phone, null)
            peer
        })
        assertTrue(result.absent)
        assertNull(DumberDirectory.cached(context, phone).peer)
    }
    @Test fun conversationChecksOnlyOnOpenAndShowsComposerTransport() {
        val controller = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = controller.setup().get()
        val status = android.widget.Button(activity)
        val pending = android.widget.Button(activity)
        var calls = 0
        var carrierOnly = false
        var engineAvailable = true
        val ui = DumberConversationUi(activity, status, pending, { phone }, { 42 }, { -1 }, { carrierOnly }, { engineAvailable }) { number, _, account ->
            calls++
            DumberDirectory.remember(activity, account, number, peer)
            DumberDirectory.Status(peer = peer)
        }
        assertEquals(0, calls)
        ui.render()
        assertEquals(activity.getString(com.dpad.messaging.R.string.dumber_status_unknown) + " · " + ownPhone, status.text.toString())
        ui.open()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        assertEquals(activity.getString(com.dpad.messaging.R.string.dumber_status_encrypted) + " · " + ownPhone, status.text.toString())
        carrierOnly = true
        ui.render()
        assertEquals(activity.getString(com.dpad.messaging.R.string.dumber_status_sms), status.text.toString())
        ui.open()
        assertEquals(1, calls) // Groups/attachments never trigger discovery.
        carrierOnly = false
        engineAvailable = false
        ui.open()
        assertEquals(1, calls)
        assertEquals(activity.getString(com.dpad.messaging.R.string.dumber_status_unknown) + " · " + ownPhone, status.text.toString())
        controller.pause().stop().destroy()
    }
    @Test fun repeatedAndConcurrentOpeningsReuseKnownPeer() = runBlocking {
        var calls = 0
        val results = (1..60).map { async { DumberDirectory.lookup(context, phone, fetch = { calls++; peer }) } }.awaitAll()
        assertEquals(1, calls)
        assertTrue(results.all { it.peer == peer })
        repeat(100) { assertEquals(DeliveryRoute.ENCRYPTED, deliveryRoute(true, DumberDirectory.cached(context, phone).peer, false)) }
    }
    @Test fun onlyDefinitive404DemotesAndNegativeResultsExpire() = runBlocking {
        DumberDirectory.lookup(context, phone, fetch = { peer })
        for (status in listOf(401, 403, 500, 502)) {
            assertEquals(peer, DumberDirectory.lookup(context, phone, force = true, fetch = { throw DumberException("failed", status) }).peer)
        }
        val missing = DumberDirectory.lookup(context, phone, force = true, fetch = { throw DumberException("missing", 404) })
        assertTrue(missing.absent)
        assertTrue(DumberDirectory.lookup(context, phone, fetch = { error("must use negative cache") }).absent)
        assertTrue(DumberDirectory.cached(context, phone, System.currentTimeMillis() + DumberDirectory.NEGATIVE_TTL + 1).absent)
        DumberAccount.update(context) { it.getJSONObject("directory").getJSONObject("recipients").getJSONObject(phone).put("checked", 0) }
        assertEquals(peer, DumberDirectory.lookup(context, phone, fetch = { peer }).peer)
    }
    @Test fun rateLimitsAndOfflineStayUnknownAndRespectRetryTime() = runBlocking {
        val offline = DumberDirectory.lookup(context, phone, fetch = { throw java.io.IOException("offline") })
        assertFalse(offline.absent); assertNull(offline.peer)
        val limited = DumberDirectory.lookup(context, phone, fetch = { throw DumberException("limited", 429, 120) })
        assertFalse(limited.absent); assertTrue(limited.retryAt > System.currentTimeMillis())
        DumberDirectory.lookup(context, "+15555550102", fetch = { error("retry guard must apply across numbers") })
        // A lookup quota never removes an already known send route.
        DumberDirectory.remember(context, DumberDirectory.account(context), phone, peer)
        assertEquals(peer, DumberDirectory.lookup(context, phone, fetch = { error("known peer must not use quota") }).peer)
    }
    @Test fun accountSwitchDoesNotExposeCachedRecipients() = runBlocking {
        DumberDirectory.lookup(context, phone, fetch = { peer })
        DumberAccount.update(context) { it.put("session", JSONObject().put("user_id", "@other:example.test")) }
        assertNull(DumberDirectory.cached(context, phone).peer)
        assertEquals("@new:example.test", DumberDirectory.lookup(context, phone, fetch = { "@new:example.test" }).peer)
    }
    @Test fun failedEncryptedRetryStaysPendingWithoutDemotingOrSendingSms() = runBlocking {
        DumberDirectory.remember(context, DumberDirectory.account(context), phone, peer)
        val message = DumberOutbox.message(DumberDirectory.account(context), phone, "keep me", 42, 1, null).put("peer", peer)
        val id = message.getString("id")
        DumberOutbox(context).use { it.save(message) }
        try {
            // No native Android library on the JVM: this exercises unavailable encryption.
            assertFalse(DumberDelivery.retry(context, id))
            DumberOutbox(context).use { assertEquals("keep me", it.get(id)!!.getString("body")) }
            assertEquals(peer, DumberDirectory.cached(context, phone).peer)
        } finally { DumberOutbox(context).use { it.delete(id) } }
    }
    @Test fun unavailableEngineKeepsNewSendPending() = runBlocking {
        DumberDirectory.remember(context, DumberDirectory.account(context), phone, peer)
        assertTrue(DumberDelivery.send(context, phone, "keep new send", 43, 1, null))
        DumberOutbox(context).use { queue ->
            val pending = queue.list(DumberDirectory.account(context), 43)
            try {
                assertEquals(1, pending.size)
                assertEquals("keep new send", pending.single().getString("body"))
                assertFalse(pending.single().optBoolean("confirmed"))
            } finally { pending.forEach { queue.delete(it.getString("id")) } }
        }
    }
    @Test fun scheduledRetriesUseStableAccountScopedTransactionIds() {
        fun message(account: String, scheduled: Long?) = DumberOutbox.message(account, phone, "hello", 42, 1, scheduled).getString("id")
        assertEquals(message("self", 7), message("self", 7))
        assertNotEquals(message("self", 7), message("other", 7))
        assertNotEquals(message("self", 7), message("self", 8))
        assertNotEquals(message("self", null), message("self", null))
    }
    @Test fun simsRouteDifferentAccountsAndDefaultTracksSmsPreference() {
        sims(1, 2)
        link("@work:example.test", "+15555550200", 2)
        assertEquals(self, DumberAccount.forSubscription(context, 1))
        assertEquals("@work:example.test", DumberAccount.forSubscription(context, 2))
        org.robolectric.shadows.ShadowSubscriptionManager.setDefaultSmsSubscriptionId(2)
        assertEquals("@work:example.test", DumberAccount.forSubscription(context, -1))
        sims(2)
        assertNull(DumberAccount.forSubscription(context, 1))
        assertTrue(DumberAccount.enabled(context, self)) // Can still receive over Wi-Fi.
        sims(3, 2) // A replacement in slot 0 must not inherit the removed SIM's account.
        assertNull(DumberAccount.forSubscription(context, 3))
    }
    @Test fun conversationSwitchesSenderAndDiscoveryWithSelectedSim() {
        sims(1, 2, 3); val work = "@work:example.test"; link(work, "+15555550200", 2)
        val controller = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = controller.setup().get()
        val status = android.widget.Button(activity)
        var selected = 1
        val lookups = mutableListOf<String>()
        val ui = DumberConversationUi(activity, status, android.widget.Button(activity), { phone }, { 42 }, { selected }, { false }, { true }) { number, _, account ->
            lookups.add(account); DumberDirectory.remember(activity, account, number, peer); DumberDirectory.Status(peer = peer)
        }
        ui.open(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(status.text.toString().endsWith(ownPhone))
        selected = 2; ui.open(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(status.text.toString().endsWith("+15555550200"))
        assertEquals(listOf(self, work), lookups)
        selected = 3; ui.open(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(activity.getString(com.dpad.messaging.R.string.dumber_status_sim_unconnected), status.text.toString())
        assertEquals(2, lookups.size)
        controller.pause().stop().destroy()
    }
    @Test fun numberDetectionIsOnlyAHintAndVerifiedInputWins() {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "n").put("phone", "+15555550200")
            .put("subscription", 2).put("expires", System.currentTimeMillis()+60000)) }
        val sim = DumberSubscriptions.Sim(2, "Work", "+15555550999") // A carrier's stale number.
        assertTrue(DumberAccount.acceptVerification(context, "n", JSONObject().put("user_id", "@work:example.test"), sim))
        assertEquals("@work:example.test", DumberAccount.forSim(context, sim))
        assertEquals("+15555550200", DumberAccount.state(context, "@work:example.test").getString("phone"))
        assertNull(DumberAccount.forSim(context, sim.copy(number = "+15555550888")))
    }
    @Test fun lookupCachesAndRateLimitsAreIndependentPerSim() = runBlocking {
        sims(1, 2); val work = "@work:example.test"; link(work, "+15555550200", 2)
        DumberDirectory.lookup(context, phone, account = self, fetch = { throw DumberException("quota", 429, 120) })
        assertEquals(peer, DumberDirectory.lookup(context, phone, account = work, fetch = { peer }).peer)
        assertNull(DumberDirectory.cached(context, phone, account = self).peer)
        DumberDirectory.lookup(context, phone, account = self, fetch = { error("must honor this account's retry time") })
        Unit
    }
    @Test fun cryptoAndInboxAreIsolatedWhileLegacyStoreSurvives() {
        val oldPassword = DumberAccount.password(context)
        val work = "@work:example.test"; link(work, "+15555550200", 2)
        val personalRequest = DumberCore.request(context, "sync", self)
        val workRequest = DumberCore.request(context, "sync", work)
        assertTrue(personalRequest.getString("store_path").endsWith("/dumber-crypto"))
        assertEquals(oldPassword, personalRequest.getString("store_password"))
        assertNotEquals(personalRequest.getString("store_path"), workRequest.getString("store_path"))
        assertNotEquals(personalRequest.getString("store_password"), workRequest.getString("store_password"))
        assertEquals(work, workRequest.getJSONObject("session").getString("user_id"))
        assertEquals(DumberProtocol.MARKER + "!room/event", DumberMessages.eventMarker(context, self, "!room", "event"))
        assertNotEquals(DumberMessages.eventMarker(context, self, "!room", "event"), DumberMessages.eventMarker(context, work, "!room", "event"))
        val event = JSONObject().put("event_id", "$" + "test").put("e2ee", true)
        DumberInbox(context, self).use { it.enqueue("!personal", event) }
        DumberInbox(context, work).use { assertTrue(it.pending().isEmpty()) }
        DumberInbox(context).use { assertEquals(1, it.pending().size) }
    }
    @Test fun legacyAccountNeedsExplicitBindingAndRelinkAdoptsNewSession() {
        DumberAccount.update(context) { it.remove("sim_bindings"); it.getJSONObject("session").put("device_id", "original") }
        assertNull(DumberAccount.forSubscription(context, 1))
        assertTrue(DumberAccount.enabled(context))
        assertTrue(DumberAccount.needsLegacyLink(context))
        runBlocking {
            try { DumberDelivery.send(context, phone, "old account", 93, 1, null); fail("must ask to link, not send SMS") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Link your existing")) }
        }
        link(self, ownPhone, 1)
        assertFalse(DumberAccount.needsLegacyLink(context))
        assertFalse(DumberAccount.state(context, self).getJSONObject("session").has("device_id"))
        assertEquals(self, DumberAccount.forSubscription(context, 1))
    }
    @Test fun verificationRejectsWrongSimStaleNonceAndChangedCard() {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "pending").put("phone", ownPhone)
            .put("subscription", 1).put("card_id", 10).put("expires", System.currentTimeMillis()+60000)) }
        val session = JSONObject().put("user_id", self)
        assertFalse(DumberAccount.acceptVerification(context, "pending", session, DumberSubscriptions.Sim(2, "SIM 2", null, 10)))
        assertFalse(DumberAccount.acceptVerification(context, "old", session, DumberSubscriptions.Sim(1, "SIM 1", null, 10)))
        assertFalse(DumberAccount.acceptVerification(context, "pending", session, DumberSubscriptions.Sim(1, "SIM 1", null, 11)))
        assertTrue(DumberAccount.acceptVerification(context, "pending", session, DumberSubscriptions.Sim(1, "SIM 1", null, 10)))
        assertNull(DumberAccount.forSim(context, DumberSubscriptions.Sim(1, "replacement", null, 11)))
    }
    @Test fun verificationSmsFromWrongOrUnknownSubscriptionDoesNotSubmit() {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "pending").put("subscription", 1)
            .put("started", System.currentTimeMillis()).put("expires", System.currentTimeMillis()+60000)) }
        for (sub in listOf(-1, 2)) {
            DumberVerification.receive(context, "Your dumber.chat verification code is 123456", System.currentTimeMillis(), sub)
            DumberVerification.submit(context, "123456", sub)
            assertFalse(DumberAccount.read(context).getJSONObject("verification").has("code"))
        }
    }
    @Test fun pendingSendsKeepOriginalAccountAndSimAndRejectFallbackAfterRemoval() = runBlocking {
        sims(1, 2); val work = "@work:example.test"; link(work, "+15555550200", 2)
        DumberDirectory.remember(context, self, phone, peer)
        DumberDirectory.remember(context, work, phone, "@workpeer:example.test")
        assertTrue(DumberDelivery.send(context, phone, "personal", 91, 1, null))
        assertTrue(DumberDelivery.send(context, phone, "work", 91, 2, null))
        DumberOutbox(context).use { queue ->
            val personal = queue.list(self, 91).single(); val office = queue.list(work, 91).single()
            try {
                assertEquals(1, personal.getInt("subscription")); assertEquals(peer, personal.getString("peer"))
                assertEquals(2, office.getInt("subscription")); assertEquals("@workpeer:example.test", office.getString("peer"))
                sims(2)
                try { DumberDelivery.sendSms(context, personal.getString("id")); fail("must not use another SIM") }
                catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("original SIM")) }
                assertNotNull(queue.get(personal.getString("id")))
            } finally { queue.delete(personal.getString("id")); queue.delete(office.getString("id")) }
        }
    }
    @Test fun unavailableSelectedSimDoesNotSilentlyUseCarrier() = runBlocking {
        sims(2)
        try { DumberDelivery.send(context, phone, "keep this", 92, 1, null); fail("must not change sender") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("available SIM")) }
    }
    @Test fun unregisteredSimUsesCarrierAndNeverBorrowsAnotherAccount() = runBlocking {
        sims(1, 2)
        DumberDirectory.remember(context, self, phone, peer)
        assertFalse(DumberDelivery.send(context, phone, "carrier", 92, 2, null))
        DumberOutbox(context).use { assertTrue(it.list(self, 92).isEmpty()) }
    }
    @Test fun outboxSurvivesReopeningAndKeepsTransactionRoomAndBodyEncrypted() {
        val message = DumberOutbox.message("@self:example.test", phone, "private body", 42, 1, null)
            .put("room", "!room:example.test").put("uncertain", true)
        val id = message.getString("id")
        DumberOutbox(context).use { it.save(message) }
        DumberOutbox(context).use { queue ->
            try {
                val restored = queue.get(id)!!
                assertEquals(id, restored.getString("id"))
                assertEquals("!room:example.test", restored.getString("room"))
                assertTrue(restored.getBoolean("uncertain"))
                assertEquals("private body", restored.getString("body"))
                assertTrue(queue.list("@other:example.test", 42).isEmpty())
                queue.readableDatabase.rawQuery("SELECT payload FROM pending WHERE id=?", arrayOf(id)).use {
                    assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("private body"))
                }
            } finally { queue.delete(id) }
        }
    }
    @Test fun inboxUpgradeDropsOnlyObsoleteHistoryCursors() {
        val id = "migration-pending"
        val done = "migration-handled"
        DumberInbox(context, self).use { inbox ->
            inbox.enqueue("!room", JSONObject().put("event_id", id).put("e2ee", true)
                .put("origin_server_ts", 1).put("content", JSONObject().put("body", "old local message")))
            inbox.enqueue("!room", JSONObject().put("event_id", done).put("e2ee", true))
            inbox.done(done)
            inbox.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS history (room_id TEXT PRIMARY KEY NOT NULL, token TEXT NOT NULL)")
            inbox.writableDatabase.execSQL("INSERT OR REPLACE INTO history VALUES ('!room', 'old-server-cursor')")
            inbox.writableDatabase.version = 1
        }
        DumberInbox(context, self).use { inbox ->
            assertEquals(2, inbox.readableDatabase.version)
            assertEquals("old local message", inbox.pending().single { it.second.getString("event_id") == id }.second.getJSONObject("content").getString("body"))
            assertTrue(inbox.handled(done))
            inbox.enqueue("!room", JSONObject().put("event_id", done).put("e2ee", true))
            assertFalse(inbox.pending().any { it.second.optString("event_id") == done })
            inbox.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='history'", null).use {
                assertFalse(it.moveToFirst())
            }
        }
    }
    @Test fun limitedSyncQueuesMessagesWithoutCallingRemovedHistoryApi() = runBlocking {
        DumberAccount.updateAccount(context, self) { it.put("connected_at", 0) }
        val event = JSONObject().put("event_id", "limited-encrypted-event")
            .put("type", "m.room.message").put("sender", peer).put("origin_server_ts", 1)
            .put("unable_to_decrypt", true).put("encrypted_event", JSONObject().put("type", "m.room.encrypted"))
        val commands = mutableListOf<String>()
        DumberMessages.sync(context, self) { request ->
            commands.add(request.getString("command"))
            check(request.getString("command") == "sync") { "Unexpected legacy call" }
            JSONObject().put("next_batch", "sdk-owned").put("rooms", JSONObject().put("join", JSONObject()
                .put("!room", JSONObject().put("timeline", JSONObject().put("limited", true)
                    .put("prev_batch", "obsolete-token").put("events", org.json.JSONArray().put(event))))))
        }
        assertEquals(listOf("sync"), commands)
        DumberInbox(context, self).use { inbox ->
            assertTrue(inbox.pending().any { it.second.optString("event_id") == "limited-encrypted-event" })
        }
    }

}
