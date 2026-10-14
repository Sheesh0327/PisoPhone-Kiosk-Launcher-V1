package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.KioskService
import com.pisophone.kiosk.R

/**
 * The owner's quick tools: open the phone for five minutes, lock it, or add or remove time. Taking something away (locking,
 * removing time) asks first, and the question names what will happen.
 */
@Composable
fun VaultBypassSection(
    context: Context,
    onClose: () -> Unit,
    onShowHelp: (String, String) -> Unit,
    onConfirm: (VaultConfirm) -> Unit,
) {
    VaultCard {
        VaultCardTitle(
            title = "Quick tools",
            onShowHelp = onShowHelp,
            helpText = "Temporarily opens the phone past the lock screen, locks it again, or adds or removes paid session time.",
        )
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PisoButton(
                text = stringResource(R.string.ui_open_5_min),
                onClick = {
                    KioskService.triggerAdminBypass(context, 300)
                    onClose()
                },
                modifier = Modifier.weight(1f),
                style = PisoButtonStyle.Success,
                size = PisoButtonSize.Small,
                icon = Icons.Filled.LockOpen,
            )
            PisoButton(
                text = stringResource(R.string.ui_lock_phone),
                onClick = {
                    onConfirm(
                        VaultConfirm(
                            title = "Lock this phone now?",
                            message = "The lock screen comes back at once. Anyone using the phone is locked out.",
                            confirmLabel = "Lock phone",
                            action = {
                                KioskService.triggerLockSession(context)
                                onClose()
                            },
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                style = PisoButtonStyle.Danger,
                size = PisoButtonSize.Small,
                icon = Icons.Filled.Lock,
            )
        }

        Spacer(Modifier.height(16.dp))
        VaultCaption("Add time")
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, seconds) in listOf("+5 min" to 300, "+15 min" to 900, "+30 min" to 1800)) {
                PisoButton(
                    text = label,
                    onClick = { KioskService.triggerAdminTimeAdjust(context, seconds) },
                    modifier = Modifier.weight(1f),
                    style = PisoButtonStyle.Tonal,
                    size = PisoButtonSize.Small,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        VaultCaption("Remove time")
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, seconds) in listOf("5 min" to 300, "15 min" to 900)) {
                PisoButton(
                    text = "-$label",
                    onClick = {
                        onConfirm(
                            VaultConfirm(
                                title = "Remove $label from this phone?",
                                message = "The customer's remaining time becomes $label shorter.",
                                confirmLabel = "Remove $label",
                                action = { KioskService.triggerAdminTimeAdjust(context, -seconds) },
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    style = PisoButtonStyle.Outline,
                    size = PisoButtonSize.Small,
                )
            }
            Column(modifier = Modifier.weight(1f)) {}
        }
    }
}
