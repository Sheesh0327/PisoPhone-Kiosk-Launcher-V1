package com.pisophone.kiosk.provisioning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ProvisioningPairingScreen(onRetry: () -> Unit) {
    var isRetrying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isRetrying = true
        onRetry()
        // Simulate a slight delay to avoid flashing UI
        kotlinx.coroutines.delay(1000)
        isRetrying = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Finalizing Pairing",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Android provisioning complete.\nWaiting for authenticated slot pairing with the ESP32...",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                isRetrying = true
                onRetry()
                isRetrying = false
            },
            enabled = !isRetrying
        ) {
            Text(if (isRetrying) "Pairing..." else "Retry Pairing")
        }
    }
}
