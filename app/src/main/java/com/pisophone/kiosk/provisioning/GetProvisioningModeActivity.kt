package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i("Provisioning", "GetProvisioningModeActivity started with action: ${intent.action}")

        if (intent.action != DevicePolicyManager.ACTION_GET_PROVISIONING_MODE) {
            Log.e("Provisioning", "Invalid action: ${intent.action}")
            finish()
            return
        }

        val allowedModes = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)

        if (allowedModes != null && !allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)) {
            Log.e("Provisioning", "Device owner provisioning not allowed by device. Allowed: $allowedModes")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        ProvisioningCoordinator.extractAndSaveAdminExtras(this, intent)

        Log.i("Provisioning", "Returning PROVISIONING_MODE_FULLY_MANAGED_DEVICE")
        val result = Intent().apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
