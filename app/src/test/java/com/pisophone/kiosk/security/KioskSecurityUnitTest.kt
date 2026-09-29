package com.pisophone.kiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskSecurityUnitTest {

    @org.junit.Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KioskSecurity.clearSharedSecret(context)
    }

    @org.junit.After
    fun tearDown() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        KioskSecurity.clearSharedSecret(context)
    }

    @Test
    fun testAesEncryptionDecryptionRoundTrip() {
        val secret = "test_super_secret_key_12345"
        val plainText = "minutes=30&amount=5.0&tx_id=test-tx-999"

        val encryptedHex = KioskSecurity.encrypt(plainText, secret)
        assertTrue("Encrypted payload should not be empty", encryptedHex.isNotBlank())
        assertTrue("Encrypted payload should be hex string >= 32 chars", encryptedHex.length >= 32)

        val decrypted = KioskSecurity.decrypt(encryptedHex, secret)
        assertEquals("Decrypted text must match original plaintext", plainText, decrypted)
    }

    @Test
    fun testAesDecryptionFailsWithWrongSecret() {
        val secretValid = "valid_master_secret_key_abc"
        val secretWrong = "wrong_attacker_secret_key_xyz"
        val plainText = "action=enable_adb&minutes=10"

        val encryptedHex = KioskSecurity.encrypt(plainText, secretValid)
        assertTrue(encryptedHex.isNotBlank())

        val decryptedWithWrongKey = KioskSecurity.decrypt(encryptedHex, secretWrong)
        assertTrue("Decryption with wrong secret must produce empty string", decryptedWithWrongKey.isBlank())
    }

    @Test
    fun testAesDecryptionFailsWithCorruptedPayload() {
        val secret = "valid_master_secret"
        val corruptedHex = "deadbeef1234"

        val decrypted = KioskSecurity.decrypt(corruptedHex, secret)
        assertEquals("Corrupted hex payload must return empty string", "", decrypted)
    }

    @Test
    fun testHmacGenerationAndVerification() {
        val secret = "shared_crypto_secret_token"
        val deviceId = "TEST-HWID-001"
        val timestamp = "1725789000000"

        val sig1 = KioskSecurity.generateTimestampSignature(deviceId, timestamp, secret)
        val sig2 = KioskSecurity.generateTimestampSignature(deviceId, timestamp, secret)

        assertEquals("HMAC signature must be deterministic", sig1, sig2)
        assertEquals(64, sig1.length) // SHA-256 hex string is 64 characters

        val sigWrongDevice = KioskSecurity.generateTimestampSignature("OTHER-HWID", timestamp, secret)
        assertNotEquals("Signature must change when device ID changes", sig1, sigWrongDevice)

        val sigWrongSecret = KioskSecurity.generateTimestampSignature(deviceId, timestamp, "wrong_secret")
        assertNotEquals("Signature must change when secret changes", sig1, sigWrongSecret)
    }

    @Test
    fun testConstantTimeEquals() {
        assertTrue(KioskSecurity.constantTimeEquals("1234", "1234"))
        assertTrue(KioskSecurity.constantTimeEquals("SUPER_SECRET_TOKEN", "SUPER_SECRET_TOKEN"))
        assertFalse(KioskSecurity.constantTimeEquals("1234", "1235"))
        assertFalse(KioskSecurity.constantTimeEquals("1234", "12345"))
    }

    @Test
    fun testMacAddressFormatting() {
        assertEquals("AA:BB:CC:DD:EE:FF", KioskSecurity.formatMacAddress("aabbccddeeff"))
        assertEquals("AA:BB:CC:DD:EE:FF", KioskSecurity.formatMacAddress("AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", KioskSecurity.formatMacAddress("aa-bb-cc-dd-ee-ff"))
        assertEquals("", KioskSecurity.formatMacAddress(""))
        assertEquals("", KioskSecurity.formatMacAddress(null))
    }

    @Test
    fun testHexadecimal64CharSecretKeyStorageAndLoading() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val hex64Secret = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        KioskSecurity.setSharedSecret(context, hex64Secret)
        val loadedSecret = KioskSecurity.getSharedSecret(context)

        assertEquals("Loaded secret must match saved 64-character hex secret", hex64Secret, loadedSecret)
        assertTrue("Payment should be configured when secret is set", KioskSecurity.isPaymentConfigured(context))
    }

    @Test
    fun testMissingCredentialsReturnsEmptyStringWithoutMasterFallback() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        
        // Clear prefs
        val prefs = KioskSecurity.getDirectBootPrefs(context)
        prefs.edit().clear().apply()

        val loadedSecret = KioskSecurity.getSharedSecret(context)
        assertEquals("Missing credentials must return empty string (no fallback to universal master key)", "", loadedSecret)
        assertFalse("Payment must not be configured when credentials are missing", KioskSecurity.isPaymentConfigured(context))
        assertFalse("Kiosk must not be provisioned without secret and MAC", KioskSecurity.isProvisioned(context))
    }

    @Test
    fun testCustomKeystoreFallbackStorageGraceful() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val testSecret = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b"
        val prefs = KioskSecurity.getDirectBootPrefs(context)

        val setSuccess = KioskCrypto.setCustomKeystoreEncryptedSecret(prefs, testSecret)
        if (setSuccess) {
            val decrypted = KioskCrypto.getCustomKeystoreEncryptedSecret(prefs)
            assertEquals("Custom Keystore decryption must recover the exact secret", testSecret, decrypted)
        } else {
            val decrypted = KioskCrypto.getCustomKeystoreEncryptedSecret(prefs)
            org.junit.Assert.assertNull(decrypted)
        }
    }

    @Test
    fun testOldUniversalSecretFailsAgainstPerBoxKey() {
        val perBoxSecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
        val legacyUniversalKey = "PISOPHONE_HMAC_MASTER_KEY"
        val payload = "COIN_EVENT:seconds=1800&amount=5.0&tx_id=TEST-PER-BOX-001"

        val encryptedWithBoxKey = KioskSecurity.encrypt(payload, perBoxSecret)
        assertTrue(encryptedWithBoxKey.isNotBlank())

        val attemptWithLegacyMaster = KioskSecurity.decrypt(encryptedWithBoxKey, legacyUniversalKey)
        assertTrue("Legacy master key must NOT decrypt data encrypted with per-box key", attemptWithLegacyMaster.isBlank())

        val sigPerBox = KioskSecurity.calculateHmac(payload, perBoxSecret)
        val sigLegacyMaster = KioskSecurity.calculateHmac(payload, legacyUniversalKey)
        assertNotEquals("HMAC generated with per-box key must not match legacy master key HMAC", sigPerBox, sigLegacyMaster)
    }

    @Test
    fun testDirectProvisioningFlowWithPerBoxCredentials() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val boxSecret = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val boxMac = "34:85:18:90:AB:CD"

        val provisioned = KioskSecurity.applyDirectProvisioning(
            context = context,
            secret = boxSecret,
            mac = boxMac,
            slot = 2,
            name = "PisoPhone Unit 2"
        )

        assertTrue(provisioned)
        assertEquals(boxSecret, KioskSecurity.getSharedSecret(context))
        assertEquals(boxMac, KioskSecurity.getConfiguredEsp32Mac(context))
        assertEquals(2, KioskSecurity.getAssignedBoxSlot(context))
        assertTrue(KioskSecurity.isProvisioned(context))
    }
}
