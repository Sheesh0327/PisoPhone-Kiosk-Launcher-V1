package com.pisophone.kiosk.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("KioskDeviceAdmin", "Device Administrator Enabled")
        
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val componentName = android.content.ComponentName(context, KioskDeviceAdminReceiver::class.java)
        try {
            val isFullySetup = com.pisophone.kiosk.security.HardwareLockManager.isTutorialCompleted(context) &&
                               com.pisophone.kiosk.security.HardwareLockManager.isAppAllowedToRun(context)
            if (dpm.isDeviceOwnerApp(context.packageName) && isFullySetup) {
                com.pisophone.kiosk.security.KioskSecurity.applyStrictKioskPolicies(context)
                Log.d("KioskDeviceAdmin", "Successfully provisioned as Device Owner & Lock Task Policies set.")
            } else {
                Log.d("KioskDeviceAdmin", "Device Owner active; deferring strict lockdown until activation and setup are complete.")
            }
        } catch (e: Exception) {
            Log.e("KioskDeviceAdmin", "Failed to apply Lock Task Policies: ${e.message}")
        }

        Toast.makeText(context, "Kiosk Device Admin Activated", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w("KioskDeviceAdmin", "Device Administrator Disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d("KioskDeviceAdmin", "Lock Task Mode Active for $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.w("KioskDeviceAdmin", "Lock Task Mode Exited")
    }
}
