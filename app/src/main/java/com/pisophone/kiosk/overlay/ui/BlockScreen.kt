package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.ActivationCelebrationDialog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

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
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    onThemeChange: () -> Unit = {},
    buttonText: String = "READY FOR COIN",
    isUnlicensed: Boolean = false,
    macAddress: String = "",
    onActivateClick: (String) -> Unit = {},
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
        // 0: PisoPhone Obsidian (Official Website Identity)
        OverlayTheme(
            name = "PISOPHONE OBSIDIAN",
            background = Color(0xFF060B14),
            primary = Color(0xFF10B981),
            onPrimary = Color(0xFF020617),
            surface = Color(0xFF0F172A),
            border = Color(0xFF1E293B),
            secondary = Color(0xFF34D399)
        ),
        // 1: Ultraviolet Arcade
        OverlayTheme(
            name = "ULTRA VIOLET",
            background = Color(0xFF0F061E),
            primary = Color(0xFFB026FF),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF221140),
            border = Color(0xFFB026FF),
            secondary = Color(0xFFFFB800)
        ),
        // 2: Matrix Emerald
        OverlayTheme(
            name = "MATRIX LIME",
            background = Color(0xFF04120B),
            primary = Color(0xFF00FF88),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF0C2B1D),
            border = Color(0xFF00FF88),
            secondary = Color(0xFF00F5D4)
        ),
        // 3: Solar Flare
        OverlayTheme(
            name = "SOLAR FLARE",
            background = Color(0xFF140804),
            primary = Color(0xFFFF6600),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF2A140B),
            border = Color(0xFFFF6600),
            secondary = Color(0xFFFFD600)
        ),
        // 4: Crimson Nova
        OverlayTheme(
            name = "CRIMSON NOVA",
            background = Color(0xFF120509),
            primary = Color(0xFFFF2A5F),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF2C111C),
            border = Color(0xFFFF2A5F),
            secondary = Color(0xFFFF6488)
        ),
        // 5: Electric Sunset
        OverlayTheme(
            name = "ELECTRIC SUNSET",
            background = Color(0xFF130410),
            primary = Color(0xFFFF007F),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xFF2D1027),
            border = Color(0xFFFF007F),
            secondary = Color(0xFFFF66B2)
        ),
        // 6: Arctic Frost
        OverlayTheme(
            name = "ARCTIC FROST",
            background = Color(0xFF060D17),
            primary = Color(0xFF38BDF8),
            onPrimary = Color(0xFF000000),
            surface = Color(0xFF16273B),
            border = Color(0xFF38BDF8),
            secondary = Color(0xFF7DD3FC)
        ),
        // 7: Neon Matrix
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
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var currentSecretKey by remember { mutableStateOf(KioskSecurity.getSharedSecret(context)) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var licenseInfo by remember { mutableStateOf(HardwareLockManager.getLicenseInfo(context)) }
    var showActivationCelebration by remember { mutableStateOf(false) }

    val licenseUpdateVer by HardwareLockManager.licenseUpdateVersion.collectAsState()
    val celebrationTrigger by HardwareLockManager.activationCelebrationEvent.collectAsState()

    LaunchedEffect(licenseUpdateVer) {
        licenseInfo = HardwareLockManager.getLicenseInfo(context)
    }

    LaunchedEffect(celebrationTrigger) {
        if (celebrationTrigger) {
            licenseInfo = HardwareLockManager.getLicenseInfo(context)
            showActivationCelebration = true
        }
    }

    // Update licenseInfo once every 10 seconds instead of every single second, saving recompositions.
    LaunchedEffect(Unit) {
        while (true) {
            licenseInfo = HardwareLockManager.getLicenseInfo(context)
            delay(10000)
        }
    }

    @Composable
    fun BlockScreenTimeHeader(batteryStatus: BatteryStatus, themeTextPrimary: Color) {
        var currentTimeStr by remember { mutableStateOf("") }
        
        LaunchedEffect(Unit) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            while (true) {
                currentTimeStr = timeFormat.format(Date())
                delay(1000)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .alpha(0.8f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                currentTimeStr,
                color = themeTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.5).sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = themeTextPrimary, modifier = Modifier.size(16.dp))
                Icon(Icons.Filled.SignalCellular4Bar, contentDescription = null, tint = themeTextPrimary, modifier = Modifier.size(16.dp))
                Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = themeTextPrimary, modifier = Modifier.size(16.dp))
                Text("${batteryStatus.level}%", color = themeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun DeviceTitleBadge(deviceIp: String, themePrimary: Color, themeTextPrimary: Color, themeTextTertiary: Color, themeSurfaceVariant: Color) {
        val ctx = LocalContext.current
        var customAlias by remember { mutableStateOf(KioskSecurity.getDeviceAlias(ctx)) }
        LaunchedEffect(Unit) {
            while(true) {
                customAlias = KioskSecurity.getDeviceAlias(ctx)
                delay(5000)
            }
        }
        val deviceNumber = remember(deviceIp) {
            try {
                val lastOctet = deviceIp.substringAfterLast(".").toIntOrNull()
                if (lastOctet != null && lastOctet in 100..120) {
                    (lastOctet - 99).toString()
                } else if (lastOctet != null && lastOctet in 1..254) {
                    lastOctet.toString()
                } else {
                    "1"
                }
            } catch (e: Exception) {
                "1"
            }
        }
        val mainTitle = if (customAlias.isNotBlank()) customAlias else "PisoPhone $deviceNumber"

        Text(
            mainTitle,
            color = themeTextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        // Device IP & System Badge reflecting ESP32 Web Page config
        Surface(
            color = themeSurfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (deviceIp != "127.0.0.1") themePrimary else Color.Gray, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (customAlias.isNotBlank()) "$mainTitle • IP: $deviceIp" else "IP: $deviceIp",
                    color = themeTextTertiary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    Box(
        modifier = modifier.background(Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
            .systemBarsPadding()
        ) {
            // Top Bar
            BlockScreenTimeHeader(batteryStatus = batteryStatus, themeTextPrimary = TextPrimary)

            // Battery Alert Banner
            if (!isUnlicensed && batteryStatus.alertState != BatteryAlertState.NONE) {
                BatteryAlertBanner(batteryStatus = batteryStatus)
            }

            // Scrollable Area
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isWide = maxWidth > 600.dp || isLandscape
                
                val mainContent: @Composable (Modifier) -> Unit = { mod ->
                    Column(
                        modifier = mod,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Lock Icon Container
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

                        DeviceTitleBadge(deviceIp, Primary, TextPrimary, TextTertiary, SurfaceVariant)

                        Text(
                            if (isWaiting) "Coins inserted: $coinsInserted" else "Insert a coin to unlock all applications for a $minutesPerCoin-minute session.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)
                        )

                        // Info Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Surface, RoundedCornerShape(24.dp))
                                .border(1.dp, Border, RoundedCornerShape(24.dp))
                                .padding(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column {
                                    Text(
                                        "STANDARD RATE",
                                        color = Primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(String.format(java.util.Locale.US, "%.2f PHP", pricePerCoin), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                                        Text("/ unit", color = TextTertiary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .background(SurfaceVariant, RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                ) {
                                    Icon(Icons.Filled.Token, contentDescription = null, tint = Primary)
                                }
                            }

                            // Alert Info Box
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Background, RoundedCornerShape(16.dp))
                                    .border(1.dp, Border, RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                                    .padding(bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = Success, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    if (isWaiting) "Waiting for coins from coinslot..." else "Device will auto-lock when timer expires. Save all work and remove account credentials before end of session.",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            
                            if (isWaiting && coinsInserted > 0) {
                                Button(
                                    onClick = onDoneClick,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Success, contentColor = Color.Black),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("DONE (${paymentTimeout}s)", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
                                }
                            } else if (!isWaiting) {
                                val activeContainerColor = if (isSlotBusy) Color(0xFFDC3545) else if (isEsp32Online) Primary else SurfaceVariant
                                val activeContentColor = if (isSlotBusy) Color.White else if (isEsp32Online) OnPrimary else TextTertiary
                                val activeText = if (isSlotBusy) "COINSLOT BUSY" else if (isEsp32Online) buttonText else "CONNECTING TO COINSLOT..."
                                Button(
                                    onClick = onInsertCoin,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = activeContainerColor, 
                                        contentColor = activeContentColor,
                                        disabledContainerColor = activeContainerColor,
                                        disabledContentColor = activeContentColor
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    enabled = isEsp32Online && !isSlotBusy
                                ) {
                                    if (isSlotBusy) {
                                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White)
                                    } else if (isEsp32Online) {
                                        Icon(Icons.Filled.AddCircle, contentDescription = null)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(activeText, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
                                }
                            } else {
                                Button(
                                    onClick = {},
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SurfaceVariant, 
                                        contentColor = TextPrimary,
                                        disabledContainerColor = SurfaceVariant,
                                        disabledContentColor = TextPrimary
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    enabled = false
                                ) {
                                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("${paymentTimeout}s WAITING...", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
                                }
                            }
                        }
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
                            IconButton(onClick = onThemeChange) { Icon(Icons.Filled.Palette, contentDescription = "Change Theme", tint = TextPrimary) }
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { showPinDialog = true }) { Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Security Vault", tint = TextPrimary) }
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                            mainContent(Modifier.padding(top = 16.dp))
                        }
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                            bottomSection(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        mainContent(Modifier.padding(horizontal = 24.dp).padding(top = 16.dp))
                        bottomSection(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
                    }
                }
            }

            if (showPinDialog) {
                val pinFocusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current

                LaunchedEffect(Unit) {
                    delay(150)
                    pinFocusRequester.requestFocus()
                    keyboardController?.show()
                }

                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Admin Authentication", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 20.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Enter Master Admin Password:", color = TextSecondary, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = enteredPin,
                                onValueChange = { if (it.length <= 32) enteredPin = it },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                isError = pinError,
                                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Border
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(pinFocusRequester)
                            )
                            if (pinError) {
                                Text("Invalid password. Default is 1234", color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { 
                                    showPinDialog = false
                                    enteredPin = ""
                                    pinError = false
                                }) { Text("Cancel") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    if (KioskSecurity.verifyAdminPin(context, enteredPin)) {
                                        showPinDialog = false
                                        enteredPin = ""
                                        pinError = false
                                        currentSecretKey = KioskSecurity.getSharedSecret(context)
                                        showSecurityDialog = true
                                    } else {
                                        pinError = true
                                    }
                                }) { Text("Unlock") }
                            }
                        }
                    }
                }
            }

            if (showSecurityDialog) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f).padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            SecurityVaultView(
                                context = context,
                                onClose = { showSecurityDialog = false }
                            )
                        }
                    }
                }
            }
            
            if (isUnlicensed) {
                var activationCode by remember { mutableStateOf("") }
                Box(
                    modifier = Modifier.fillMaxSize().background(Background).clickable(enabled = false) {},
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 100.dp, start = 32.dp, end = 32.dp).background(Surface, RoundedCornerShape(24.dp)).border(2.dp, Border, RoundedCornerShape(24.dp)).padding(32.dp)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Color.Red, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("DEVICE ACTIVATION REQUIRED", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("This Piso phone machine is unlicensed.", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("Hardware ID (MAC):", color = TextSecondary, fontSize = 12.sp)
                        Text(macAddress.ifEmpty { "UNKNOWN" }, color = Primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(24.dp))
                        
                        OutlinedTextField(
                            value = activationCode,
                            onValueChange = { activationCode = it.trim().uppercase() },
                            placeholder = { Text("e.g. PISO-XXXX-XXXX", color = TextSecondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Border,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { onActivateClick(activationCode) },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("ACTIVATE MACHINE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (showActivationCelebration) {
                ActivationCelebrationDialog(
                    licenseInfo = licenseInfo,
                    onDismiss = {
                        showActivationCelebration = false
                        HardwareLockManager.activationCelebrationEvent.value = false
                    }
                )
            }
        }
    }
}
