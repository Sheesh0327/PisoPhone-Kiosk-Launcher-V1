package com.pisophone.kiosk.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskActivationManagerUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear DirectBoot and normal shared preferences for clean state
        val directBootPrefs = KioskSecurity.getDirectBootPrefs(context, "kiosk_activation_vault")
        directBootPrefs.edit().clear().commit()
        val securityPrefs = KioskSecurity.getDirectBootPrefs(context, "kiosk_security_vault")
        securityPrefs.edit().clear().commit()
    }

    @Test
    fun testFreshInstallSavesDeviceIdAndAllowsAppToRun() {
        // App installs like normal, auto-saves device ID, and allows app to run
        assertTrue(
            "isAppAllowedToRun must return true on fresh install",
            KioskActivationManager.isAppAllowedToRun(context)
        )
        assertTrue(
            "Bound hardware ID must be automatically saved and non-empty",
            KioskActivationManager.getBoundHardwareId(context).isNotBlank()
        )
    }

    @Test
    fun testProvisioningRecordsDeviceIdentity() {
        val recorded = KioskActivationManager.recordDeviceIdentity(context)
        assertTrue("recordDeviceIdentity should succeed", recorded)

        assertTrue(
            "isAppAllowedToRun must be true after provisioning",
            KioskActivationManager.isAppAllowedToRun(context)
        )
        assertTrue(
            "Bound hardware ID must match current fingerprint",
            KioskActivationManager.getBoundHardwareId(context) == KioskActivationManager.getHardwareFingerprint(context)
        )
    }

    @Test
    fun testSetupModeWindowBehavior() {
        // Before startSetupWindow, isSetupModeActive must be false (read-only check)
        assertFalse(
            "isSetupModeActive must be false before startSetupWindow is explicitly called",
            KioskActivationManager.isSetupModeActive(context)
        )

        // Calling startSetupWindow initializes the setup mode window
        KioskActivationManager.startSetupWindow(context)
        assertTrue(
            "isSetupModeActive must be true within the setup window duration",
            KioskActivationManager.isSetupModeActive(context)
        )
    }

    @Test
    fun testSlotLockdownManagement() {
        assertFalse(
            "Slot should not be locked down initially",
            KioskActivationManager.isSlotLockedDown(context)
        )

        KioskActivationManager.setSlotLockdown(
            context = context,
            locked = true,
            reason = "Slot 1 Expired",
            slotNum = 1,
            expiryTs = 1700000000000L
        )

        assertTrue(
            "Slot should be locked down after setting",
            KioskActivationManager.isSlotLockedDown(context)
        )

        val (reason, slotNum, expiryTs) = KioskActivationManager.getSlotLockdownDetails(context)
        org.junit.Assert.assertEquals("Slot 1 Expired", reason)
        org.junit.Assert.assertEquals(1, slotNum)
        org.junit.Assert.assertEquals(1700000000000L, expiryTs)
    }
}
