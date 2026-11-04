package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * Home (PisoKids) flavor lock screen delegate.
 * Renders the gamified Dual-Hero Kids Quest lock screen hub.
 */
object ProductLockScreenDelegate {
    @Composable
    fun renderAlternativeLockScreen(
        context: Context,
        modifier: Modifier,
        onInsertCoin: () -> Unit,
        isWaiting: Boolean,
        coinsInserted: Int,
        paymentTimeout: Int,
        onDoneClick: () -> Unit,
        isEsp32Online: Boolean,
        pricePerCoin: Double,
        minutesPerCoin: Int,
        batteryStatus: BatteryStatus,
        heldSeconds: Int,
    ): Boolean {
        var showPinDialog by remember { mutableStateOf(false) }
        var showSecurityDialog by remember { mutableStateOf(false) }
        var showEmergencyRecoveryDialog by remember { mutableStateOf(false) }

        KidsQuestLockScreen(
            onInsertCoin = onInsertCoin,
            isWaiting = isWaiting,
            coinsInserted = coinsInserted,
            paymentTimeout = paymentTimeout,
            onDoneClick = onDoneClick,
            isEsp32Online = isEsp32Online,
            pricePerCoin = pricePerCoin,
            minutesPerCoin = minutesPerCoin,
            batteryStatus = batteryStatus,
            heldSeconds = heldSeconds,
            onOpenParentPin = { showPinDialog = true },
            modifier = modifier,
        )

        if (showPinDialog) {
            AdminAuthenticationDialog(
                context = context,
                onDismiss = { showPinDialog = false },
                onUnlockSuccess = {
                    showPinDialog = false
                    showSecurityDialog = true
                },
                onOpenEmergencyRecovery = {
                    showPinDialog = false
                    showEmergencyRecoveryDialog = true
                },
            )
        }
        if (showSecurityDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.92f)
                        .padding(12.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = TechNet.surface),
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        SecurityVaultView(
                            context = context,
                            onClose = { showSecurityDialog = false },
                            onOpenRecoveryHub = {
                                showSecurityDialog = false
                                showEmergencyRecoveryDialog = true
                            },
                        )
                    }
                }
            }
        }
        if (showEmergencyRecoveryDialog) {
            EmergencyRecoveryDialog(
                context = context,
                onClose = { showEmergencyRecoveryDialog = false },
            )
        }

        return true
    }
}
