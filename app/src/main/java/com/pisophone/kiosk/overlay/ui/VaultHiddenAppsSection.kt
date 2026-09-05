package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.AppLauncher

@Composable
fun VaultHiddenAppsSection(
    context: Context,
    allInstalledApps: List<AppInfo>,
    hiddenSet: Set<String>,
    onHiddenSetChange: (Set<String>) -> Unit,
    onClose: () -> Unit,
    onShowHelp: (String, String) -> Unit
) {
    var showAppPicker by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }

    val hiddenAppsList = remember(allInstalledApps, hiddenSet) {
        allInstalledApps.filter { hiddenSet.contains(it.packageName) }
    }

    val filteredAllApps = remember(allInstalledApps, appSearchQuery) {
        if (appSearchQuery.isBlank()) allInstalledApps
        else allInstalledApps.filter { 
            it.name.contains(appSearchQuery, ignoreCase = true) || 
            it.packageName.contains(appSearchQuery, ignoreCase = true) 
        }
    }

    val vaultTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color(0xFF6366F1),
        unfocusedBorderColor = Color(0xFF475569),
        focusedContainerColor = Color(0xFF1E293B),
        unfocusedContainerColor = Color(0xFF1E293B),
        focusedPlaceholderColor = Color.Gray,
        unfocusedPlaceholderColor = Color.Gray
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text("Hidden Apps (Admin Access)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(4.dp))
            HelpInfoButton(
                title = "Hidden Apps (Admin Restricted Access)",
                description = "Applications marked as hidden are completely concealed from the customer game launcher. Only technicians in this Security Vault can launch or manage them. Launching any hidden app automatically unlocks a maintenance session so the app can be used without lock screen obstruction.",
                onShowHelp = onShowHelp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { showAppPicker = !showAppPicker },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (showAppPicker) Color(0xFF475569) else Color(0xFF10B981),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(
                if (showAppPicker) Icons.Filled.Close else Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (showAppPicker) "Done" else "Manage Apps", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (showAppPicker) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("Select Apps to Hide from Launcher:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                
                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = { Text("Search installed apps...", color = Color.Gray) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    colors = vaultTextFieldColors,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filteredAllApps.forEach { app ->
                        val isHidden = hiddenSet.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    KioskSecurity.toggleAppHidden(context, app.packageName)
                                    onHiddenSetChange(KioskSecurity.getHiddenApps(context))
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.bitmap != null) {
                                Image(
                                    bitmap = app.bitmap,
                                    contentDescription = app.name,
                                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(app.packageName, color = Color.Gray, fontSize = 10.sp)
                            }
                            Checkbox(
                                checked = isHidden,
                                onCheckedChange = {
                                    KioskSecurity.toggleAppHidden(context, app.packageName)
                                    onHiddenSetChange(KioskSecurity.getHiddenApps(context))
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF10B981), checkmarkColor = Color.Black)
                            )
                        }
                        HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (hiddenAppsList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No apps hidden currently. Tap 'Manage Apps' above to hide apps.", color = Color(0xFFA6ADC8), fontSize = 11.sp)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            hiddenAppsList.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (app.bitmap != null) {
                            Image(
                                bitmap = app.bitmap,
                                contentDescription = app.name,
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Icon(Icons.Filled.Apps, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(app.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(app.packageName, color = Color(0xFFA6ADC8), fontSize = 10.sp)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val success = AppLauncher.launchApp(context, app.packageName, bypassKiosk = true)
                                if (success) {
                                    onClose()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1), contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Filled.RocketLaunch, contentDescription = "Launch", modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("LAUNCH", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                KioskSecurity.toggleAppHidden(context, app.packageName)
                                onHiddenSetChange(KioskSecurity.getHiddenApps(context))
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Unhide", tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.5f))
            }
        }
    }
}
