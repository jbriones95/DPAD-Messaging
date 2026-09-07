package com.dpad.messaging.helpers

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
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
        val swatchSizePx = (density * 48f).toInt()
        val spacingPx = (density * 8f).toInt()
        val paddingPx = (density * 16f).toInt()

        val rows = (PALETTE.size + COLUMNS - 1) / COLUMNS

        val grid = GridLayout(context).apply {
            columnCount = COLUMNS
            rowCount = rows + 1
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        lateinit var dialog: AlertDialog

        val swatches = PALETTE.mapIndexed { index, color ->
            View(context).apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / COLUMNS),
                    GridLayout.spec(index % COLUMNS)
                ).apply {
                    width = swatchSizePx
                    height = swatchSizePx
                    setMargins(spacingPx, spacingPx, spacingPx, spacingPx)
                }
                background = swatchDrawable(color)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(color)
                }
            }
        }

        val defaultCell = TextView(context).apply {
            text = context.getString(R.string.color_default)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(paddingPx, 0, paddingPx, 0)
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(rows),
                GridLayout.spec(0, COLUMNS)
            ).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = swatchSizePx
                setMargins(spacingPx, spacingPx, spacingPx, spacingPx)
            }
            background = swatchDrawable(0xFF607D8B.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                dialog.dismiss()
                onSelected(null)
            }
        }

        swatches.forEach { grid.addView(it) }
        grid.addView(defaultCell)

        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val target = currentColor?.let { PALETTE.indexOf(it) } ?: -1
            val focusTarget = if (target >= 0) swatches[target] else defaultCell
            focusTarget.requestFocus()
        }

        dialog.show()
    }
}