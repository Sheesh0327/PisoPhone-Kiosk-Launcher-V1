package com.pisophone.kiosk.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class KioskAdminActionReceiverUnitTest {
    private lateinit var context: Context
    private val receiver = KioskAdminActionReceiver()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun restart(extras: Intent.() -> Unit = {}): Intent =
        Intent(KioskAdminActionReceiver.ACTION_RESTART).apply(extras)

    @Test
    fun restartWithoutPinIsIgnored() {
        receiver.onReceive(context, restart())
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun restartWithWrongPinIsIgnored() {
        receiver.onReceive(context, restart { putExtra("pin", "9999") })
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun restartWithSharedSecretAloneIsIgnored() {
        receiver.onReceive(context, restart { putExtra("secret", com.pisophone.kiosk.security.KioskSecurity.DEFAULT_SHARED_SECRET) })
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun restartWithCorrectPinRestartsTheService() {
        com.pisophone.kiosk.security.KioskSecurity.setAdminPin(context, "test-pin-4821")
        val pin = com.pisophone.kiosk.security.KioskSecurity.getAdminPin(context)
        receiver.onReceive(context, restart { putExtra("pin", pin) })
        assertNotNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    private fun bypass(extras: Intent.() -> Unit = {}): Intent =
        Intent(KioskAdminActionReceiver.ACTION_ADMIN_BYPASS).apply(extras)

    @Test
    fun adminBypassWithSharedSecretAloneIsIgnored() {
        receiver.onReceive(context, bypass { putExtra("secret", com.pisophone.kiosk.security.KioskSecurity.DEFAULT_SHARED_SECRET) })
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun adminBypassWithSharedSecretAndWrongPinIsIgnored() {
        receiver.onReceive(
            context,
            bypass {
                putExtra("pin", "9999")
                putExtra("secret", com.pisophone.kiosk.security.KioskSecurity.DEFAULT_SHARED_SECRET)
            },
        )
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun adminBypassWithCorrectPinStartsTheService() {
        com.pisophone.kiosk.security.KioskSecurity.setAdminPin(context, "test-pin-4821")
        val pin = com.pisophone.kiosk.security.KioskSecurity.getAdminPin(context)
        receiver.onReceive(context, bypass { putExtra("pin", pin) })
        assertNotNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun noPinIsSetOnAFreshInstallAndNoPinUnlocksIt() {
        assertTrue(com.pisophone.kiosk.security.KioskSecurity.isAdminPinUnset(context))
        // neither an empty PIN nor the old factory PIN gets in
        assertFalse(com.pisophone.kiosk.security.KioskSecurity.verifyAdminPin(context, ""))
        assertFalse(com.pisophone.kiosk.security.KioskSecurity.verifyAdminPin(context, "1234"))
        receiver.onReceive(context, restart { putExtra("pin", "1234") })
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }
}
