package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * The owner's settings cards: background videos, and battery health with its spoken reminders. The battery settings are folded
 * away by default so a slider is not moved by accident while scrolling; tapping the title opens them.
 */
@Composable
fun VaultSleepAndBatterySection(
    context: Context,
    onShowHelp: (String, String) -> Unit,
) {
    var isBatteryExpanded by remember { mutableStateOf(false) }
    var batteryAlertsEnabled by remember { mutableStateOf(KioskSecurity.isBatteryAlertsEnabled(context)) }
    var lowBatteryThresh by remember { mutableIntStateOf(KioskSecurity.getLowBatteryThreshold(context)) }
    var highBatteryThresh by remember { mutableIntStateOf(KioskSecurity.getHighBatteryThreshold(context)) }
    var videoEnabled by remember { mutableStateOf(KioskSecurity.isVideoBackgroundEnabled(context)) }

    VaultCard {
        VaultCardTitle(title = "Settings", onShowHelp = onShowHelp)
        Spacer(Modifier.height(8.dp))

        VaultToggleRow(
            title = "Background videos",
            detail = "Animated logo behind the lock screen and the home screen. Off shows a still image.",
            checked = videoEnabled,
            onCheckedChange = { isChecked ->
                videoEnabled = isChecked
                KioskSecurity.setVideoBackgroundEnabled(context, isChecked)
                Toast.makeText(context, if (isChecked) "Background videos on" else "Background videos off", Toast.LENGTH_SHORT).show()
            },
        )

        VaultDivider()

        // the battery group: a title row that folds
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { isBatteryExpanded = !isBatteryExpanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Battery health and voice reminders", color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    HelpInfoButton(
                        title = "Battery health and voice reminders",
                        description = "Watches the battery. A voice and an alert tone remind the staff to plug the charger in when the battery is low, " +
                            "and to unplug it when it is full, so the battery does not swell.",
                        onShowHelp = onShowHelp,
                    )
                }
                Text(
                    text = if (batteryAlertsEnabled) "On · keeps the battery between $lowBatteryThresh% and $highBatteryThresh%" else "Off · tap to set up",
                    color = if (batteryAlertsEnabled) TechNet.ok else TechNet.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                imageVector = if (isBatteryExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (isBatteryExpanded) "Fold" else "Open",
                tint = TechNet.textMuted,
                modifier = Modifier.size(24.dp),
            )
        }

        AnimatedVisibility(
            visible = isBatteryExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                VaultToggleRow(
                    title = "Voice alerts for the battery",
                    detail = null,
                    checked = batteryAlertsEnabled,
                    onCheckedChange = { isChecked ->
                        batteryAlertsEnabled = isChecked
                        KioskSecurity.setBatteryAlertsEnabled(context, isChecked)
                        Toast.makeText(context, if (isChecked) "Battery reminders on" else "Battery reminders off", Toast.LENGTH_SHORT).show()
                    },
                )

                if (batteryAlertsEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep the battery between", color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                        HelpInfoButton(
                            title = "Battery range",
                            description = "A voice reminder asks to plug in when the battery falls to the lower value, and to unplug when it reaches the " +
                                "upper value. This protects the lifespan of the battery.",
                            onShowHelp = onShowHelp,
                        )
                    }

                    var sliderPosition by remember { mutableStateOf(lowBatteryThresh.toFloat()..highBatteryThresh.toFloat()) }
                    RangeSlider(
                        value = sliderPosition,
                        onValueChange = { range -> sliderPosition = range },
                        onValueChangeFinished = {
                            val newLow = sliderPosition.start.toInt()
                            val newHigh = sliderPosition.endInclusive.toInt()
                            lowBatteryThresh = newLow
                            highBatteryThresh = newHigh
                            KioskSecurity.setLowBatteryThreshold(context, newLow)
                            KioskSecurity.setHighBatteryThreshold(context, newHigh)
                        },
                        valueRange = 0f..100f,
                        steps = 99,
                        colors = vaultSliderColors(),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Plug in at ${sliderPosition.start.toInt()}%", color = TechNet.dangerText, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Unplug at ${sliderPosition.endInclusive.toInt()}%", color = TechNet.gold, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(12.dp))
                    PisoButton(
                        text = stringResource(R.string.ui_test_the_voice),
                        onClick = {
                            KioskService.triggerTestTts(context, "Piso Phone voice system online and functional.")
                            Toast.makeText(context, "Testing the voice…", Toast.LENGTH_SHORT).show()
                        },
                        style = PisoButtonStyle.Tonal,
                        size = PisoButtonSize.Small,
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                    )
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}
