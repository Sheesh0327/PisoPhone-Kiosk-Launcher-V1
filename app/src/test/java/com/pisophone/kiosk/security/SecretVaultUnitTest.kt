package com.pisophone.kiosk.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stand-in for the Keystore: reversible, and can be told to fail or to lose its key. */
private class FakeCipher : SecretCipher {
    var failEncrypt = false
    var failDecrypt = false

    override fun encrypt(plain: ByteArray): ByteArray? = if (failEncrypt) null else plain.reversedArray() + byteArrayOf(7)

    override fun decrypt(blob: ByteArray): ByteArray? = if (failDecrypt || blob.isEmpty()) null else blob.copyOf(blob.size - 1).reversedArray()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretVaultUnitTest {
    private val secret = "Abcd2345Efgh6789Jkmn"
    private val fake = FakeCipher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.createDeviceProtectedStorageContext().getSharedPreferences("kiosk_security_vault", Context.MODE_PRIVATE).edit().clear().commit()
        KioskSecurity.resetCachesForTests()
        KioskSecurity.secretVault = SecretVault(fake)
    }

    @Test
    fun sealedValueIsNotThePlainSecretAndOpensAgain() {
        val vault = SecretVault(fake)
        val sealed = vault.seal(secret)
        assertTrue(sealed.startsWith(SecretVault.PREFIX))
        assertFalse(sealed.contains(secret))
        val opened = vault.open(sealed)
        assertEquals(secret, opened.value)
        assertFalse(opened.wasPlain)
    }

    @Test
    fun whenTheKeystoreFailsTheSecretIsKeptPlainNotLost() {
        fake.failEncrypt = true
        assertEquals(secret, SecretVault(fake).seal(secret))
    }

    @Test
    fun aWrapForWhichDecryptionFailsIsNotTrusted() {
        fake.failDecrypt = true // wrapping "works" but cannot be read back: keep the plain value
        assertEquals(secret, SecretVault(fake).seal(secret))
    }

    @Test
    fun aLostKeyReadsAsNotSetInsteadOfCrashing() {
        val sealed = SecretVault(fake).seal(secret)
        fake.failDecrypt = true
        assertEquals("", SecretVault(fake).open(sealed).value)
        assertEquals("", SecretVault(fake).open(SecretVault.PREFIX + "!!!not base64").value)
    }

    @Test
    fun provisioningStoresTheWrappedFormAndReadsItBack() {
        assertTrue(KioskSecurity.setBoxSecret(context, secret))
        val raw = context.createDeviceProtectedStorageContext().getSharedPreferences("kiosk_security_vault", Context.MODE_PRIVATE)
            .getString("box_shared_secret", "") ?: ""
        assertTrue("stored value is wrapped: $raw", raw.startsWith(SecretVault.PREFIX))
        KioskSecurity.resetCachesForTests()
        KioskSecurity.secretVault = SecretVault(fake)
        assertEquals(secret, KioskSecurity.getSharedSecret(context))
    }

    @Test
    fun anOldPlainSecretIsUpgradedOnFirstRead() {
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("kiosk_security_vault", Context.MODE_PRIVATE)
        prefs.edit().putString("box_shared_secret", secret).commit()
        assertEquals(secret, KioskSecurity.getSharedSecret(context))
        assertTrue(prefs.getString("box_shared_secret", "")!!.startsWith(SecretVault.PREFIX))
    }

    @Test
    fun anOldPlainSecretSurvivesWhenTheKeystoreIsBroken() {
        fake.failEncrypt = true
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("kiosk_security_vault", Context.MODE_PRIVATE)
        prefs.edit().putString("box_shared_secret", secret).commit()
        assertEquals(secret, KioskSecurity.getSharedSecret(context))
        assertEquals(secret, prefs.getString("box_shared_secret", ""))
    }

    private val vaultPrefs
        get() = context.createDeviceProtectedStorageContext().getSharedPreferences("kiosk_security_vault", Context.MODE_PRIVATE)

    @Test
    fun theAdminPinIsStoredWrappedAndReadsBack() {
        KioskSecurity.setAdminPin(context, " 482916 ")
        val raw = vaultPrefs.getString("admin_access_pin", "") ?: ""
        assertTrue("stored value is wrapped: $raw", raw.startsWith(SecretVault.PREFIX))
        assertFalse(raw.contains("482916"))
        KioskSecurity.resetCachesForTests()
        KioskSecurity.secretVault = SecretVault(fake)
        assertEquals("482916", KioskSecurity.getAdminPin(context))
        assertEquals(KioskSecurity.PinCheck.OK, KioskSecurity.verifyAdminPinThrottled(context, "482916", "local"))
        assertEquals(KioskSecurity.PinCheck.WRONG, KioskSecurity.verifyAdminPinThrottled(context, "000000", "local"))
    }

    @Test
    fun anOldPlainPinIsUpgradedAndStillWorks() {
        vaultPrefs.edit().putString("admin_access_pin", "482916").commit()
        assertEquals("482916", KioskSecurity.getAdminPin(context))
        assertTrue(vaultPrefs.getString("admin_access_pin", "")!!.startsWith(SecretVault.PREFIX))
    }

    @Test
    fun theAdminPinSurvivesABrokenKeystore() {
        fake.failEncrypt = true
        KioskSecurity.setAdminPin(context, "482916")
        assertEquals("482916", vaultPrefs.getString("admin_access_pin", ""))
        KioskSecurity.resetCachesForTests()
        KioskSecurity.secretVault = SecretVault(fake)
        assertEquals("482916", KioskSecurity.getAdminPin(context))
    }

    @Test
    fun aWrappedPinWhoseKeyIsGoneReadsAsNotSetAndIsKept() {
        KioskSecurity.setAdminPin(context, "482916")
        val raw = vaultPrefs.getString("admin_access_pin", "")
        KioskSecurity.resetCachesForTests()
        fake.failDecrypt = true
        KioskSecurity.secretVault = SecretVault(fake)
        assertTrue(KioskSecurity.isAdminPinUnset(context))
        assertEquals("nothing can match, so nobody gets in", KioskSecurity.PinCheck.WRONG, KioskSecurity.verifyAdminPinThrottled(context, "482916", "local"))
        assertEquals("the stored blob is not deleted", raw, vaultPrefs.getString("admin_access_pin", ""))
    }
}
