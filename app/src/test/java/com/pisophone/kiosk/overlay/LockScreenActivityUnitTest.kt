package com.pisophone.kiosk.overlay

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver
import com.pisophone.kiosk.service.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The lock screen as an activity, for a phone without the overlay permission (set up by QR code). */
@RunWith(RobolectricTestRunner::class)
class LockScreenActivityUnitTest {
    private lateinit var context: Context
    private val appState = MutableStateFlow(SessionState.LOCKED.code)

    private fun overlay() =
        KioskOverlay(
            context = context,
            appStateFlow = appState,
            sessionTimeFlow = MutableStateFlow(0),
            paymentTimeoutFlow = MutableStateFlow(0),
            coinsInsertedFlow = MutableStateFlow(0),
            themeIndexFlow = MutableStateFlow(0),
            isEsp32OnlineFlow = MutableStateFlow(false),
            pricePerCoinFlow = MutableStateFlow(1.0),
            minutesPerCoinFlow = MutableStateFlow(5),
            onInsertCoinClick = {},
            onDoneClick = {},
            onThemeChange = {},
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LockScreenActivity.host = null
    }

    @After
    fun tearDown() {
        LockScreenActivity.host = null
    }

    @Test
    fun usedOnlyByADeviceOwnerWithoutTheOverlayPermission() {
        assertFalse("not the device owner", LockScreenActivity.shouldUse(context))
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setDeviceOwner(ComponentName(context, KioskDeviceAdminReceiver::class.java))
        assertTrue(LockScreenActivity.isDeviceOwner(context))
        assertTrue("device owner, no overlay permission", LockScreenActivity.shouldUse(context))
    }

    @Test
    fun shownWhileThePhoneIsLockedOnly() {
        assertFalse("no kiosk running yet", LockScreenActivity.shouldShowNow())
        LockScreenActivity.host = overlay()
        appState.value = SessionState.LOCKED.code
        assertTrue(LockScreenActivity.shouldShowNow())
        appState.value = SessionState.ARMED_LOCKED.code
        assertTrue(LockScreenActivity.shouldShowNow())
        appState.value = SessionState.UNLOCKED.code
        assertFalse(LockScreenActivity.shouldShowNow())
    }

    @Test
    fun withoutAKioskRunningItClosesAtOnce() {
        val activity = Robolectric.buildActivity(LockScreenActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertFalse(LockScreenActivity.isAlive)
    }
}
