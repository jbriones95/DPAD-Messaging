package chat.dumber.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[34])
class ChatClientTest {
    private val session = Session("private-credentials","contact_a","device_a",null)
    private val config = ChatConfig("http://127.0.0.1:9",session,CryptoStore("/private/store","private-password"))
    private val group = Conversation("conversation_a",ConversationKind.GROUP,"Friends",
        listOf(GroupMember(Contact("contact_a",null),Membership.JOINED,true)),GroupCapabilities(true,true),emptyList(),Membership.JOINED)
    private val message = Message("event_a",Contact("contact_a",null),42,MessageContent.Text("quote \" newline\n💬"),true,"retry-id")

    @Test fun callSignalsStayTypedAndSeparateFromMessages() {
        val signal=CallSignal(CallKind.CANDIDATES,"call-a","party-b",1234,
            candidates=listOf(IceCandidate("candidate:1","0",0)))
        assertEquals(signal,CallWire.decode(CallWire.encode(signal)))
        val wireEvent=JSONObject().put("event_id","event-call").put("conversation_id","conversation_a")
            .put("sender",JSONObject().put("id","contact_b").put("phone","+15555550102"))
            .put("expires_at_ms",61234).put("signal",CallWire.encode(signal))
        val client=ChatClient(config) { request ->
            val operation=JSONObject(request).getJSONObject("operation")
            when(operation.getString("command")) {
                "sync" -> JSONObject().put("ok",true).put("data",JSONObject()
                    .put("conversations",org.json.JSONArray()).put("contacts_pending",false)
                    .put("calls",org.json.JSONArray().put(wireEvent))).toString()
                "send_call" -> {
                    assertEquals("conversation_a",operation.getString("conversation_id"))
                    assertEquals("retry-call",operation.getString("transaction_id"))
                    assertEquals(signal,CallWire.decode(operation.getJSONObject("signal")))
                    """{"ok":true,"data":null}"""
                }
                else -> error("Unexpected operation")
            }
        }
        val update=client.sync()
        assertTrue(update.conversations.isEmpty())
        assertEquals(signal,update.calls.single().signal)
        client.sendCall("conversation_a",signal,"retry-call")
        assertFalse(TurnConfig(listOf("turn:example.org"),"private-user","private-password",60).toString().contains("private"))
    }

