package com.pisophone.kiosk.network

import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject

/**
 * Interprets the body of a successful ESP32 heartbeat reply and reports what it says (slot state, config,
 * arena mode, online status) to the [Esp32ConnectionDelegate]. Holds no connection state of its own.
 *
 * [requestPairing] is called with the box address when the box reports this phone as unassigned.
 */
internal class Esp32HeartbeatBodyHandler(
    private val delegate: Esp32ConnectionDelegate,
    private val requestPairing: (String) -> Unit,
) {
    fun handle(body: String, targetIp: String) {
        if (body.isBlank()) {
            Log.d(TAG, "[HEARTBEAT] Body is blank. Setting online to true.")
            delegate.onOnlineStatusChanged(true, null)
            return
        }
        try {
            val json = JSONObject(body)
            val slotNum = json.optInt("slot_num", json.optInt("slot", 0))
            val isUnassigned = json.optString("status", "") == "unassigned" ||
                json.optString("slot_status", "") == "unassigned" ||
                (!json.optBoolean("is_paired", true) && slotNum <= 0)
            val isExpired = isUnassigned ||
                json.optBoolean("slot_expired", false) ||
                json.optBoolean("lockdown", false) ||
                json.optString("status", "") == "expired" ||
                json.optString("slot_status", "") == "expired"
            val expiresAt = json.optLong("expires_at", 0L)
            if (isUnassigned) {
                requestPairing(targetIp)
            }
            val errorMsg = if (json.has("message") && json.optString("message").isNotBlank()) {
                json.optString("message")
            } else {
                json.optString("error", "Please activate device slot on ESP32 Portal.")
            }

            if (isExpired) {
                delegate.onSlotLockdown(errorMsg, slotNum, expiresAt)
            } else {
                delegate.onSlotRestored(slotNum)
            }

            val mac = if (json.has("mac")) json.optString("mac", "") else null
            val alias = if (json.has("device_name")) json.optString("device_name", "").trim() else null
            val price = if (json.has("price")) json.optDouble("price", 5.0) else null
            val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null
            val encryptedPin = json.optString("admin_pin", "")
            val decryptedPin = if (encryptedPin.isNotBlank()) {
                val dec = KioskSecurity.decrypt(encryptedPin, delegate.getSecretKey()).trim()
                if (dec.startsWith("PIN:")) dec.substring(4).trim().takeIf { it.isNotBlank() } else null
            } else {
                null
            }

            Log.d(TAG, "[HEARTBEAT] JSON parsing successful. Setting online to true.")
            delegate.onOnlineStatusChanged(true, mac)
            delegate.onConfigSynced(price, minutes, alias, decryptedPin, slotNum)

            if (json.has("arena_active")) {
                val arenaActive = json.optBoolean("arena_active", false)
                val arenaRole = json.optInt("arena_role", 0)
                val arenaStake = json.optInt("arena_stake", 15)
                delegate.onArenaModeSynced(arenaActive, arenaRole, arenaStake)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HEARTBEAT] Exception parsing JSON body: ${e.message}", e)
        }
    }

    private companion object {
        private const val TAG = "Esp32HeartbeatBody"
    }
}
