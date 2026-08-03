package com.dpad.messaging.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.dpad.messaging.R

/** Keeps long message text scrollable without making the whole message row scroll. */
class MessageBubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val body = findViewById<TextView>(R.id.tv_body)
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -1
            else -> 0
        }

        if (direction != 0 && body != null && body.canScrollVertically(direction)) {
            body.scrollBy(0, direction * body.lineHeight)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }
}
