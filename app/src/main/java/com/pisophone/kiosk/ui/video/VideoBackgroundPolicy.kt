package com.pisophone.kiosk.ui.video

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * When the looping background videos (lock screen logo, launcher backdrop) may play. They are decoded in hardware and muted,
 * but a decoder still costs memory, and cheap phones have only one or two of them: so a video plays only while somebody can
 * see it, and never on a phone that cannot afford it (the still first frame is shown instead).
 */
object VideoBackgroundPolicy {
    /** Below this charge, and not charging, the videos stop. */
    const val LOW_BATTERY_PERCENT = 15

    /** Pure, so it is tested without a phone. [batteryPercent] is -1 when unknown. */
    fun shouldPlay(
        enabled: Boolean,
        lowRamDevice: Boolean,
        powerSaveMode: Boolean,
        batteryPercent: Int,
        charging: Boolean,
        screenInFront: Boolean,
        covered: Boolean,
    ): Boolean {
        if (!enabled || lowRamDevice || powerSaveMode || !screenInFront || covered) return false
        val lowAndDraining = batteryPercent in 0 until LOW_BATTERY_PERCENT && !charging
        return !lowAndDraining
    }

    /** The x and y scale that makes a video of the given size fill a view of the given size without distortion. */
    fun cropScale(viewW: Float, viewH: Float, videoW: Float, videoH: Float): Pair<Float, Float> {
        val viewAspect = viewW / viewH
        val videoAspect = videoW / videoH
        return if (videoAspect > viewAspect) videoAspect / viewAspect to 1f else 1f to viewAspect / videoAspect
    }

    data class DeviceFacts(val lowRamDevice: Boolean, val powerSaveMode: Boolean, val batteryPercent: Int, val charging: Boolean)

    fun readDeviceFacts(context: Context): DeviceFacts {
        val app = context.applicationContext ?: context
        val lowRam = (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        val powerSave = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        val battery: Intent? = try {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceFacts(lowRam, powerSave, percent, charging)
    }
}

/**
 * Whether the full-screen lock screen is on screen. The launcher sits underneath it while the phone is locked, so its video
 * must not decode there. The lock screen reports itself in and out of composition.
 */
object LockScreenPresence {
    private val users = AtomicInteger(0)
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown

    fun enter() {
        users.incrementAndGet()
        _shown.value = true
    }

    fun leave() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            _shown.value = false
        }
    }
}
