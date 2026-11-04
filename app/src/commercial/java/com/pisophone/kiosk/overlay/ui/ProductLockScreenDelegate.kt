package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pisophone.kiosk.model.BatteryStatus

/**
 * Commercial flavor lock screen delegate.
 * Returns false so the standard arcade kiosk lock screen is rendered.
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
    ): Boolean = false
}
