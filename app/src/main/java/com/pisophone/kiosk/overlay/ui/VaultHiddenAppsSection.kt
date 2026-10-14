package com.pisophone.kiosk.overlay.ui

import android.content.Context
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.AppLauncher

@Composable
fun VaultHiddenAppsSection(
    context: Context,
    allInstalledApps: List<AppInfo>,
    hiddenSet: Set<String>,
    onHiddenSetChange: (Set<String>) -> Unit,
    onClose: () -> Unit,
    onShowHelp: (String, String) -> Unit,
) {
    var showAppPicker by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }

    val hiddenAppsList = remember(allInstalledApps, hiddenSet) {
        allInstalledApps.filter { hiddenSet.contains(it.packageName) }
    }

    val filteredAllApps = remember(allInstalledApps, appSearchQuery) {
        if (appSearchQuery.isBlank()) {
            allInstalledApps
        } else {
            allInstalledApps.filter {
                it.name.contains(appSearchQuery, ignoreCase = true) ||
                    it.packageName.contains(appSearchQuery, ignoreCase = true)
            }
        }
    }

    VaultCard {
        VaultCardTitle(
            title = "Hidden apps",
            onShowHelp = onShowHelp,
            helpText = "Apps marked as hidden are removed from the customers' launcher. Only the owner can open them from here. " +
                "Opening a hidden app starts a maintenance session, so the lock screen does not get in the way.",
        )
        Spacer(Modifier.height(8.dp))
        PisoButton(
            text = if (showAppPicker) "Done" else "Choose apps to hide",
            onClick = { showAppPicker = !showAppPicker },
            style = if (showAppPicker) PisoButtonStyle.Outline else PisoButtonStyle.Tonal,
            size = PisoButtonSize.Small,
        )
        Spacer(Modifier.height(12.dp))

        if (showAppPicker) {
            Column(modifier = Modifier.fillMaxWidth().background(TechNet.surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = { Text("Search installed apps", color = TechNet.textMuted) },
                    singleLine = true,
                    textStyle = TextStyle(color = TechNet.text, fontSize = 14.sp),
                    colors = vaultTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    filteredAllApps.forEach { app ->
                        val isHidden = hiddenSet.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    KioskSecurity.toggleAppHidden(context, app.packageName)
                                    onHiddenSetChange(KioskSecurity.getHiddenApps(context))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app, 28)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.name, color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                                Text(app.packageName, color = TechNet.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                            Checkbox(
                                checked = isHidden,
                                onCheckedChange = {
                                    KioskSecurity.toggleAppHidden(context, app.packageName)
                                    onHiddenSetChange(KioskSecurity.getHiddenApps(context))
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = TechNet.teal,
                                    checkmarkColor = TechNet.onTeal,
                                    uncheckedColor = TechNet.lineStrong,
                                ),
                            )
                        }
                        HorizontalDivider(color = TechNet.line)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (hiddenAppsList.isEmpty()) {
            VaultCaption("No apps are hidden. Choose apps to hide above.")
        } else {
            hiddenAppsList.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        AppIcon(app, 32)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(app.name, color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                            Text(app.packageName, color = TechNet.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PisoButton(
                            text = stringResource(R.string.ui_open),
                            onClick = {
                                val success = AppLauncher.launchApp(context, app.packageName, bypassKiosk = true)
                                if (success) {
                                    onClose()
                                }
                            },
                            modifier = Modifier.width(96.dp),
                            size = PisoButtonSize.Small,
                            icon = Icons.Filled.RocketLaunch,
                        )
                        IconButton(
                            onClick = {
                                KioskSecurity.toggleAppHidden(context, app.packageName)
                                onHiddenSetChange(KioskSecurity.getHiddenApps(context))
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Show ${app.name} again", tint = TechNet.dangerText)
                        }
                    }
                }
                HorizontalDivider(color = TechNet.line)
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppInfo, sizeDp: Int) {
    if (app.bitmap != null) {
        Image(bitmap = app.bitmap, contentDescription = app.name, modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Icon(Icons.Filled.Apps, contentDescription = null, tint = TechNet.textMuted, modifier = Modifier.size(sizeDp.dp))
    }
}
