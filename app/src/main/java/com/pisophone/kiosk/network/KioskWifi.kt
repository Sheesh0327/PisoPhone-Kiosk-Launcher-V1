package com.pisophone.kiosk.network

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity

/**
 * Keeps a rental phone on the shop's PisoKiosk Wi-Fi (a hidden network: only phones provisioned from the box's page know it). The phone finds its coin box only on that network, and the box is
 * where it gets its admin PIN: a phone on any other network can never pair, so the network name and password are given
 * to the phone when it is provisioned (see the setup page) and re-applied whenever the phone is not on it.
 *
 * The phone is the device owner, so it may add the network itself (no dialog, no Settings screen). Any other install
 * falls back to a network suggestion, which Android asks the user to approve once.
 */
object KioskWifi {
    private const val TAG = "KioskWifi"
    const val DEFAULT_SSID = "PisoKiosk"

    fun isValidSsid(ssid: String): Boolean = ssid.length in 1..32 && ssid.all { it in ' '..'~' }

    /** WPA2 passphrase: 8 to 63 printable ASCII characters. */
    fun isValidPassword(password: String): Boolean = password.length in 8..63 && password.all { it in ' '..'~' }

    /** Adds the saved network and connects to it unless the phone is on it already. Returns true when it is connected. */
    @Suppress("DEPRECATION")
    fun ensureConnected(context: Context): Boolean {
        val app = context.applicationContext
        val ssid = KioskSecurity.getKioskWifiSsid(app)
        val password = KioskSecurity.getKioskWifiPassword(app)
        if (!isValidSsid(ssid) || !isValidPassword(password)) return false
        return try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            if (!wm.isWifiEnabled) wm.isWifiEnabled = true
            if (isOnNetwork(wm, ssid)) return true

            if (Build.VERSION.SDK_INT >= 29) {
                val suggestion = WifiNetworkSuggestion.Builder().setSsid(ssid).setWpa2Passphrase(password).setIsHiddenSsid(true).build()
                wm.removeNetworkSuggestions(listOf(suggestion))
                wm.addNetworkSuggestions(listOf(suggestion))
            }

            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (Build.VERSION.SDK_INT < 29 || dpm?.isDeviceOwnerApp(app.packageName) == true) {
                val config = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$password\""
                    hiddenSSID = true // the kiosk network is not broadcast: the phone has to look for it by name
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    status = WifiConfiguration.Status.ENABLED
                }
                var id = try {
                    wm.configuredNetworks?.firstOrNull { it.SSID == config.SSID }?.networkId ?: -1
                } catch (e: SecurityException) {
                    -1
                }
                if (id >= 0) {
                    config.networkId = id
                    id = wm.updateNetwork(config)
                } else {
                    id = wm.addNetwork(config)
                }
                if (id >= 0) {
                    wm.enableNetwork(id, true)
                    wm.reconnect()
                    Log.i(TAG, "Joined the saved kiosk Wi-Fi \"$ssid\"")
                } else {
                    Log.w(TAG, "Android refused to add the kiosk Wi-Fi \"$ssid\"")
                }
            }
            isOnNetwork(wm, ssid)
        } catch (e: Exception) {
            Log.w(TAG, "Could not join the kiosk Wi-Fi: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun isOnNetwork(wm: WifiManager, ssid: String): Boolean {
        val info = wm.connectionInfo ?: return false
        if (info.supplicantState != SupplicantState.COMPLETED) return false
        // The name is hidden from apps without the location permission ("<unknown ssid>"): then the saved network's id decides.
        val name = info.ssid?.trim('"') ?: ""
        if (name == ssid) return true
        if (name.isNotEmpty() && name != WifiManager.UNKNOWN_SSID) return false
        return try {
            val id = wm.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: -1
            id >= 0 && info.networkId == id
        } catch (e: SecurityException) {
            false
        }
    }
}
