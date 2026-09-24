package com.pisophone.kiosk.overlay

import android.content.Context
import com.pisophone.kiosk.model.BatteryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class KioskOverlay(
    private val context: Context,
    private val appStateFlow: StateFlow<Int>,
    private val sessionTimeFlow: StateFlow<Int>,
    private val paymentTimeoutFlow: StateFlow<Int>,
    private val coinsInsertedFlow: StateFlow<Int>,
    private val themeIndexFlow: StateFlow<Int>,
    private val isEsp32OnlineFlow: StateFlow<Boolean>,
    private val esp32MacAddressFlow: StateFlow<String> = MutableStateFlow(""),
    private val isSlotBusyFlow: StateFlow<Boolean> = MutableStateFlow(false),
    private val pricePerCoinFlow: StateFlow<Double>,
    private val minutesPerCoinFlow: StateFlow<Int>,
    private val deviceIpFlow: StateFlow<String> = MutableStateFlow("127.0.0.1"),
    private val slotNumberFlow: StateFlow<Int> = MutableStateFlow(1),
    private val batteryStatusFlow: StateFlow<BatteryStatus> = MutableStateFlow(BatteryStatus()),
    private val slotWarningDaysLeftFlow: StateFlow<Int?> = MutableStateFlow(null),
    private val isSlotExpiredFlow: StateFlow<Boolean> = MutableStateFlow(false),
    private val slotExpiryReasonFlow: StateFlow<String> = MutableStateFlow(""),
    private val isArenaModeFlow: StateFlow<Boolean> = MutableStateFlow(false),
    private val arenaPlayerRoleFlow: StateFlow<Int> = MutableStateFlow(0),
    private val arenaStakeMinutesFlow: StateFlow<Int> = MutableStateFlow(15),
    private val isArenaBannerVisibleFlow: StateFlow<Boolean> = MutableStateFlow(false),
    private val onDismissArenaBanner: () -> Unit = {},
    private val onInsertCoinClick: () -> Unit,
    private val onDoneClick: () -> Unit,
    private val onCancelClick: () -> Unit = {},
    private val onThemeChange: () -> Unit,
    private val onActivateClick: (String) -> Unit = {}
) {
    private val lockScreenOverlay = LockScreenOverlay(
        context = context,
        appStateFlow = appStateFlow,
        paymentTimeoutFlow = paymentTimeoutFlow,
        coinsInsertedFlow = coinsInsertedFlow,
        themeIndexFlow = themeIndexFlow,
        isEsp32OnlineFlow = isEsp32OnlineFlow,
        esp32MacAddressFlow = esp32MacAddressFlow,
        isSlotBusyFlow = isSlotBusyFlow,
        pricePerCoinFlow = pricePerCoinFlow,
        minutesPerCoinFlow = minutesPerCoinFlow,
        deviceIpFlow = deviceIpFlow,
        slotNumberFlow = slotNumberFlow,
        batteryStatusFlow = batteryStatusFlow,
        slotWarningDaysLeftFlow = slotWarningDaysLeftFlow,
        isSlotExpiredFlow = isSlotExpiredFlow,
        slotExpiryReasonFlow = slotExpiryReasonFlow,
        isArenaModeFlow = isArenaModeFlow,
        arenaPlayerRoleFlow = arenaPlayerRoleFlow,
        arenaStakeMinutesFlow = arenaStakeMinutesFlow,
        onInsertCoinClick = onInsertCoinClick,
        onDoneClick = onDoneClick,
        onCancelClick = onCancelClick,
        onThemeChange = onThemeChange,
        onActivateClick = onActivateClick
    )
    private val floatingPillOverlay = FloatingPillOverlay(
        context = context,
        appStateFlow = appStateFlow,
        sessionTimeFlow = sessionTimeFlow,
        paymentTimeoutFlow = paymentTimeoutFlow,
        coinsInsertedFlow = coinsInsertedFlow,
        themeIndexFlow = themeIndexFlow,
        isEsp32OnlineFlow = isEsp32OnlineFlow,
        isSlotBusyFlow = isSlotBusyFlow,
        pricePerCoinFlow = pricePerCoinFlow,
        minutesPerCoinFlow = minutesPerCoinFlow,
        batteryStatusFlow = batteryStatusFlow,
        isArenaModeFlow = isArenaModeFlow,
        arenaPlayerRoleFlow = arenaPlayerRoleFlow,
        arenaStakeMinutesFlow = arenaStakeMinutesFlow,
        isArenaBannerVisibleFlow = isArenaBannerVisibleFlow,
        onDismissArenaBanner = onDismissArenaBanner,
        onInsertCoinClick = onInsertCoinClick,
        onDoneClick = onDoneClick,
        onCancelClick = onCancelClick
    )
    
    fun show(): Boolean {
        val lockShown = lockScreenOverlay.show()
        floatingPillOverlay.show()
        return lockShown
    }

    fun isAttached(): Boolean {
        return lockScreenOverlay.isAttached()
    }
    
    fun remove() {
        lockScreenOverlay.remove()
        floatingPillOverlay.remove()
    }

    fun onScreenWake() {
        lockScreenOverlay.onScreenWake()
        floatingPillOverlay.onScreenWake()
    }

    fun onScreenSleep() {
        lockScreenOverlay.onScreenSleep()
        floatingPillOverlay.onScreenSleep()
    }
}
