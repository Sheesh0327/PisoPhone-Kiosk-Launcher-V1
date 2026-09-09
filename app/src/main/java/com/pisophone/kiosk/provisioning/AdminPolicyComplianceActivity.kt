package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminPolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i("Provisioning", "AdminPolicyComplianceActivity started with action: ${intent.action}")

        if (intent.action != DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE) {
            Log.e("Provisioning", "Invalid action: ${intent.action}")
            finish()
            return
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Log.e("Provisioning", "Not running as device owner app. Compliance aborting.")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val success = ProvisioningCoordinator.extractAndSaveAdminExtras(this, intent)
        
        if (success) {
            // Return OK locally. The actual pairing check will happen via the service or boot.
            Log.i("Provisioning", "Local compliance accepted.")
            setResult(RESULT_OK)
        } else {
            Log.e("Provisioning", "Local compliance failed due to invalid extras.")
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
