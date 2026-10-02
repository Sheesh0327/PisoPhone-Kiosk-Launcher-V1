package com.pisophone.kiosk.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskLockdownUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AdminMaintenanceMode.end(context)
    }

    @After
    fun tearDown() {
        AdminMaintenanceMode.end(context)
    }

    @Test
    fun testAdminOnlyPackagesAreAlwaysHiddenFromRenters() {
        for (pkg in KioskPolicyManager.ADMIN_ONLY_PACKAGES) {
            assertTrue("$pkg must be hidden", KioskSecurity.isAppHidden(context, pkg))
            assertTrue("$pkg must be in the hidden set", KioskSecurity.getHiddenApps(context).contains(pkg))
            assertTrue("$pkg cannot be un-hidden", KioskSecurity.toggleAppHidden(context, pkg))
        }
        assertFalse(KioskSecurity.isAppHidden(context, "com.example.game"))
    }

    @Test
    fun testAdminOnlyPackagesOnlyAllowedInLockTaskDuringMaintenance() {
        val renter = KioskPolicyManager.getAllowedLockTaskPackages(context).toSet()
        for (pkg in KioskPolicyManager.ADMIN_ONLY_PACKAGES) {
            assertFalse("$pkg must not be allowlisted for renters", renter.contains(pkg))
        }
        assertTrue(renter.contains(context.packageName))

        AdminMaintenanceMode.begin(context, 60)
        assertTrue(AdminMaintenanceMode.isActive(context))
        val admin = KioskPolicyManager.getAllowedLockTaskPackages(context).toSet()
        assertTrue(admin.containsAll(KioskPolicyManager.ADMIN_ONLY_PACKAGES))

        AdminMaintenanceMode.end(context)
        assertFalse(AdminMaintenanceMode.isActive(context))
        val after = KioskPolicyManager.getAllowedLockTaskPackages(context).toSet()
        assertFalse(after.contains("com.android.settings"))
    }

    @Test
    fun testShorterBypassNeverShortensOpenMaintenanceWindow() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.begin(context, 1)
        Thread.sleep(1100)
        assertTrue(AdminMaintenanceMode.isActive(context))
    }
}
