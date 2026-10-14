package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.security.AdminMaintenanceMode
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.AppCloser
import com.pisophone.kiosk.util.KioskSettings
import com.pisophone.kiosk.util.SocialApps

private fun hourText(hour: Int): String = String.format(java.util.Locale.US, "%02d:00", hour)

/** A labelled slider with its value in words under the label. */
@Composable
private fun SettingSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
        Text(valueText, color = TechNet.gold, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"))
    }
    Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, colors = vaultSliderColors())
}

/** How the phone speaks: on or off, how loud, and quiet hours. */
@Composable
fun VaultSpeechCard(context: Context, onShowHelp: (String, String) -> Unit) {
    var enabled by remember { mutableStateOf(KioskSettings.isSpeechEnabled(context)) }
    var volume by remember { mutableFloatStateOf(KioskSettings.getSpeechVolumePercent(context).toFloat()) }
    var quiet by remember { mutableStateOf(KioskSettings.isQuietHoursEnabled(context)) }
    var quietStart by remember { mutableFloatStateOf(KioskSettings.getQuietStartHour(context).toFloat()) }
    var quietEnd by remember { mutableFloatStateOf(KioskSettings.getQuietEndHour(context).toFloat()) }

    VaultCard {
        VaultCardTitle(
            title = "Spoken messages",
            onShowHelp = onShowHelp,
            helpText = "The voice that counts down the last 5 seconds, warns at 5 minutes, 3 minutes, 1 minute and 10 seconds, and says " +
                "goodbye when time is up. Quiet hours turn the voice off at night (the screen and the vibration still warn).",
        )
        VaultToggleRow(
            title = "Speak warnings and the countdown",
            detail = if (enabled) "On" else "Off · only sounds, vibration and the screen warn",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                KioskSettings.setSpeechEnabled(context, it)
            },
        )
        if (enabled) {
            SettingSlider(
                label = "Voice volume",
                valueText = "${volume.toInt()}%",
                value = volume,
                range = 20f..100f,
                onChange = { volume = it },
                onDone = { KioskSettings.setSpeechVolumePercent(context, volume.toInt()) },
            )
            VaultToggleRow(
                title = "Quiet hours",
                detail = if (quiet) "No voice from ${hourText(quietStart.toInt())} to ${hourText(quietEnd.toInt())}" else "Off",
                checked = quiet,
                onCheckedChange = {
                    quiet = it
                    KioskSettings.setQuietHoursEnabled(context, it)
                },
            )
            if (quiet) {
                SettingSlider(
                    label = "Quiet from",
                    valueText = hourText(quietStart.toInt()),
                    value = quietStart,
                    range = 0f..23f,
                    onChange = { quietStart = it },
                    onDone = { KioskSettings.setQuietHours(context, quietStart.toInt(), quietEnd.toInt()) },
                )
                SettingSlider(
                    label = "Quiet until",
                    valueText = hourText(quietEnd.toInt()),
                    value = quietEnd,
                    range = 0f..23f,
                    onChange = { quietEnd = it },
                    onDone = { KioskSettings.setQuietHours(context, quietStart.toInt(), quietEnd.toInt()) },
                )
            }
            Spacer(Modifier.height(8.dp))
            PisoButton(
                text = stringResource(R.string.ui_test_the_voice),
                onClick = {
                    KioskService.triggerTestTts(context, "Time is up, thank you for playing!")
                    Toast.makeText(context, "Testing the voice…", Toast.LENGTH_SHORT).show()
                },
                style = PisoButtonStyle.Tonal,
                size = PisoButtonSize.Small,
                icon = Icons.AutoMirrored.Filled.VolumeUp,
            )
        }
    }
}

/**
 * The phone between customers: the screen turns off when the lock screen is left alone, picking the phone up wakes it, and the
 * social apps are wiped after the phone has had no paid time for a while. The wipe list is editable. Boost needs Usage Access.
 */
