package com.pisophone.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.AppLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareLockAdminBypassTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset prefs for clean test state
        val prefs = KioskSecurity.getDirectBootPrefs(context, "pisophone_hw_lock")
        prefs.edit().clear().commit()
    }

    @Test
    fun testRebindWithAdminPinDoesNotBypassUnactivatedLicense() {
        // 1. First run / unactivated machine
        HardwareLockManager.sealToCurrentDevice(context)
        val initialInfo = HardwareLockManager.getLicenseInfo(context)
        assertEquals(HardwareLockManager.LicenseState.UNACTIVATED, initialInfo.state)
        assertFalse("App must not be allowed to run when unactivated", HardwareLockManager.isAppAllowedToRun(context))

        // 2. Admin enters correct PIN (default "1234")
        val rebindSuccess = HardwareLockManager.rebindWithAdminPin(context, "1234")
        assertTrue("Admin rebind with correct PIN must return true", rebindSuccess)

        // 3. Verify that license status is STILL UNACTIVATED and app is STILL blocked from running
        val infoAfterRebind = HardwareLockManager.getLicenseInfo(context)
        assertEquals("License must remain UNACTIVATED after admin rebind", HardwareLockManager.LicenseState.UNACTIVATED, infoAfterRebind.state)
        assertFalse("App must NOT be allowed to run after admin rebind on unactivated device", HardwareLockManager.isAppAllowedToRun(context))

        // 4. Verify AppLauncher blocks launching apps when not allowed to run
        val launched = AppLauncher.launchApp(context, "com.android.chrome")
        assertFalse("AppLauncher must block app launch when license is unactivated", launched)
    }

    @Test
    fun testRebindWithIncorrectPinFails() {
        HardwareLockManager.sealToCurrentDevice(context)
        val rebindSuccess = HardwareLockManager.rebindWithAdminPin(context, "999999_WRONG_PIN")
        assertFalse("Admin rebind with wrong PIN must return false", rebindSuccess)
    }
}
