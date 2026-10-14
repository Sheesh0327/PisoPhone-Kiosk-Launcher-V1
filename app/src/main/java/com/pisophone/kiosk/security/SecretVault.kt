package com.pisophone.kiosk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts small byte arrays; returns null instead of throwing when it cannot. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray?
    fun decrypt(blob: ByteArray): ByteArray?
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore. The key needs no screen unlock, so it also works
 * in Direct Boot (the kiosk starts before anyone unlocks the phone). Output is a 12-byte IV followed by the ciphertext.
 */
class KeystoreCipher(private val alias: String = "pisophone_box_secret_v1") : SecretCipher {
    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    } catch (_: Exception) {
        null
    }

    override fun encrypt(plain: ByteArray): ByteArray? = try {
        val k = key() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        c.iv + c.doFinal(plain)
    } catch (_: Exception) {
        null
    }

    override fun decrypt(blob: ByteArray): ByteArray? = try {
        if (blob.size <= IV_BYTES) {
            null
        } else {
            val k = key() ?: return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, IV_BYTES)) }
            c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val IV_BYTES = 12
    }
}

/**
 * Stores a secret as "ks1:" + base64 when the Keystore works, and as the plain text it was before when it does not,
 * so a Keystore problem can never make the phone lose its box secret.
 *  - [seal] only returns the wrapped form after decrypting it again and getting the same text back.
 *  - [open] reads either form; a plain value is reported so the caller can upgrade it.
 */
class SecretVault(private val cipher: SecretCipher) {
    class Opened(val value: String, val wasPlain: Boolean)

    fun seal(plain: String): String {
        val blob = cipher.encrypt(plain.toByteArray(Charsets.UTF_8)) ?: return plain
        val back = cipher.decrypt(blob)?.toString(Charsets.UTF_8)
        return if (back == plain) PREFIX + Base64.getEncoder().encodeToString(blob) else plain
    }

    /** value is "" when a wrapped secret cannot be decrypted (key lost); the caller then treats it as not set. */
    fun open(stored: String): Opened {
        if (!stored.startsWith(PREFIX)) return Opened(stored, wasPlain = true)
        val blob = try {
            Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            return Opened("", wasPlain = false)
        }
        return Opened(cipher.decrypt(blob)?.toString(Charsets.UTF_8) ?: "", wasPlain = false)
    }

    companion object {
        const val PREFIX = "ks1:"
    }
}
