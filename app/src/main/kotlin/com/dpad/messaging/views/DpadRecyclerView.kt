package com.dpad.messaging.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView subclass with proper D-Pad boundary navigation for dumbphone use.
 *
 * Standard RecyclerView absorbs all D-Pad key events even at list boundaries,
 * leaving focus stuck with no way to reach sibling views (toolbar, compose bar).
 *
 * This implementation:
 *  - Passes FOCUS_UP out of the list when the first item is already visible/focused
 *  - Passes FOCUS_DOWN out of the list when the last item is already visible/focused
 *  - Preserves normal intra-list navigation for all other cases
 */
class DpadRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Called when the user tries to navigate UP past the first item. */
    var onTopEdgeReached: (() -> Unit)? = null

    /** Called when the user tries to navigate DOWN past the last item. */
    var onBottomEdgeReached: (() -> Unit)? = null

    override fun focusSearch(focused: View, direction: Int): View? {
        val lm = layoutManager as? LinearLayoutManager

        if (lm != null) {
            when (direction) {
                FOCUS_UP -> {
                    val firstVisible = lm.findFirstVisibleItemPosition()
                    val focusedPos = getFocusedAdapterPosition(focused)
                    if (firstVisible <= 0 && (focusedPos <= 0 || focusedPos == RecyclerView.NO_POSITION)) {
                        onTopEdgeReached?.invoke()
                        // Let the framework find the next focusable view above this RecyclerView
                        return focusSearchParent(direction)
                    }
                }
                FOCUS_DOWN -> {
                    val itemCount = adapter?.itemCount ?: 0
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val focusedPos = getFocusedAdapterPosition(focused)
                    if (itemCount > 0 && lastVisible >= itemCount - 1 && focusedPos >= itemCount - 1) {
                        val handled = onBottomEdgeReached
                        if (handled != null) {
                            // The callback fully owns focus handling; skip the default
                            // focus search so it can't override where the callback
                            // requested (e.g. returning to the compose field).
                            handled.invoke()
                            return null
                        }
                        return focusSearchParent(direction)
                    }
                }
            }
        }

        return super.focusSearch(focused, direction)
    }

    /**
     * Returns adapter position for any view inside an item (not only direct children).
     * Prevents ClassCastException when focus lands on nested views with non-RV LayoutParams.
     */
    private fun getFocusedAdapterPosition(focused: View): Int {
        val itemView = findContainingItemView(focused) ?: return RecyclerView.NO_POSITION
        return getChildAdapterPosition(itemView)
    }

    /** Requests focus search starting from this RecyclerView's perspective (not from a child). */
    private fun focusSearchParent(direction: Int): View? {
        return parent?.let {
            if (it is View) it.focusSearch(direction) else null
        }
    }

    /**
     * Requests focus on the first visible item.
     * Use when entering the list from the toolbar (D-Pad DOWN into list).
     */
    fun focusFirstItem() {
        post {
            if (focusedChild != null) return@post
            val lm = layoutManager as? LinearLayoutManager ?: return@post
            val firstPos = lm.findFirstVisibleItemPosition()
                .takeIf { it != NO_POSITION } ?: 0
            findViewHolderForAdapterPosition(firstPos)?.itemView?.requestFocus()
        }
    }

    /**
     * Requests focus on the last visible item.
     * Use when entering the message list from the compose bar (D-Pad UP into list).
     */
    fun focusLastItem() {
        post {
            if (focusedChild != null) return@post
            val itemCount = adapter?.itemCount ?: return@post
            if (itemCount == 0) return@post
            scrollToPosition(itemCount - 1)
            post {
                if (focusedChild == null) {
                    findViewHolderForAdapterPosition(itemCount - 1)?.itemView?.requestFocus()
                }
            }
        }
    }

    /**
     * Scrolls to and focuses the item at [position].
     * Skips focus restoration if the user has already navigated during the layout delay.
     */
    fun focusItem(position: Int) {
        if (position < 0) return
        scrollToPosition(position)
        post {
            if (focusedChild == null) {
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Allow ENTER / DPAD_CENTER to trigger click on the focused child
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            return focusedChild?.performClick() == true || super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }
}
