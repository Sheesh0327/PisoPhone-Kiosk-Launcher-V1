package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity

/**
 * The box's proof that a coin is real. Every coin the box reports (by WebSocket, in the status list, or in an /add_time call) carries
 * `v_sig` = HMAC-SHA256(box secret, "v1:<device id>:<tx id>:<pesos>:<ts>"). Only the box and its paired phones know the secret, so
 * whatever merely answers on the box's address cannot make a phone credit time. The phone checks it before crediting.
 */
internal object CoinProof {
    /** No rate is anywhere near this many seconds per peso; it caps what a damaged (unsigned) `seconds` field can ever grant. */
    const val MAX_SECONDS_PER_PESO = 3600

    fun message(deviceId: String, txId: String, pesos: Int, ts: String) = "v1:$deviceId:$txId:$pesos:$ts"

    fun isValid(deviceId: String, txId: String, pesos: Int, ts: String, sig: String, secret: String): Boolean {
        if (deviceId.isBlank() || txId.isBlank() || ts.isBlank() || sig.isBlank() || secret.isBlank()) return false
        val expected = KioskSecurity.calculateHmac(message(deviceId, txId, pesos, ts), secret)
        return KioskSecurity.constantTimeEquals(sig.lowercase(), expected.lowercase())
    }

    /** The seconds are not part of the signed text (the rate is the box's setting), so they must at least fit the signed amount. */
    fun secondsPlausible(seconds: Int, pesos: Int): Boolean =
        pesos > 0 && seconds > 0 && seconds.toLong() <= pesos.toLong() * MAX_SECONDS_PER_PESO
}
