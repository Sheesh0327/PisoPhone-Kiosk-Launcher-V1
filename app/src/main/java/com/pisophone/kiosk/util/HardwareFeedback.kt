package com.pisophone.kiosk.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
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

    private fun findFlashCameraId(cameraManager: CameraManager): String? {
        return try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (flashAvailable) {
                    return id
                }
            }
            cameraManager.cameraIdList.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun triggerFlashlight(context: Context, durationMs: Long = 1500L) {
        Handler(Looper.getMainLooper()).post {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@post
                val cameraId = findFlashCameraId(cameraManager) ?: return@post
                cameraManager.setTorchMode(cameraId, true)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        cameraManager.setTorchMode(cameraId, false)
                    } catch (_: Exception) {}
                }, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle torch: ${e.message}")
            }
        }
    }

    fun triggerFlashlightStrobe(context: Context, durationMs: Long = 3000L) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@post
                val cameraId = findFlashCameraId(cameraManager) ?: return@post
                val pulseInterval = 200L
                val steps = (durationMs / pulseInterval).toInt()
                for (i in 0 until steps) {
                    handler.postDelayed({
                        try {
                            val isOn = (i % 2 == 0)
                            cameraManager.setTorchMode(cameraId, isOn)
                        } catch (_: Exception) {}
                    }, i * pulseInterval)
                }
                handler.postDelayed({
                    try {
                        cameraManager.setTorchMode(cameraId, false)
                    } catch (_: Exception) {}
                }, durationMs + 100L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to strobe torch: ${e.message}")
            }
        }
    }

    fun triggerAlertFeedback(context: Context) {
        triggerShortHaptic(context, 1500L)
        triggerFlashlight(context, 1500L)
    }
}
