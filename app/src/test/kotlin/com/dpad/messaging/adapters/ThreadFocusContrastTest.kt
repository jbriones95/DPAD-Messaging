package com.dpad.messaging.adapters

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreadFocusContrastTest {

    @Test
    fun amberFocusSurfaceUsesDarkText() {
        val background = 0xFFFFE08A.toInt()
        val preferred = 0xFF241800.toInt()

        val result = ThreadAdapter.higherContrastText(background, preferred)

        assertEquals(preferred, result)
        assertTrue(ColorUtils.calculateContrast(result, background) >= 4.5)
    }

    @Test
    fun darkFocusSurfaceUsesLightText() {
        val background = 0xFF241C10.toInt()
        val preferred = 0xFFFFF4D8.toInt()

        val result = ThreadAdapter.higherContrastText(background, preferred)

        assertEquals(preferred, result)
        assertTrue(ColorUtils.calculateContrast(result, background) >= 4.5)
    }
}
