package com.pisophone.kiosk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.MainActivity

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "KioskBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "Received system broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Auto-starting Kiosk services post-boot/update...")

            // 1. Start Kiosk Foreground Service safely if allowed
            val isFullySetup = com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)
            if (isFullySetup) {
                try {
                    val serviceIntent = Intent(context, KioskService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Foreground service start deferred/restricted on boot: ${e.message}")
                }
            } else {
                Log.d(TAG, "Device not yet fully setup/activated. KioskService lock screen deferred.")
            }

            // 2. Schedule Watchdog
            try {
                KioskWatchdogReceiver.scheduleWatchdog(context)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to schedule watchdog on boot: ${e.message}")
            }

            // 3. Launch MainActivity only if explicit package update (since OS launches HOME natively on boot)
            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                try {
                    val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                    val isUnlocked = Build.VERSION.SDK_INT < Build.VERSION_CODES.N || userManager?.isUserUnlocked == true
                    if (isUnlocked) {
                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(launchIntent)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to launch MainActivity on package replace: ${e.message}")
                }
            }
        }
    }
}
