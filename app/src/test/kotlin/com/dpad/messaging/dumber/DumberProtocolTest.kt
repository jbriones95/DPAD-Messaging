package com.dpad.messaging.dumber

import org.junit.Assert.*
import org.junit.Test

class DumberProtocolTest {
    @Test fun verificationRequiresOneSixDigitCodeAndContext() {
        assertEquals("040939",DumberProtocol.verificationCode("Your dumber.chat verification code is: 040939."))
        assertNull(DumberProtocol.verificationCode("Meet at 123456"))
        assertNull(DumberProtocol.verificationCode("Verification code 1234567"))
        assertNull(DumberProtocol.verificationCode("Your code 123456 or 654321"))
        assertNull(DumberProtocol.verificationCode("Call +48534911588 for a code"))
    }
    @Test fun labelsRequireTransportMetadata() {
        assertFalse(DumberProtocol.encryptedMarker(null))
        assertFalse(DumberProtocol.encryptedMarker("encrypted"))
        assertFalse(DumberProtocol.encryptedMarker("+15555550101"))
        assertFalse(DumberProtocol.encryptedMarker(DumberProtocol.MARKER))
        assertTrue(DumberProtocol.encryptedMarker(DumberProtocol.MARKER + "!room/$" + "event"))
    }
    @Test fun knownRecipientsDoNotNeedAnotherLookupToSend() {
        repeat(100) { assertEquals(DeliveryRoute.ENCRYPTED, deliveryRoute(true, "@peer:example.test", false)) }
    }
    @Test fun onlyConfirmedAbsenceOrOptOutSelectsCarrier() {
        assertEquals(DeliveryRoute.CARRIER, deliveryRoute(false, null, false))
        assertEquals(DeliveryRoute.CARRIER, deliveryRoute(true, null, true))
        assertEquals(DeliveryRoute.CONFIRM_SMS, deliveryRoute(true, null, false))
        assertEquals(DeliveryRoute.ENCRYPTED, deliveryRoute(true, "@peer:example.test", true))
    }
}
