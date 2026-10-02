package com.pisophone.kiosk.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskSecurityUnitTest {
    @Before
    fun cleanState() {
        KioskSecurity.resetCachesForTests()
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
    fun testCoinslotRequestSignatureMatchesFirmwareFormat() {
        // Expected values computed independently with Python hmac/sha256 over the firmware's
        // "v1:<action>:<device>:<ts>[:<tx>]" payload.
        assertEquals(
            "e08e6c51e0d791208efe56de6838e81125fe3bbd52783e278949c35019716006",
            KioskSecurity.signCoinslotRequest("arm", "dev1", "1700000000000", "", "secret123"),
        )
        assertEquals(
            "5134caa51edc4292a5b7be46ff376dbc7acb2426168cb8e5c772827ff2f8f38c",
            KioskSecurity.signCoinslotRequest("ack", "dev1", "1700000000000", "tx-9", "secret123"),
        )
        // An arm signature is not valid for unarm or for a different device.
        assertNotEquals(
            KioskSecurity.signCoinslotRequest("arm", "dev1", "1700000000000", "", "secret123"),
            KioskSecurity.signCoinslotRequest("unarm", "dev1", "1700000000000", "", "secret123"),
        )
    }

    @Test
    fun phoneUsesTheLegacyKeyUntilItIsGivenABoxSecret() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        assertTrue(KioskSecurity.usesLegacySharedSecret(context))
        assertEquals(KioskSecurity.DEFAULT_SHARED_SECRET, KioskSecurity.getSharedSecret(context))

        assertTrue(KioskSecurity.setBoxSecret(context, "Abcd2345Efgh6789Jkmn"))
        assertFalse(KioskSecurity.usesLegacySharedSecret(context))
        assertEquals("Abcd2345Efgh6789Jkmn", KioskSecurity.getSharedSecret(context))
    }

    @Test
    fun boxSecretMustBeLongEnoughAndNeverTheSharedKey() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        assertFalse(KioskSecurity.setBoxSecret(context, "short"))
        assertFalse(KioskSecurity.setBoxSecret(context, "has a space in it 12345"))
        assertFalse(KioskSecurity.setBoxSecret(context, KioskSecurity.DEFAULT_SHARED_SECRET))
        assertTrue(KioskSecurity.usesLegacySharedSecret(context))
    }

    @Test
    fun provisioningStoresTheSecretAndIgnoresAnInvalidOne() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        KioskSecurity.applyDirectProvisioning(context, secret = "bad", mac = "AA:BB:CC:DD:EE:FF")
        assertTrue(KioskSecurity.usesLegacySharedSecret(context))
        KioskSecurity.applyDirectProvisioning(context, secret = "Abcd2345Efgh6789Jkmn", mac = "AA:BB:CC:DD:EE:FF")
        assertEquals("Abcd2345Efgh6789Jkmn", KioskSecurity.getSharedSecret(context))
    }
}
