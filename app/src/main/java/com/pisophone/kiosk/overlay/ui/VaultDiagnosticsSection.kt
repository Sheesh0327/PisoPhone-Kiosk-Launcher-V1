package com.pisophone.kiosk.overlay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.BuildConfig
import com.pisophone.kiosk.network.KioskWifi
import com.pisophone.kiosk.util.DiagnosticsLog
import com.pisophone.kiosk.util.KioskHealth
import kotlinx.coroutines.delay

private const val SHOWN_LINES = 40

/** The phone's Wi-Fi right now; the signal is read without the location permission (the network name is not). */
@Suppress("DEPRECATION")
private fun currentWifi(context: Context): KioskHealth.Wifi {
    val address = KioskWifi.wifiAddress()
    val rssi = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.rssi
    } catch (_: Exception) {
        null
    }
    return KioskHealth.Wifi(address, rssi?.takeIf { address.isNotBlank() && it > -127 && it < 0 })
}

private fun healthNow(context: Context) = KioskHealth.summarize(System.currentTimeMillis(), KioskHealth.snapshot(), currentWifi(context))

private fun levelLabel(level: KioskHealth.Level) = when (level) {
    KioskHealth.Level.OK -> "OK"
    KioskHealth.Level.WARN -> "CHECK"
    KioskHealth.Level.PROBLEM -> "PROBLEM"
}

private fun levelColor(level: KioskHealth.Level) = when (level) {
    KioskHealth.Level.OK -> Color(0xFF22C55E)
    KioskHealth.Level.WARN -> Color(0xFFFFB86C)
    KioskHealth.Level.PROBLEM -> Color(0xFFFF6B6B)
}

@Composable
fun VaultDiagnosticsSection(context: Context) {
    var lines by remember { mutableStateOf(DiagnosticsLog.snapshot()) }
    var health by remember { mutableStateOf(healthNow(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            lines = DiagnosticsLog.snapshot()
            health = healthNow(context)
        }
    }

    val header = "PisoPhone ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Diagnostics", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(header, color = Color(0xFFA6ADC8), fontSize = 10.sp)
        if (com.pisophone.kiosk.security.KioskSecurity.isAdminPinUnset(androidx.compose.ui.platform.LocalContext.current)) {
            Text("Warning: no admin PIN is set yet. Pair this phone with its box to receive one.", color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (com.pisophone.kiosk.security.KioskSecurity.usesLegacySharedSecret(androidx.compose.ui.platform.LocalContext.current)) {
            Text("Notice: this phone still uses the old shared key. Provision it with its box's secret (box dashboard > Install & Provision).", color = Color(0xFFFFB86C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Text("Health: ${levelLabel(health.level)}", color = levelColor(health.level), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            health.lines.forEach { line ->
                Text(line, color = Color(0xFFE2E8F0), fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            if (lines.isEmpty()) {
                Text("No events recorded yet.", color = Color(0xFFA6ADC8), fontSize = 10.sp)
            } else {
                lines.takeLast(SHOWN_LINES).asReversed().forEach { line ->
                    Text(line, color = Color(0xFFCBD5E1), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    lines = DiagnosticsLog.snapshot()
                    health = healthNow(context)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(32.dp),
            ) {
                Text("Refresh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val now = healthNow(context)
                    val summary = "Health: ${levelLabel(now.level)}\n" + now.lines.joinToString("\n")
                    val text = header + "\n" + summary + "\n\n" + DiagnosticsLog.snapshot().joinToString("\n")
                    clipboard?.setPrimaryClip(ClipData.newPlainText("PisoPhone diagnostics", text))
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(32.dp),
            ) {
                Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
