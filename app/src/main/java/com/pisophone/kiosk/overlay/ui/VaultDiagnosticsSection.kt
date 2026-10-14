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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.BuildConfig
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.DiagnosticsLog
import kotlinx.coroutines.delay

private const val SHOWN_LINES = 40

@Composable
fun VaultDiagnosticsSection(context: Context, onShowHelp: (String, String) -> Unit) {
    var lines by remember { mutableStateOf(DiagnosticsLog.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            lines = DiagnosticsLog.snapshot()
        }
    }

    val header = "PisoPhone ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    val viewContext = LocalContext.current

    VaultCard {
        VaultCardTitle(
            title = "Diagnostics",
            onShowHelp = onShowHelp,
            helpText = "The most recent events of this phone. Copy them and send them to support when something does not work.",
        )
        VaultCaption(header)
        if (KioskSecurity.isAdminPinUnset(viewContext)) {
            VaultCaption(
                "No admin password is set yet. Pair this phone with its box to receive one.",
                modifier = Modifier.padding(top = 6.dp),
                color = TechNet.dangerText,
            )
        }
        if (KioskSecurity.usesLegacySharedSecret(viewContext)) {
            VaultCaption(
                "This phone still uses the old shared key. Set it up again from its box (box dashboard, Set up a phone).",
                modifier = Modifier.padding(top = 6.dp),
                color = TechNet.gold,
            )
        }
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TechNet.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            if (lines.isEmpty()) {
                VaultCaption("No events recorded yet.")
            } else {
                lines.takeLast(SHOWN_LINES).asReversed().forEach { line ->
                    Text(line, color = TechNet.text, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PisoButton(
                text = stringResource(R.string.ui_refresh),
                onClick = { lines = DiagnosticsLog.snapshot() },
                modifier = Modifier.weight(1f),
                style = PisoButtonStyle.Tonal,
                size = PisoButtonSize.Small,
            )
            PisoButton(
                text = stringResource(R.string.ui_copy),
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = header + "\n" + DiagnosticsLog.snapshot().joinToString("\n")
                    clipboard?.setPrimaryClip(ClipData.newPlainText("PisoPhone diagnostics", text))
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                size = PisoButtonSize.Small,
            )
        }
    }
}
