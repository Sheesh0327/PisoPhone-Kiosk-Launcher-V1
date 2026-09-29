package com.pisophone.kiosk.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KioskCrypto {
    private const val TAG = "KioskCrypto"
    private const val CUSTOM_KEYSTORE_ALIAS = "kiosk_custom_secret_key"
    private const val KEY_CUSTOM_ENCRYPTED_SECRET = "custom_encrypted_device_secret"

    fun calculateHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    fun generateTimestampSignature(deviceId: String, ts: String, secret: String): String {
        return calculateHmac("$deviceId:$ts", secret)
    }

    fun getAesKeySpec(secret: String): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun encrypt(plainText: String, secret: String): String {
        try {
            val keySpec = getAesKeySpec(secret)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return bytesToHex(iv) + bytesToHex(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "AES Encryption error: ${e.message}")
            return ""
        }
    }

    fun decrypt(encryptedHex: String, secret: String): String {
        try {
            val hex = encryptedHex.trim()
            if (hex.length < 32) return ""
            val encryptedBytes = hexToBytes(hex)
            if (encryptedBytes.size < 17) return ""
            val iv = encryptedBytes.copyOfRange(0, 16)
            val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val keySpec = getAesKeySpec(secret)
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(cipherText)
            val result = String(decryptedBytes, Charsets.UTF_8)
            if (result.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' } || result.contains('\uFFFD')) {
                Log.w(TAG, "AES Decrypted text contains non-printable characters or invalid UTF-8 (wrong secret key)")
                return ""
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "AES Decryption error: ${e.message}")
            return ""
        }
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    fun getCustomKeystoreEncryptedSecret(prefs: SharedPreferences): String? {
        val encryptedBase64 = prefs.getString(KEY_CUSTOM_ENCRYPTED_SECRET, null) ?: return null
        return try {
            val parts = encryptedBase64.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val cipherText = Base64.decode(parts[1], Base64.DEFAULT)
            
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = keyStore.getKey(CUSTOM_KEYSTORE_ALIAS, null) as? SecretKey ?: return null
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainTextBytes = cipher.doFinal(cipherText)
            String(plainTextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Custom Keystore decryption failed: ${e.message}")
            null
        }
    }

    fun setCustomKeystoreEncryptedSecret(prefs: SharedPreferences, secret: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(CUSTOM_KEYSTORE_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val keySpec = KeyGenParameterSpec.Builder(
                    CUSTOM_KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(keySpec)
                keyGenerator.generateKey()
            }
            val secretKey = keyStore.getKey(CUSTOM_KEYSTORE_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherTextBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            prefs.edit().putString(KEY_CUSTOM_ENCRYPTED_SECRET, "$ivBase64:$cipherTextBase64").apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Custom Keystore encryption failed: ${e.message}")
            false
        }
    }
}
