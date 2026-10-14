package com.pisophone.kiosk.overlay.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.ui.theme.TechNet

@Composable
fun BatteryAlertBanner(
    batteryStatus: BatteryStatus,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp,
) {
    if (batteryStatus.alertState == BatteryAlertState.NONE) return

    val isLowBattery = batteryStatus.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED
    KidsBanner(
        accent = if (isLowBattery) TechNet.danger else TechNet.warn,
        icon = if (isLowBattery) Icons.Filled.BatteryAlert else Icons.Filled.Bolt,
        title = if (isLowBattery) {
            stringResource(R.string.banner_low_battery, batteryStatus.level)
        } else {
            stringResource(R.string.banner_unplug, batteryStatus.level)
        },
        detail = if (isLowBattery) stringResource(R.string.banner_low_battery_detail) else stringResource(R.string.banner_unplug_detail),
        modifier = modifier,
        horizontalInset = horizontalInset,
    )
}
