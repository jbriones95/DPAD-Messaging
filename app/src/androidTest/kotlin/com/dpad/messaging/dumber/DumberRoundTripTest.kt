package com.dpad.messaging.dumber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.dumber.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Opt-in network test. Requires the SSH-only development service; never uses Twilio. */
@RunWith(AndroidJUnit4::class)
class DumberRoundTripTest {
    @Test fun encryptedRoundTrip() {
        val service = InstrumentationRegistry.getArguments().getString("dumberTestService") ?: ""
        assumeTrue(service.startsWith("http://127.0.0.1:"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "dumber-test-${UUID.randomUUID()}")
        val password = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val sessions = mutableMapOf<String,Session>()
        fun client(peer: String): ChatApi = ChatClient(ChatConfig(service,sessions[peer],CryptoStore(File(root,peer).absolutePath,password)))
        fun receive(peer: String, room: String, eventId: String, body: String) {
            repeat(8) {
                val event = client(peer).sync().conversations.filter { it.conversation.id == room }.flatMap { it.messages }.firstOrNull { it.eventId == eventId && it.content is MessageContent.Text }
                if(event != null) { assertTrue(event.encrypted); assertEquals(body,(event.content as MessageContent.Text).body); return }
            }
            fail("Encrypted message was not decrypted by the other device")
        }
        try {
            for ((peer, phone) in listOf("alice" to "+15555550101", "bob" to "+15555550102")) {
                assertTrue("Only the development SMS service is allowed", client(peer).requestCode(phone).development)
                sessions[peer] = client(peer).verify(phone,"123456")
                client(peer).sync()
            }
            val other = client("alice").checkRecipient("+15555550102")
            assertEquals(Availability.AVAILABLE,other.availability)
            assertEquals(sessions.getValue("bob").accountId,other.contact!!.id)
            val room = client("alice").openChat("+15555550102",cachedOnly=true).id
            client("bob").sync()
            client("bob").join(room)
            client("bob").sync()
            client("alice").sync()
            for ((from, to) in listOf("alice" to "bob", "bob" to "alice")) {
                val body = "DPAD encrypted integration test ${UUID.randomUUID()}"
                val sent = client(from).sendText(room,body,UUID.randomUUID().toString())
                assertTrue(sent.encrypted)
                val id = sent.eventId
                assertCiphertext(sessions.getValue(from),id,body)
                receive(to, room, id, body)
            }
        } finally {
            for (peer in sessions.keys) runCatching { client(peer).logout() }
            root.deleteRecursively()
        }
    }
    /** Test-only transport inspection, deliberately outside application code. */
    private fun assertCiphertext(session: Session, event: String, body: String) {
        val credentials=JSONObject(session.credentials)
        fun get(path: String): JSONObject? {
            val connection=URL(credentials.getString("homeserver")+path).openConnection() as HttpURLConnection
            connection.connectTimeout=15000; connection.readTimeout=15000
            connection.setRequestProperty("Authorization","Bearer "+credentials.getString("access_token"))
            return try { if(connection.responseCode == 200) JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) else null }
                finally { connection.disconnect() }
        }
        val rooms=get("/_matrix/client/v3/joined_rooms")!!.getJSONArray("joined_rooms")
        for(i in 0 until rooms.length()) {
            val room=java.net.URLEncoder.encode(rooms.getString(i),"UTF-8")
            val id=java.net.URLEncoder.encode(event,"UTF-8")
            val raw=get("/_matrix/client/v3/rooms/$room/event/$id") ?: continue
            assertEquals("m.room.encrypted",raw.getString("type"))
            assertFalse(raw.toString().contains(body)); return
        }
        fail("Accepted event not found on the server")
    }
}
