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

        val cleanMac = "AABBCCDDEEFF"
        val sig = KioskSecurity.calculateHmac("DISCOVERY:$cleanMac:$testIp", testSecret)
        val json = JSONObject().apply {
            put("type", "PISOPHONE_ESP32_RESPONSE")
            put("mac", testMac)
            put("sig", sig)
        }

        assertTrue(scanner.validateEsp32Response(testMac, testIp, sig, json.toString()))
    }

    @Test
    fun testValidateEsp32Response_sharedTestVector_matchingKey_returnsTrue_wrongKey_returnsFalse() {
        // Shared Test Vector Specs:
        // Raw MAC: "AA:BB:CC:DD:EE:FF" -> Clean MAC without colons: "AABBCCDDEEFF"
        // ESP32 IP: "192.168.1.150"
        // Payload string: "DISCOVERY:AABBCCDDEEFF:192.168.1.150"
        // Shared Secret: "secretKey12345"
        val scanner = Esp32DiscoveryScanner(
            context = context,
            scope = testScope,
            delegate = fakeDelegate,
            isAlreadyBound = { false }
        )

        val cleanMac = "AABBCCDDEEFF"
        val payload = "DISCOVERY:$cleanMac:$testIp"
        val expectedMatchingSig = KioskSecurity.calculateHmac(payload, testSecret)
        val wrongKeySig = KioskSecurity.calculateHmac(payload, "WRONG_SECRET_KEY_9999")

        // 1. Proves matching key produces valid HMAC acceptance
        assertTrue("Matching key HMAC must produce identical valid signature and be accepted",
            scanner.validateEsp32Response("AA:BB:CC:DD:EE:FF", testIp, expectedMatchingSig, null))

        // 2. Proves wrong key produces invalid HMAC rejection
        assertFalse("Wrong key HMAC signature must be rejected",
            scanner.validateEsp32Response("AA:BB:CC:DD:EE:FF", testIp, wrongKeySig, null))
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
        val cleanOtherMac = "112233445566"
        val sig = KioskSecurity.calculateHmac("DISCOVERY:$cleanOtherMac:$testIp", testSecret)
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
}
