package com.pisophone.kiosk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Esp32DiscoveryScannerUnitTest {

    private lateinit var context: Context
    private val testScope = TestScope()
    private val testMac = "AA:BB:CC:DD:EE:FF"
    private val testSecret = "secretKey12345"
    private val testIp = "192.168.1.10"

    private val fakeDelegate = object : Esp32DiscoveryDelegate {
        override fun onEsp32Discovered(ip: String, rawResponseBody: String?) {}
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
        KioskSecurity.setSharedSecret(context, testSecret)
    }

    @Test
    fun testValidateEsp32Response_validMacAndHmac_returnsTrue() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        val sig = KioskSecurity.calculateHmac("DISCOVERY:$testMac:$testIp", testSecret)
        val json = JSONObject().apply {
            put("type", "PISOPHONE_ESP32_RESPONSE")
            put("mac", testMac)
            put("sig", sig)
        }

        assertTrue(scanner.validateEsp32Response(testMac, testIp, sig, json.toString()))
    }

    @Test
    fun testValidateEsp32Response_wrongMac_returnsFalse() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        val otherMac = "11:22:33:44:55:66"
        val sig = KioskSecurity.calculateHmac("DISCOVERY:$otherMac:$testIp", testSecret)
        val json = JSONObject().apply {
            put("type", "PISOPHONE_ESP32_RESPONSE")
            put("mac", otherMac)
            put("sig", sig)
        }

        assertFalse(scanner.validateEsp32Response(otherMac, testIp, sig, json.toString()))
    }

    @Test
    fun testValidateEsp32Response_invalidHmac_returnsFalse() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        val badSig = "invalid_signature_12345"
        val json = JSONObject().apply {
            put("type", "PISOPHONE_ESP32_RESPONSE")
            put("mac", testMac)
            put("sig", badSig)
        }

        assertFalse(scanner.validateEsp32Response(testMac, testIp, badSig, json.toString()))
    }

    @Test
    fun testValidateEsp32Response_missingSignatureWhenSecretConfigured_returnsFalse() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        val json = JSONObject().apply {
            put("type", "PISOPHONE_ESP32_RESPONSE")
            put("mac", testMac)
        }

        assertFalse(scanner.validateEsp32Response(testMac, testIp, "", json.toString()))
    }

    @Test
    fun testStaticEsp32IpConstant() {
        org.junit.Assert.assertEquals("192.168.1.10", Esp32DiscoveryScanner.STATIC_ESP32_IP)
    }

    @Test
    fun testIsEsp32MacMatching_matchingMac_returnsTrue() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )
        val json = JSONObject().apply {
            put("mac", testMac)
        }
        assertTrue(scanner.isEsp32MacMatching(json.toString()))
    }

    @Test
    fun testIsEsp32MacMatching_mismatchedMac_returnsFalse() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )
        val json = JSONObject().apply {
            put("mac", "00:11:22:33:44:55")
        }
        assertFalse(scanner.isEsp32MacMatching(json.toString()))
    }

    @Test
    fun testGetEsp32HostAndPort_defaultAndCustom() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )
        val (host1, port1) = scanner.getEsp32HostAndPort("192.168.1.10")
        org.junit.Assert.assertEquals("192.168.1.10", host1)
        org.junit.Assert.assertEquals(80, port1)

        val (host2, port2) = scanner.getEsp32HostAndPort("192.168.1.10:8080")
        org.junit.Assert.assertEquals("192.168.1.10", host2)
        org.junit.Assert.assertEquals(8080, port2)
    }
}
