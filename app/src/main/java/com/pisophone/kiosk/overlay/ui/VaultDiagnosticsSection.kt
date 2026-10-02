package com.pisophone.kiosk.overlay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.pisophone.kiosk.util.DiagnosticsLog
import kotlinx.coroutines.delay

private const val SHOWN_LINES = 40

@Composable
fun VaultDiagnosticsSection(context: Context) {
    var lines by remember { mutableStateOf(DiagnosticsLog.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            lines = DiagnosticsLog.snapshot()
        }
    }

    val header = "PisoPhone ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Diagnostics", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(header, color = Color(0xFFA6ADC8), fontSize = 10.sp)
        if (com.pisophone.kiosk.security.KioskSecurity.isAdminPinDefault(androidx.compose.ui.platform.LocalContext.current)) {
            Text("Warning: the default admin PIN is still active. Change it before going live.", color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .padding(8.dp)
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
                onClick = { lines = DiagnosticsLog.snapshot() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(32.dp)
            ) {
                Text("Refresh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = header + "\n" + DiagnosticsLog.snapshot().joinToString("\n")
                    clipboard?.setPrimaryClip(ClipData.newPlainText("PisoPhone diagnostics", text))
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(32.dp)
            ) {
                Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
