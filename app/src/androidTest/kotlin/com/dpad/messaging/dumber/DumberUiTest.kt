package com.dpad.messaging.dumber

import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dpad.messaging.R
import com.dpad.messaging.adapters.ThreadAdapter
import com.dpad.messaging.databinding.ItemMessageReceivedBinding
import com.dpad.messaging.databinding.ItemMessageSentBinding
import com.dpad.messaging.models.Message
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DumberUiTest {
    @Test fun connectScreenFocusStaysOnControls() {
        ActivityScenario.launch(DumberConnectActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val scroll = activity.findViewById<View>(R.id.connect_scroll)
                val numbers = activity.findViewById<View>(R.id.connect_container)
                assertFalse(content.getChildAt(0).isFocusable)
                assertFalse(scroll.isFocusable)
                assertFalse(numbers.isFocusable)
                val back = activity.findViewById<View>(R.id.btn_back)
                assertTrue(back.requestFocus())
                assertSame(back, activity.currentFocus)
                val next = back.focusSearch(View.FOCUS_DOWN)
                assertNotSame(scroll, next)
                assertNotSame(numbers, next)
            }
        }
    }

    @Test fun encryptedLabelsFollowTransportWhenBubblesAreRecycled() {
        ActivityScenario.launch(DumberConnectActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val inflater = LayoutInflater.from(activity)
                val adapter = ThreadAdapter(onMessageLongClick = {})
                val sent = ItemMessageSentBinding.inflate(inflater)
                val received = ItemMessageReceivedBinding.inflate(inflater)
                val sentHolder = adapter.SentViewHolder(sent)
                val receivedHolder = adapter.ReceivedViewHolder(received)
                val encrypted = Message(id = 1, threadId = 1, type = Message.TYPE_SENT, body = "test", isEncrypted = true)
                sentHolder.bind(encrypted, 0)
                receivedHolder.bind(encrypted.copy(type = Message.TYPE_INBOX), 0)
                assertEquals(View.VISIBLE, sent.tvEncrypted.visibility)
                assertEquals(View.VISIBLE, received.tvEncrypted.visibility)
                assertEquals(received.tvSenderName.textSize, received.tvEncrypted.textSize, 0f)
                assertEquals(received.tvSenderName.typeface.style, received.tvEncrypted.typeface.style)
                assertEquals("encrypted", sent.tvEncrypted.text.toString())
                sentHolder.bind(encrypted.copy(isEncrypted = false), 0)
                receivedHolder.bind(encrypted.copy(type = Message.TYPE_INBOX, isEncrypted = false), 0)
                assertEquals(View.GONE, sent.tvEncrypted.visibility)
                assertEquals(View.GONE, received.tvEncrypted.visibility)
            }
        }
    }
}
