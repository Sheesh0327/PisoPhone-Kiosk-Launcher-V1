@file:Suppress("DEPRECATION")
package com.pisophone.kiosk.receiver

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.MainActivity

/**
 * BroadcastReceiver listening for remote administrative commands via ADB / WebADB.
 *
 * Commands:
 * 1. Clean Deprovision & Remove Device Owner:
 *    adb shell am broadcast -a com.pisophone.kiosk.DEPROVISION
 *
 * 2. Restart Kiosk Service & Launcher:
 *    adb shell am broadcast -a com.pisophone.kiosk.RESTART
 */
class KioskAdminActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DEPROVISION = "com.pisophone.kiosk.DEPROVISION"
        const val ACTION_ENABLE_ADB = "com.pisophone.kiosk.ENABLE_ADB"
        const val ACTION_EMERGENCY_RECOVERY = "com.pisophone.kiosk.EMERGENCY_RECOVERY"
        const val ACTION_EXIT_KIOSK = "com.pisophone.kiosk.EXIT_KIOSK"
        const val ACTION_OPEN_SETTINGS = "com.pisophone.kiosk.OPEN_SETTINGS"
        const val ACTION_STATUS_REFRESH = "com.pisophone.kiosk.STATUS_REFRESH"
        const val ACTION_RESTART = "com.pisophone.kiosk.RESTART"
        const val ACTION_REBOOT = "com.pisophone.kiosk.REBOOT"
        const val ACTION_FACTORY_RESET = "com.pisophone.kiosk.FACTORY_RESET"
        const val ACTION_SCREEN_OFF = "com.pisophone.kiosk.SCREEN_OFF"
        const val ACTION_SCREEN_ON = "com.pisophone.kiosk.SCREEN_ON"
        const val ACTION_LOCK_NOW = "com.pisophone.kiosk.LOCK_NOW"
        const val ACTION_GRANT_PERMISSIONS = "com.pisophone.kiosk.GRANT_PERMISSIONS"
        const val ACTION_SET_VOLUME = "com.pisophone.kiosk.SET_VOLUME"
        const val ACTION_ADMIN_BYPASS = "com.pisophone.kiosk.ADMIN_BYPASS"
        const val ACTION_TEST_TTS = "com.pisophone.kiosk.TEST_TTS"
        const val ACTION_ACTIVATE = "com.pisophone.kiosk.ACTIVATE"
        const val ACTION_CONFIGURE_ESP32 = "com.pisophone.kiosk.CONFIGURE_ESP32"
        const val ACTION_GET_DEVICE_ID = "com.pisophone.kiosk.GET_DEVICE_ID"
        private const val TAG = "KioskAdminAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received admin action: $action")

        when (action) {
            ACTION_ENABLE_ADB, "com.pisophone.kiosk.ACTION_ENABLE_ADB" -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger ENABLE_ADB rejected.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN or Secret required.", Toast.LENGTH_SHORT).show()
                    setResultCode(android.app.Activity.RESULT_CANCELED)
                    return
                }
                Log.i(TAG, "Emergency Enable ADB broadcast received.")
                val success = com.pisophone.kiosk.security.KioskSecurity.emergencyEnableUsbDebugging(context)
                Toast.makeText(context, "⚡ Emergency: USB Debugging Re-Enabled!", Toast.LENGTH_LONG).show()
                setResultCode(if (success) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED)
            }

            ACTION_EMERGENCY_RECOVERY, "com.pisophone.kiosk.ACTION_EMERGENCY_RECOVERY" -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger EMERGENCY_RECOVERY rejected.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN or Secret required.", Toast.LENGTH_SHORT).show()
                    setResultCode(android.app.Activity.RESULT_CANCELED)
                    return
                }
                Log.i(TAG, "Emergency Full Recovery broadcast received.")
                com.pisophone.kiosk.security.KioskSecurity.emergencyEnableUsbDebugging(context)
                com.pisophone.kiosk.security.KioskSecurity.emergencyExitKiosk(context)
                Toast.makeText(context, "⚠️ Emergency Recovery: Kiosk Exited & ADB Enabled!", Toast.LENGTH_LONG).show()
                setResultCode(android.app.Activity.RESULT_OK)
            }

            ACTION_EXIT_KIOSK, "com.pisophone.kiosk.ACTION_EXIT_KIOSK" -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger EXIT_KIOSK rejected.")
                    return
                }
                Log.i(TAG, "Exit Kiosk command received.")
                com.pisophone.kiosk.security.KioskSecurity.emergencyExitKiosk(context)
            }

            ACTION_OPEN_SETTINGS, "com.pisophone.kiosk.ACTION_OPEN_SETTINGS" -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger OPEN_SETTINGS rejected.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN or Secret required.", Toast.LENGTH_SHORT).show()
                    return
                }
                try {
                    val sIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(sIntent)
                } catch (_: Exception) {}
            }

            ACTION_ADMIN_BYPASS -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger ADMIN_BYPASS rejected. Valid PIN or secret required.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN required.", Toast.LENGTH_SHORT).show()
                    return
                }
                if (!com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)) {
                    Log.w(TAG, "ADMIN_BYPASS rejected: Device is not provisioned or is hardware locked.")
                    Toast.makeText(context, "Bypass rejected: Hardware provisioning required.", Toast.LENGTH_SHORT).show()
                    return
                }
                val duration = intent.getIntExtra("duration", 900)
                Log.i(TAG, "Admin bypass command authenticated & received for $duration seconds.")
                KioskService.triggerAdminBypass(context, duration)
            }

            ACTION_TEST_TTS -> {
                val text = intent.getStringExtra("text") ?: "Arcade OS voice system online and functional."
                Log.i(TAG, "Test TTS command received: $text")
                KioskService.triggerTestTts(context, text)
            }
            ACTION_RESTART -> {
                Log.i(TAG, "Admin restart triggered via broadcast.")
                try {
                    val serviceIntent = Intent(context, KioskService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(mainIntent)
                    Toast.makeText(context, "ArcadeOS Kiosk restarted.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart Kiosk: ${e.message}", e)
                }
            }

            ACTION_REBOOT -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger REBOOT rejected.")
                    return
                }
                Log.i(TAG, "Device reboot command received.")
                val success = com.pisophone.kiosk.security.KioskSecurity.rebootDevice(context)
                if (!success) {
                    Toast.makeText(context, "Device not Activated. Contact admin for support.", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_FACTORY_RESET -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger FACTORY_RESET rejected.")
                    return
                }
                Log.w(TAG, "Factory Reset command received and authorized! Wiping device...")
                com.pisophone.kiosk.security.KioskSecurity.factoryResetDevice(context)
            }

            ACTION_SCREEN_OFF, ACTION_LOCK_NOW -> {
                Log.i(TAG, "Screen off / lock now command received.")
                com.pisophone.kiosk.security.KioskSecurity.turnScreenOff(context)
            }

            ACTION_SCREEN_ON -> {
                Log.i(TAG, "Screen on / wake command received.")
                com.pisophone.kiosk.security.KioskSecurity.wakeScreenUp(context)
            }

            ACTION_GRANT_PERMISSIONS -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger GRANT_PERMISSIONS rejected.")
                    return
                }
                Log.i(TAG, "Auto-grant permissions command received.")
                com.pisophone.kiosk.security.KioskSecurity.autoGrantAllPermissions(context)
                Toast.makeText(context, "Permissions granted to kiosk apps.", Toast.LENGTH_SHORT).show()
            }

            ACTION_SET_VOLUME -> {
                val volume = intent.getIntExtra("volume", -1)
                if (volume in 0..100) {
                    Log.i(TAG, "Setting media volume to $volume%")
                    com.pisophone.kiosk.security.KioskSecurity.setMediaVolume(context, volume)
                }
            }

            ACTION_GET_DEVICE_ID -> {
                val hwId = com.pisophone.kiosk.security.HardwareLockManager.getHardwareFingerprint(context)
                val devName = com.pisophone.kiosk.security.HardwareLockManager.getHardwareDescription()
                val isAuthorized = com.pisophone.kiosk.security.HardwareLockManager.isHardwareAuthorized(context)
                Log.i(TAG, "GET_DEVICE_ID requested via ADB broadcast. Returning: $hwId ($devName), hardwareSealed=$isAuthorized")
                setResultCode(android.app.Activity.RESULT_OK)
                setResultData(hwId)
                val extras = android.os.Bundle().apply {
                    putString("hardware_id", hwId)
                    putString("device_name", devName)
                    putBoolean("hardware_sealed", isAuthorized)
                }
                setResultExtras(extras)
            }

            ACTION_CONFIGURE_ESP32, "com.pisophone.kiosk.ACTION_CONFIGURE_ESP32" -> {
                val isProvisioned = com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context) || com.pisophone.kiosk.security.KioskSecurity.getSharedSecret(context).isNotBlank()
                if (isProvisioned && !isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to re-configure ESP32 on provisioned device rejected.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN or Secret required.", Toast.LENGTH_SHORT).show()
                    setResultCode(android.app.Activity.RESULT_CANCELED)
                    return
                }

                val secret = intent.getStringExtra("secret") ?: intent.getStringExtra("setup_secret") ?: intent.getStringExtra("shared_secret")
                val mac = intent.getStringExtra("esp32_mac") ?: intent.getStringExtra("mac") ?: intent.getStringExtra("box_mac")
                val ip = intent.getStringExtra("esp32_ip") ?: intent.getStringExtra("ip")
                val slot = intent.getIntExtra("slot", intent.getIntExtra("setup_slot", -1))
                val name = intent.getStringExtra("name") ?: intent.getStringExtra("alias")

                Log.i(TAG, "CONFIGURE_ESP32 received. Infusing Master MAC: '$mac', IP: '$ip', Slot: $slot, SecretSet=${!secret.isNullOrBlank()}")

                if (!mac.isNullOrBlank() || !ip.isNullOrBlank() || slot > 0 || !secret.isNullOrBlank()) {
                    KioskService.configureMasterBox(context, mac ?: "", ip, slot, secret, name)
                }

                val savedMac = com.pisophone.kiosk.security.KioskSecurity.getConfiguredEsp32Mac(context)
                Toast.makeText(context, "Hardware Box Paired! MAC: ${savedMac.ifEmpty { "Auto" }}", Toast.LENGTH_SHORT).show()
                setResultCode(android.app.Activity.RESULT_OK)
                setResultData("SUCCESS")
            }

            ACTION_ACTIVATE -> {
                val isProvisioned = com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context) || com.pisophone.kiosk.security.KioskSecurity.getSharedSecret(context).isNotBlank()
                if (isProvisioned && !isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to re-activate already provisioned device rejected.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN or Secret required.", Toast.LENGTH_SHORT).show()
                    setResultCode(android.app.Activity.RESULT_CANCELED)
                    return
                }

                val key = intent.getStringExtra("key") ?: intent.getStringExtra("code") ?: "ACTIVATION_KEY"
                val secret = intent.getStringExtra("secret") ?: intent.getStringExtra("setup_secret") ?: intent.getStringExtra("shared_secret")
                val esp32Mac = intent.getStringExtra("esp32_mac") ?: intent.getStringExtra("mac") ?: intent.getStringExtra("box_mac")
                val esp32Ip = intent.getStringExtra("esp32_ip") ?: intent.getStringExtra("ip")
                val slot = intent.getIntExtra("slot", intent.getIntExtra("setup_slot", -1))
                val name = intent.getStringExtra("name") ?: intent.getStringExtra("alias")

                if (!esp32Mac.isNullOrBlank() || !esp32Ip.isNullOrBlank() || slot > 0 || !secret.isNullOrBlank()) {
                    Log.i(TAG, "Infusing ESP32 Master params with activation: MAC '$esp32Mac', IP '$esp32Ip', Slot $slot, SecretSet=${!secret.isNullOrBlank()}")
                    KioskService.configureMasterBox(context, esp32Mac ?: "", esp32Ip, slot, secret, name)
                }

                Log.i(TAG, "Activation broadcast received with key: $key")
                val success = com.pisophone.kiosk.security.HardwareLockManager.sealToCurrentDevice(context)
                setResultCode(if (success) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED)
                setResultData(if (success) "SUCCESS" else "FAILED")
                if (success) {
                    Toast.makeText(context, "PisoPhone Cryptographic Hardware Seal Established!", Toast.LENGTH_LONG).show()
                    // Restart Kiosk Service and reload UI
                    try {
                        val serviceIntent = Intent(context, KioskService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        val mainIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(mainIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not bring MainActivity to top after activation: ${e.message}")
                    }
                } else {
                    Toast.makeText(context, "Activation Failed. Please contact administrator.", Toast.LENGTH_LONG).show()
                }
            }

            ACTION_DEPROVISION -> {
                if (!isAuthorized(context, intent)) {
                    Log.w(TAG, "Unauthorized attempt to trigger DEPROVISION rejected. Valid PIN or secret required.")
                    Toast.makeText(context, "Unauthorized: Valid Admin PIN required to deprovision.", Toast.LENGTH_LONG).show()
                    return
                }
                Log.w(TAG, "Deprovisioning request authenticated & received. Removing Device Owner and clearing lockdown.")
                
                try {
                    // 1. Stop background Kiosk Service
                    context.stopService(Intent(context, KioskService::class.java))
                    
                    // 2. Clear Device Policy & Lock Task mode
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(context, KioskDeviceAdminReceiver::class.java)
                    
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        try {
                            dpm.setLockTaskPackages(adminComponent, emptyArray())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to clear lock task packages: ${e.message}")
                        }
                        try {
                            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_FACTORY_RESET)
                            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_SAFE_BOOT)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to clear user restrictions: ${e.message}")
                        }
                        dpm.clearDeviceOwnerApp(context.packageName)
                        Log.i(TAG, "Device Owner successfully cleared!")
                    }
                    
                    if (dpm.isAdminActive(adminComponent)) {
                        dpm.removeActiveAdmin(adminComponent)
                        Log.i(TAG, "Active Admin removed!")
                    }

                    Toast.makeText(
                        context,
                        "PisoPhone Deprovisioned! Device Owner removed.",
                        Toast.LENGTH_LONG
                    ).show()

                } catch (e: Exception) {
                    Log.e(TAG, "Error during deprovisioning: ${e.message}", e)
                    Toast.makeText(context, "Deprovision error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isAuthorized(context: Context, intent: Intent): Boolean {
        val pin = intent.getStringExtra("pin") ?: intent.getStringExtra("admin_pin")
        val secret = intent.getStringExtra("secret") ?: intent.getStringExtra("key")
        
        if (!pin.isNullOrBlank() && com.pisophone.kiosk.security.KioskSecurity.verifyAdminPin(context, pin.trim())) {
            return true
        }
        if (!secret.isNullOrBlank() && secret.trim() == com.pisophone.kiosk.security.KioskSecurity.getSharedSecret(context).trim()) {
            return true
        }
        return false
    }
}
