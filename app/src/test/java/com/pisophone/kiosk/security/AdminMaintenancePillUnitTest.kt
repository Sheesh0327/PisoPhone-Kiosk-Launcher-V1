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

/** The floating pill is hidden while an admin works in Play Store / Settings / the installer, and only then. */
@RunWith(RobolectricTestRunner::class)
class AdminMaintenancePillUnitTest {
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
    fun pillStaysVisibleWithoutAMaintenanceWindow() {
        AdminMaintenanceMode.noteAdminAppOpened()
        AdminMaintenanceMode.setKioskScreenInFront(false)
        assertFalse(AdminMaintenanceMode.hidePill.value)
    }

    @Test
    fun anAdminBypassThatOpensNoAdminAppLeavesThePillAlone() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.setKioskScreenInFront(false) // e.g. a customer app is in front
        assertFalse(AdminMaintenanceMode.hidePill.value)
    }

    @Test
    fun pillHidesWhileTheAdminIsInPlayStoreAndReturnsWhenBackOnTheKiosk() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.noteAdminAppOpened()
        assertFalse("still on the kiosk screen while Play Store starts", AdminMaintenanceMode.hidePill.value)
        AdminMaintenanceMode.setKioskScreenInFront(false) // Play Store is in front
        assertTrue(AdminMaintenanceMode.hidePill.value)
        AdminMaintenanceMode.setKioskScreenInFront(true) // admin pressed Home
        assertFalse(AdminMaintenanceMode.hidePill.value)
        AdminMaintenanceMode.setKioskScreenInFront(false) // a customer app now: the pill is not hidden again
        assertFalse(AdminMaintenanceMode.hidePill.value)
    }

    @Test
    fun pillReturnsWhenTheMaintenanceWindowEnds() {
        AdminMaintenanceMode.begin(context, 900)
        AdminMaintenanceMode.noteAdminAppOpened()
        AdminMaintenanceMode.setKioskScreenInFront(false)
        assertTrue(AdminMaintenanceMode.hidePill.value)
        AdminMaintenanceMode.end(context)
        assertFalse(AdminMaintenanceMode.hidePill.value)
    }
}
