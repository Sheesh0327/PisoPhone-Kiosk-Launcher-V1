package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject

/** A validated coin reported over the ESP32 WebSocket. */
internal data class WebSocketCoin(val txId: String, val seconds: Int, val amount: Double)

internal object Esp32WebSocketMessages {
    /**
     * Reads a `COIN_DETECTED` message and returns the coin only if the box proved it (see [CoinProof]): the encrypted `payload`
     * must decrypt with the box secret ([secretKey]), name THIS phone ([deviceId]), and carry a `v_sig` that matches its own
     * transaction id, amount and time. The plain fields next to the payload are for logging only and are never trusted: a
     * message that merely looks right, from something that does not know the secret, credits nothing.
     */
    fun parseCoinDetected(json: JSONObject, deviceId: String, secretKey: () -> String): WebSocketCoin? {
        val payload = json.optString("payload", "")
        if (payload.isBlank()) return null
        val secret = secretKey()
        val decrypted = KioskSecurity.decrypt(payload, secret)
        if (decrypted.isBlank()) return null
        val inner = try {
            JSONObject(decrypted)
        } catch (_: Exception) {
            return null
        }

        val txId = inner.optString("tx_id", "").trim()
        val pesos = inner.optDouble("amount", 0.0)
        val seconds = run {
            val sec = inner.optLong("seconds", 0L)
            val min = inner.optLong("minutes", 0L)
            (if (sec > 0L) sec else min * 60L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        val owner = inner.optString("device_id", "").trim()
        if (txId.isBlank() || pesos <= 0.0 || seconds <= 0) return null
        if (owner.isBlank() || owner != deviceId.trim()) return null
        val pesoCount = pesos.toInt()
        if (!CoinProof.isValid(owner, txId, pesoCount, inner.optString("ts", "").trim(), inner.optString("v_sig", "").trim(), secret)) {
            return null
        }
        if (!CoinProof.secondsPlausible(seconds, pesoCount)) return null
        return WebSocketCoin(txId, seconds, pesos)
    }
}
