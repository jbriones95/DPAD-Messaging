package com.dpad.messaging.dumber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.dumber.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DumberNativeTest {
    @Test fun nativeLibraryLoadsAndReturnsTypedErrorsWithoutNetwork() {
        assertTrue(ChatClient.isAvailable())
        try {
            ChatClient(ChatConfig("http://127.0.0.1:9")).sessionStatus()
            fail("Missing session must fail locally")
        } catch (e: ChatException) { assertEquals(ErrorKind.INVALID_INPUT,e.kind) }
    }
    @Test fun pendingQueueKeepsEncryptedBodiesAndTombstones() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val event = Message("test-local-event",Contact("test-sender"),1,MessageContent.Text("private test body"),true)
        val account = "test-local-account"
        DumberAccount.update(context) { root ->
            val accounts = root.optJSONObject("accounts") ?: JSONObject().also { root.put("accounts", it) }
            accounts.put(account, JSONObject().put("store_name", "dumber-native-test"))
        }
        DumberInbox(context, account).use { inbox ->
            try {
                inbox.enqueue("test-local-room",event)
                assertTrue(inbox.pending().any { it.second.eventId=="test-local-event" })
                val raw=inbox.readableDatabase.rawQuery("SELECT payload FROM events WHERE event_id='test-local-event'",null).use { c->c.moveToFirst();c.getString(0) }
                assertFalse(raw.contains("private test body"))
                inbox.done("test-local-event")
                inbox.enqueue("test-local-room",event)
                assertTrue(inbox.handled("test-local-event"))
            } finally {
                inbox.writableDatabase.delete("events","event_id=?",arrayOf("test-local-event"))
                DumberAccount.update(context) { it.getJSONObject("accounts").remove(account) }
            }
        }
    }
}
