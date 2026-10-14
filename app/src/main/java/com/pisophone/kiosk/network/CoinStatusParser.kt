package com.pisophone.kiosk.network

import org.json.JSONObject

/**
 * Reads the coins out of the ESP32's `/api/coinslot/status` answer.
 *
 * A phone may only credit coins that were meant for it: each transaction carries the `device_id` it belongs to and
 * anything else is ignored. Without this check the phone that pressed Insert Coin next credited the previous
 * customer's coins (free time). A transaction with no `device_id` (older firmware) is ignored too: that coin still
 * arrives by the box's direct push, which is addressed and signed.
 *
 * The answer itself is not signed, so each transaction must carry the box's own proof, `v_sig` (see [CoinProof]), made with the
 * box secret. Without it (firmware older than 3.5.4) the list credits nothing: the coin still arrives by the box's push.
 */
object CoinStatusParser {
    data class Coin(val txId: String, val seconds: Int, val amount: Double)

    fun ownedCoins(json: JSONObject, deviceId: String, secret: String): List<Coin> {
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
            val pesos = tx.optInt("pulses", 0)
            val ts = tx.opt("ts")?.toString()?.trim() ?: ""
            if (txId.isBlank() || seconds <= 0 || amount <= 0.0) continue
            if (!CoinProof.isValid(owner, txId, pesos, ts, tx.optString("v_sig", "").trim(), secret)) continue
            if (!CoinProof.secondsPlausible(seconds, pesos)) continue
            coins.add(Coin(txId, seconds, amount))
        }
        return coins
    }
}
