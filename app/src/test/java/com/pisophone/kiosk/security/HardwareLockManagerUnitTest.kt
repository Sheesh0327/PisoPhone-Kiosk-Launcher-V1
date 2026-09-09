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
    fun testFreshInstallIsUnsealedAndRequiresPairing() {
        // App installs in unsealed state: hardware not authorized and app not allowed to run until paired
        assertFalse(
            "Fresh install must not be authorized before pairing",
            HardwareLockManager.isHardwareAuthorized(context)
        )
        assertFalse(
            "isAppAllowedToRun must return false on unsealed fresh install",
            HardwareLockManager.isAppAllowedToRun(context)
        )
        assertTrue(
            "Bound hardware ID must be empty on unsealed fresh install",
            HardwareLockManager.getBoundHardwareId(context).isEmpty()
        )
        assertTrue(
            "Canonical device ID must be derived and non-empty",
            HardwareLockManager.getCanonicalDeviceId(context).isNotBlank()
        )
    }

    @Test
    fun testProvisioningSealsDeviceAndAuthorizes() {
        // Configure box parameters and shared secret
        val configured = KioskSecurity.applyDirectProvisioning(
            context = context,
            secret = "test_shared_secret",
            mac = "AA:BB:CC:DD:EE:FF",
            ip = "192.168.1.100",
            slot = 1,
            name = "PisoPhone 1"
        )
        assertTrue("applyDirectProvisioning should succeed", configured)

        // When installer / pairing coordinator seals the device
        val sealed = HardwareLockManager.sealToCurrentDevice(context)
        assertTrue("sealToCurrentDevice should succeed", sealed)

        assertTrue(
            "Device must be authorized after provisioning",
            HardwareLockManager.isHardwareAuthorized(context)
        )
        assertTrue(
            "isAppAllowedToRun must be true after provisioning and sealing",
            HardwareLockManager.isAppAllowedToRun(context)
        )
    }

    @Test
    fun testRebindWithAdminPin() {
        // Incorrect PIN fails
        val wrongPinSuccess = HardwareLockManager.rebindWithAdminPin(context, "9999")
        assertFalse("Rebind with wrong PIN must fail", wrongPinSuccess)

        // Correct default PIN (1234) succeeds and seals device
        val correctPinSuccess = HardwareLockManager.rebindWithAdminPin(context, "1234")
        assertTrue("Rebind with correct Admin PIN must succeed", correctPinSuccess)
        assertTrue(HardwareLockManager.isHardwareAuthorized(context))
    }
}
