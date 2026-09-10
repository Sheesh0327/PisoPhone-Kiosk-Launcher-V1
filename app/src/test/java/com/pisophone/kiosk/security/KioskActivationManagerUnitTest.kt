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
        val directBootPrefs = KioskSecurity.getDirectBootPrefs(context, "kiosk_hardware_seal_vault")
        directBootPrefs.edit().clear().commit()
        val securityPrefs = KioskSecurity.getDirectBootPrefs(context, "kiosk_security_vault")
        securityPrefs.edit().clear().commit()
    }

    @Test
    fun testFreshInstallSavesDeviceIdAndIsAuthorized() {
        // App installs like normal, auto-saves device ID, and allows app to run
        assertTrue(
            "Fresh install must save device ID and allow app to run",
            KioskActivationManager.isHardwareAuthorized(context)
        )
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
    fun testProvisioningSealsDeviceAndAuthorizes() {
        // When installer / WebADB triggers sealToCurrentDevice
        val sealed = KioskActivationManager.sealToCurrentDevice(context)
        assertTrue("sealToCurrentDevice should succeed", sealed)

        assertTrue(
            "Device must be authorized after provisioning",
            KioskActivationManager.isHardwareAuthorized(context)
        )
        assertTrue(
            "isAppAllowedToRun must be true after provisioning",
            KioskActivationManager.isAppAllowedToRun(context)
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
    fun testRebindWithAdminPin() {
        // Incorrect PIN fails
        val wrongPinSuccess = KioskActivationManager.rebindWithAdminPin(context, "9999")
        assertFalse("Rebind with wrong PIN must fail", wrongPinSuccess)

        // Correct default PIN (1234) succeeds and seals device
        val correctPinSuccess = KioskActivationManager.rebindWithAdminPin(context, "1234")
        assertTrue("Rebind with correct Admin PIN must succeed", correctPinSuccess)
        assertTrue(KioskActivationManager.isHardwareAuthorized(context))
    }
}
