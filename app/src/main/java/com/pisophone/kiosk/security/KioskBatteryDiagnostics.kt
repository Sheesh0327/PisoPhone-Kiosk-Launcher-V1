package com.pisophone.kiosk.security

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log

/**
 * Data class representing current device battery metrics.
 */
data class BatteryInfo(
    val level: Int,
    val scale: Int,
    val percentage: Int,
    val isCharging: Boolean,
    val plugType: String,
    val temperatureCelsius: Float,
    val voltageMv: Int,
    val health: String
)

/**
 * Handles hardware battery diagnostics inspection and media volume configuration.
 */
object KioskBatteryDiagnostics {
    private const val TAG = "KioskBatteryDiagnostics"

    fun getBatteryDiagnostics(context: Context): BatteryInfo {
        val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Wall"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        val rawTemp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = rawTemp / 10.0f
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val rawHealth = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: 0
        val healthStr = when (rawHealth) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        return BatteryInfo(
            level = level,
            scale = scale,
            percentage = pct,
            isCharging = isCharging,
            plugType = plugType,
            temperatureCelsius = tempCelsius,
            voltageMv = voltage,
            health = healthStr
        )
    }

    fun setMediaVolume(context: Context, volumePercent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((volumePercent.coerceIn(0, 100) / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Media volume set to $volumePercent% (level $target/$maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}")
        }
    }
}
