package com.dpad.messaging.helpers

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dpad.messaging.R

/**
 * Per-contact message colors.
 *
 * The user can pick a color for a phone number; that color is used for the
 * conversation avatar and for that contact's received message bubbles.
 * Colors are keyed by the normalized (digits-only) phone number.
 */
object ContactColors {

    /** Curated palette (Material 600 tones) that works on light and dark themes. */
    val PALETTE = intArrayOf(
        0xFFE53935.toInt(), // Red
        0xFFD81B60.toInt(), // Pink
        0xFF8E24AA.toInt(), // Purple
        0xFF5E35B1.toInt(), // Deep Purple
        0xFF3949AB.toInt(), // Indigo
        0xFF1E88E5.toInt(), // Blue
        0xFF0097A7.toInt(), // Cyan
        0xFF00897B.toInt(), // Teal
        0xFF43A047.toInt(), // Green
        0xFF7CB342.toInt(), // Light Green
        0xFFF4511E.toInt(), // Deep Orange
        0xFF6D4C41.toInt()  // Brown
    )

    private const val COLUMNS = 4

    /** Digits-only form used as the storage key. */
    fun normalize(phoneNumber: String): String =
        phoneNumber.filter { it.isDigit() }.ifBlank { phoneNumber.trim() }

    /** User-assigned color for [phoneNumber], or null if unset. */
    fun customColor(phoneNumber: String): Int? =
        Prefs.get().getContactColor(normalize(phoneNumber))

    /**
     * Best color for [phoneNumber] = custom color if set,
     * otherwise a stable hash-derived color (so untouched contacts still look nice).
     */
    fun resolveColor(phoneNumber: String): Int =
        customColor(phoneNumber) ?: defaultFor(phoneNumber)

    /** Stable, readable HSL colour derived from a seed string (number or name). */
    fun defaultFor(seed: String): Int {
        val hue = (Math.abs(seed.hashCode()) % 360).toFloat()
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.35f))
    }

    /** High-contrast text colour to place on top of [background]. */
    fun textColorOn(background: Int): Int =
        if (ColorUtils.calculateLuminance(background) < 0.5) Color.WHITE else 0xFF1B1F24.toInt()

    /** Slightly transparent meta colour to place on top of [background]. */
    fun metaColorOn(background: Int): Int =
        if (ColorUtils.calculateLuminance(background) < 0.5) 0xCCFFFFFF.toInt() else 0xAA1B1F24.toInt()

    // ── Drawables ───────────────────────────────────────────────────────────

    private fun roundedRect(color: Int, radiusDp: Float, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp
            if (strokeWidth > 0) setStroke(strokeWidth, 0xFF00FFFF.toInt())
        }

    /** Rounded-square swatch that shows the app's cyan focus ring when focused. */
    fun swatchDrawable(color: Int): Drawable {
        val normal = roundedRect(color, 8f, 0)
        val focused = roundedRect(color, 8f, 7)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(IntArray(0), normal)
        }
    }

    /**
     * Received-bubble background using [color]. Overall shape matches
     * drawable/bubble_received.xml; focus state adds the cyan ring.
     */
    fun receivedBubbleDrawable(color: Int): Drawable {
        val normal = roundedRect(color, 0f, 0).apply {
            cornerRadii = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 2f, 2f)
        }
        val focused = roundedRect(color, 0f, 7).apply {
            cornerRadii = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 2f, 2f)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(IntArray(0), normal)
        }
    }

    // ── Picker dialog ───────────────────────────────────────────────────────

    /**
     * Shows a D-Pad-friendly color picker.
     * [currentColor] selects the matching swatch when the dialog opens;
     * [onSelected] receives the chosen color, or null to clear back to default.
     */
    fun showColorPicker(
        context: Context,
        title: String,
        currentColor: Int?,
        onSelected: (Int?) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val spacingPx = (density * 8f).toInt()
        val paddingPx = (density * 16f).toInt()

        val colors = PALETTE.toList() + null  // null represents "default"
        val columns = 4

        val recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, columns)
            adapter = ColorPickerAdapter(colors, currentColor, onSelected)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            addItemDecoration(SwatchItemDecoration(spacingPx))
            // Limit max height to fit on small screens (Sonim XP3900: 320dp height)
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.height = maxHeight.coerceAtMost(params.height)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(recycler)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val target = currentColor?.let { PALETTE.indexOf(it) } ?: colors.size - 1
            recycler.layoutManager?.scrollToPosition(target)
            recycler.getChildAt(0)?.requestFocus()
            // Ensure dialog fits on screen
            dialog.window?.apply {
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // Limit height to 70% of screen height
                val maxHeight = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
                if (attributes.height > maxHeight) {
                    attributes.height = maxHeight
                }
            }
        }

        dialog.show()
    }

    private class ColorPickerAdapter(
        private val colors: List<Int?>,
        private val currentColor: Int?,
        private val onSelected: (Int?) -> Unit
    ) : RecyclerView.Adapter<ColorPickerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val density = parent.context.resources.displayMetrics.density
            val swatchSizePx = (density * 48f).toInt()
            val view = View(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(swatchSizePx, swatchSizePx)
                isFocusable = true
                isFocusableInTouchMode = true
            }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val color = colors[position]
            holder.itemView.apply {
                background = if (color != null) swatchDrawable(color) else swatchDrawable(0xFF607D8B.toInt())
                setOnClickListener {
                    onSelected(color)
                }
                if (color == currentColor) {
                    requestFocus()
                }
            }
        }

        override fun getItemCount() = colors.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private class SwatchItemDecoration(private val spacingPx: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(spacingPx, spacingPx, spacingPx, spacingPx)
        }
    }
}