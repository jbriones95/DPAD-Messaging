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
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                focusSearch(View.FOCUS_RIGHT)?.requestFocus()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
