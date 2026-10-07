package com.pisophone.kiosk.provisioning

import android.app.Activity
import android.os.Bundle

/**
 * The last step of QR setup on Android 12+ (ADMIN_POLICY_COMPLIANCE): the app is the device owner now; the box's details
 * from the QR code are stored, then Android finishes its setup screens and opens the kiosk (the app is the home screen).
 * Only the system can start this screen (BIND_DEVICE_ADMIN in the manifest).
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QrProvisioning.applyFromIntent(this, intent)
        QrProvisioning.finishSetup(this)
        setResult(RESULT_OK)
        finish()
    }
}
