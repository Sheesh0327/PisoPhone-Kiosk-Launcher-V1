@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import javax.crypto.spec.SecretKeySpec

/**
 * Core security coordinator, configuration facade, and cryptographic authentication manager.
 * Specific responsibilities are modularized in:
 * - [KioskConfigStore] (Preferences, thresholds, assigned slot, network configs)
 * - [KioskCryptoManager] (AES, Keystore, Shared secrets, constant-time validation)
 * - [KioskBatteryDiagnostics] (Battery telemetry and volume settings)
 * - [KioskRecoveryManager] (Emergency unlock, recovery challenge, ADB rescue)
 * - [KioskPolicyManager] (Device admin, lock task, status bar policies)
 * - [KioskDataCleaner] (App cache and user data sanitization)
 * - [KioskActivationManager] (Hardware fingerprint and slot activation)
 */
object KioskSecurity {
    private const val TAG = "KioskSecurity"
    
    const val DEFAULT_ESP32_IP: String = KioskConfigStore.DEFAULT_ESP32_IP
    const val DEFAULT_SHARED_SECRET: String = KioskCryptoManager.DEFAULT_SHARED_SECRET
    const val DEFAULT_PIN: String = KioskConfigStore.DEFAULT_PIN

    fun getEncryptedPrefs(context: Context): SharedPreferences? =
        KioskConfigStore.getEncryptedPrefs(context)

    fun getEncryptedPreferences(context: Context): SharedPreferences =
        KioskConfigStore.getEncryptedPreferences(context)

    fun getDirectBootPrefs(context: Context, name: String = "kiosk_security_vault"): SharedPreferences =
        KioskConfigStore.getDirectBootPrefs(context, name)

    fun isBatteryAlertsEnabled(context: Context): Boolean =
        KioskConfigStore.isBatteryAlertsEnabled(context)

    fun setBatteryAlertsEnabled(context: Context, enabled: Boolean) =
        KioskConfigStore.setBatteryAlertsEnabled(context, enabled)

    fun getLowBatteryThreshold(context: Context): Int =
        KioskConfigStore.getLowBatteryThreshold(context)

    fun setLowBatteryThreshold(context: Context, threshold: Int) =
        KioskConfigStore.setLowBatteryThreshold(context, threshold)

    fun getHighBatteryThreshold(context: Context): Int =
        KioskConfigStore.getHighBatteryThreshold(context)

    fun setHighBatteryThreshold(context: Context, threshold: Int) =
        KioskConfigStore.setHighBatteryThreshold(context, threshold)

    fun getConfiguredEsp32Mac(context: Context): String =
        KioskConfigStore.getConfiguredEsp32Mac(context)

    fun setConfiguredEsp32Mac(context: Context, mac: String) =
        KioskConfigStore.setConfiguredEsp32Mac(context, mac)

    fun clearPinnedEsp32Mac(context: Context) =
        KioskConfigStore.clearPinnedEsp32Mac(context)

    fun getConfiguredEsp32Ip(context: Context): String =
        KioskConfigStore.getConfiguredEsp32Ip(context)

    fun setConfiguredEsp32Ip(context: Context, ip: String): Boolean =
        KioskConfigStore.setConfiguredEsp32Ip(context, ip)

    fun isValidIpv4(ip: String): Boolean =
        KioskConfigStore.isValidIpv4(ip)

    fun getAssignedBoxSlot(context: Context): Int =
        KioskConfigStore.getAssignedBoxSlot(context)

    fun setAssignedBoxSlot(context: Context, slot: Int) =
        KioskConfigStore.setAssignedBoxSlot(context, slot)

    fun getApkUpdateUrl(context: Context): String =
        KioskConfigStore.getApkUpdateUrl(context)

    fun setApkUpdateUrl(context: Context, url: String) =
        KioskConfigStore.setApkUpdateUrl(context, url)

    fun formatMacAddress(input: String?): String =
        KioskConfigStore.formatMacAddress(input)

