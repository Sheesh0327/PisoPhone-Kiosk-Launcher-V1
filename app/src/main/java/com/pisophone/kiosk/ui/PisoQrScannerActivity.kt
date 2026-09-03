package com.pisophone.kiosk.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * Custom CaptureActivity for QR scanning in PisoPhone.
 * Handles camera preview, orientation lock, and clean dark UI theme.
 */
class PisoQrScannerActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
