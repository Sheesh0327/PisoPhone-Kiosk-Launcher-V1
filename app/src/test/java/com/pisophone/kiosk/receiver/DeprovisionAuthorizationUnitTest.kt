package com.pisophone.kiosk.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeprovisionAuthorizationUnitTest {
    @Test
    fun theKioskIsRemovedWithThePinOrWhileUsbDebuggingIsOn() {
        assertTrue(KioskAdminActionReceiver.deprovisionAllowed(pinAuthorized = true, usbDebuggingOn = false))
        assertTrue(KioskAdminActionReceiver.deprovisionAllowed(pinAuthorized = false, usbDebuggingOn = true))
    }

    @Test
    fun aRentalPhoneWithUsbDebuggingOffStillNeedsThePin() {
        assertFalse(KioskAdminActionReceiver.deprovisionAllowed(pinAuthorized = false, usbDebuggingOn = false))
    }
}
