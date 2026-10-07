package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings

/** The "display over other apps" step a phone set up by QR code shows instead of its apps. */
@RunWith(RobolectricTestRunner::class)
class OverlayPermissionStepUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.resetCachesForTests()
        context.getSharedPreferences("kiosk_overlay_step", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun neededUntilThePermissionIsOn() {
        assertTrue(OverlayPermissionStep.isNeeded(context))
        ShadowSettings.setCanDrawOverlays(true)
        assertFalse(OverlayPermissionStep.isNeeded(context))
    }

    @Test
    fun noPinRightAfterTheQrSetupOrWithoutAPinThePinAfterwards() {
        assertFalse("no admin PIN yet", OverlayPermissionStep.needsPin(context))
        KioskSecurity.setAdminPin(context, "box-admin-123")
        assertTrue("a phone in use: the admin PIN is asked", OverlayPermissionStep.needsPin(context))
        OverlayPermissionStep.markQrSetup(context)
        assertFalse("just set up by QR code", OverlayPermissionStep.needsPin(context))
    }

    @Test
    fun androidGoPhonesUseUsbOthersTheSwitch() {
        assertFalse("a normal phone: the switch first", OverlayPermissionStep.usbFirst(context))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setIsLowRamDevice(true)
        assertTrue("an Android Go phone: USB first", OverlayPermissionStep.usbFirst(context))
    }

    @Test
    fun theButtonOpensTheSetting() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertTrue(OverlayPermissionStep.openSetting(activity))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, started.action)
        assertEquals("package:${activity.packageName}", started.dataString)
    }
}
