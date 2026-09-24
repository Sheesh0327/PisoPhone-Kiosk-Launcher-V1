package com.pisophone.kiosk.security

import android.content.Context
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
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles cryptographic operations, AES encryption/decryption, Keystore secret handling,
 * and constant-time string comparisons for the Kiosk application.
 */
object KioskCryptoManager {
    private const val TAG = "KioskCryptoManager"
    private const val CUSTOM_KEYSTORE_ALIAS = "kiosk_custom_secret_key"
    private const val KEY_CUSTOM_ENCRYPTED_SECRET = "custom_encrypted_device_secret"
    private const val KEY_DEVICE_SECRET = "device_crypto_secret"
    private const val KEY_SECRET_EXPLICITLY_PROVISIONED = "kiosk_secret_explicitly_provisioned"
    
    const val DEFAULT_SHARED_SECRET = "PISOPHONE_HMAC_MASTER_KEY"

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

    fun getSharedSecret(context: Context): String {
        val prefs = KioskConfigStore.getPrefs(context)
        if (!prefs.getBoolean(KEY_SECRET_EXPLICITLY_PROVISIONED, false)) {
            return DEFAULT_SHARED_SECRET
        }

        val encryptedPrefs = KioskConfigStore.getEncryptedPrefs(context)
        if (encryptedPrefs != null) {
            try { 
                val existingSecret = encryptedPrefs.getString(KEY_DEVICE_SECRET, null)
                if (!existingSecret.isNullOrBlank()) return existingSecret
            } catch (e: Exception) { Log.e(TAG, "Encrypted prefs read failed: ${e.message}") }
        }
        
        var secret = getCustomKeystoreEncryptedSecret(prefs)
        if (secret.isNullOrBlank()) {
            secret = null
        }
        
        if (secret == null && prefs.contains(KEY_DEVICE_SECRET)) {
            val oldPlainSecret = prefs.getString(KEY_DEVICE_SECRET, null)
            if (!oldPlainSecret.isNullOrBlank()) {
                val keystoreSuccess = setCustomKeystoreEncryptedSecret(prefs, oldPlainSecret)
                if (keystoreSuccess) {
                    prefs.edit().remove(KEY_DEVICE_SECRET).apply()
                }
                secret = oldPlainSecret
            }
        }
        
        if (secret == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            secret = randomBytes.joinToString("") { "%02x".format(it) }
            
            if (encryptedPrefs != null) {
                try {
                    encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
                    return secret
                } catch (e: Exception) { Log.e(TAG, "Encrypted prefs write failed: ${e.message}") }
            }
            setCustomKeystoreEncryptedSecret(prefs, secret)
        }
        return secret
    }

    fun setSharedSecret(context: Context, newSecret: String) {
        val trimmed = newSecret.trim()
        if (trimmed.isEmpty()) {
            Log.e(TAG, "Attempted to set an empty or blank shared secret. Rejected for security!")
            return
        }
        val encryptedPrefs = KioskConfigStore.getEncryptedPrefs(context)
        var successWithEncryptedPrefs = false
        if (encryptedPrefs != null) {
            try { 
                encryptedPrefs.edit().putString(KEY_DEVICE_SECRET, trimmed).apply()
                successWithEncryptedPrefs = true
            } catch (e: Exception) { Log.e(TAG, "Encrypted prefs write failed: ${e.message}") }
        } 
        
        val prefs = KioskConfigStore.getPrefs(context)
        prefs.edit().putBoolean(KEY_SECRET_EXPLICITLY_PROVISIONED, true).apply()
        if (!successWithEncryptedPrefs) {
            val keystoreSuccess = setCustomKeystoreEncryptedSecret(prefs, trimmed)
            if (!keystoreSuccess) {
                prefs.edit().putString(KEY_DEVICE_SECRET, trimmed).apply()
            } else {
                prefs.edit().remove(KEY_DEVICE_SECRET).apply()
            }
        } else {
            prefs.edit().remove(KEY_DEVICE_SECRET).apply()
        }
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
            return String(decryptedBytes, Charsets.UTF_8)
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
}
