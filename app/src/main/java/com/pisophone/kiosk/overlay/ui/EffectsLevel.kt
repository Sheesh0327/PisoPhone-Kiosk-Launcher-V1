package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.video.VideoBackgroundPolicy

/**
 * How much the lock screen may move (docs/design/GUI_SPEC_PISOPHONE_KIDS.md sections 8 and 10).
 *  - [Full]: the mascot's idle wave and the event effects (coins, sparkles, hop).
 *  - [Events]: only effects that answer a tap or a coin (a warm phone, or the admin switched videos off, or low RAM / power saving).
 *  - [Still]: the system's animation scale is 0: nothing moves.
 *  - [Flat]: the phone is running hot: no art at all, only the content.
 */
enum class EffectsLevel {
    Full,
    Events,
    Still,
    Flat,
    ;

    val ambient: Boolean get() = this == Full
    val events: Boolean get() = this == Full || this == Events
    val art: Boolean get() = this != Flat

    companion object {
        /** PowerManager.THERMAL_STATUS_MODERATE and THERMAL_STATUS_SEVERE (kept as numbers so this stays a plain function). */
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3

        fun of(thermalStatus: Int, policyAllowsMotion: Boolean, animationsOff: Boolean): EffectsLevel = when {
            thermalStatus >= THERMAL_SEVERE -> Flat
            animationsOff -> Still
            thermalStatus >= THERMAL_MODERATE || !policyAllowsMotion -> Events
            else -> Full
        }
    }
}

private fun policyAllowsMotion(context: Context): Boolean {
    val facts = VideoBackgroundPolicy.readDeviceFacts(context)
    return VideoBackgroundPolicy.shouldPlay(
        enabled = KioskSecurity.isVideoBackgroundEnabled(context),
        lowRamDevice = facts.lowRamDevice,
        powerSaveMode = facts.powerSaveMode,
        batteryPercent = facts.batteryPercent,
        charging = facts.charging,
        screenInFront = true,
        covered = false,
    )
}

internal fun animationsAreOff(context: Context): Boolean =
    try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        false
    }

internal fun thermalNow(context: Context): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
    return try {
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
    } catch (e: Exception) {
        0
    }
}

/** The phone's thermal status now (0 when it reports none); it follows the temperature while the screen is up. */
@Composable
fun rememberThermalStatus(): Int {
    val context = LocalContext.current
    val thermal = remember { mutableIntStateOf(thermalNow(context)) }
    DisposableEffect(context) {
        val pm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(PowerManager::class.java) else null
        val listener = if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { status -> thermal.intValue = status }
        } else {
            null
        }
        if (pm != null && listener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.addThermalStatusListener(listener)
        onDispose {
            if (pm != null && listener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.removeThermalStatusListener(listener)
        }
    }
    return thermal.intValue
}

/** The effects level for this moment; it follows the phone's temperature while the lock screen is up. */
@Composable
fun rememberEffectsLevel(): EffectsLevel {
    val context = LocalContext.current
    val thermal = rememberThermalStatus()
    val motionAllowed = remember { policyAllowsMotion(context) }
    val animationsOff = remember { animationsAreOff(context) }
    return EffectsLevel.of(thermal, motionAllowed, animationsOff)
}

/** Whether the shop-name marquee may scroll right now, by [MarqueeRule] on this phone's temperature, battery and settings. */
@Composable
fun rememberMarqueeScrolls(): Boolean {
    val context = LocalContext.current
    val thermal = rememberThermalStatus()
    val facts = remember { VideoBackgroundPolicy.readDeviceFacts(context) }
    val animationsOff = remember { animationsAreOff(context) }
    return MarqueeRule.shouldScroll(
        thermalStatus = thermal,
        powerSaveMode = facts.powerSaveMode,
        batteryPercent = facts.batteryPercent,
        charging = facts.charging,
        animationsOn = !animationsOff,
        screenInFront = true,
    )
}
