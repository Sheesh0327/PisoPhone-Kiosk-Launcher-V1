package com.pisophone.kiosk.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPolicyManagerAppRemovalUnitTest {
    @Test
    fun customersCannotRemoveAppsButTheOwnerCanInAMaintenanceWindow() {
        assertFalse(KioskPolicyManager.appRemovalAllowed(maintenanceActive = false))
        assertTrue(KioskPolicyManager.appRemovalAllowed(maintenanceActive = true))
    }
}