    @Test fun codecsPreserveOpaqueIdsNullsAndMessageVariantsWithoutLeakingSecretsInLogs() {
        assertEquals(session,ChatStorage.decodeSession(ChatStorage.encodeSession(session)))
        assertEquals(group,ChatStorage.decodeConversation(ChatStorage.encodeConversation(group)))
        for(content in listOf(message.content,MessageContent.UnableToDecrypt,MessageContent.Unsupported)) {
            val value=message.copy(content=content)
            assertEquals(value,ChatStorage.decodeMessage(ChatStorage.encodeMessage(value)))
        }
        assertFalse(config.toString().contains("private-credentials"))
        assertFalse(config.toString().contains("private-password"))
    }
    @Test fun sendKeepsTransactionIdentityAndConfigurationOutOfOperation() {
        val client = ChatClient(config) { request ->
            val envelope=JSONObject(request)
            assertEquals(2,envelope.getInt("api_version"))
            assertEquals("private-credentials",envelope.getJSONObject("session").getString("credentials"))
            assertEquals("/private/store",envelope.getString("store_path"))
            val operation=envelope.getJSONObject("operation")
            assertEquals(setOf("command","conversation_id","body","transaction_id"),operation.keys().asSequence().toSet())
            assertEquals("send_text",operation.getString("command"))
            assertEquals("retry-id",operation.getString("transaction_id"))
            assertEquals((message.content as MessageContent.Text).body,operation.getString("body"))
            """{"ok":true,"data":{"event_id":"event_a","encrypted":true}}"""
        }
        assertEquals(SentMessage("event_a",true),client.sendText(group.id,(message.content as MessageContent.Text).body,"retry-id"))
    }
    @Test fun typedSyncHandlesNullPhonesMembershipAndUnknownContent() {
        val client=ChatClient(config) {
            """{"ok":true,"data":{"conversations":[{"conversation":${ChatStorage.encodeConversation(group)},"messages":[${ChatStorage.encodeMessage(message)}],"limited":true}],"contacts_pending":true}}"""
        }
        assertEquals(SyncUpdate(listOf(ConversationUpdate(group,listOf(message),true)),true),client.sync())
        val unknown=JSONObject(ChatStorage.encodeMessage(message)).apply { getJSONObject("content").put("kind","future_attachment") }
        assertEquals(MessageContent.Unsupported,ChatStorage.decodeMessage(unknown.toString()).content)
    }
    @Test fun arraysAndVoidOperationsDoNotRequireAppSideEnvelopes() {
        val commands=mutableListOf<String>()
        val client=ChatClient(config) {
            val op=JSONObject(it).getJSONObject("operation"); val command=op.getString("command"); commands.add(command)
            when(command) {
                "conversations" -> """{"ok":true,"data":[${ChatStorage.encodeConversation(group)}]}"""
                "create_group" -> {
                    assertEquals("+15555550101",op.getJSONArray("phones").getString(0))
                    assertEquals("stable-operation",op.getString("operation_id"))
                    """{"ok":true,"data":${ChatStorage.encodeConversation(group)}}"""
                }
                else -> """{"ok":true,"data":null}"""
            }
        }
        assertEquals(listOf(group),client.conversations())
        assertEquals(group,client.createGroup("Friends",listOf("+15555550101"),"stable-operation"))
        client.join(group.id);client.leave(group.id);client.inviteMember(group.id,"+15555550101")
        client.renameGroup(group.id,"New name");client.removeMember(group.id,"contact_b");client.grantAdmin(group.id,"contact_b")
        client.exportKeys("/private/backup","passphrase");client.logout()
        assertEquals(listOf("conversations","create_group","join","leave","invite_member","rename_group","remove_member","grant_admin","export_keys","logout"),commands)
    }
    @Test fun domainErrorsAndRetryAfterRemainTyped() {
        for(kind in ErrorKind.values()) {
            val client=ChatClient(config) { """{"ok":false,"error":"failed","error_kind":"${kind.name.lowercase()}","retry_after_seconds":123}""" }
            try { client.sync();fail("Expected typed failure") }
            catch(e: ChatException) { assertEquals(kind,e.kind);assertEquals(123L,e.retryAfterSeconds);assertEquals(kind==ErrorKind.RECIPIENT_UNAVAILABLE,e.recipientUnavailable) }
        }
    }
    @Test fun authDiscoveryAndKeyResultsAreDecodedByTheLibrary() {
        val client=ChatClient(config) {
            when(JSONObject(it).getJSONObject("operation").getString("command")) {
                "request_code" -> """{"ok":true,"data":{"status":"sent","development":false}}"""
                "verify" -> """{"ok":true,"data":${ChatStorage.encodeSession(session)}}"""
                "session_status" -> """{"ok":true,"data":{"role":"main","main_active":true}}"""
                "pairing_review", "pairing_approve" -> """{"ok":true,"data":{"description":"Browser","expires_at":123,"approved":true}}"""
                "recipient_status", "check_recipient" -> """{"ok":true,"data":{"availability":"unavailable","contact":null,"retry_at_ms":456}}"""
                "open_chat", "conversation_info" -> """{"ok":true,"data":${ChatStorage.encodeConversation(group)}}"""
                "devices" -> """{"ok":true,"data":{"contact":{"id":"contact_a","phone":null},"current_device_id":"device_a","current_device_fingerprint":null,"devices":[{"device_id":"device_a","name":null,"verified":false,"fingerprint":null}]}}"""
                "import_keys" -> """{"ok":true,"data":{"imported":2,"total":3}}"""
                else -> error("Unexpected operation")
            }
        }
        assertEquals(CodeRequest("sent",false),client.requestCode("+15555550101"))
        assertEquals(session,client.verify("+15555550101","123456"))
        assertEquals(SessionStatus(SessionRole.MAIN,true),client.sessionStatus())
        assertEquals(PairingReview("Browser",123,true),client.reviewPairing("12345678"))
        assertEquals(client.reviewPairing("12345678"),client.approvePairing("12345678"))
        assertEquals(RecipientStatus(Availability.UNAVAILABLE,null,456),client.recipientStatus("+15555550101"))
        assertEquals(client.recipientStatus("+15555550101"),client.checkRecipient("+15555550101",true))
        assertEquals(group,client.openChat("+15555550101"));assertEquals(group,client.conversationInfo(group.id))
        assertEquals(Device("device_a",null,false,null),client.devices().devices.single())
        assertEquals(KeyImport(2,3),client.importKeys("path","passphrase"))
    }
}
