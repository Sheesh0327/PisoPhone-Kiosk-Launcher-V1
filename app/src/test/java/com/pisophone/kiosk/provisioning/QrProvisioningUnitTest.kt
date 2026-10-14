package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class QrProvisioningUnitTest {
    private lateinit var context: Context

    // what the provisioning page puts in the QR code's admin extras
    private fun boxBundle(slotAsString: Boolean = false) =
        PersistableBundle().apply {
            putString("secret", "box-secret-ABCDEFGH12345678")
            putString("mac", "aa:bb:cc:dd:ee:0f")
            if (slotAsString) putString("slot", "3") else putInt("slot", 3)
            putString("wifi_ssid", "PisoKiosk")
            putString("wifi_pass", "kiosk-pass-123")
        }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.resetCachesForTests()
    }

    @Test
    fun theBoxDetailsAreReadFromTheBundle() {
        val v = QrProvisioning.fromBundle(boxBundle())!!
        assertEquals("box-secret-ABCDEFGH12345678", v.secret)
        assertEquals("aa:bb:cc:dd:ee:0f", v.mac)
        assertEquals(3, v.slot)
        assertEquals("kiosk-pass-123", v.wifiPass)
        assertEquals(3, QrProvisioning.fromBundle(boxBundle(slotAsString = true))!!.slot)
        assertNull(QrProvisioning.fromBundle(PersistableBundle()))
        assertNull(QrProvisioning.fromBundle(null))
    }

    @Test
    fun applyingStoresWhatTheUsbSetupStores() {
        assertTrue(QrProvisioning.apply(context, QrProvisioning.fromBundle(boxBundle())!!))
        assertEquals("AA:BB:CC:DD:EE:0F", KioskSecurity.getConfiguredEsp32Mac(context).uppercase())
        assertEquals(3, KioskSecurity.getAssignedBoxSlot(context))
        assertEquals("PisoKiosk", KioskSecurity.getKioskWifiSsid(context))
        assertEquals("kiosk-pass-123", KioskSecurity.getKioskWifiPassword(context))
        assertEquals("box-secret-ABCDEFGH12345678", KioskSecurity.getSharedSecret(context))
        assertTrue(KioskActivationManager.isPairingCompleted(context))
    }

    @Test
    fun theProvisioningModeIsAFullyManagedPhoneAndTheDetailsArePassedOn() {
        val intent = Intent("android.app.action.GET_PROVISIONING_MODE").putExtra(QrProvisioning.EXTRA_ADMIN_EXTRAS, boxBundle())
        val activity = Robolectric.buildActivity(ProvisioningModeActivity::class.java, intent).create().get()
        val shadow = shadowOf(activity)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        assertEquals(1, shadow.resultIntent.getIntExtra(ProvisioningModeActivity.EXTRA_PROVISIONING_MODE, -1))
        assertEquals(3, QrProvisioning.fromBundle(QrProvisioning.adminExtras(shadow.resultIntent))!!.slot)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun theLastStepStoresTheDetailsAndFinishes() {
        val intent = Intent("android.app.action.ADMIN_POLICY_COMPLIANCE").putExtra(QrProvisioning.EXTRA_ADMIN_EXTRAS, boxBundle())
        val activity = Robolectric.buildActivity(PolicyComplianceActivity::class.java, intent).create().get()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertTrue(activity.isFinishing)
        assertEquals("kiosk-pass-123", KioskSecurity.getKioskWifiPassword(context))
    }

    @Test
    fun aCodeWithoutBoxDetailsStillFinishesTheSetup() {
        val activity = Robolectric.buildActivity(PolicyComplianceActivity::class.java, Intent("android.app.action.ADMIN_POLICY_COMPLIANCE")).create().get()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertFalse(QrProvisioning.applyFromIntent(context, Intent()))
    }
}
