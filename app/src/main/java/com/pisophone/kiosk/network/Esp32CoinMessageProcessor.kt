package com.pisophone.kiosk.network

import android.util.Log
import com.pisophone.kiosk.protocol.KioskProtocol
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Validates, decrypts, reconciles, and processes incoming coin messages from the ESP32 WebSocket,
 * sending durable cryptographic ACKs back to the ESP32.
 */
object Esp32CoinMessageProcessor {
    private const val TAG = "Esp32CoinProcessor"
    private const val MAX_TIMESTAMP_SKEW_MS = 60000L

    fun processMessage(
        text: String,
        delegate: Esp32ConnectionDelegate,
        webSocket: WebSocket,
        attemptId: Long,
        onCoinProcessed: () -> Unit
    ) {
        Log.d(TAG, "Master WebSocket onMessage (attempt #$attemptId): $text")
        try {
            val json = JSONObject(text)
            val event = json.optString("event", "")

            if (event == "PULSE" || event == "COIN_DETECTED") {
                val outerPayload = json.optString("payload", "")
                if (outerPayload.isBlank()) {
                    val pulses = json.optInt("pulses", json.optInt("amount", 0))
                    if (pulses <= 0) {
                        Log.w(TAG, "Rejected WebSocket coin event: Invalid pulse count ($pulses)")
                        return
                    }
                    val secondsFromMsg = json.optInt("seconds", 0)
                    val minutesFromMsg = json.optInt("minutes", 0)
                    val seconds = if (secondsFromMsg > 0) {
                        secondsFromMsg
                    } else if (minutesFromMsg > 0) {
                        minutesFromMsg * 60
                    } else {
                        pulses * 600
                    }
                    val txId = json.optString("tx_id", "pulse_${System.currentTimeMillis()}_$pulses")
                    
                    Log.i(TAG, "⚡ Clean Pulse Received: +${pulses} pulses -> +${seconds}s (txId: $txId)")
                    delegate.onCoinMessageReceived(
                        seconds = seconds,
                        amount = pulses.toDouble(),
                        txId = txId,
                        operationKind = "COIN",
                        coinAmount = pulses,
                        pricePerCoin = 1.0,
                        boxInstallationEpoch = 0L,
                        phonePairingEpoch = 0L
                    )
                    
                    val ackJson = JSONObject().apply {
                        put("event", "ACK")
                        put("tx_id", txId)
                        put("amount", pulses)
                        put("seconds", seconds)
                        put("status", "OK")
                    }
                    webSocket.send(ackJson.toString())
                    onCoinProcessed()
                    return
                }

                val outerHmac = json.optString("v_sig", "").trim()
                if (outerHmac.isBlank()) {
                    Log.w(TAG, "Rejected WebSocket coin event: Missing signature")
                    return
                }

                val outerTxId = json.optString("tx_id", "").trim()
                if (outerTxId.isBlank()) {
                    Log.w(TAG, "Rejected WebSocket coin event: Missing tx_id in envelope")
                    return
                }

                val outerTsStr = json.optString("ts", "").trim()
                val outerTs = outerTsStr.toLongOrNull() ?: 0L
                val now = System.currentTimeMillis()
                val skew = Math.abs(now - outerTs)
                if (outerTs <= 0L || skew > MAX_TIMESTAMP_SKEW_MS) {
                    Log.w(TAG, "Rejected WebSocket coin event: Stale/invalid envelope timestamp ($outerTs, now=$now, skew=${skew}ms, max=${MAX_TIMESTAMP_SKEW_MS}ms)")
                    return
                }

                val outerDevId = json.optString("device_id", "").trim().ifBlank { delegate.getDeviceId() }
                val myDevId = delegate.getDeviceId()
                if (outerDevId.isNotBlank() && myDevId.isNotBlank() && !outerDevId.equals(myDevId, ignoreCase = true)) {
                    Log.w(TAG, "Rejected WebSocket coin event: Recipient mismatch (target='$outerDevId', local='$myDevId')")
                    return
                }

                val outerSeconds = if (json.has("seconds")) json.optInt("seconds") else null
                val outerAmount = if (json.has("amount")) json.optDouble("amount") else null

                val secretKey = delegate.getSecretKey()

                // STEP 1: Verify integrity BEFORE decryption
                if (!KioskSecurity.verifyWsPaySignature(
                        event = "COIN_DETECTED",
                        recipient = outerDevId,
                        txId = outerTxId,
                        ts = outerTsStr,
                        payload = outerPayload,
                        sig = outerHmac,
                        secret = secretKey
                    )) {
                    Log.w(TAG, "Rejected WebSocket coin event: Signature verification failed BEFORE decryption")
                    return
                }

                // STEP 2: Decrypt payload only after signature is verified
                val decryptedStr = KioskSecurity.decrypt(outerPayload, secretKey)
                if (decryptedStr.isBlank()) {
                    Log.w(TAG, "Rejected WebSocket coin event: Decryption failed or empty plaintext")
                    return
                }

                val decryptedJson = try {
                    JSONObject(decryptedStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Rejected WebSocket coin event: Malformed decrypted JSON: ${e.message}")
                    return
                }

                val innerTxId = decryptedJson.optString("tx_id", "").trim()
                val innerDev = decryptedJson.optString("device_id", "").trim()
                val innerTsStr = decryptedJson.optString("ts", "").trim()
                val innerTs = innerTsStr.toLongOrNull() ?: 0L
                val minutesLong = decryptedJson.optLong("minutes", 0L)
                val secondsOptLong = decryptedJson.optLong("seconds", 0L)
                val rawSeconds = if (secondsOptLong > 0L) secondsOptLong else (minutesLong * 60L)
                val amount = decryptedJson.optDouble("amount", 0.0)

                // STEP 3: Reconcile decrypted context with outer verified envelope
                val envelope = KioskProtocol.VerifiedEnvelope(
                    event = "COIN_DETECTED",
                    recipient = outerDevId,
                    txId = outerTxId,
                    ts = outerTs,
                    payload = outerPayload,
                    seconds = outerSeconds,
                    amount = outerAmount
                )
                val innerContext = KioskProtocol.DecryptedPaymentContext(
                    deviceId = innerDev,
                    txId = innerTxId,
                    ts = innerTs,
                    seconds = rawSeconds.toInt(),
                    amount = amount
                )
                val reconResult = KioskProtocol.reconcilePaymentContext(envelope, innerContext)
                if (reconResult is KioskProtocol.ProtocolValidationResult.Invalid) {
                    Log.w(TAG, "Rejected WebSocket coin event: Context reconciliation failed: ${reconResult.reason} (${reconResult.code})")
                    return
                }

                if (rawSeconds !in 1L..Int.MAX_VALUE.toLong() || amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
                    Log.w(TAG, "Rejected WebSocket coin event: Invalid seconds ($rawSeconds) or amount ($amount)")
                    return
                }
                val seconds = rawSeconds.toInt()
                val amountPulses = amount.toInt()
                val txId = innerTxId.ifBlank { outerTxId }
                val targetDev = innerDev.ifBlank { outerDevId }

                val rawOpKind = decryptedJson.optString("op_kind", "1")
                val opKindStr = when (rawOpKind) {
                    "1" -> "COIN"
                    "2" -> "QUICK_ADJUST"
                    "3" -> "MANUAL_DEDUCTION"
                    "4" -> "MATCH_TRANSFER"
                    "5" -> "CONTROLLER"
                    else -> rawOpKind
                }
                val pricePerCoin = decryptedJson.optDouble("price_per_coin", 0.0)
                val boxEpoch = decryptedJson.optLong("box_installation_epoch", 0L)
                val phoneEpoch = decryptedJson.optLong("phone_pairing_epoch", 0L)

                Log.i(TAG, "⚡ Validated WebSocket Coin Processed: +${seconds}s, amount=₱$amount, txId=$txId, opKind=$opKindStr")
                val result = delegate.onCoinMessageReceived(
                    seconds = seconds,
                    amount = amount,
                    txId = txId,
                    operationKind = opKindStr,
                    coinAmount = amountPulses,
                    pricePerCoin = pricePerCoin,
                    boxInstallationEpoch = boxEpoch,
                    phonePairingEpoch = phoneEpoch
                )

                if (result == PaymentResult.APPLIED || result == PaymentResult.ALREADY_APPLIED) {
                    try {
                        val ackNow = System.currentTimeMillis()
                        val statusStr = if (result == PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
                        val ackSig = KioskSecurity.calculateAckSignature(
                            deviceId = targetDev,
                            txId = txId,
                            amount = amountPulses,
                            seconds = seconds,
                            ts = ackNow.toString(),
                            status = statusStr,
                            secret = secretKey
                        )
                        val ackJson = JSONObject().apply {
                            put("event", "ACK")
                            put("device_id", targetDev)
                            put("tx_id", txId)
                            put("amount", amountPulses)
                            put("seconds", seconds)
                            put("ts", ackNow.toString())
                            put("v_sig", ackSig)
                            put("status", statusStr)
                        }
                        webSocket.send(ackJson.toString())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send WebSocket ACK for $txId: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "WebSocket ACK suppressed for $txId due to payment result: $result")
                }

                onCoinProcessed()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WebSocket message: ${e.message}")
        }
    }
}
