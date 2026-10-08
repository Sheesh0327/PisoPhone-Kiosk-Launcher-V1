package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject

/** A validated coin reported over the ESP32 WebSocket. */
internal data class WebSocketCoin(val txId: String, val seconds: Int, val amount: Double)

internal object Esp32WebSocketMessages {
    /**
     * Reads a `COIN_DETECTED` message. Fields missing from the plain message are filled from its encrypted
     * `payload` (decrypted with the key from [secretKey], which is only asked for when a payload is present).
     * Returns null unless the transaction id, seconds and amount are all present and positive.
     */
    fun parseCoinDetected(json: JSONObject, secretKey: () -> String): WebSocketCoin? {
        var txId = json.optString("tx_id", "").trim()
        var seconds = json.optInt("seconds", 0)
        var amount = json.optDouble("amount", 0.0)

        val payload = json.optString("payload", "")
        if (payload.isNotBlank()) {
            val decryptedStr = KioskSecurity.decrypt(payload, secretKey())
            if (decryptedStr.isNotBlank()) {
                try {
                    val decryptedJson = JSONObject(decryptedStr)
                    if (txId.isBlank()) txId = decryptedJson.optString("tx_id", "").trim()
                    if (seconds <= 0) {
                        val min = decryptedJson.optLong("minutes", 0L)
                        val sec = decryptedJson.optLong("seconds", 0L)
                        seconds = if (sec > 0L) sec.toInt() else (min * 60L).toInt()
                    }
                    if (amount <= 0.0) amount = decryptedJson.optDouble("amount", 0.0)
                } catch (_: Exception) {}
            }
        }

        return if (txId.isNotBlank() && seconds > 0 && amount > 0.0) WebSocketCoin(txId, seconds, amount) else null
    }
}
