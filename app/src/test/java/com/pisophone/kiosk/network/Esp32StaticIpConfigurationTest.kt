package com.pisophone.kiosk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskSecurity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Esp32StaticIpConfigurationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testDefaultEsp32Ip() {
        val defaultIp = KioskSecurity.getConfiguredEsp32Ip(context)
        assertEquals("192.168.1.10", defaultIp)
    }

    @Test
    fun testValidIpv4Validation() {
        assertTrue(KioskSecurity.isValidIpv4("192.168.1.10"))
        assertTrue(KioskSecurity.isValidIpv4("10.0.0.1"))
        assertTrue(KioskSecurity.isValidIpv4("172.16.0.50"))
        assertTrue(KioskSecurity.isValidIpv4("192.168.4.1"))

        assertFalse(KioskSecurity.isValidIpv4(""))
        assertFalse(KioskSecurity.isValidIpv4("abc"))
        assertFalse(KioskSecurity.isValidIpv4("192.168.1"))
        assertFalse(KioskSecurity.isValidIpv4("192.168.1.256"))
        assertFalse(KioskSecurity.isValidIpv4("192.168.1.-1"))
        assertFalse(KioskSecurity.isValidIpv4("192.168.01.10"))
    }

    @Test
    fun testSetConfiguredEsp32Ip() {
        val success = KioskSecurity.setConfiguredEsp32Ip(context, "192.168.1.55")
        assertTrue(success)
        assertEquals("192.168.1.55", KioskSecurity.getConfiguredEsp32Ip(context))

        val invalidSuccess = KioskSecurity.setConfiguredEsp32Ip(context, "999.999.999.999")
        assertFalse(invalidSuccess)
        assertEquals("192.168.1.55", KioskSecurity.getConfiguredEsp32Ip(context))
    }
}
