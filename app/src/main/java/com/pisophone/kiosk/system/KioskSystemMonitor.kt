package com.pisophone.kiosk.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.audio.KioskAudioManager
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface KioskSystemMonitorDelegate {
    fun onScreenSleep()
    fun onScreenWake()
    fun getAudioManager(): KioskAudioManager?
}

class KioskSystemMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: KioskSystemMonitorDelegate
) {
    companion object {
        private const val TAG = "KioskSystemMonitor"
    }

    private val _batteryStatus = MutableStateFlow(BatteryStatus())
    val batteryStatus: StateFlow<BatteryStatus> = _batteryStatus.asStateFlow()

    private var previousAlertState = BatteryAlertState.NONE
    private var lastBatteryVoiceReminderMs = 0L

    private var screenOffReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null

    // ========================================================================
    // SCREEN OFF / SLEEP RECEIVER
    // ========================================================================

    fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen OFF detected.")
                        delegate.onScreenSleep()
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Log.d(TAG, "Screen ON/User Present detected.")
                        KioskSecurity.wakeScreenUp(context)
                        KioskSecurity.dismissKeyguard(context)
                        delegate.onScreenWake()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenOffReceiver, filter)
    }

    fun unregisterScreenOffReceiver() {
        try {
            if (screenOffReceiver != null) {
                context.unregisterReceiver(screenOffReceiver)
                screenOffReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering screen receiver: ${e.message}")
        }
    }

    // ========================================================================
    // BATTERY MONITORING
    // ========================================================================

    fun registerBatteryMonitor() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                updateBatteryStatus(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(batteryReceiver, filter)

        try {
            val initialIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            updateBatteryStatus(initialIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read initial battery intent: ${e.message}")
        }
    }

    fun unregisterBatteryMonitor() {
        try {
            if (batteryReceiver != null) {
                context.unregisterReceiver(batteryReceiver)
                batteryReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
        }
    }

    fun getRealTimeBatteryInfo(): Pair<Int, Boolean> {
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0

            var pct = if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            } else {
                -1
            }

            if (pct < 0) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val bmCap = try {
                    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                } catch (_: Exception) { -1 }
                if (bmCap in 0..100) {
                    pct = bmCap
                }
            }

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL ||
                             plugged > 0

            if (pct >= 0) {
                val alertsEnabled = KioskSecurity.isBatteryAlertsEnabled(context)
                val lowThreshold = KioskSecurity.getLowBatteryThreshold(context)
                val highThreshold = KioskSecurity.getHighBatteryThreshold(context)

                val newAlertState = when {
                    !alertsEnabled -> BatteryAlertState.NONE
                    pct <= lowThreshold && !isCharging -> BatteryAlertState.LOW_BATTERY_UNPLUGGED
                    pct >= highThreshold && isCharging -> BatteryAlertState.HIGH_BATTERY_PLUGGED
                    else -> BatteryAlertState.NONE
                }
                previousAlertState = newAlertState
                _batteryStatus.value = BatteryStatus(level = pct, isCharging = isCharging, alertState = newAlertState)
                return Pair(pct, isCharging)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading real time battery info: ${e.message}")
        }
        return Pair(_batteryStatus.value.level, _batteryStatus.value.isCharging)
    }

    private fun updateBatteryStatus(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val bmCapacity = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) { -1 }

        val pct = when {
            level >= 0 && scale > 0 -> (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            bmCapacity in 0..100 -> bmCapacity
            else -> _batteryStatus.value.level.coerceIn(0, 100)
        }
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL ||
                         plugged > 0

        val alertsEnabled = KioskSecurity.isBatteryAlertsEnabled(context)
        val lowThreshold = KioskSecurity.getLowBatteryThreshold(context)
        val highThreshold = KioskSecurity.getHighBatteryThreshold(context)

        val newAlertState = when {
            !alertsEnabled -> BatteryAlertState.NONE
            pct <= lowThreshold && !isCharging -> BatteryAlertState.LOW_BATTERY_UNPLUGGED
            pct >= highThreshold && isCharging -> BatteryAlertState.HIGH_BATTERY_PLUGGED
            else -> BatteryAlertState.NONE
        }

        val audioMgr = delegate.getAudioManager()
        if (audioMgr != null) {
            if (previousAlertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED && isCharging) {
                audioMgr.speakWarning("Charger connected. Battery charging.")
                audioMgr.playSynthesizedTone(1046, 220) // High C6 confirmation chime
            } else if (previousAlertState == BatteryAlertState.HIGH_BATTERY_PLUGGED && !isCharging) {
                audioMgr.speakWarning("Charger disconnected. Battery protection active.")
                audioMgr.playSynthesizedTone(784, 220) // G5 confirmation chime
            }
        }

        previousAlertState = newAlertState
        _batteryStatus.value = BatteryStatus(level = pct, isCharging = isCharging, alertState = newAlertState)
    }

    fun checkPeriodicBatteryAlerts() {
        val currentBattery = _batteryStatus.value
        val now = System.currentTimeMillis()
        val audioMgr = delegate.getAudioManager() ?: return

        if (currentBattery.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED) {
                if (now - lastBatteryVoiceReminderMs >= 15000L) {
                    lastBatteryVoiceReminderMs = now
                    audioMgr.playAnnoyingLowBatteryTone()
                    HardwareFeedback.triggerVibration(context, longArrayOf(0, 200, 100, 200, 100, 400))
                    audioMgr.speakWarning("Warning! Battery is very low at ${currentBattery.level} percent. Please connect the charger immediately to prevent shutdown.")
                }
            } else if (currentBattery.alertState == BatteryAlertState.HIGH_BATTERY_PLUGGED) {
                if (now - lastBatteryVoiceReminderMs >= 20000L) {
                    lastBatteryVoiceReminderMs = now
                    audioMgr.playHighBatteryAttentionTone()
                    HardwareFeedback.triggerVibration(context, longArrayOf(0, 150, 80, 150))
                    audioMgr.speakWarning("Attention! Battery has reached ${currentBattery.level} percent. Please disconnect the charger now to protect battery health.")
                }
            }
        }

    fun shutdown() {
        unregisterScreenOffReceiver()
        unregisterBatteryMonitor()
    }
}
