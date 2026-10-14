package com.pisophone.kiosk.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.network.KioskWifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KioskWifiUnitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.createDeviceProtectedStorageContext().getSharedPreferences("kiosk_security_vault", Context.MODE_PRIVATE).edit().clear().commit()
        KioskSecurity.resetCachesForTests()
    }

    @Test
    fun theKioskWifiIsStoredAndReadBack() {
        assertEquals("", KioskSecurity.getKioskWifiSsid(context))
        assertTrue(KioskSecurity.setKioskWifi(context, "PisoKiosk", "3hC4RATnpQMJ"))
        assertEquals("PisoKiosk", KioskSecurity.getKioskWifiSsid(context))
        assertEquals("3hC4RATnpQMJ", KioskSecurity.getKioskWifiPassword(context))
    }

    @Test
    fun anUnusableNameOrPasswordIsRefusedAndKeepsWhatWasStored() {
        assertTrue(KioskSecurity.setKioskWifi(context, "PisoKiosk", "3hC4RATnpQMJ"))
        assertFalse(KioskSecurity.setKioskWifi(context, "PisoKiosk", "short"))
        assertFalse(KioskSecurity.setKioskWifi(context, "", "3hC4RATnpQMJ"))
        assertFalse(KioskSecurity.setKioskWifi(context, "x".repeat(33), "3hC4RATnpQMJ"))
        assertEquals("3hC4RATnpQMJ", KioskSecurity.getKioskWifiPassword(context))
    }

    @Test
    fun provisioningStoresTheWifiAndDefaultsTheName() {
        KioskSecurity.applyDirectProvisioning(context, wifiPassword = "3hC4RATnpQMJ")
        assertEquals(KioskWifi.DEFAULT_SSID, KioskSecurity.getKioskWifiSsid(context))
        assertEquals("3hC4RATnpQMJ", KioskSecurity.getKioskWifiPassword(context))
    }

    @Test
    fun theRulesMatchWpa2() {
        assertTrue(KioskWifi.isValidPassword("12345678"))
        assertFalse(KioskWifi.isValidPassword("1234567"))
        assertFalse(KioskWifi.isValidPassword("x".repeat(64)))
        assertTrue(KioskWifi.isValidSsid("PisoKiosk"))
        assertFalse(KioskWifi.isValidSsid(""))
    }

    @Test
    fun onlyTheKioskNetworkIsKeptAmongTheSavedOnes() {
        val saved = listOf(3 to "\"PisoKiosk\"", 4 to "\"Neighbour\"", 5 to "\"Shop WiFi\"", -1 to "\"Broken\"", 6 to "\"PisoKiosk 2\"")
        assertEquals(listOf(4, 5, 6), KioskWifi.foreignNetworkIds(saved, "PisoKiosk"))
        assertEquals(emptyList<Int>(), KioskWifi.foreignNetworkIds(listOf(3 to "\"PisoKiosk\""), "PisoKiosk"))
        assertEquals(listOf(7), KioskWifi.foreignNetworkIds(listOf(7 to "PisoKiosk"), "Other"))
    }

    @Test
    fun theLockIsOnOnlyForAProvisionedPhoneOutsideAnAdminWindow() {
        assertTrue(KioskWifi.shouldLock("PisoKiosk", "3hC4RATnpQMJ", adminWindowOpen = false))
        assertFalse(KioskWifi.shouldLock("PisoKiosk", "3hC4RATnpQMJ", adminWindowOpen = true))
        assertFalse(KioskWifi.shouldLock("", "3hC4RATnpQMJ", adminWindowOpen = false))
        assertFalse(KioskWifi.shouldLock("PisoKiosk", "short", adminWindowOpen = false))
    }

    @Test
    fun aPhoneThatIsNotTheDeviceOwnerIsNeverLockedAndNothingBreaks() {
        KioskSecurity.setKioskWifi(context, "PisoKiosk", "3hC4RATnpQMJ")
        assertFalse(KioskWifi.lockToKioskNetwork(context))
        KioskWifi.releaseNetworkLock(context)
    }
}
