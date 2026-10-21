package com.dpad.messaging.dumber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
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
        val sessions = mutableMapOf<String, JSONObject>()
        fun call(peer: String, command: String, fields: JSONObject = JSONObject()): JSONObject {
            fields.put("service", service).put("command", command)
                .put("store_path", File(root, peer).absolutePath).put("store_password", password)
            sessions[peer]?.let { fields.put("session", it) }
            return DumberCore.call(fields)
        }
        fun receive(peer: String, room: String, eventId: String, body: String) {
            val retries = JSONArray()
            repeat(8) {
                val result = call(peer, "sync", JSONObject().put("retry_events", retries))
                val events = result.optJSONObject("rooms")?.optJSONObject("join")?.optJSONObject(room)
                    ?.optJSONObject("timeline")?.optJSONArray("events") ?: JSONArray()
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    if (event.optString("event_id") != eventId) continue
                    if (event.optBoolean("e2ee")) {
                        assertEquals(body, event.getJSONObject("content").getString("body"))
                        return
                    }
                    event.optJSONObject("encrypted_event")?.let { raw ->
                        retries.put(JSONObject().put("room_id", room).put("event", raw))
                    }
                }
            }
            fail("Encrypted message was not decrypted by the other device")
        }
        try {
            for ((peer, phone) in listOf("alice" to "+15555550101", "bob" to "+15555550102")) {
                assertTrue("Only the development SMS service is allowed", call(peer, "request_code", JSONObject().put("phone", phone)).getBoolean("development"))
                sessions[peer] = call(peer, "verify", JSONObject().put("phone", phone).put("code", "123456"))
                call(peer, "sync")
            }
            val other = call("alice", "lookup", JSONObject().put("phone", "+15555550102")).getString("user_id")
            assertEquals(sessions.getValue("bob").getString("user_id"), other)
            val resolved = call("alice", "resolve", JSONObject().put("user_ids", JSONArray().put(other)))
            assertEquals("+15555550102", resolved.getJSONObject("phones").getString(other))
            val room = call("alice", "open_chat", JSONObject().put("user_id", other)).getString("room_id")
            call("bob", "join", JSONObject().put("room_id", room))
            call("bob", "sync")
            call("alice", "sync")
            for ((from, to) in listOf("alice" to "bob", "bob" to "alice")) {
                val body = "DPAD encrypted integration test ${UUID.randomUUID()}"
                val sent = call(from, "send", JSONObject().put("room_id", room).put("body", body).put("transaction_id", UUID.randomUUID().toString()))
                assertTrue(sent.getBoolean("e2ee"))
                val id = sent.getString("event_id")
                // Verify the homeserver stores ciphertext, rather than trusting only UI metadata.
                val session = sessions.getValue(from)
                val connection = URL(session.getString("homeserver") + "/_matrix/client/v3/rooms/" + room + "/event/" + id).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.setRequestProperty("Authorization", "Bearer " + session.getString("access_token"))
                try {
                    val raw = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    assertEquals("m.room.encrypted", raw.getString("type"))
                    assertFalse(raw.getJSONObject("content").has("body"))
                } finally { connection.disconnect() }
                receive(to, room, id, body)
            }
        } finally {
            for (peer in sessions.keys) runCatching { call(peer, "logout") }
            root.deleteRecursively()
        }
    }
}
