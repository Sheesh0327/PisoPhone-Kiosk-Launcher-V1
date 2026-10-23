package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.pisophone.kiosk.BuildConfig
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.security.KioskUpdateManager
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * The owner's console. Everything the owner can do on a phone, in cards: quick tools, settings, hidden apps, the app update
 * and the diagnostics. The title is also the way to the recovery hub (seven taps), as before.
 */
@Composable
fun SecurityVaultView(
    context: Context,
    onClose: () -> Unit,
    onOpenRecoveryHub: (() -> Unit)? = null,
) {
    var activeHelpDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingConfirm by remember { mutableStateOf<VaultConfirm?>(null) }
    var showRecoveryHub by remember { mutableStateOf(false) }
    var hiddenSet by remember { mutableStateOf(KioskSecurity.getHiddenApps(context)) }
    var vaultTitleTapCount by remember { mutableIntStateOf(0) }

    val allInstalledApps = remember(context) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos.mapNotNull { resolveInfo ->
            val pkgName = resolveInfo.activityInfo.packageName
            if (pkgName == context.packageName) {
                null
            } else {
                AppInfo(
                    name = resolveInfo.loadLabel(pm).toString(),
                    packageName = pkgName,
                    bitmap = try { resolveInfo.activityInfo.loadIcon(pm).toBitmap().asImageBitmap() } catch (e: Exception) { null },
                )
            }
        }.sortedBy { it.name }
    }
    val showHelp: (String, String) -> Unit = { t, d -> activeHelpDialog = Pair(t, d) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // header: the title (seven taps open the recovery hub), the owner chip, close
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            vaultTitleTapCount++
                            if (vaultTitleTapCount >= 7) {
                                vaultTitleTapCount = 0
                                if (onOpenRecoveryHub != null) {
                                    onOpenRecoveryHub()
                                } else {
                                    showRecoveryHub = true
                                }
                            }
                        },
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = TechNet.brandFg, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Owner settings", fontWeight = FontWeight.ExtraBold, color = TechNet.text, fontSize = 18.sp, lineHeight = 24.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Owner mode",
                        color = TechNet.text,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(TechNet.surfaceHi, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ui_close), tint = TechNet.text)
                }
            }

            VaultBypassSection(context = context, onClose = onClose, onShowHelp = showHelp, onConfirm = { pendingConfirm = it })

            VaultSleepAndBatterySection(context = context, onShowHelp = showHelp)

            VaultSpeechCard(context = context, onShowHelp = showHelp)

            VaultIdleCard(context = context, allInstalledApps = allInstalledApps, onClose = onClose, onShowHelp = showHelp)

            VaultHiddenAppsSection(
                context = context,
                allInstalledApps = allInstalledApps,
                hiddenSet = hiddenSet,
                onHiddenSetChange = { hiddenSet = it },
                onClose = onClose,
                onShowHelp = showHelp,
            )

            VaultUpdateCard(context = context, onShowHelp = showHelp)

            VaultBrandingCard(context = context, onShowHelp = showHelp)

            VaultDiagnosticsSection(context = context, onShowHelp = showHelp)

            PisoButton(text = stringResource(R.string.ui_exit_owner_settings), onClick = onClose, style = PisoButtonStyle.Primary, size = PisoButtonSize.Medium)
            Spacer(Modifier.height(8.dp))
        }

        activeHelpDialog?.let { (helpTitle, helpDesc) ->
            VaultNote(title = helpTitle, message = helpDesc, onDismiss = { activeHelpDialog = null })
        }
        pendingConfirm?.let { confirm ->
            VaultNote(title = confirm.title, message = confirm.message, confirm = confirm, onDismiss = { pendingConfirm = null })
        }

        if (showRecoveryHub) {
            EmergencyRecoveryDialog(
                context = context,
                onClose = { showRecoveryHub = false },
            )
        }
    }
}

/** The direct app update: check, download, install, with the state of it in words. */
@Composable
private fun VaultUpdateCard(context: Context, onShowHelp: (String, String) -> Unit) {
    val updateState by KioskUpdateManager.updateState.collectAsState()
    val currentVersionName = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    VaultCard {
        VaultCardTitle(
            title = "App update",
            onShowHelp = onShowHelp,
            helpText = "Downloads and installs the newest signed version of this app. A phone set up as the device owner also does this " +
                "by itself every hour, whenever nobody is using it.",
        )
        VaultCaption("Installed: version $currentVersionName")
        Spacer(Modifier.height(12.dp))

        when (val state = updateState) {
            is KioskUpdateManager.UpdateState.Idle -> {
                PisoButton(
                    text = stringResource(R.string.ui_check_for_update_and_install),
                    onClick = { KioskUpdateManager.startUpdate(context, "${BuildConfig.UPDATE_BASE_URL}/app-release.apk") },
                    size = PisoButtonSize.Small,
                    icon = Icons.Filled.SystemUpdateAlt,
                )
            }
            is KioskUpdateManager.UpdateState.Downloading -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Downloading the update", color = TechNet.brandFg, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${(state.progress * 100).toInt()}%", color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(TechNet.line, RoundedCornerShape(3.dp))) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(TechNet.brandFg, RoundedCornerShape(3.dp)),
                    )
                }
            }
            is KioskUpdateManager.UpdateState.Installing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TechNet.gold, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Installing the update", color = TechNet.gold, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            is KioskUpdateManager.UpdateState.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = TechNet.ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Update started", color = TechNet.ok, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            is KioskUpdateManager.UpdateState.UpToDate -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = TechNet.ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.message, color = TechNet.ok, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                PisoButton(text = stringResource(R.string.ui_ok), onClick = { KioskUpdateManager.resetState() }, style = PisoButtonStyle.Tonal, size = PisoButtonSize.Small)
            }
            is KioskUpdateManager.UpdateState.Error -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = TechNet.dangerText, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Update failed: ${state.message}",
                        color = TechNet.dangerText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                PisoButton(text = stringResource(R.string.ui_try_again), onClick = { KioskUpdateManager.resetState() }, style = PisoButtonStyle.Tonal, size = PisoButtonSize.Small)
            }
        }
    }
}
