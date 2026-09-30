

package com.pisophone.kiosk.overlay.ui
import kotlinx.coroutines.isActive
import com.pisophone.kiosk.overlay.ui.AdminAuthenticationDialog
import com.pisophone.kiosk.overlay.ui.SecurityVaultView
import com.pisophone.kiosk.overlay.ui.EmergencyRecoveryDialog
import com.pisophone.kiosk.overlay.ui.BatteryAlertBanner

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
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.delay

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
    isArenaMode: Boolean = false,
    arenaRole: Int = 0,
    arenaStakeMinutes: Int = 15,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    data class OverlayTheme(
        val name: String,
        val background: Color,
        val primary: Color,
        val onPrimary: Color,
        val surface: Color,
        val border: Color,
        val secondary: Color
    )

    val themes = listOf(
        OverlayTheme(
            name = "PISOPHONE OBSIDIAN",
            background = Color(0xFF060B14),
            primary = Color(0xFF10B981),
            onPrimary = Color(0xFF020617),
            surface = Color(0xFF0F172A),
            border = Color(0xFF1E293B),
            secondary = Color(0xFF34D399)
        ),
        OverlayTheme(
            name = "ULTRA VIOLET",
            background = Color(0xFF0F061E),
            primary = Color(0xFFB026FF),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF221140),
            border = Color(0xFFB026FF),
            secondary = Color(0xFFFFB800)
        ),
        OverlayTheme(
            name = "MATRIX LIME",
            background = Color(0xFF04120B),
            primary = Color(0xFF00FF88),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF0C2B1D),
            border = Color(0xFF00FF88),
            secondary = Color(0xFF00F5D4)
        ),
        OverlayTheme(
            name = "SOLAR FLARE",
            background = Color(0xFF140804),
            primary = Color(0xFFFF6600),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF2A140B),
            border = Color(0xFFFF6600),
            secondary = Color(0xFFFFD600)
        ),
        OverlayTheme(
            name = "CRIMSON NOVA",
            background = Color(0xFF120509),
            primary = Color(0xFFFF2A5F),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF2C111C),
            border = Color(0xFFFF2A5F),
            secondary = Color(0xFFFF6488)
        ),
        OverlayTheme(
            name = "ELECTRIC SUNSET",
            background = Color(0xFF130410),
            primary = Color(0xFFFF007F),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF2D1027),
            border = Color(0xFFFF007F),
            secondary = Color(0xFFFF66B2)
        ),
        OverlayTheme(
            name = "ARCTIC FROST",
            background = Color(0xFF060D17),
            primary = Color(0xFF38BDF8),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF16273B),
            border = Color(0xFF38BDF8),
            secondary = Color(0xFF7DD3FC)
        ),
        OverlayTheme(
            name = "NEON MATRIX",
            background = Color(0xFF040E07),
            primary = Color(0xFF00FF66),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF0F2A16),
            border = Color(0xFF00FF66),
            secondary = Color(0xFF66FF99)
        )
    )

    val currentTheme = themes[themeIndex % themes.size]
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
                            onInsertCoin = onInsertCoin
                        )
                    }
                }
                
                val bottomSection: @Composable (Modifier) -> Unit = { mod ->
                    Column(modifier = mod) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Surface, RoundedCornerShape(28.dp))
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
                                            .background(if (isEsp32Online) Success else Color.Red, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            if (isEsp32Online) "HARDWARE CONTROLLER CONNECTED" else "HARDWARE CONTROLLER OFFLINE", 
                                            color = TextPrimary, 
                                            fontSize = 11.sp, 
                                            fontWeight = FontWeight.Bold, 
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            if (isEsp32Online) "Autonomous Discovery & Interlock Synchronized" else "Searching for ESP32 on network...", 
                                            color = TextTertiary, 
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
                                Icon(Icons.Filled.Palette, contentDescription = "Change Theme", tint = TextPrimary) 
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { showPinDialog = true }) { 
                                Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Security Vault", tint = TextPrimary) 
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
