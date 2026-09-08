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
class HardwareLockManagerUnitTest {

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
    fun testFreshSideloadedInstallIsNotAuthorized() {
        // When an APK is extracted and sideloaded via adb onto a new phone,
        // it has not been provisioned by the installer/ESP32, so isHardwareAuthorized MUST return false
        assertFalse(
            "Fresh sideloaded APK must NOT be authorized automatically",
            HardwareLockManager.isHardwareAuthorized(context)
        )
        assertFalse(
            "isAppAllowedToRun must return false on unprovisioned sideloaded device",
            HardwareLockManager.isAppAllowedToRun(context)
        )
    }

    @Test
    fun testProvisioningSealsDeviceAndAuthorizes() {
        // When installer / WebADB triggers sealToCurrentDevice
        val sealed = HardwareLockManager.sealToCurrentDevice(context)
        assertTrue("sealToCurrentDevice should succeed", sealed)

        assertTrue(
            "Device must be authorized after legitimate provisioning",
            HardwareLockManager.isHardwareAuthorized(context)
        )
        assertTrue(
            "isAppAllowedToRun must be true after legitimate provisioning",
            HardwareLockManager.isAppAllowedToRun(context)
        )
    }

    @Test
    fun testTamperedHardwareMismatchLocksDevice() {
        // Provision initially
        HardwareLockManager.sealToCurrentDevice(context)
        assertTrue(HardwareLockManager.isHardwareAuthorized(context))

        // Simulate cloned SharedPreferences onto a foreign device with different hardware ID
        val directBootPrefs = KioskSecurity.getDirectBootPrefs(context, "kiosk_hardware_seal_vault")
        directBootPrefs.edit().putString("bound_hardware_fingerprint", "HW-FAKE-9999-CLONED").commit()

        assertFalse(
            "Cloned preferences with mismatched hardware fingerprint must be rejected",
            HardwareLockManager.isHardwareAuthorized(context)
        )

        // Device should now have hardware lock flag set
        assertTrue(
            "Tamper breach must set hardware_lock_enforced flag",
            directBootPrefs.getBoolean("hardware_lock_enforced", false)
        )
    }

    @Test
    fun testAdminPinRebind() {
        // Sideloaded unprovisioned device
        assertFalse(HardwareLockManager.isHardwareAuthorized(context))

        // Incorrect PIN fails
        val wrongPinSuccess = HardwareLockManager.rebindWithAdminPin(context, "9999")
        assertFalse("Rebind with wrong PIN must fail", wrongPinSuccess)
        assertFalse(HardwareLockManager.isHardwareAuthorized(context))

        // Correct default PIN (1234) succeeds and seals device
        val correctPinSuccess = HardwareLockManager.rebindWithAdminPin(context, "1234")
        assertTrue("Rebind with correct Admin PIN must succeed", correctPinSuccess)
        assertTrue(HardwareLockManager.isHardwareAuthorized(context))
    }
}
