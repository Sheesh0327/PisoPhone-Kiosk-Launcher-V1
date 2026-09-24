package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onCancelClick: () -> Unit = {},
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
    isArenaMode: Boolean = false,
    arenaRole: Int = 0,
    arenaStakeMinutes: Int = 15,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val currentTheme = KIOSK_OVERLAY_THEMES[themeIndex % KIOSK_OVERLAY_THEMES.size]
    val Background = currentTheme.background
    val TextPrimary = Color(0xFFFFFFFF)
    val Primary = currentTheme.primary
    val OnPrimary = currentTheme.onPrimary
    val Surface = currentTheme.surface
    val Border = currentTheme.border.copy(alpha = 0.5f)
    val TextSecondary = Color(0xFFA6ADC8)
    val TextTertiary = Color(0xFFE2E8F0)
    val Success = currentTheme.secondary
    val SurfaceVariant = currentTheme.surface.copy(alpha = 0.8f)

    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showEmergencyRecoveryDialog by remember { mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = modifier.background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Top Bar
            BlockScreenTimeHeader(batteryStatus = batteryStatus, themeTextPrimary = TextPrimary)

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

            if (isArenaMode) {
                val roleName = if (arenaRole == 1) "Player 1" else if (arenaRole == 2) "Player 2" else "Participant"
                val roleBadge = if (arenaRole == 1) "P1" else if (arenaRole == 2) "P2" else "1v1"
                val roleColor = if (arenaRole == 1) Color(0xFF38BDF8) else Color(0xFFFF5252)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1035)),
                    border = BorderStroke(1.5.dp, Color(0xFF8B5CF6))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚔️", fontSize = 20.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "1V1 ARENA DUEL ACTIVE",
                                color = Color(0xFFA78BFA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "You are $roleName • ${arenaStakeMinutes}m Stake",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = roleColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, roleColor.copy(alpha = 0.8f))
                        ) {
                            Text(
                                text = roleBadge,
                                color = roleColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
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
                                .background(Primary.copy(alpha = 0.12f), CircleShape)
                                .border(1.5.dp, Primary.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isWaiting) {
                                Text("$paymentTimeout", color = Primary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        DeviceTitleBadge(deviceIp, slotNumber, Primary, TextPrimary, TextTertiary, SurfaceVariant)

                        Text(
                            if (isWaiting) "Coins inserted: $coinsInserted" else "Insert a coin to unlock all applications for a $minutesPerCoin-minute session.",
                            color = TextSecondary,
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
                            primaryColor = Primary,
                            onPrimaryColor = OnPrimary,
                            surfaceColor = Surface,
                            surfaceVariantColor = SurfaceVariant,
                            borderColor = Border,
                            backgroundColor = Background,
                            textPrimaryColor = TextPrimary,
                            textSecondaryColor = TextSecondary,
                            textTertiaryColor = TextTertiary,
                            successColor = Success,
                            onDoneClick = onDoneClick,
                            onCancelClick = onCancelClick,
                            onInsertCoin = onInsertCoin
                        )
                    }
                }
                
                val bottomSection: @Composable (Modifier) -> Unit = { mod ->
                    Column(modifier = mod) {
                        BlockScreenHardwareFooter(
                            context = context,
                            isEsp32Online = isEsp32Online,
                            surfaceColor = Surface,
                            successColor = Success,
                            textPrimaryColor = TextPrimary,
                            textTertiaryColor = TextTertiary,
                            onThemeChange = onThemeChange,
                            onOpenSecurityVault = { showPinDialog = true }
                        )
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
                    surfaceColor = Surface,
                    primaryColor = Primary,
                    borderColor = Border,
                    textPrimaryColor = TextPrimary,
                    textSecondaryColor = TextSecondary,
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
