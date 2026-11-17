package com.dpad.messaging.dumber

import android.app.Application
import chat.dumber.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DumberGroupMessagesTest {
    private val pending get() = JSONObject().put("id", "txn").put("body", "Draft")
    private fun event(sender: String) = Message("event", Contact(sender), 123,
        MessageContent.Text("Sent"), true, "txn")

    @Test fun projectionUsesSharedThreadItemsAndDoesNotLabelUnsentTextEncrypted() {
        val rows = DumberGroupMessages.rows("self", Conversation("group"), listOf(event("other")), listOf(pending.put("timestamp", 456L)))
        val display = rows.mapIndexed { index, row -> row.display(index.toLong(), "Alice", "Unable to decrypt", "Unsupported") }
        assertEquals("Alice", display.first().senderName)
        assertTrue(display.first().isEncrypted)
        assertFalse(display.last().isEncrypted)
        assertEquals(com.dpad.messaging.models.Message.TYPE_FAILED, display.last().type)
        assertEquals(456L, display.last().date)
        val items = com.dpad.messaging.models.ThreadItem.fromMessages(display)
        assertTrue(items.first() is com.dpad.messaging.models.ThreadItem.DateHeader)
        assertTrue(items[1] is com.dpad.messaging.models.ThreadItem.ReceivedMessage)
        assertTrue(items[2] is com.dpad.messaging.models.ThreadItem.SentMessage)
    }

    @Test fun transportEncryptionFlagAndUnsupportedContentArePreserved() {
        val message = event("other").copy(content = MessageContent.Unsupported, encrypted = false)
        val row = DumberGroupMessages.rows("self", Conversation("group"), listOf(message), emptyList()).single()
        val display = row.display(1, "Alice", "Unable to decrypt", "Unsupported")
        assertEquals("Unsupported", display.body)
        assertFalse(display.isEncrypted)
    }

    @Test fun sharedRendererShowsGroupSenderAndPendingRetryAction() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.dpad.messaging.helpers.Prefs.init(context)
        val themed = android.view.ContextThemeWrapper(context, com.dpad.messaging.R.style.Theme_DpadMessaging)
        val parent = android.widget.FrameLayout(themed)
        val rows = DumberGroupMessages.rows("self", Conversation("group"), listOf(event("other")), listOf(pending))
        val display = rows.mapIndexed { index, row -> row.display(index.toLong(), "Alice", "Unable to decrypt", "Unsupported") }
        var clicked: Long? = null
        val adapter = com.dpad.messaging.adapters.ThreadAdapter(
            onMessageLongClick = {}, onMessageClick = { clicked = it.id }, messageStateLabel = { "Pending" })
        adapter.submitList(com.dpad.messaging.models.ThreadItem.fromMessages(display))
        val received = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1))
        adapter.onBindViewHolder(received, 1)
        assertEquals("Alice", received.itemView.findViewById<android.widget.TextView>(com.dpad.messaging.R.id.tv_sender_name).text.toString())
        assertEquals(android.view.View.VISIBLE, received.itemView.findViewById<android.view.View>(com.dpad.messaging.R.id.tv_encrypted).visibility)
        val failed = adapter.onCreateViewHolder(parent, adapter.getItemViewType(2))
        adapter.onBindViewHolder(failed, 2)
        assertEquals("Pending", failed.itemView.findViewById<android.widget.TextView>(com.dpad.messaging.R.id.tv_state).text.toString())
        failed.itemView.findViewById<android.view.View>(com.dpad.messaging.R.id.bubble_container).performClick()
        assertEquals(1L, clicked)
        // A recycled received bubble must not retain its previous encryption label.
        (received as com.dpad.messaging.adapters.ThreadAdapter.ReceivedViewHolder).bind(com.dpad.messaging.models.ThreadItem.ReceivedMessage(display.first().copy(isEncrypted = false)), 1)
        assertEquals(android.view.View.GONE, received.itemView.findViewById<android.view.View>(com.dpad.messaging.R.id.tv_encrypted).visibility)
    }

    @Test fun outgoingEchoDoesNotDisplayPendingBubbleTwice() {
        val rows = DumberGroupMessages.rows("self", Conversation("group"), listOf(event("self")), listOf(pending))
        assertEquals(1, rows.size)
        assertTrue(rows.single().outgoing)
        assertNull(rows.single().pendingId)
    }

    @Test fun incomingMessageCannotHideOurPendingBubble() {
        val rows = DumberGroupMessages.rows("self", Conversation("group"), listOf(event("other")), listOf(pending))
        assertEquals(2, rows.size)
        assertFalse(rows.first().outgoing)
        assertEquals("txn", rows.last().pendingId)
    }

    @Test fun incomingBubbleRetainsSenderAndFailedPendingRetainsError() {
        val info = Conversation("group", members = listOf(GroupMember(Contact("other", "+46123456789"), Membership.JOINED)))
        val rows = DumberGroupMessages.rows("self", info, listOf(event("other")), listOf(pending.put("error", "Offline")))
        assertEquals("+46123456789", rows.first().phone)
        assertEquals("Offline", rows.last().error)
        assertEquals("Draft", rows.last().body)
    }
}
