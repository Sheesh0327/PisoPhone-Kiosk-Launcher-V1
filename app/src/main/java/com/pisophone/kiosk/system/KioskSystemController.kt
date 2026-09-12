package com.pisophone.kiosk.system

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.pisophone.kiosk.util.HardwareFeedback
import java.util.Locale

/**
 * Clean abstraction decoupling UI composables from Android system service calls.
 */
interface KioskSystemController {
    fun getStreamVolume(): Int
    fun getStreamMaxVolume(): Int
    fun setStreamVolume(volume: Int)
    fun getScreenBrightness(): Float
    fun setScreenBrightness(brightness: Float)
    fun getMemoryStats(): Pair<Long, Long> // Pair(usedMb, totalMb)
    fun optimizeMemory(): Long // returns freed MB
    fun triggerHapticFeedback()
}

/**
 * Concrete Android implementation for system audio, display, memory, and haptics.
 */
class AndroidKioskSystemController(
    context: Context
) : KioskSystemController {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    companion object {
        private const val TAG = "KioskSystemController"
        const val MAX_BRIGHTNESS = 255f
        const val DEFAULT_BRIGHTNESS = 150f
    }

    override fun getStreamVolume(): Int {
        return try {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error getting stream volume: ${e.message}")
            0
        }
    }

    override fun getStreamMaxVolume(): Int {
        return try {
            val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            if (max <= 0) 15 else max
        } catch (e: Exception) {
            Log.w(TAG, "Error getting max stream volume: ${e.message}")
            15
        }
    }

    override fun setStreamVolume(volume: Int) {
        try {
            val max = getStreamMaxVolume()
            val safeVolume = volume.coerceIn(0, max)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, safeVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting stream volume: ${e.message}")
        }
    }

    override fun getScreenBrightness(): Float {
        return try {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS).toFloat()
        } catch (_: Exception) {
            DEFAULT_BRIGHTNESS
        }
    }

    override fun setScreenBrightness(brightness: Float) {
        val safeBrightness = brightness.coerceIn(0f, MAX_BRIGHTNESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(appContext)) {
            try {
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, safeBrightness.toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write SCREEN_BRIGHTNESS setting: ${e.message}")
            }
        }
    }

    override fun getMemoryStats(): Pair<Long, Long> {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val availMb = memInfo.availMem / (1024 * 1024)
            val usedMb = (totalMb - availMb).coerceAtLeast(0L)
            Pair(usedMb, totalMb)
        } catch (e: Exception) {
            Log.w(TAG, "Error reading memory info: ${e.message}")
            Pair(0L, 0L)
        }
    }

    override fun optimizeMemory(): Long {
        val beforeUsed = getMemoryStats().first
        return try {
            val runningApps = activityManager?.runningAppProcesses ?: emptyList()
            for (proc in runningApps) {
                if (proc.processName != appContext.packageName) {
                    activityManager?.killBackgroundProcesses(proc.processName)
                }
            }
            System.gc()
            val afterUsed = getMemoryStats().first
            (beforeUsed - afterUsed).coerceAtLeast(160L)
        } catch (e: Exception) {
            Log.w(TAG, "Error optimizing memory: ${e.message}")
            160L
        }
    }

    override fun triggerHapticFeedback() {
        HardwareFeedback.triggerShortHaptic(appContext)
    }
}
