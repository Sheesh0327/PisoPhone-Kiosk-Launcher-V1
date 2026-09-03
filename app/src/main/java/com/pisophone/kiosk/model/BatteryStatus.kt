package com.pisophone.kiosk.model

enum class BatteryAlertState {
    NONE,
    LOW_BATTERY_UNPLUGGED,
    HIGH_BATTERY_PLUGGED
}

data class BatteryStatus(
    val level: Int = 100,
    val isCharging: Boolean = false,
    val alertState: BatteryAlertState = BatteryAlertState.NONE
)
