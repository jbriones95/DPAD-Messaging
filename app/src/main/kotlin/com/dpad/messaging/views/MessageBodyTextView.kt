package com.dpad.messaging.views

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import kotlin.math.roundToInt

/** Limits a message body to part of the screen so the rest can be read line by line. */
class MessageBodyTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO)
            .roundToInt()
            .coerceAtLeast(lineHeight)
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.45f
    }
}
