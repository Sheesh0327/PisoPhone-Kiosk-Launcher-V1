package com.pisophone.kiosk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Esp32DirectPairingUnitTest {

    private lateinit var context: Context
    private val testMac = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
    }

    @Test
    fun testDefaultStaticEsp32IpConstant() {
        assertEquals("192.168.1.10", Esp32ConnectionManager.DEFAULT_STATIC_ESP32_IP)
    }

    @Test
    fun testGetEsp32HostAndPort_defaultAndCustom() {
        val (host1, port1) = Esp32ConnectionManager.getEsp32HostAndPort("192.168.1.10")
        assertEquals("192.168.1.10", host1)
        assertEquals(80, port1)

        val (host2, port2) = Esp32ConnectionManager.getEsp32HostAndPort("192.168.1.10:8080")
        assertEquals("192.168.1.10", host2)
        assertEquals(8080, port2)

        val (host3, port3) = Esp32ConnectionManager.getEsp32HostAndPort("")
        assertEquals("192.168.1.10", host3)
        assertEquals(80, port3)
    }

    @Test
    fun testIsEsp32MacMatching_matchingMac_returnsTrue() {
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("AABBCCDDEEFF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun testIsEsp32MacMatching_mismatchedMac_returnsFalse() {
        assertFalse(Esp32ConnectionManager.isEsp32MacMatching("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"))
        assertFalse(Esp32ConnectionManager.isEsp32MacMatching("AA:BB:CC:DD:EE:FF", ""))
    }

    @Test
    fun testIsEsp32MacMatching_blankExpected_allowsAnyMac() {
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("", "AA:BB:CC:DD:EE:FF"))
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching(null, "AA:BB:CC:DD:EE:FF"))
    }
}
