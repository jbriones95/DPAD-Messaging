package com.dpad.messaging.dumber

import chat.dumber.sdk.*

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
    private val recipients = mutableMapOf<String,String?>()
    private fun remember(context: Context, account: String, number: String, peer: String?, authoritative: Boolean = false) {
        recipients["$account/$number"]=peer
    }
    private fun session(account: String = self, credentials: String = "test", device: String = "test-device") = Session(credentials,account,device,null)
    private fun unusedApi(): ChatApi = java.lang.reflect.Proxy.newProxyInstance(ChatApi::class.java.classLoader, arrayOf(ChatApi::class.java)) { _, method, _ -> error("Unexpected API call: ${method.name}") } as ChatApi
    @org.junit.After fun resetTransport() { DumberCore.factory = { ChatClient(it) } }
    @Before fun setup() {
        recipients.clear()
        DumberCore.factory = { config -> object : ChatApi by unusedApi() {
            override fun recipientStatus(phone: String): RecipientStatus {
                val key = "${config.session!!.accountId}/$phone"
                val peer = recipients[key]
                return RecipientStatus(if(peer != null) Availability.AVAILABLE else if(recipients.containsKey(key)) Availability.UNAVAILABLE else Availability.UNKNOWN, peer?.let { Contact(it) })
            }
        } }

        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Test provider") {
            init {
                put("KeyStore.AndroidKeyStore", TestAndroidKeyStore::class.java.name)
                put("KeyGenerator.AES", TestAndroidKeyGenerator::class.java.name)
            }
        })
        context = ApplicationProvider.getApplicationContext()
        DumberAccount.update(context) {
            it.keys().asSequence().toList().forEach { key -> it.remove(key) }
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
        assertTrue(DumberAccount.acceptVerification(context, "test-$subscription", session(account),
            DumberSubscriptions.Sim(subscription, "SIM $subscription", null, card)))
    }
    @Test fun emptyGroupCreationRetriesTheSameOperationAndAllowsLaterInvites() = runBlocking {
        val operations = mutableListOf<String>()
        var info = Conversation("!empty-group", ConversationKind.GROUP, "Friends",
            capabilities = GroupCapabilities(manage = true, send = true), membership = Membership.JOINED)
        DumberCore.factory = { object : ChatApi by unusedApi() {
            override fun createGroup(name: String, phones: List<String>, operationId: String): Conversation {
                assertEquals("Friends", name)
                assertTrue(phones.isEmpty())
                operations.add(operationId)
                if (operations.size == 1) throw ChatException("Offline", ErrorKind.TRANSPORT_FAILURE)
                return info
            }
            override fun inviteMember(conversationId: String, phone: String) {
                assertEquals(info.id, conversationId)
                info = info.copy(members = listOf(GroupMember(Contact(peer, phone), Membership.INVITED)))
            }
            override fun conversationInfo(conversationId: String) = info
        } }
        assertTrue(runCatching { DumberGroups.create(context, self, "Friends", emptyList()) }.isFailure)
        val ref = DumberGroups.create(context, self, "Friends", emptyList())
        assertEquals(2, operations.size)
        assertEquals(operations.first(), operations.last())
        DumberGroupStore(context).use { assertNull(it.creation(self)) }
        val invited = DumberGroups.action(context, ref, DumberGroups.Action.INVITE, phone)
        assertEquals(Membership.INVITED, invited.members.single().membership)
    }

    @Test fun groupRetryHonorsSendPermissionAndKeepsItsTransactionAfterFailure() = runBlocking {
        val ref = GroupReference(self, "!permission-group")
        var maySend = false
        val transactions = mutableListOf<String>()
        DumberCore.factory = { object : ChatApi by unusedApi() {
            override fun conversationInfo(conversationId: String) = Conversation(ref.room, ConversationKind.GROUP,
                capabilities = GroupCapabilities(send = maySend), membership = Membership.JOINED)
            override fun sendText(conversationId: String, body: String, transactionId: String): SentMessage {
                assertEquals(ref.room, conversationId)
                transactions.add(transactionId)
                if (transactions.size == 1) throw ChatException("Offline", ErrorKind.TRANSPORT_FAILURE)
                return SentMessage("group-delivered", true)
            }
        } }
        val pending = DumberGroups.enqueue(context, ref, "Hello")
        assertTrue(pending.getLong("timestamp") > 0)
        assertTrue(runCatching { DumberGroups.retry(context, ref, pending) }.isFailure)
        assertTrue(transactions.isEmpty())
        maySend = true
        assertTrue(runCatching { DumberGroups.retry(context, ref, pending) }.isFailure)
        DumberGroupStore(context).use { store ->
            DumberGroups.retry(context, ref, store.pending(ref).single())
        }
        assertEquals(listOf(pending.getString("id"), pending.getString("id")), transactions)
        DumberGroupStore(context).use { store ->
            assertTrue(store.pending(ref).isEmpty())
            assertTrue(store.messages(ref).single().encrypted)
        }
    }

    @Test fun invitationIsUnreadAndRecentWithoutRepeatingAfterItIsRead() {
        val ref = GroupReference(self, "!new-invitation")
        val invitation = Conversation(ref.room, ConversationKind.GROUP, "Friends", membership = Membership.INVITED)
        DumberGroupStore(context).use { store ->
            assertTrue(store.metadata(ref, invitation))
            val row = store.room(ref)!!
            assertTrue(row.getLong("date") > 0)
            assertEquals(1, row.getInt("unread"))
            assertTrue(row.getBoolean("invitation_pending"))
            val receivedAt = row.getLong("date")
            store.markRead(ref)
            assertFalse(store.metadata(ref, invitation))
            assertEquals(receivedAt, store.room(ref)!!.getLong("date"))
            assertEquals(0, store.room(ref)!!.getInt("unread"))
            assertFalse(store.room(ref)!!.getBoolean("invitation_pending"))
            store.metadata(ref, invitation.copy(membership = Membership.LEFT))
            store.flag(ref, "hidden", true)
            store.flag(ref, "archived", true)
            assertTrue(store.metadata(ref, invitation))
            assertEquals(1, store.room(ref)!!.getInt("unread"))
            assertFalse(store.room(ref)!!.getBoolean("hidden"))
            assertFalse(store.room(ref)!!.getBoolean("archived"))
        }
    }

    @Test fun existingInvitationWithoutReceiptMetadataIsRepairedOnce() {
        val ref = GroupReference(self, "!legacy-invitation")
        val invitation = Conversation(ref.room, ConversationKind.GROUP, "Friends", membership = Membership.INVITED)
        DumberGroupStore(context).use { store ->
            store.metadata(ref, invitation)
            val legacy = store.room(ref)!!
            legacy.remove("invitation_received_at")
            legacy.remove("invitation_pending")
            legacy.remove("date")
            legacy.put("unread", 0)
            store.writableDatabase.execSQL("UPDATE rooms SET payload=? WHERE id=?", arrayOf(DumberAccount.seal(legacy.toString()), ref.key))
            assertTrue(store.metadata(ref, invitation))
            assertTrue(store.room(ref)!!.getLong("date") > 0)
            assertEquals(1, store.room(ref)!!.getInt("unread"))
            assertFalse(store.metadata(ref, invitation))
        }
    }

    @Test fun emptyIncrementalSyncRecoversCachedInvitationAndNotifiesOnce() = runBlocking {
        val ref = GroupReference(self, "!cached-invitation")
        val invitation = Conversation(ref.room, ConversationKind.GROUP, "Friends", membership = Membership.INVITED)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(com.dpad.messaging.App.CHANNEL_MESSAGES, "Messages", android.app.NotificationManager.IMPORTANCE_HIGH))
        val api = object : ChatApi by unusedApi() {
            override fun sync() = SyncUpdate(emptyList())
            override fun conversations() = listOf(invitation)
        }
        DumberMessages.sync(context, self, api)
        DumberGroupStore(context).use { store ->
            assertEquals(Membership.INVITED, store.info(ref)!!.membership)
            assertEquals(1, store.room(ref)!!.getInt("unread"))
            assertFalse(store.room(ref)!!.getBoolean("invitation_pending"))
            store.markRead(ref)
        }
        assertTrue(manager.activeNotifications.any { it.id == 51003 && it.tag == ref.key })
        DumberGroups.clearInvitationNotification(context, ref)
        DumberMessages.sync(context, self, api)
        assertTrue(manager.activeNotifications.none { it.id == 51003 && it.tag == ref.key })
        DumberGroupStore(context).use { assertEquals(0, it.room(ref)!!.getInt("unread")) }
    }

    @Test fun groupAndPartialInvitationsRequireExplicitAcceptance() = runBlocking {
        val commands = mutableListOf<String>()
        val api = object : ChatApi by unusedApi() {
            override fun sync(): SyncUpdate {
                commands.add("sync")
                return SyncUpdate(listOf(ConversationKind.GROUP,ConversationKind.UNKNOWN).map { kind ->
                    ConversationUpdate(Conversation(if(kind == ConversationKind.GROUP) "!group-invite" else "!partial-invite",kind,"Friends",membership=Membership.INVITED),emptyList())
                })
            }
            override fun conversations(): List<Conversation> { commands.add("conversations"); return emptyList() }
        }
        DumberMessages.sync(context,self,api)
        assertEquals(listOf("sync","conversations"),commands)
        DumberGroupStore(context).use { store ->
            assertEquals(Membership.INVITED,store.info(GroupReference(self,"!group-invite"))!!.membership)
            assertNull(store.room(GroupReference(self,"!partial-invite")))
        }
    }
    @Test fun groupHistoryIsRoomAndAccountScopedAndDeletionDoesNotResurrect() {
        val first = GroupReference(self,"!group-one")
        val second = GroupReference(self,"!group-two")
        val otherAccount = GroupReference(peer,"!group-one")
        val info = Conversation(first.room,ConversationKind.GROUP,"Friends",membership=Membership.JOINED)
        fun message(body: String) = Message("group-event",Contact(peer,phone),42,MessageContent.Text(body),true)
        DumberGroupStore(context).use { store ->
            listOf(first,second,otherAccount).forEach { store.metadata(it,info.copy(id=it.room)) }
            assertTrue(store.event(first,message("private group body")))
            assertFalse(store.event(first,message("private group body")))
            assertTrue(store.event(second,message("second room")))
            assertTrue(store.event(otherAccount,message("other account")))
            assertEquals(1,store.room(first)!!.getInt("unread"))
            store.markRead(first)
            assertEquals(0,store.room(first)!!.getInt("unread"))
            store.setRead(first, false)
            assertEquals(1,store.room(first)!!.getInt("unread"))
            assertEquals(1,store.room(second)!!.getInt("unread"))
            assertEquals(3,store.conversations().size)
            assertEquals(3,store.conversations().map { it.referenceKey }.distinct().size)
            store.flag(first,"archived",true)
            assertEquals(first,store.conversations(true).single().group)
            store.readableDatabase.rawQuery("SELECT payload FROM messages",null).use { cursor ->
                while(cursor.moveToNext()) assertFalse(cursor.getString(0).contains("private group body"))
            }
            DumberInbox(context,self).use { it.enqueue(first.room,Message("waiting-group-key",Contact(peer),1,MessageContent.UnableToDecrypt,true)) }
            store.deleteHistory(first)
            DumberInbox(context,self).use { assertTrue(it.handled("waiting-group-key")) }
            assertFalse(store.event(first,message("private group body")))
            assertTrue(store.messages(first).isEmpty())
            assertEquals("second room",(store.messages(second).single().content as MessageContent.Text).body)
        }
        DumberGroupStore(context).use { store ->
            assertTrue(store.messages(first).isEmpty())
            assertEquals(1,store.messages(otherAccount).size)
        }
    }
    @Test fun groupOutboxEchoCompletesOnlyItsOwnRoomAndOriginalAccount() {
        val ref = GroupReference(self,"!outbox-group")
        val other = GroupReference(peer,"!outbox-group")
        val pending = JSONObject().put("id","group-txn").put("account",self).put("room",ref.room).put("body","pending")
        DumberGroupStore(context).use { store -> store.savePending(ref,pending) }
        DumberGroupStore(context).use { store ->
            assertEquals(self,store.pending(ref).single().getString("account"))
            val echo = Message("group-echo",Contact(self,ownPhone),1,MessageContent.Text("pending"),true,"group-txn")
            store.event(other,echo)
            assertEquals(1,store.pending(ref).size)
            store.event(ref,echo)
            assertTrue(store.pending(ref).isEmpty())
        }
    }
    @Test fun replacementAdoptsSessionAndChangesCryptoStoreWithoutDroppingAccountHistory() {
        DumberAccount.update(context) {
            it.getJSONObject("accounts").getJSONObject(self).put("session",ChatStorage.encodeSession(session(credentials="old-token",device="old")))
            it.put("verification", JSONObject().put("nonce", "replace").put("phone", ownPhone)
                .put("subscription", 1).put("expires", System.currentTimeMillis() + 60000))
        }
        val oldStore = DumberAccount.storeName(context, self)
        val oldPassword = DumberAccount.password(context, self)
        assertTrue(DumberAccount.acceptVerification(context, "replace",
            session(credentials="new-token", device="new"),
            DumberSubscriptions.Sim(1, "SIM 1", null)))
        assertEquals("new-token", DumberAccount.session(context,self)!!.credentials)
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
            assertEquals(other, it.accountId)
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
            it.getJSONObject("accounts").getJSONObject(self).put("session",ChatStorage.encodeSession(session(credentials="old")))
            it.put("verification", JSONObject().put("phone", ownPhone).put("nonce", "old-verification"))
        }
        assertTrue(DumberAccount.logout(context, self))
        assertFalse(DumberAccount.read(context).has("verification"))
        link(self, ownPhone, 1)
        assertTrue(DumberDisconnect.flush(context) {
            assertEquals("old", it.credentials)
            JSONObject()
        })
        assertTrue(DumberAccount.enabled(context, self))
    }
    @Test fun manualLogoutPreservesOtherNumbersVerification() {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("phone", "+15555550102")) }
        assertTrue(DumberAccount.logout(context, self))
        assertTrue(DumberAccount.read(context).has("verification"))
    }
    @Test fun removedSimStaysConnectedUntilExplicitDeregistration() {
        val other = "@other:example.test"
        sims(1, 2)
        link(other, "+15555550102", 2)
        val store = DumberAccount.storeName(context, self)
        val password = DumberAccount.password(context, self)
        assertTrue(DumberAccount.reviewMissing(context, setOf(2)))
        val prompt = DumberAccount.pendingRemovals(context).single()
        assertEquals(self, prompt.account)
        assertEquals(ownPhone, prompt.phone)
        assertTrue(DumberAccount.enabled(context, self))
        assertFalse(DumberAccount.logoutPending(context, self))
        assertFalse(DumberAccount.reviewMissing(context, setOf(2)))
        DumberAccount.chooseRemoval(context, prompt, false)
        assertFalse(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.enabled(context, other))
        assertEquals(store, DumberAccount.storeName(context, self))
        assertEquals(password, DumberAccount.password(context, self))
        assertTrue(DumberAccount.pendingRemovals(context).isEmpty())
        assertTrue(DumberAccount.logoutPending(context, self))
        DumberAccount.chooseRemoval(context, prompt, false)
        assertEquals(1, DumberAccount.read(context).getJSONObject("pending_logouts").length())
    }
    @Test fun unboundAccountAsksForChoiceWithoutDisconnecting() {
        DumberAccount.update(context) { it.remove("sim_bindings") }
        link("@comviq:example.test", "+15555550102", 2)
        assertTrue(DumberAccount.reviewMissing(context, setOf(2)))
        assertEquals(self, DumberAccount.pendingRemovals(context).single().account)
        assertTrue(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.enabled(context, "@comviq:example.test"))
    }
    @Test fun keepConnectionPersistsAndReinsertionAllowsANewChoice() {
        assertTrue(DumberAccount.reviewMissing(context, emptySet()))
        val old = DumberAccount.pendingRemovals(context).single()
        DumberAccount.chooseRemoval(context, old, true)
        assertTrue(DumberAccount.enabled(context))
        assertFalse(DumberAccount.logoutPending(context, self))
        assertTrue(DumberAccount.pendingRemovals(context).isEmpty())
        // Re-read the encrypted file and restart the monitor: no repeated prompt.
        DumberAccount::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        assertTrue(DumberAccount.read(context).getJSONObject("accounts").getJSONObject(self)
            .getJSONObject("sim_choice").getBoolean("keep"))
        val monitor = DumberSimMonitor()
        monitor.check(context, emptySet(), 0)
        monitor.check(context, emptySet(), 5000)
        assertTrue(DumberAccount.pendingRemovals(context).isEmpty())
        DumberAccount.reviewMissing(context, setOf(1))
        DumberAccount.reviewMissing(context, emptySet())
        val next = DumberAccount.pendingRemovals(context).single()
        assertNotEquals(old.nonce, next.nonce)
        DumberAccount.chooseRemoval(context, old, false)
        assertTrue(DumberAccount.enabled(context))
        assertFalse(DumberAccount.logoutPending(context, self))
        DumberAccount.chooseRemoval(context, next, false)
        assertFalse(DumberAccount.enabled(context))
    }
    @Test fun staleRemovalDialogCannotDeregisterNewSession() {
        DumberAccount.reviewMissing(context, emptySet())
        val prompt = DumberAccount.pendingRemovals(context).single()
        link(self, ownPhone, 1)
        DumberAccount.chooseRemoval(context, prompt, false)
        assertTrue(DumberAccount.enabled(context))
        assertFalse(DumberAccount.logoutPending(context, self))
        assertTrue(DumberAccount.pendingRemovals(context).isEmpty())
    }
    private fun deregisterMissing() {
        DumberAccount.reviewMissing(context, emptySet())
        DumberAccount.chooseRemoval(context, DumberAccount.pendingRemovals(context).single(), false)
    }
    @Test fun offlineLogoutRetriesAndDoesNotReenableAccount() {
        deregisterMissing()
        assertFalse(DumberDisconnect.flush(context) { throw java.io.IOException("offline") })
        assertFalse(DumberAccount.enabled(context, self))
        var calls = 0
        assertTrue(DumberDisconnect.flush(context) {
            calls++
            assertEquals(self, it.accountId)
            JSONObject()
        })
        assertEquals(1, calls)
        assertTrue(DumberDisconnect.flush(context) { error("already logged out") })
    }
    @Test fun revokedSessionCompletesLogout() {
        deregisterMissing()
        assertTrue(DumberDisconnect.flush(context) { throw ChatException("revoked", ErrorKind.AUTHENTICATION_REQUIRED) })
    }
    @Test fun reconnectRequiresVerificationAndOldLogoutCannotRevokeNewSession() {
        DumberAccount.updateAccount(context, self) { it.put("session",ChatStorage.encodeSession(session(credentials="old"))) }
        deregisterMissing()
        sims(1)
        assertNull(DumberAccount.forSubscription(context, 1))
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "reconnect")
            .put("phone", ownPhone).put("subscription", 1).put("expires", System.currentTimeMillis() + 60000)) }
        assertTrue(DumberAccount.acceptVerification(context, "reconnect",
            session(credentials="new"), DumberSubscriptions.Sim(1, "SIM 1", null)))
        assertTrue(DumberAccount.enabled(context, self))
        assertTrue(DumberAccount.removalWarnings(context).isEmpty())
        assertTrue(DumberDisconnect.flush(context) {
            assertEquals("old", it.credentials); JSONObject()
        })
        assertEquals("new", DumberAccount.session(context,self)!!.credentials)
    }
    @Test fun uncertainOrBrieflyMissingSubscriptionsDoNotPrompt() {
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
        assertTrue(DumberAccount.pendingRemovals(context).isEmpty())
        monitor.check(context, emptySet(), 21000)
        monitor.check(context, emptySet(), 26000)
        assertEquals(1, DumberAccount.pendingRemovals(context).size)
        assertTrue(DumberAccount.enabled(context, self))
        assertFalse(DumberAccount.logoutPending(context, self))
    }
    @Test fun unavailableRecipientDialogOffersSmsAndCancelKeepsMessage() {
        val controller = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = controller.setup().get()
        val message = DumberOutbox.message(self, phone, "keep this draft", 880, 1, null)
            .put("recipient_unavailable", true).put("reason", "Recipient no longer available")
        val id = message.getString("id")
        remember(context, self, phone, null)
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
            remember(context, self, phone, peer, authoritative = true)
            val message = DumberOutbox.message(self, phone, "keep rejected text", 778, 1, null)
                .put("peer", peer).put("uncertain", uncertain)
            val id = message.getString("id")
            DumberOutbox(context).use { it.save(message) }
            try {
                assertFalse(DumberDelivery.retry(context, id) { _, _ ->
                    remember(context,self,phone,null) // Rust has already updated its availability cache.
                    throw ChatException("Recipient no longer available", ErrorKind.RECIPIENT_UNAVAILABLE)
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
                assertFalse(DumberDelivery.send(context, phone, "next text", 778, 1, null))
            } finally { DumberOutbox(context).use { it.delete(id) } }
        }
    }
    @Test fun permissionAndNetworkFailuresNeverDemoteRecipient() = runBlocking {
        remember(context, self, phone, peer)
        for (error in listOf(ChatException("forbidden", ErrorKind.PERMISSION_DENIED), ChatException("offline", ErrorKind.TRANSPORT_FAILURE),
                ChatException("server error", ErrorKind.TRANSPORT_FAILURE))) {
            val message = DumberOutbox.message(self, phone, "pending", 779, 1, null).put("peer", peer)
            val id = message.getString("id")
            DumberOutbox(context).use { it.save(message) }
            try {
                assertFalse(DumberDelivery.retry(context, id) { _, _ -> throw error })
                assertEquals(peer, DumberDirectory.cached(context, phone, account = self).peer)
                DumberOutbox(context).use { assertFalse(it.get(id)!!.optBoolean("recipient_unavailable")) }
            } finally { DumberOutbox(context).use { it.delete(id) } }
        }
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
            remember(activity, account, number, peer)
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
    @Test fun failedEncryptedRetryStaysPendingWithoutDemotingOrSendingSms() = runBlocking {
        remember(context, self, phone, peer)
        val message = DumberOutbox.message(self, phone, "keep me", 42, 1, null).put("peer", peer)
        val id = message.getString("id")
        DumberOutbox(context).use { it.save(message) }
        try {
            // No native Android library on the JVM: this exercises unavailable encryption.
            assertFalse(DumberDelivery.retry(context, id))
            DumberOutbox(context).use { assertEquals("keep me", it.get(id)!!.getString("body")) }
            assertEquals(peer, DumberDirectory.cached(context, phone, account = self).peer)
        } finally { DumberOutbox(context).use { it.delete(id) } }
    }
    @Test fun unavailableEngineKeepsNewSendPending() = runBlocking {
        remember(context, self, phone, peer)
        assertTrue(DumberDelivery.send(context, phone, "keep new send", 43, 1, null))
        DumberOutbox(context).use { queue ->
            val pending = queue.list(self, 43)
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
            lookups.add(account); remember(activity, account, number, peer); DumberDirectory.Status(peer = peer)
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
        assertTrue(DumberAccount.acceptVerification(context, "n", session("@work:example.test"), sim))
        assertEquals("@work:example.test", DumberAccount.forSim(context, sim))
        assertEquals("+15555550200", DumberAccount.state(context, "@work:example.test").getString("phone"))
        assertNull(DumberAccount.forSim(context, sim.copy(number = "+15555550888")))
    }
    @Test fun cryptoAndInboxAreAccountScoped() {
        val oldPassword = DumberAccount.password(context, self)
        val work = "@work:example.test"; link(work, "+15555550200", 2)
        val personalRequest = DumberCore.config(context, self)
        val workRequest = DumberCore.config(context, work)
        assertEquals(oldPassword, personalRequest.store!!.password)
        assertNotEquals(personalRequest.store!!.path, workRequest.store!!.path)
        assertNotEquals(personalRequest.store!!.password, workRequest.store!!.password)
        assertEquals(work, workRequest.session!!.accountId)
        assertEquals(DumberProtocol.MARKER + self + "/!room/event", DumberMessages.eventMarker(self, "!room", "event"))
        assertNotEquals(DumberMessages.eventMarker(self, "!room", "event"), DumberMessages.eventMarker(work, "!room", "event"))
        val event = Message("$" + "test",Contact(peer),1,MessageContent.Text("body"),true)
        DumberInbox(context, self).use { it.enqueue("!personal", event) }
        DumberInbox(context, work).use { assertTrue(it.pending().isEmpty()) }
        DumberInbox(context, self).use { assertEquals(1, it.pending().size) }
    }
    @Test fun verificationRejectsWrongSimStaleNonceAndChangedCard() {
        DumberAccount.update(context) { it.put("verification", JSONObject().put("nonce", "pending").put("phone", ownPhone)
            .put("subscription", 1).put("card_id", 10).put("expires", System.currentTimeMillis()+60000)) }
        val session = session()
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
        remember(context, self, phone, peer)
        remember(context, work, phone, "@workpeer:example.test")
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
        remember(context, self, phone, peer)
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
    @Test fun inboxReopensWithPendingMessagesAndDeletionTombstones() {
        val id = "reopened-pending"
        val done = "reopened-handled"
        DumberInbox(context, self).use { inbox ->
            inbox.enqueue("!room", Message(id,Contact(peer),1,MessageContent.Text("old local message"),true))
            inbox.enqueue("!room", Message(done,Contact(peer),1,MessageContent.Text("body"),true))
            inbox.done(done)
        }
        DumberInbox(context, self).use { inbox ->
            assertEquals(2, inbox.readableDatabase.version)
            assertEquals("old local message", inbox.pending().single { it.second.eventId == id }.second.content.let { (it as MessageContent.Text).body })
            assertTrue(inbox.handled(done))
            inbox.enqueue("!room", Message(done,Contact(peer),1,MessageContent.Text("body"),true))
            assertFalse(inbox.pending().any { it.second.eventId == done })
            inbox.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='history'", null).use {
                assertFalse(it.moveToFirst())
            }
        }
    }
    @Test fun limitedSyncQueuesMessagesWithoutCallingRemovedHistoryApi() = runBlocking {
        DumberAccount.updateAccount(context,self) { it.put("connected_at",0) }
        val event = Message("limited-encrypted-event",Contact(peer),1,MessageContent.UnableToDecrypt,true)
        val commands = mutableListOf<String>()
        val api = object : ChatApi by unusedApi() {
            override fun sync(): SyncUpdate { commands.add("sync"); return SyncUpdate(listOf(ConversationUpdate(Conversation("!room"),listOf(event),true))) }
            override fun conversations(): List<Conversation> { commands.add("conversations"); return emptyList() }
        }
        DumberMessages.sync(context,self,api)
        assertEquals(listOf("sync","conversations"),commands)
        DumberInbox(context,self).use { assertTrue(it.pending().any { record -> record.second.eventId == "limited-encrypted-event" }) }
    }
}
