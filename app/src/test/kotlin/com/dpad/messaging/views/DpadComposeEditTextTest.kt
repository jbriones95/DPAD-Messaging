package com.dpad.messaging.views

import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DpadComposeEditTextTest {

    @Test
    fun detectsAnActiveComposingWord() {
        val field = DpadComposeEditText(ApplicationProvider.getApplicationContext())
        field.setText("hello")
        val editable = requireNotNull(field.text)

        BaseInputConnection.setComposingSpans(editable)
        assertTrue(field.hasActiveComposition())

        BaseInputConnection.removeComposingSpans(editable)
        assertFalse(field.hasActiveComposition())
    }

    @Test
    fun plainTextHasNoComposition() {
        val field = DpadComposeEditText(ApplicationProvider.getApplicationContext())
        field.setText("hello")
        assertFalse(field.hasActiveComposition())
    }
}
