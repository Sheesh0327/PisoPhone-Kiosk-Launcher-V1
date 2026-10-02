package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity

/**
 * Builds the signed coin-slot URLs (`/api/coinslot/arm`, `unarm`, `ack`) the ESP32 expects.
 *
 * The three coin-slot calls (arm, unarm, ack) share one format so the signing rule lives in a
 * single place: `sig = HMAC(secret, "v1:<action>:<deviceId>:<ts>[:<txId>]")`, mirrored by
 * `coinslotRequestAuthorized()` in the firmware (WebServerCoinslot.cpp).
 */
object Esp32CoinslotRequests {
    const val ACTION_ARM = "arm"
    const val ACTION_UNARM = "unarm"
    const val ACTION_ACK = "ack"

    /**
     * @param host ESP32 host name or IP (without port)
     * @param action one of [ACTION_ARM], [ACTION_UNARM], [ACTION_ACK]
     * @param txId transaction being acknowledged; only used by [ACTION_ACK], and part of the signature
     * @param extraQuery additional, unsigned query parameters such as `ip=...&duration=...`
     * @param nowMs timestamp in epoch milliseconds (injectable for tests)
     */
    fun signedUrl(
        host: String,
        action: String,
        deviceId: String,
        secret: String,
        txId: String = "",
        extraQuery: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val ts = nowMs.toString()
        val sig = KioskSecurity.signCoinslotRequest(action, deviceId, ts, txId, secret)
        val txPart = if (txId.isEmpty()) "" else "&tx_id=$txId"
        val extraPart = if (extraQuery.isEmpty()) "" else "&$extraQuery"
        return "http://$host:80/api/coinslot/$action?device_id=$deviceId$txPart$extraPart&ts=$ts&sig=$sig"
    }
}
