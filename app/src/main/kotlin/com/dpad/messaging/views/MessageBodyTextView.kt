package com.dpad.messaging.views

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.Spannable
import android.text.Selection
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.text.TextPaint
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dpad.messaging.R
import kotlin.math.roundToInt

/** Limits a message body to part of the screen so the rest can be read line by line. */
class MessageBodyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private var applyingLinks = false

    init {
        maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO)
            .roundToInt()
            .coerceAtLeast(lineHeight)
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        linksClickable = true
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = context.getColor(R.color.bubbleTextHighlight)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        if (applyingLinks) return
        applyingLinks = true
        try {
            Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
            replacePhoneLinks()
        } finally {
            applyingLinks = false
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        val spannable = text as? Spannable ?: return
        val links = spannable.getSpans(0, spannable.length, android.text.style.ClickableSpan::class.java)
        if (focused) {
            links.firstOrNull()?.let { link ->
                Selection.setSelection(spannable, spannable.getSpanStart(link), spannable.getSpanEnd(link))
            }
        } else {
            Selection.removeSelection(spannable)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_ENTER
        ) {
            val spannable = text as? Spannable
            val selectionStart = spannable?.let { Selection.getSelectionStart(it) } ?: -1
            val link = spannable
                ?.getSpans(0, spannable.length, android.text.style.ClickableSpan::class.java)
                ?.firstOrNull { selectionStart in spannable.getSpanStart(it)..spannable.getSpanEnd(it) }
                ?: spannable?.getSpans(
                    0, spannable.length, android.text.style.ClickableSpan::class.java
                )?.firstOrNull()
            if (link != null) {
                link.onClick(this)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun replacePhoneLinks() {
        isFocusable = false
        isFocusableInTouchMode = false
        val text = text as? Spannable ?: return
        text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
            if (!span.url.startsWith("tel:", ignoreCase = true)) return@forEach
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val number = Uri.decode(Uri.parse(span.url).schemeSpecificPart ?: return@forEach)
            text.removeSpan(span)
            text.setSpan(object : ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    showPhoneActions(number)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = true
                    ds.color = currentTextColor
                }
            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val hasLinks = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).isNotEmpty()
        isFocusable = hasLinks
        isFocusableInTouchMode = hasLinks
    }

    private fun showPhoneActions(number: String) {
        val actions = arrayOf(
            context.getString(R.string.call),
            context.getString(R.string.send_message),
            context.getString(R.string.add_to_contacts)
        )
        AlertDialog.Builder(context)
            .setTitle(number)
            .setItems(actions) { _, which ->
                val intent = when (which) {
                    0 -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                    1 -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                    2 -> Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    }
                    else -> return@setItems
                }
                context.startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.45f
    }
}
