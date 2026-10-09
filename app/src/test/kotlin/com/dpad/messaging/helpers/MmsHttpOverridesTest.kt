package com.dpad.messaging.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsHttpOverridesTest {
    @Test
    fun `recognizes Verizon MCC MNCs including MVNO ranges`() {
        assertTrue(MmsHttpOverrides.isVerizonOperator("311480"))
        assertTrue(MmsHttpOverrides.isVerizonOperator("310010"))
    }

    @Test
    fun `does not classify unrelated operators as Verizon`() {
        assertFalse(MmsHttpOverrides.isVerizonOperator("310260"))
        assertFalse(MmsHttpOverrides.isVerizonOperator("312480"))
        assertFalse(MmsHttpOverrides.isVerizonOperator(""))
    }
}
