package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FloatingPillSecurityOverlay(
    context: Context,
    showPinDialog: Boolean,
    showSecurityDialog: Boolean,
    outlineColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    onDismissPin: () -> Unit,
    onPinSuccess: () -> Unit,
    onCloseSecurity: () -> Unit
) {
    if (!showPinDialog && !showSecurityDialog) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        if (showPinDialog) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 420.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    FloatingPillAdminAuthCard(
                        context = context,
                        outlineColor = outlineColor,
                        primaryColor = primaryColor,
                        onPrimaryColor = onPrimaryColor,
                        onDismiss = onDismissPin,
                        onUnlockSuccess = onPinSuccess
                    )
                }
            }
        } else if (showSecurityDialog) {
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
                        onClose = onCloseSecurity
                    )
                }
            }
        }
    }
}
