package com.pisophone.kiosk.overlay.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus

@Composable
fun BlockScreen(
    onInsertCoin: () -> Unit,
    isWaiting: Boolean = false,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    isEsp32Online: Boolean,
    isSlotBusy: Boolean = false,
    pricePerCoin: Double = 5.0,
    minutesPerCoin: Int = 30,
    deviceIp: String = "127.0.0.1",
    slotNumber: Int = 1,
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    onThemeChange: () -> Unit = {},
    buttonText: String = "READY FOR COIN",
    slotWarningDaysLeft: Int? = null,
    isSlotExpired: Boolean = false,
    slotExpiryReason: String = "",
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val currentTheme = BlockScreenThemes.themes[themeIndex % BlockScreenThemes.themes.size]
    val background = currentTheme.background
    val textPrimary = Color(0xFFFFFFFF)
    val primary = currentTheme.primary
    val onPrimary = currentTheme.onPrimary
    val surface = currentTheme.surface
    val border = currentTheme.border.copy(alpha = 0.5f)
    val textSecondary = Color(0xFFA6ADC8)
    val textTertiary = Color(0xFFE2E8F0)
    val success = currentTheme.secondary
    val surfaceVariant = currentTheme.surface.copy(alpha = 0.8f)

    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showEmergencyRecoveryDialog by remember { mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = modifier.background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Top Bar
            BlockScreenTimeHeader(batteryStatus = batteryStatus, themeTextPrimary = textPrimary)

            // Battery Alert Banner
            if (batteryStatus.alertState != BatteryAlertState.NONE) {
                BatteryAlertBanner(batteryStatus = batteryStatus)
            }

            // Slot Expiration Warning & Expired Banners
            if (isSlotExpired) {
                SlotExpiredBanner(reason = slotExpiryReason)
            } else if (slotWarningDaysLeft != null && slotWarningDaysLeft >= 0) {
                SlotExpirationWarningBanner(daysLeft = slotWarningDaysLeft)
            }

            // Scrollable Content
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isWide = maxWidth > 600.dp || isLandscape
                
                val mainContent: @Composable (Modifier) -> Unit = { mod ->
                    Column(
                        modifier = mod,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(primary.copy(alpha = 0.12f), CircleShape)
                                .border(1.5.dp, primary.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isWaiting) {
                                Text("$paymentTimeout", color = primary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        DeviceTitleBadge(deviceIp, slotNumber, primary, textPrimary, textTertiary, surfaceVariant)

                        Text(
                            if (isWaiting) "Coins inserted: $coinsInserted" else "Insert a coin to unlock all applications for a $minutesPerCoin-minute session.",
                            color = textSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                        )

                        BlockScreenRateTableCard(
                            pricePerCoin = pricePerCoin,
                            minutesPerCoin = minutesPerCoin,
                            isWaiting = isWaiting,
                            coinsInserted = coinsInserted,
                            paymentTimeout = paymentTimeout,
                            isEsp32Online = isEsp32Online,
                            isSlotBusy = isSlotBusy,
                            isSlotExpired = isSlotExpired,
                            buttonText = buttonText,
                            primaryColor = primary,
                            onPrimaryColor = onPrimary,
                            surfaceColor = surface,
                            surfaceVariantColor = surfaceVariant,
                            borderColor = border,
                            backgroundColor = background,
                            textPrimaryColor = textPrimary,
                            textSecondaryColor = textSecondary,
                            textTertiaryColor = textTertiary,
                            successColor = success,
                            onDoneClick = onDoneClick,
                            onInsertCoin = onInsertCoin
                        )
                    }
                }
                
                val bottomSection: @Composable (Modifier) -> Unit = { mod ->
                    Column(modifier = mod) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(surface, RoundedCornerShape(28.dp))
                                .padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(if (isEsp32Online) success else Color.Red, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            if (isEsp32Online) "HARDWARE CONTROLLER CONNECTED" else "HARDWARE CONTROLLER OFFLINE", 
                                            color = textPrimary, 
                                            fontSize = 11.sp, 
                                            fontWeight = FontWeight.Bold, 
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            if (isEsp32Online) "Autonomous Discovery & Interlock Synchronized" else "Searching for ESP32 on network...", 
                                            color = textTertiary, 
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp)
                                .alpha(0.6f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onThemeChange) { 
                                Icon(Icons.Filled.Palette, contentDescription = "Change Theme", tint = textPrimary) 
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { showPinDialog = true }) { 
                                Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Security Vault", tint = textPrimary) 
                            }
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            mainContent(Modifier.padding(top = 16.dp))
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            bottomSection(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        mainContent(
                            Modifier
                                .padding(horizontal = 24.dp)
                                .padding(top = 16.dp)
                        )
                        bottomSection(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        )
                    }
                }
            }

            if (showPinDialog) {
                AdminAuthenticationDialog(
                    context = context,
                    surfaceColor = surface,
                    primaryColor = primary,
                    borderColor = border,
                    textPrimaryColor = textPrimary,
                    textSecondaryColor = textSecondary,
                    onDismiss = { showPinDialog = false },
                    onUnlockSuccess = {
                        showPinDialog = false
                        showSecurityDialog = true
                    },
                    onOpenEmergencyRecovery = {
                        showPinDialog = false
                        showEmergencyRecoveryDialog = true
                    }
                )
            }

            if (showSecurityDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.92f)
                            .padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            SecurityVaultView(
                                context = context,
                                onClose = { showSecurityDialog = false },
                                onOpenRecoveryHub = {
                                    showSecurityDialog = false
                                    showEmergencyRecoveryDialog = true
                                }
                            )
                        }
                    }
                }
            }

            if (showEmergencyRecoveryDialog) {
                EmergencyRecoveryDialog(
                    context = context,
                    onClose = { showEmergencyRecoveryDialog = false }
                )
            }
        }
    }
}
