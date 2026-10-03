package com.pisophone.kiosk.network

import org.json.JSONObject

/**
 * Reads the coins out of the ESP32's `/api/coinslot/status` answer.
 *
 * A phone may only credit coins that were meant for it: each transaction carries the `device_id` it belongs to and
 * anything else is ignored. Without this check the phone that pressed Insert Coin next credited the previous
 * customer's coins (free time). A transaction with no `device_id` (older firmware) is ignored too: that coin still
 * arrives by the box's direct push, which is addressed and signed.
 */
object CoinStatusParser {
    data class Coin(val txId: String, val seconds: Int, val amount: Double)

    fun ownedCoins(json: JSONObject, deviceId: String): List<Coin> {
        val txArray = json.optJSONArray("transactions") ?: return emptyList()
        val coins = ArrayList<Coin>()
        for (i in 0 until txArray.length()) {
            val tx = txArray.optJSONObject(i) ?: continue
            val owner = tx.optString("device_id", "").trim()
            if (owner.isEmpty() || !owner.equals(deviceId.trim(), ignoreCase = true)) continue
            if (tx.optBoolean("acknowledged", false)) continue
            val txId = tx.optString("tx_id", "").trim()
            val seconds = tx.optInt("seconds", 0)
            val amount = tx.optDouble("amount", 0.0)
            if (txId.isNotBlank() && seconds > 0 && amount > 0.0) coins.add(Coin(txId, seconds, amount))
        }
        return coins
    }
}
