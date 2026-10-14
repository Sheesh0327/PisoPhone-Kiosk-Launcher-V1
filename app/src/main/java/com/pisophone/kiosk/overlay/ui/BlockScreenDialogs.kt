package com.pisophone.kiosk.overlay.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.CardScanActivity
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * The owner's way in from the lock screen: the admin password, and under it the two recovery doors (the recovery hub, which asks
 * for the password too, and the recovery code scan, which needs no password because the code itself must be signed by the owner).
 */
@Composable
fun AdminAuthenticationDialog(
    context: Context,
    onDismiss: () -> Unit,
    onUnlockSuccess: (String) -> Unit,
    onOpenEmergencyRecovery: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .background(TechNet.surfaceHi, RoundedCornerShape(28.dp))
                .border(1.dp, TechNet.line, RoundedCornerShape(28.dp))
                .padding(20.dp),
        ) {
            OwnerSignIn(
                context = context,
                onDismiss = onDismiss,
                onUnlock = { onUnlockSuccess(KioskSecurity.getSharedSecret(context)) },
                extra = { enteredPassword, wrongPassword ->
                    Column {
                        PisoButton(
                            text = stringResource(R.string.ui_system_recovery),
                            onClick = {
                                if (KioskSecurity.verifyAdminPin(context, enteredPassword)) {
                                    onOpenEmergencyRecovery()
                                } else {
                                    wrongPassword()
                                }
                            },
                            style = PisoButtonStyle.Danger,
                            size = PisoButtonSize.Small,
                            icon = Icons.Filled.Warning,
                        )
                        Spacer(Modifier.height(6.dp))
                        PisoButton(
                            text = stringResource(R.string.ui_scan_a_recovery_code),
                            onClick = {
                                // No password needed: the code itself has to be signed with the owner key.
                                try {
                                    context.startActivity(
                                        Intent(context, CardScanActivity::class.java)
                                            .putExtra(CardScanActivity.EXTRA_RECOVERY, true)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (_: Exception) {
                                }
                                onDismiss()
                            },
                            style = PisoButtonStyle.Outline,
                            size = PisoButtonSize.Small,
                        )
                    }
                },
            )
        }
    }
}
