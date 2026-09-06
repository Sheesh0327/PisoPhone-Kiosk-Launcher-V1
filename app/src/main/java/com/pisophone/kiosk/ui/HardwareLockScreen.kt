

package com.pisophone.kiosk.ui
import kotlinx.coroutines.isActive

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pisophone.kiosk.MainActivity
import com.pisophone.kiosk.receiver.KioskAdminActionReceiver
import com.pisophone.kiosk.security.HardwareLockManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HardwareLockScreen(
    onRebindSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showAdminDialog by remember { mutableStateOf(false) }
    var showActivationCodeDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var checkServerCooldown by remember { mutableLongStateOf(0L) }

    var showCelebrationDialog by remember { mutableStateOf(false) }
    var activatedLicenseInfo by remember { mutableStateOf<HardwareLockManager.LicenseInfo?>(null) }

    var licenseInfo by remember { mutableStateOf(HardwareLockManager.getLicenseInfo(context)) }
    val currentHwId = licenseInfo.hardwareId
    val currentDevName = licenseInfo.deviceName

    val isUnactivated = licenseInfo.state == HardwareLockManager.LicenseState.UNACTIVATED
    val isExpiredLicense = licenseInfo.state == HardwareLockManager.LicenseState.EXPIRED_LOCKED

    val coroutineScope = rememberCoroutineScope()
    var isCheckingServer by remember { mutableStateOf(false) }

    // Helper: apply activation and show celebratory confirmation before restart
    fun applyActivation(licenseKey: String) {
        val trimmed = licenseKey.trim()
        if (trimmed.isNotBlank()) {
            val activated = HardwareLockManager.activateOneYearLicense(context, trimmed)
            if (activated) {
                showActivationCodeDialog = false
                val updatedLicense = HardwareLockManager.getLicenseInfo(context)
                licenseInfo = updatedLicense
                activatedLicenseInfo = updatedLicense
                showCelebrationDialog = true
            } else {
                Toast.makeText(context, "❌ Invalid License Key or Device Mismatch.", Toast.LENGTH_LONG).show()
                errorMessage = "Invalid License Key or Device Mismatch. Please check the code."
                showActivationCodeDialog = true
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            applyActivation(result.contents)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060B14),
                        Color(0xFF0B1120),
                        Color(0xFF0F172A)
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lock Icon Badge
            val badgeBgColor = when {
                isUnactivated -> Color(0x2210B981)
                isExpiredLicense -> Color(0x22F59E0B)
                else -> Color(0x22EF4444)
            }
            val badgeBorderColor = when {
                isUnactivated -> Color(0xFF10B981)
                isExpiredLicense -> Color(0xFFF59E0B)
                else -> Color(0xFFEF4444)
            }
            val badgeIconTint = when {
                isUnactivated -> Color(0xFF10B981)
                isExpiredLicense -> Color(0xFFF59E0B)
                else -> Color(0xFFEF4444)
            }

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(badgeBgColor, CircleShape)
                    .border(1.5.dp, badgeBorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isUnactivated) Icons.Filled.Usb else Icons.Filled.Lock,
                    contentDescription = "Device Lock",
                    tint = badgeIconTint,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = when {
                    isUnactivated -> "ACTIVATION REQUIRED"
                    isExpiredLicense -> "LICENSE EXPIRED"
                    else -> "HARDWARE LOCK ACTIVE"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = when {
                    isUnactivated -> Color(0xFF10B981)
                    isExpiredLicense -> Color(0xFFFBBF24)
                    else -> Color(0xFFF87171)
                },
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isUnactivated -> "Connect via USB or Enter License Key"
                    isExpiredLicense -> "Commercial License Required to Continue"
                    else -> "Unauthorized Device Detected"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isUnactivated ->
                        "Connect this phone via USB cable to your computer. Open the PisoPhone portal (web-pisophone.pages.dev/activate) to activate with 1 click, or enter your license key below."
                    isExpiredLicense ->
                        "Your 1-year commercial license on this hardware has ended. Activate a renewal license key to unlock."
                    else ->
                        "This application is cryptographically sealed to its authorized phone hardware. Copying or cloning this app to an unauthorized device is prohibited."
                },
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Diagnostic Hardware Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isUnactivated) Icons.Filled.Usb else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = when {
                                isUnactivated -> Color(0xFF10B981)
                                isExpiredLicense -> Color(0xFFF59E0B)
                                else -> Color(0xFFEF4444)
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isUnactivated -> "Status: Pending USB Activation"
                                isExpiredLicense -> "Hardware Status: License Expired"
                                else -> "Hardware Signature Mismatch"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isUnactivated -> Color(0xFF10B981)
                                isExpiredLicense -> Color(0xFFF59E0B)
                                else -> Color(0xFFEF4444)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF1E293B))

                    // Current Device
                    Text("HARDWARE IDENTIFIER (THIS PHONE):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Text(currentDevName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            currentHwId,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(currentHwId))
                                Toast.makeText(context, "Device ID copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy Device ID",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bound Device
                    Text("LICENSE STATUS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Text(
                        when {
                            isUnactivated -> "Awaiting WebADB USB Activation (1-Click)"
                            isExpiredLicense -> "Expired (Renewal required)"
                            else -> "Unbound / Mismatch"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isUnactivated) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Option 1: Scan QR Code (Primary button)
            Button(
                onClick = {
                    val options = ScanOptions()
                    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    options.setPrompt("Scan PisoPhone Activation QR Code")
                    options.setCameraId(0)
                    options.setBeepEnabled(true)
                    options.setBarcodeImageEnabled(false)
                    options.setOrientationLocked(false)
                    scanLauncher.launch(options)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("scan_qr_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF020617)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color(0xFF020617))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Scan Activation QR Code",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF020617)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Tip: You can also activate instantly via USB directly from your dashboard.",
                fontSize = 12.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF334155))
                Text("Fallback Options", fontSize = 11.sp, color = Color(0xFF64748B), modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF334155))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(
                    onClick = {
                        codeInput = ""
                        errorMessage = null
                        showActivationCodeDialog = true
                    }
                ) {
                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Manual Code",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                }

                TextButton(
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now < checkServerCooldown) {
                            val remaining = (checkServerCooldown - now) / 1000
                            Toast.makeText(context, "Checking... Please wait ${remaining}s.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        
                        if (!isCheckingServer) {
                            isCheckingServer = true
                            checkServerCooldown = now + 60_000L // 60s cooldown
                            
                            coroutineScope.launch {
                                val syncSuccess = HardwareLockManager.syncWithBackend(context)
                                val updated = HardwareLockManager.getLicenseInfo(context)
                                licenseInfo = updated
                                isCheckingServer = false
                                if (updated.state == HardwareLockManager.LicenseState.PAID_ACTIVE) {
                                    Toast.makeText(context, "✅ License active! Unlocking...", Toast.LENGTH_SHORT).show()
                                    onRebindSuccess()
                                } else if (updated.state == HardwareLockManager.LicenseState.EXPIRED_LOCKED) {
                                    Toast.makeText(context, "⚠️ Subscription is expired on server. Please renew on website.", Toast.LENGTH_LONG).show()
                                } else {
                                    if (syncSuccess) {
                                        Toast.makeText(context, "Server checked: Device registered. Activation required.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Server check deferred (offline). Awaiting WebADB or network.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (isCheckingServer) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF94A3B8), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Checking...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Fetch via Wi-Fi",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Option 3: Admin Authorization Button
            OutlinedButton(
                onClick = {
                    pinInput = ""
                    errorMessage = null
                    showAdminDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("admin_rebind_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF94A3B8))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Admin Diagnostics & Reset",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFCBD5E1)
                )
            }
        }
    }

    // License Activation Dialog
    if (showActivationCodeDialog) {
        Dialog(
            onDismissRequest = { showActivationCodeDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Key,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Enter License Key",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Paste or enter your 1-year commercial license key to permanently activate this device.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it
                            errorMessage = null
                        },
                        placeholder = { Text("PISO-1Y.HW-XXXX-XXXX...", color = Color(0xFF64748B), fontSize = 12.sp) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        )
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 12.sp,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showActivationCodeDialog = false },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                if (codeInput.isNotBlank()) {
                                    applyActivation(codeInput)
                                } else {
                                    errorMessage = "Please enter a valid activation code."
                                }
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Activate", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }
        }
    }

    // Admin Rebind Dialog
    if (showAdminDialog) {
        Dialog(
            onDismissRequest = { showAdminDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.AdminPanelSettings,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Administrator Authorization",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Enter Admin Password to re-seal and authorize this phone hardware.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 32) {
                                pinInput = it
                                errorMessage = null
                            }
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        placeholder = { Text("Enter Admin Password", color = Color(0xFF64748B)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_pin_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        )
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 12.sp,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAdminDialog = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                if (HardwareLockManager.rebindWithAdminPin(context, pinInput)) {
                                    val updated = HardwareLockManager.getLicenseInfo(context)
                                    licenseInfo = updated
                                    showAdminDialog = false
                                    if (HardwareLockManager.isAppAllowedToRun(context)) {
                                        Toast.makeText(context, "✅ Device authorized and bound successfully!", Toast.LENGTH_LONG).show()
                                        onRebindSuccess()
                                    } else {
                                        Toast.makeText(context, "✅ Hardware seal updated. License activation required.", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    errorMessage = "Invalid Admin Password. Authorization rejected."
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("admin_confirm_rebind_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Authorize", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // License Activation Celebration Dialog
    if (showCelebrationDialog) {
        val isTutorialDone = remember { HardwareLockManager.isTutorialCompleted(context) }
        ActivationCelebrationDialog(
            licenseInfo = activatedLicenseInfo ?: licenseInfo,
            isTutorialCompleted = isTutorialDone,
            onDismiss = {
                showCelebrationDialog = false
                HardwareLockManager.activationCelebrationEvent.value = false
                onRebindSuccess()
            }
        )
    }
}
