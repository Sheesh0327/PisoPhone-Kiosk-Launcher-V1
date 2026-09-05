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

    init {
        deviceIp.value = getLocalIpAddress()
        initDeviceId()
        restoreState()
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
                .putInt("session_time_remaining", sessionTimeRemaining.value)
                .putInt("coins_inserted", coinsInserted.value)
                .putFloat("price_per_coin", pricePerCoin.value.toFloat())
                .putInt("minutes_per_coin", minutesPerCoin.value)
                .putString("esp32_ip", esp32Ip)
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
            val savedTime = prefs.getInt("session_time_remaining", 0)
            pricePerCoin.value = prefs.getFloat("price_per_coin", 5.0f).toDouble()
            minutesPerCoin.value = prefs.getInt("minutes_per_coin", 30)
            esp32Ip = prefs.getString("esp32_ip", null)
            if (esp32Ip.isNullOrBlank()) {
                val configured = KioskSecurity.getConfiguredEsp32Ip(context)
                if (configured.isNotBlank()) {
                    esp32Ip = configured
                }
            }

            val savedTxSet = prefs.getStringSet("processed_tx_ids", emptySet()) ?: emptySet()

            if (savedTime > 0) {
                sessionTimeRemaining.value = savedTime
                appState.value = if (savedState == 1 || savedState == 3) 3 else 2
                Log.d(TAG, "Restored active session: ${savedTime}s remaining")
            } else {
                appState.value = 0
                sessionTimeRemaining.value = 0
            }
            
            coinsInserted.value = 0
            saveState(savedTxSet)
            savedTxSet
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state: ${e.message}")
            emptySet()
        }
    }

    fun getLocalIpAddress(): String {
        var fallbackIp: String? = null
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            if (networkInterface.name.contains("wlan") || networkInterface.name.contains("eth")) {
                                return ip
                            }
                            if (fallbackIp == null) {
                                fallbackIp = ip
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return fallbackIp ?: "127.0.0.1"
    }
}