    fun isProvisioned(context: Context): Boolean =
        KioskConfigStore.isProvisioned(context)

    fun isAdbAllowed(context: Context): Boolean =
        KioskConfigStore.isAdbAllowed(context)

    fun setAdbAllowed(context: Context, allowed: Boolean) =
        KioskConfigStore.setAdbAllowed(context, allowed)

    fun clearAppCacheAndData(context: Context): Boolean =
        KioskDataCleaner.clearAppCacheAndData(context)

    fun getHiddenApps(context: Context): Set<String> =
        KioskConfigStore.getHiddenApps(context)

    fun setHiddenApps(context: Context, hiddenApps: Set<String>) =
        KioskConfigStore.setHiddenApps(context, hiddenApps)

    fun isAppHidden(context: Context, packageName: String): Boolean =
        KioskConfigStore.isAppHidden(context, packageName)

    fun toggleAppHidden(context: Context, packageName: String): Boolean =
        KioskConfigStore.toggleAppHidden(context, packageName)

    fun getDeviceAlias(context: Context): String =
        KioskConfigStore.getDeviceAlias(context)

    fun getHardwareId(context: Context): String =
        KioskConfigStore.getHardwareId(context)

    fun setDeviceAlias(context: Context, alias: String) =
        KioskConfigStore.setDeviceAlias(context, alias)

    fun getSharedSecret(context: Context): String =
        KioskCryptoManager.getSharedSecret(context)

    fun setSharedSecret(context: Context, newSecret: String) =
        KioskCryptoManager.setSharedSecret(context, newSecret)

    fun getAdminPin(context: Context): String =
        KioskConfigStore.getAdminPin(context)

    fun setAdminPin(context: Context, newPin: String) =
        KioskConfigStore.setAdminPin(context, newPin)

    fun verifyAdminPin(context: Context, enteredPin: String): Boolean {
        val storedPin = getAdminPin(context)
        return constantTimeEquals(enteredPin.trim(), storedPin)
    }

    fun calculateHmac(data: String, key: String): String =
        com.pisophone.kiosk.protocol.KioskProtocol.calculateHmac(data, key)

    fun calculateHttpReqSignature(
        method: String,
        endpoint: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        secret: String
    ): String = com.pisophone.kiosk.protocol.KioskProtocol.calculateHttpReqSignature(
        method, endpoint, recipient, txId, ts, payload, secret
    )

    fun verifyHttpReqSignature(
        method: String,
        endpoint: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        sig: String,
        secret: String
    ): Boolean = com.pisophone.kiosk.protocol.KioskProtocol.verifyHttpReqSignature(
        method, endpoint, recipient, txId, ts, payload, sig, secret
    )

    fun calculateWsPaySignature(
        event: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        secret: String
    ): String = com.pisophone.kiosk.protocol.KioskProtocol.calculateWsPaySignature(
        event, recipient, txId, ts, payload, secret
    )

    fun verifyWsPaySignature(
        event: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        sig: String,
        secret: String
    ): Boolean = com.pisophone.kiosk.protocol.KioskProtocol.verifyWsPaySignature(
        event, recipient, txId, ts, payload, sig, secret
    )

    fun calculateAckSignature(
        deviceId: String,
        txId: String,
        amount: Int,
        seconds: Int,
        ts: String,
        status: String,
        secret: String
    ): String = com.pisophone.kiosk.protocol.KioskProtocol.calculateAckSignature(
        deviceId, txId, amount, seconds, ts, status, secret
    )

    fun verifyAckSignature(
        deviceId: String,
        txId: String,
        amount: Int,
        seconds: Int,
        ts: String,
        status: String,
        sig: String,
        secret: String
    ): Boolean = com.pisophone.kiosk.protocol.KioskProtocol.verifyAckSignature(
        deviceId, txId, amount, seconds, ts, status, sig, secret
    )

    fun generateTimestampSignature(deviceId: String, ts: String, secret: String): String =
        calculateHmac("$deviceId:$ts", secret)

