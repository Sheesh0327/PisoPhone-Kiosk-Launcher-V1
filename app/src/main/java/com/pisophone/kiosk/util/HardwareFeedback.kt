package com.pisophone.kiosk.util

import android.content.Context
import android.hardware.camera2.CameraManager
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
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger vibration: ${e.message}")
        }
    }

    fun triggerShortHaptic(context: Context, durationMs: Long = 120L) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger short haptic: ${e.message}")
        }
    }

    fun triggerFlashlight(context: Context, durationMs: Long = 1500L) {
        Handler(Looper.getMainLooper()).post {
            try {
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

    private var locateStrobe: Runnable? = null
    private val locateHandler = Handler(Looper.getMainLooper())

    /** Pulsing vibration plus a fast torch strobe that keep going until [stopLocateFeedback]. */
    fun startLocateFeedback(context: Context) {
        stopLocateFeedback(context)
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 250), 0))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start locate vibration: ${e.message}")
        }
        locateHandler.post {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@post
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    try {
                        cameraManager.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } catch (_: Exception) {
                        false
                    }
                } ?: return@post
                val strobe = object : Runnable {
                    var on = false
                    override fun run() {
                        on = !on
                        try {
                            cameraManager.setTorchMode(cameraId, on)
                        } catch (_: Exception) {}
                        locateHandler.postDelayed(this, 120L)
                    }
                }
                locateStrobe = strobe
                locateHandler.post(strobe)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start locate strobe: ${e.message}")
            }
        }
    }

    fun stopLocateFeedback(context: Context) {
        try {
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        } catch (_: Exception) {}
        locateHandler.post {
            locateStrobe?.let { locateHandler.removeCallbacks(it) }
            locateStrobe = null
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@post
                cameraManager.cameraIdList.forEach { id ->
                    try {
                        cameraManager.setTorchMode(id, false)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    fun triggerAlertFeedback(context: Context) {
        triggerShortHaptic(context, 1500L)
        triggerFlashlight(context, 1500L)
    }
}
