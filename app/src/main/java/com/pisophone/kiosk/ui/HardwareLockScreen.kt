package com.pisophone.kiosk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pisophone.kiosk.MainActivity
import com.pisophone.kiosk.receiver.KioskAdminActionReceiver
import com.pisophone.kiosk.security.HardwareLockManager

@Composable
fun HardwareLockScreen(
    onRebindSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAdminDialog by remember { mutableStateOf(false) }
    var showActivationCodeDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val licenseInfo = remember { HardwareLockManager.getLicenseInfo(context) }
    val currentHwId = remember { licenseInfo.hardwareId }
    val currentDevName = remember { licenseInfo.deviceName }

    val isUnactivated = licenseInfo.state == HardwareLockManager.LicenseState.UNACTIVATED
    val isExpiredLicense = licenseInfo.state == HardwareLockManager.LicenseState.EXPIRED_LOCKED

    // Helper: apply activation and restart application to guarantee clean state
    fun applyActivationAndRestart(scannedLicenseKey: String) {
        val trimmed = scannedLicenseKey.trim()
        if (trimmed.isNotBlank()) {
            val activated = HardwareLockManager.activateOneYearLicense(context, trimmed)
            if (activated) {
                Toast.makeText(context, "✅ 1-Year License activated! Restarting app...", Toast.LENGTH_LONG).show()
                showActivationCodeDialog = false
                onRebindSuccess()

                // Trigger application restart via broadcast to ensure kiosk services reload cleanly
                try {
                    val restartIntent = Intent(KioskAdminActionReceiver.ACTION_RESTART).apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(restartIntent)

                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(mainIntent)
                } catch (e: Exception) {
                    // Fallback to onRebindSuccess
                }
            } else {
                Toast.makeText(context, "❌ Invalid License QR code or signature mismatch.", Toast.LENGTH_LONG).show()
                errorMessage = "Invalid License Key or Device Mismatch. Please check the QR code."
                showActivationCodeDialog = true
            }
        }
    }

    // QR Code scanner launcher using zxing ScanContract
    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            val scannedContent = result.contents
            if (!scannedContent.isNullOrBlank()) {
                applyActivationAndRestart(scannedContent)
            }
        }
    )

    // Camera permission request launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Align PisoPhone Activation QR Code within frame")
                    setCameraId(0)
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                    setOrientationLocked(true)
                    setCaptureActivity(PisoQrScannerActivity::class.java)
                }
                qrScannerLauncher.launch(options)
            } else {
                Toast.makeText(context, "Camera permission required to scan QR code. You can also enter the code manually.", Toast.LENGTH_LONG).show()
                showActivationCodeDialog = true
            }
        }
    )

    fun startQrScan() {
        val hasCamPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCamPermission) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Align PisoPhone Activation QR Code within frame")
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PisoQrScannerActivity::class.java)
            }
            qrScannerLauncher.launch(options)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
                    imageVector = if (isUnactivated) Icons.Filled.QrCodeScanner else Icons.Filled.Lock,
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
                    isUnactivated -> "Ready to Link with PisoPhone Coin Hardware"
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
                        "Your hardware profile is securely registered. Scan your activation QR code from your web dashboard or enter the license key to unlock the kiosk."
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
                            if (isUnactivated) Icons.Filled.QrCodeScanner else Icons.Filled.Warning,
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
                                isUnactivated -> "Status: Pending License Activation"
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
                    Text(
                        currentHwId,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bound Device
                    Text("LICENSE STATUS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    Text(
                        when {
                            isUnactivated -> "Awaiting Activation (No USB/ADB required)"
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

            // Option 1: Scan License QR Code (Primary / Recommended)
            Button(
                onClick = {
                    startQrScan()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("scan_qr_code_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF020617)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR Code", modifier = Modifier.size(22.dp), tint = Color(0xFF020617))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Scan License QR Code",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF020617)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Option 2: Enter Code Manually (Fallback if camera is unavailable)
            OutlinedButton(
                onClick = {
                    codeInput = ""
                    errorMessage = null
                    showActivationCodeDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("activate_machine_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF10B981)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF10B981))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Enter Code Manually",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF10B981)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Admin Authorization Button (Option 3)
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
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Enter License Code",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Enter your purchase activation code or license token to permanently bind a 1-Year License to this device.",
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
                        placeholder = { Text("e.g. FULL-1YEAR-XXXX", color = Color(0xFF64748B)) },
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

                    // Shortcut to open camera scanner from inside dialog
                    TextButton(
                        onClick = {
                            showActivationCodeDialog = false
                            startQrScan()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Or scan via Camera QR Scanner", color = Color(0xFF38BDF8), fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

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
                                    applyActivationAndRestart(codeInput)
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
                                    Toast.makeText(context, "✅ Device authorized and bound successfully!", Toast.LENGTH_LONG).show()
                                    showAdminDialog = false
                                    onRebindSuccess()
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
}
