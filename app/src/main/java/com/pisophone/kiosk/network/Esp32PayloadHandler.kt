package com.pisophone.kiosk.network

import android.util.Log
import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject

/**
 * Data transfer object representing a verified coin payment event from ESP32.
 */
data class CoinEventPayload(
    val seconds: Int,
    val amount: Double,
    val txId: String
)

/**
 * Handles cryptographic decryption and structural validation of incoming ESP32 payloads.
 */
object Esp32PayloadHandler {
    private const val TAG = "Esp32PayloadHandler"

    /**
     * Decrypts and parses a WebSocket COIN_DETECTED frame payload.
     * Enforces signature validity, timestamp skew guard, and numeric bounds.
     */
    fun parseWebSocketCoinEvent(
        text: String,
        secretKey: String,
        maxTimestampSkewMs: Long
    ): CoinEventPayload? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed WebSocket frame JSON: ${e.message}")
            return null
        }

        val payload = json.optString("payload", "").trim()
        if (payload.isBlank()) {
            Log.w(TAG, "Rejected WebSocket coin event: Missing encrypted payload")
            return null
        }

        val decryptedStr = KioskSecurity.decrypt(payload, secretKey).trim()
        if (decryptedStr.isBlank()) {
            Log.w(TAG, "Rejected WebSocket coin event: Decryption failed (invalid key or ciphertext)")
            return null
        }

        val decryptedJson = try {
            JSONObject(decryptedStr)
        } catch (e: Exception) {
            Log.e(TAG, "Rejected WebSocket coin event: Malformed decrypted payload: ${e.message}")
            return null
        }

        val txId = decryptedJson.optString("tx_id", "").trim()
        if (txId.isBlank()) {
            Log.w(TAG, "Rejected WebSocket coin event: Missing tx_id in decrypted payload")
            return null
        }

        val tsStr = decryptedJson.optString("ts", "").trim()
        val ts = tsStr.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val skew = Math.abs(now - ts)
        if (ts <= 0L || skew > maxTimestampSkewMs) {
            Log.w(TAG, "Rejected WebSocket coin event: Stale/invalid timestamp ($ts vs now=$now, skew=${skew}ms, max=${maxTimestampSkewMs}ms)")
            return null
        }

        val minutesLong = decryptedJson.optLong("minutes", 0L)
        val secondsOptLong = decryptedJson.optLong("seconds", 0L)
        val rawSeconds = if (secondsOptLong > 0L) secondsOptLong else (minutesLong * 60L)
        val amount = decryptedJson.optDouble("amount", 0.0)

        if (rawSeconds !in 1L..Int.MAX_VALUE.toLong() || amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
            Log.w(TAG, "Rejected WebSocket coin event: Out-of-bounds seconds ($rawSeconds) or amount ($amount)")
            return null
        }

        return CoinEventPayload(rawSeconds.toInt(), amount, txId)
    }

    /**
     * Parses an HTTP /heartbeat JSON response and notifies the delegate.
     */
    fun parseHeartbeatJson(
        body: String,
        secretKey: String,
        delegate: Esp32ConnectionDelegate
    ) {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing heartbeat response JSON: ${e.message}")
            return
        }

        val isExpired = json.optBoolean("slot_expired", false) ||
                json.optBoolean("lockdown", false) ||
                json.optString("status", "") == "expired" ||
                json.optString("slot_status", "") == "expired"
        val slotNum = json.optInt("slot_num", json.optInt("slot", 0))
        val expiresAt = json.optLong("expires_at", 0L)
        val errorMsg = if (json.has("message") && json.optString("message").isNotBlank()) {
            json.optString("message")
        } else {
            json.optString("error", "Please activate device slot on ESP32 Portal.")
        }

        if (isExpired) {
            delegate.onSlotLockdown(errorMsg, slotNum, expiresAt)
        } else {
            delegate.onSlotRestored(slotNum)

            val isWarning = json.optBoolean("slot_warning", false) ||
                    json.optString("slot_status", "") == "warning"
            val daysLeft = if (json.has("days_left")) json.optInt("days_left", -1) else -1
            val warnMsg = json.optString("warning_message", "Slot license nearing expiration")
            if (isWarning && daysLeft in 0..7) {
                delegate.onSlotWarning(daysLeft, expiresAt, slotNum, warnMsg)
            }
        }

        val mac = if (json.has("mac")) json.optString("mac", "").takeIf { it.isNotBlank() } else null
        val alias = if (json.has("device_name")) json.optString("device_name", "").trim().takeIf { it.isNotBlank() } else null
        val price = if (json.has("price")) json.optDouble("price", 5.0) else null
        val minutes = if (json.has("minutes")) json.optInt("minutes", 30) else null
        val encryptedPin = json.optString("admin_pin", "")
        val decryptedPin = if (encryptedPin.isNotBlank()) {
            val dec = KioskSecurity.decrypt(encryptedPin, secretKey).trim()
            if (dec.startsWith("PIN:")) dec.substring(4).trim().takeIf { it.isNotBlank() } else null
        } else null

        delegate.onOnlineStatusChanged(true, mac)
        delegate.onConfigSynced(price, minutes, alias, decryptedPin, slotNum)
    }
}
