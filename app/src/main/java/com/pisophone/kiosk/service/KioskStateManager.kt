package com.pisophone.kiosk.service

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class KioskStateManager(private val context: Context) {
    companion object {
        private const val TAG = "KioskStateManager"
        private const val PREFS_NAME = "kiosk_persistent_state"
        const val ESP32_WEB_PORT = 8055
    }

    val appState = MutableStateFlow(0) // 0: block, 1: wait, 2: unlocked, 3: unlocked+wait, 4: unlicensed
    val sessionTimeRemaining = MutableStateFlow(0)
    val sessionExpiryDeadlineMs = MutableStateFlow(0L)
    val sessionRevision = MutableStateFlow(0L)
    val paymentTimeout = MutableStateFlow(0)
    val coinsInserted = MutableStateFlow(0)
    val themeIndex = MutableStateFlow(0)
    val deviceIp = MutableStateFlow("127.0.0.1")
    val deviceId = MutableStateFlow("")
    val isEsp32Online = MutableStateFlow(false)
    val esp32MacAddress = MutableStateFlow("")
    val isSlotBusy = MutableStateFlow(false)
    val pricePerCoin = MutableStateFlow(5.0)
    val minutesPerCoin = MutableStateFlow(30)
    var esp32Ip: String? = null
    val slotWarningDaysLeft = MutableStateFlow<Int?>(null)
    val isSlotExpired = MutableStateFlow(false)
    val slotExpiryMessage = MutableStateFlow("")
    val slotNumber = MutableStateFlow(0)
    val isArenaMode = MutableStateFlow(false)
    val arenaPlayerRole = MutableStateFlow(0) // 1: Player 1, 2: Player 2
    val arenaStakeMinutes = MutableStateFlow(15)
    val isArenaBannerVisible = MutableStateFlow(false)

    init {
        deviceIp.value = getLocalIpAddress()
        initDeviceId()
    }

    private fun initDeviceId() {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = deviceContext.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
        var savedUuid = prefs.getString("device_uuid", null)
        if (savedUuid == null) {
            savedUuid = UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", savedUuid).apply()
        }
        deviceId.value = savedUuid
    }

    @Synchronized
    fun applySessionUpdate(
        snapshot: com.pisophone.kiosk.repository.SessionSnapshot,
        targetAppState: Int? = null
    ): Boolean {
        if (snapshot.revision < sessionRevision.value) {
            Log.d(TAG, "Ignoring stale session update: incoming rev ${snapshot.revision} < current rev ${sessionRevision.value}")
            return false
        }
        sessionRevision.value = snapshot.revision
        sessionExpiryDeadlineMs.value = snapshot.deadlineMs
        sessionTimeRemaining.value = snapshot.remainingSeconds
        if (targetAppState != null) {
            appState.value = targetAppState
        }
        return true
    }

    @Synchronized
    fun applySessionUpdate(
        deadlineMs: Long,
        remainingSeconds: Int,
        revision: Long,
        targetAppState: Int? = null
    ): Boolean {
        return applySessionUpdate(
            com.pisophone.kiosk.repository.SessionSnapshot(deadlineMs, remainingSeconds, revision),
            targetAppState
        )
    }

    fun saveState(txSet: Set<String> = emptySet()) {
        try {
            val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("app_state", appState.value)
                .putInt("coins_inserted", coinsInserted.value)
                .putFloat("price_per_coin", pricePerCoin.value.toFloat())
                .putInt("minutes_per_coin", minutesPerCoin.value)
                .putInt("target_port", ESP32_WEB_PORT)
                .putStringSet("processed_tx_ids", txSet.take(20).toSet())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist state to SharedPreferences: ${e.message}")
        }
    }

    fun restoreState(): Set<String> {
        return try {
            val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedState = prefs.getInt("app_state", 0)
            
            // Read authoritative paid balance through PaymentRepository
            val paymentRepo = com.pisophone.kiosk.repository.PaymentRepository(
                db = com.pisophone.kiosk.db.AppDatabase.getDatabase(context),
                context = context
            )
            val restored = paymentRepo.restoreSessionState()

            pricePerCoin.value = prefs.getFloat("price_per_coin", 5.0f).toDouble()
            minutesPerCoin.value = prefs.getInt("minutes_per_coin", 30)
            esp32Ip = null

            val savedTxSet = prefs.getStringSet("processed_tx_ids", emptySet()) ?: emptySet()

            val effectiveRemainingSec = restored.remainingSeconds
            val effectiveDeadline = restored.deadlineMs
            sessionRevision.value = restored.revision

            if (effectiveRemainingSec > 0) {
                sessionTimeRemaining.value = effectiveRemainingSec
                sessionExpiryDeadlineMs.value = effectiveDeadline
                appState.value = if (savedState == 1 || savedState == 3) 3 else 2
                Log.d(TAG, "Restored active session: ${effectiveRemainingSec}s remaining (Monotonic deadline: $effectiveDeadline, isReboot=${restored.isReboot}, rev=${restored.revision})")
            } else {
                appState.value = 0
                sessionTimeRemaining.value = 0
                sessionExpiryDeadlineMs.value = 0L
            }
            
            coinsInserted.value = 0
            val (reason, slotNum, _) = com.pisophone.kiosk.security.KioskActivationManager.getSlotLockdownDetails(context)
            val isLocked = com.pisophone.kiosk.security.KioskActivationManager.isSlotLockedDown(context)
            isSlotExpired.value = isLocked
            slotExpiryMessage.value = reason
            slotNumber.value = slotNum

            if (isLocked) {
                appState.value = 0
                sessionTimeRemaining.value = 0
                sessionExpiryDeadlineMs.value = 0L
                paymentRepo.expireSessionBlocking()
            }

            saveState(savedTxSet)
            savedTxSet
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state: ${e.message}")
            emptySet()
        }
    }

    fun getLocalIpAddress(): String {
        val ip = com.pisophone.kiosk.network.Esp32ConnectionManager.getLocalIpAddress()
        return if (ip.isNotBlank()) ip else "127.0.0.1"
    }

    fun setArenaMode(active: Boolean, role: Int = 0, stake: Int = 15, showBanner: Boolean = false) {
        val wasActive = isArenaMode.value
        isArenaMode.value = active
        if (active) {
            if (role > 0) arenaPlayerRole.value = role
            if (stake > 0) arenaStakeMinutes.value = stake
            if (showBanner || !wasActive) {
                isArenaBannerVisible.value = true
            }
        } else {
            arenaPlayerRole.value = 0
            isArenaBannerVisible.value = false
        }
    }

    fun dismissArenaBanner() {
        isArenaBannerVisible.value = false
    }
}
