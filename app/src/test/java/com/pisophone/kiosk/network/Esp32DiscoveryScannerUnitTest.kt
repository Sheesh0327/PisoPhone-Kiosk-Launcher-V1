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
    private val testIp = "192.168.1.150"

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
    fun testValidateEsp32Response_identifyPayloadWithHmac_returnsTrue() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        // Matches ESP32 firmware /identify response contract
        val sig = KioskSecurity.calculateHmac("DISCOVERY:$testMac:$testIp", testSecret)
        val json = JSONObject().apply {
            put("device", "HARDWARE_kiosk")
            put("mac", testMac)
            put("ip", testIp)
            put("sig", sig)
            put("version", "3.0")
            put("minutes", 30)
            put("price", 1.0)
        }

        assertTrue(scanner.validateEsp32Response(testMac, testIp, sig, json.toString()))
    }

    @Test
    fun testValidateEsp32Response_identifyPayloadWithInvalidHmac_returnsFalse() {
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        val wrongSig = "wrong_signature_bytes"
        val json = JSONObject().apply {
            put("device", "HARDWARE_kiosk")
            put("mac", testMac)
            put("ip", testIp)
            put("sig", wrongSig)
            put("version", "3.0")
        }

        assertFalse(scanner.validateEsp32Response(testMac, testIp, wrongSig, json.toString()))
    }
}
