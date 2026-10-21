package com.dpad.messaging.dumber

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.dumber.app.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DumberNativeTest {
    @Test fun nativeLibraryLoadsAndReturnsErrorsWithoutNetwork() {
        assertTrue(DumberCore.available())
        assertFalse(JSONObject(NativeCore.invoke("{")).getBoolean("ok"))
    }
    @Test fun removedHistoryCommandFailsLocallyWithUpdatedNativeLibrary() {
        val response = JSONObject(NativeCore.invoke(JSONObject().put("command", "history")
            .put("service", "http://127.0.0.1:9")
            .put("session", JSONObject().put("homeserver", "http://127.0.0.1:9")
                .put("user_id", "@test:example.org").put("access_token", "test").put("device_id", "TEST"))
            .toString()))
        assertFalse(response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("Server history is not available"))
    }
    @Test fun pendingQueueKeepsEncryptedBodiesAndTombstones() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val event=JSONObject().put("event_id","test-local-event").put("e2ee",true)
            .put("type","m.room.message").put("content",JSONObject().put("body","private test body"))
        DumberInbox(context).use { inbox ->
            try {
                inbox.enqueue("test-local-room",event)
                assertTrue(inbox.pending().any { it.second.optString("event_id")=="test-local-event" })
                val raw=inbox.readableDatabase.rawQuery("SELECT payload FROM events WHERE event_id='test-local-event'",null).use { c->c.moveToFirst();c.getString(0) }
                assertFalse(raw.contains("private test body"))
                inbox.done("test-local-event")
                inbox.enqueue("test-local-room",event)
                assertTrue(inbox.handled("test-local-event"))
            } finally { inbox.writableDatabase.delete("events","event_id=?",arrayOf("test-local-event")) }
        }
    }
}
