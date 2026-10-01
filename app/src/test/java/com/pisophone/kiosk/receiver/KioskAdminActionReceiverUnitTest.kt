package com.pisophone.kiosk.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val pin = com.pisophone.kiosk.security.KioskSecurity.getAdminPin(context)
        receiver.onReceive(context, restart { putExtra("pin", pin) })
        assertNotNull(shadowOf(context as android.app.Application).nextStartedService)
    }
}
