package com.dpad.messaging.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.AppCompatEditText

/** Compose field for keypad devices. */
class DpadComposeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatEditText(context, attrs, defStyleAttr) {

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                focusSearch(View.FOCUS_DOWN)?.requestFocus()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                moveCaretOrExit(keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun moveCaretOrExit(toLeft: Boolean) {
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        if (start != end) {
            setSelection(if (toLeft) minOf(start, end) else maxOf(start, end))
            return
        }

        val textLength = text?.length ?: 0
        if (toLeft && start == 0) {
            focusSearch(View.FOCUS_LEFT)?.requestFocus()
            return
        }
        if (!toLeft && start == textLength) {
            focusSearch(View.FOCUS_RIGHT)?.requestFocus()
            return
        }

        val next = if (toLeft) {
            layout?.getOffsetToLeftOf(start) ?: (start - 1)
        } else {
            layout?.getOffsetToRightOf(start) ?: (start + 1)
        }
        setSelection(next.coerceIn(0, textLength))
    }
}
