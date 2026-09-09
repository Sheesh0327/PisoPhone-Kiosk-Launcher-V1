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
                    Log.w(TAG, "Cannot strobe flashlight: CAMERA permission is not granted.")
                    return@post
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
                }

                if (cameraId == null) {
                    Log.w(TAG, "Cannot strobe flashlight: no flash-capable camera was found.")
                } else {
                    val handler = Handler(Looper.getMainLooper())
                    val intervalMs = 150L
                    val endTime = android.os.SystemClock.elapsedRealtime() + durationMs
                    
                    val strobeRunnable = object : Runnable {
                        var state = false
                        var failureLogged = false
                        override fun run() {
                            if (android.os.SystemClock.elapsedRealtime() < endTime) {
                                state = !state
                                try {
                                    cameraManager.setTorchMode(cameraId, state)
                                } catch (e: Exception) {
                                    if (!failureLogged) {
                                        failureLogged = true
                                        Log.w(TAG, "Failed to strobe flashlight: ${e.message}")
                                    }
                                }
                                handler.postDelayed(this, intervalMs)
                            } else {
                                try {
                                    cameraManager.setTorchMode(cameraId, false)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to turn flashlight off after strobe: ${e.message}")
                                }
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
