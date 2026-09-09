package com.dpad.messaging.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dpad.messaging.BuildConfig

/** Compose field for keypad devices. */
class DpadComposeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** True while a keyboard (e.g. TT9) has an active text-entry session, meaning
     *  the D-pad belongs to the keyboard (candidate/word/symbol scrolling). */
    fun isTypingWithIme(): Boolean {
        if (!hasFocus()) return false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val accepting = imm?.isAcceptingText == true
        val active = imm?.isActive == true
        val imeVisible = ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (BuildConfig.DEBUG) {
            android.util.Log.w("DPAD_MSG", "isTypingWithIme focus=${hasFocus()} accepting=$accepting active=$active imeVisible=$imeVisible")
        }
        return accepting || active || imeVisible
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (isTypingWithIme()) {
            // TT9 active: 
            // - LEFT/RIGHT -> keyboard (candidate scrolling)
            // - DOWN -> app navigation (consume here)
            // - UP -> pass to app for goUpFromCompose
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Pass to keyboard for candidate scrolling
                    return super.onKeyPreIme(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        focusSearch(View.FOCUS_DOWN)?.requestFocus()
                    }
                    return true // Consume, don't send to IME
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Pass to app for goUpFromCompose
                    return super.onKeyPreIme(keyCode, event)
                }
            }
            return super.onKeyPreIme(keyCode, event)
        }

        // Keyboard not active: original behavior
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