@Composable
fun VaultIdleCard(context: Context, allInstalledApps: List<AppInfo>, onClose: () -> Unit, onShowHelp: (String, String) -> Unit) {
    var screenOff by remember { mutableStateOf(KioskSettings.isScreenOffEnabled(context)) }
    var screenOffMinutes by remember { mutableFloatStateOf(KioskSettings.getScreenOffMinutes(context).toFloat()) }
    var pickup by remember { mutableStateOf(KioskSettings.isWakeOnPickupEnabled(context)) }
    var clear by remember { mutableStateOf(KioskSettings.isGuestClearEnabled(context)) }
    var clearMinutes by remember { mutableFloatStateOf(KioskSettings.getGuestClearMinutes(context).toFloat()) }
    var extra by remember { mutableStateOf(KioskSettings.getSocialExtra(context)) }
    var excluded by remember { mutableStateOf(KioskSettings.getSocialExcluded(context)) }
    var showList by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val usageAccess = remember(showList, screenOff) { AppCloser.hasUsageAccess(context) }

    VaultCard {
        VaultCardTitle(
            title = "Idle phone",
            onShowHelp = onShowHelp,
            helpText = "What the phone does when nobody is using it. The screen turns off when the lock screen is left alone, and picking " +
                "the phone up wakes it (so the power button wears less). After the phone has had no time for a while, the data of the " +
                "social, chat, mail and browser apps is wiped so the next customer starts clean. Games are never wiped. Wiping needs the " +
                "phone to be the device owner and Android 9 or newer.",
        )
        VaultToggleRow(
            title = "Turn the screen off when idle",
            detail = if (screenOff) "After ${screenOffMinutes.toInt()} min with nobody touching the lock screen" else "Off",
            checked = screenOff,
            onCheckedChange = {
                screenOff = it
                KioskSettings.setScreenOffEnabled(context, it)
            },
        )
        if (screenOff) {
            SettingSlider(
                label = "Screen off after",
                valueText = "${screenOffMinutes.toInt()} min",
                value = screenOffMinutes,
                range = 1f..30f,
                onChange = { screenOffMinutes = it },
                onDone = { KioskSettings.setScreenOffMinutes(context, screenOffMinutes.toInt()) },
            )
        }
        VaultToggleRow(
            title = "Wake the phone when it is picked up",
            detail = if (pickup) "On" else "Off · press the power button",
            checked = pickup,
            onCheckedChange = {
                pickup = it
                KioskSettings.setWakeOnPickupEnabled(context, it)
            },
        )

        VaultDivider()

        VaultToggleRow(
            title = "Wipe social apps and browsers",
            detail = if (clear) "After ${clearMinutes.toInt()} min without paid time" else "Off",
            checked = clear,
            onCheckedChange = {
                clear = it
                KioskSettings.setGuestClearEnabled(context, it)
            },
        )
        if (clear) {
            SettingSlider(
                label = "Wipe after",
                valueText = "${clearMinutes.toInt()} min",
                value = clearMinutes,
                range = 1f..30f,
                onChange = { clearMinutes = it },
                onDone = { KioskSettings.setGuestClearMinutes(context, clearMinutes.toInt()) },
            )
            PisoButton(
                text = if (showList) "Done" else "Choose which apps are wiped",
                onClick = { showList = !showList },
                style = if (showList) PisoButtonStyle.Outline else PisoButtonStyle.Tonal,
                size = PisoButtonSize.Small,
            )
            if (showList) {
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().background(TechNet.surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search installed apps", color = TechNet.textMuted) },
                        singleLine = true,
                        textStyle = TextStyle(color = TechNet.text, fontSize = 14.sp),
                        colors = vaultTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val shown = remember(allInstalledApps, query) {
                        if (query.isBlank()) allInstalledApps else allInstalledApps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }
                    }
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { app ->
                            val wiped = SocialApps.isSocial(app.packageName, extra, excluded)
                            val toggle = {
                                // a built-in app is taken off through the excluded list, any other is added through the extra list
                                val builtIn = SocialApps.isSocial(app.packageName)
                                val newExtra = extra.toMutableSet()
                                val newExcluded = excluded.toMutableSet()
                                if (wiped) {
                                    newExtra.remove(app.packageName)
                                    if (builtIn) newExcluded.add(app.packageName)
                                } else {
                                    newExcluded.remove(app.packageName)
                                    if (!builtIn) newExtra.add(app.packageName)
                                }
                                extra = newExtra
                                excluded = newExcluded
                                KioskSettings.setSocialLists(context, newExtra, newExcluded)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.bitmap != null) {
                                    Image(bitmap = app.bitmap, contentDescription = app.name, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
                                } else {
                                    Icon(Icons.Filled.Apps, contentDescription = null, tint = TechNet.textMuted, modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.name, color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(app.packageName, color = TechNet.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
                                }
                                Checkbox(
                                    checked = wiped,
                                    onCheckedChange = { toggle() },
                                    colors = CheckboxDefaults.colors(checkedColor = TechNet.danger, checkmarkColor = TechNet.onGold, uncheckedColor = TechNet.lineStrong),
                                )
                            }
                            HorizontalDivider(color = TechNet.line)
                        }
                    }
                }
            }
        }

        VaultDivider()

        // Boost keeps the app the customer is using only when it can see which one that is
        VaultToggleRow(
            title = "Boost keeps the current app",
            detail = if (usageAccess) "Usage Access is on" else "Usage Access is off · Boost stops only background apps. Turn it on for this app.",
            checked = usageAccess,
            onCheckedChange = {
                try {
                    AdminMaintenanceMode.noteAdminAppOpened()
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onClose()
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open Usage Access settings", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
