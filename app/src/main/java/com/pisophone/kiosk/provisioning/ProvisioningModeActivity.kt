package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Android 12+ asks the app, during QR setup, which kind of setup it wants (GET_PROVISIONING_MODE): always a fully managed
 * phone (the app becomes the device owner). The box's details are passed on to [PolicyComplianceActivity]. Only the system
 * can start this screen (BIND_DEVICE_ADMIN in the manifest).
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(EXTRA_PROVISIONING_MODE, PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        QrProvisioning.adminExtras(intent)?.let { result.putExtra(QrProvisioning.EXTRA_ADMIN_EXTRAS, it) }
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        // DevicePolicyManager.EXTRA_PROVISIONING_MODE / PROVISIONING_MODE_FULLY_MANAGED_DEVICE (API 29+), spelled out
        // so the app still builds and runs on its minimum SDK
        const val EXTRA_PROVISIONING_MODE = "android.app.extra.PROVISIONING_MODE"
        const val PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1
    }
}
