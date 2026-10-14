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

/** The overlay windows are taken off the screen while an admin works in Play Store / Settings / the installer, and only then. */
@RunWith(RobolectricTestRunner::class)
class AdminMaintenanceOverlaysUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AdminMaintenanceMode.end(context)
        AdminMaintenanceMode.setKioskScreenInFront(true)
    }

    @After
    fun tearDown() {
        AdminMaintenanceMode.end(context)
        AdminMaintenanceMode.setKioskScreenInFront(true)
    }

    @Test
    fun overlaysStayWithoutAMaintenanceWindow() {
        AdminMaintenanceMode.noteAdminAppOpened()
        AdminMaintenanceMode.setKioskScreenInFront(false)
        assertFalse(AdminMaintenanceMode.suspendOverlays.value)
    }

    @Test
    fun anAdminBypassThatOpensNoAdminAppLeavesTheOverlaysAlone() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.setKioskScreenInFront(false) // e.g. a customer app is in front
        assertFalse(AdminMaintenanceMode.suspendOverlays.value)
    }

    @Test
    fun overlaysGoWhileTheAdminIsInPlayStoreAndReturnWhenBackOnTheKiosk() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.noteAdminAppOpened()
        assertFalse("still on the kiosk screen while Play Store starts", AdminMaintenanceMode.suspendOverlays.value)
        AdminMaintenanceMode.setKioskScreenInFront(false) // Play Store is in front
        assertTrue(AdminMaintenanceMode.suspendOverlays.value)
        AdminMaintenanceMode.setKioskScreenInFront(true) // admin pressed Home
        assertFalse(AdminMaintenanceMode.suspendOverlays.value)
        AdminMaintenanceMode.setKioskScreenInFront(false) // a customer app now: the overlays are not removed again
        assertFalse(AdminMaintenanceMode.suspendOverlays.value)
    }

    @Test
    fun overlaysReturnWhenTheMaintenanceWindowEnds() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.noteAdminAppOpened()
        AdminMaintenanceMode.setKioskScreenInFront(false)
        assertTrue(AdminMaintenanceMode.suspendOverlays.value)
        AdminMaintenanceMode.end(context)
        assertFalse(AdminMaintenanceMode.suspendOverlays.value)
    }
}
