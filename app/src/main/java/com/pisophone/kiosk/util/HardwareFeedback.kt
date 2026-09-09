package com.pisophone.kiosk.util

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

object HardwareFeedback {
    private const val TAG = "HardwareFeedback"

    fun triggerVibration(context: Context, pattern: LongArray) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger vibration: ${e.message}")
        }
    }

    fun triggerShortHaptic(context: Context, durationMs: Long = 120L) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger short haptic: ${e.message}")
        }
    }

    fun triggerFlashlight(context: Context, durationMs: Long = 1500L) {
        Handler(Looper.getMainLooper()).post {
            try {
                val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, 
                    android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasCameraPermission) {
                    Log.w(TAG, "CAMERA permission is NOT granted! Camera and flashlight queries might fail on some device models.")
                }

                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@post
                // Find a camera that actually supports FLASH
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } catch (_: Exception) {
                        false
                    }
                } ?: cameraManager.cameraIdList.firstOrNull() // Fallback to first if none report flash info available

                if (cameraId != null) {
                    val handler = Handler(Looper.getMainLooper())
                    val intervalMs = 150L
                    val endTime = System.currentTimeMillis() + durationMs
                    
                    val strobeRunnable = object : Runnable {
                        var state = false
                        override fun run() {
                            if (System.currentTimeMillis() < endTime) {
                                state = !state
                                try {
                                    cameraManager.setTorchMode(cameraId, state)
                                } catch (_: Exception) {}
                                handler.postDelayed(this, intervalMs)
                            } else {
                                try {
                                    cameraManager.setTorchMode(cameraId, false)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    handler.post(strobeRunnable)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle torch: ${e.message}")
            }
        }
    }

    fun triggerAlertFeedback(context: Context) {
        triggerShortHaptic(context, 1500L)
        triggerFlashlight(context, 1500L)
    }
}