    fun getAesKeySpec(secret: String): SecretKeySpec =
        KioskCryptoManager.getAesKeySpec(secret)

    fun bytesToHex(bytes: ByteArray): String =
        KioskCryptoManager.bytesToHex(bytes)

    fun hexToBytes(hex: String): ByteArray =
        KioskCryptoManager.hexToBytes(hex)

    fun encrypt(plainText: String, secret: String): String =
        KioskCryptoManager.encrypt(plainText, secret)

    fun decrypt(encryptedHex: String, secret: String): String =
        KioskCryptoManager.decrypt(encryptedHex, secret)

    fun constantTimeEquals(a: String, b: String): Boolean =
        KioskCryptoManager.constantTimeEquals(a, b)

    fun getBatteryDiagnostics(context: Context): BatteryInfo =
        KioskBatteryDiagnostics.getBatteryDiagnostics(context)

    fun setMediaVolume(context: Context, volumePercent: Int) =
        KioskBatteryDiagnostics.setMediaVolume(context, volumePercent)

    // --- Delegated Policy & Display Methods ---

    fun getAllowedLockTaskPackages(context: Context): Array<String> =
        KioskPolicyManager.getAllowedLockTaskPackages(context)

    fun autoGrantAllPermissions(context: Context) =
        KioskPolicyManager.autoGrantAllPermissions(context)

    fun applyStrictKioskPolicies(context: Context) =
        KioskPolicyManager.applyStrictKioskPolicies(context)

    fun wakeScreenUp(context: Context) =
        KioskPolicyManager.wakeScreenUp(context)

    fun dismissKeyguard(context: Context) =
        KioskPolicyManager.dismissKeyguard(context)

    fun turnScreenOff(context: Context): Boolean =
        KioskPolicyManager.turnScreenOff(context)

    fun setStatusBarDisabled(context: Context, disabled: Boolean): Boolean =
        KioskPolicyManager.setStatusBarDisabled(context, disabled)

    fun collapseStatusBar(context: Context) =
        KioskPolicyManager.collapseStatusBar(context)

    // --- Delegated Recovery Methods ---

    fun isUsbDebuggingEnabled(context: Context): Boolean =
        KioskRecoveryManager.isUsbDebuggingEnabled(context)

    fun emergencyEnableUsbDebugging(context: Context): Boolean =
        KioskRecoveryManager.emergencyEnableUsbDebugging(context)

    fun emergencyExitKiosk(context: Context) =
        KioskRecoveryManager.emergencyExitKiosk(context)

    fun emergencyClearDeviceOwner(context: Context): Boolean =
        KioskRecoveryManager.emergencyClearDeviceOwner(context)

    fun factoryResetDevice(context: Context): Boolean =
        KioskRecoveryManager.factoryResetDevice(context)

    // --- Direct Provisioning & WebADB Setup ---

    fun applyDirectProvisioning(
        context: Context,
        secret: String? = null,
        mac: String? = null,
        slot: Int = -1,
        name: String? = null
    ): Boolean {
        if (!secret.isNullOrBlank()) {
            setSharedSecret(context, secret.trim())
        } else if (secret != null) {
            Log.e(TAG, "[-] Rejected direct provisioning with empty or blank secret key for security!")
        }
        if (!mac.isNullOrBlank()) {
            val formattedMac = formatMacAddress(mac.trim())
            if (formattedMac.isNotBlank()) {
                setConfiguredEsp32Mac(context, formattedMac)
            }
        }
        if (slot > 0) {
            setAssignedBoxSlot(context, slot)
            setDeviceAlias(context, "PisoPhone $slot")
        } else if (!name.isNullOrBlank()) {
            setDeviceAlias(context, name.trim())
        }
        Log.i(TAG, "[+] Successfully applied Direct Provisioning setup: MAC=$mac, Slot=$slot, SecretConfigured=${!secret.isNullOrBlank()}")
        return true
    }
}
