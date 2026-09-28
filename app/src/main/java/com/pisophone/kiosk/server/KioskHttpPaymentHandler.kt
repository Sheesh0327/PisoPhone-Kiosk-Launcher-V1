package com.pisophone.kiosk.server

import android.util.Log
import com.pisophone.kiosk.protocol.KioskProtocol
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.net.URLDecoder

class KioskHttpPaymentHandler(
    private val delegate: KioskServerDelegate
) {
    companion object {
        private const val TAG = "KioskHttpPaymentHandler"
        private const val MAX_TIMESTAMP_SKEW_MS = 60000L
    }

    fun handlePaymentRequest(session: IHTTPSession): Response {
        if (!delegate.isInitialized()) {
            Log.w(TAG, "Rejecting payment HTTP request: KioskEngine initialization in progress")
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "INITIALIZATION_IN_PROGRESS"
            )
        }

        try {
            if (session.method == NanoHTTPD.Method.POST) {
                val files = HashMap<String, String>()
                session.parseBody(files)
            }
            val parms = session.parms

            val payload = parms["payload"]
            val hmac = parms["hmac"]
            val deviceId = parms["device_id"] ?: ""
            val txId = parms["tx_id"] ?: ""
            val ts = parms["ts"] ?: ""

            val secretKey = delegate.getSecretKey()
            val expectedDevId = delegate.getDeviceId()

            val extractedParams = HashMap<String, String>()

            if (!payload.isNullOrBlank()) {
                val tsVal = ts.toLongOrNull() ?: 0L
                val currentMs = System.currentTimeMillis()
                if (Math.abs(currentMs - tsVal) > MAX_TIMESTAMP_SKEW_MS) {
                    Log.w(TAG, "Rejected HTTP request: Timestamp skew ($currentMs vs $tsVal)")
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "TIMESTAMP_SKEW")
                }

                val targetDev = if (deviceId.isNotBlank()) deviceId else expectedDevId
                val validSig = KioskProtocol.verifyHttpReqSignature(
                    method = session.method.name,
                    endpoint = session.uri,
                    recipient = targetDev,
                    txId = txId,
                    ts = ts,
                    payload = payload,
                    sig = hmac ?: "",
                    secret = secretKey
                )
                if (!validSig) {
                    Log.w(TAG, "Rejected HTTP request: Invalid signature for $txId")
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "INVALID_SIGNATURE")
                }

                val decrypted = KioskSecurity.decrypt(payload, secretKey)
                parseQueryParams(decrypted, extractedParams)
            } else {
                extractedParams.putAll(parms)
            }

            val effectiveDevId = extractedParams["device_id"] ?: deviceId
            if (effectiveDevId.isNotBlank() && expectedDevId.isNotBlank() && effectiveDevId != "ALL" && effectiveDevId != expectedDevId) {
                Log.w(TAG, "Rejected HTTP payment request: Recipient mismatch (expected $expectedDevId vs got $effectiveDevId)")
                return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "RECIPIENT_MISMATCH")
            }

            val effectiveTxId = extractedParams["tx_id"] ?: txId
            if (effectiveTxId.isBlank()) {
                Log.w(TAG, "Rejected HTTP payment request: Missing tx_id")
                return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "MISSING_TX_ID")
            }

            val secondsVal = extractedParams["seconds"]?.toIntOrNull()
                ?: extractedParams["minutes"]?.toIntOrNull()?.let { it * 60 }
                ?: 0
            val amountVal = extractedParams["amount"]?.toDoubleOrNull() ?: 0.0

            val opKindInt = extractedParams["op_kind"]?.toIntOrNull()
            val opKindStr = when (opKindInt) {
                1 -> "QUICK_ADJUST"
                2 -> "MANUAL_DEDUCTION"
                0 -> "COIN"
                else -> extractedParams["op_kind"] ?: if (amountVal <= 0.0) "QUICK_ADJUST" else "COIN"
            }

            val coinAmount = extractedParams["pulses"]?.toIntOrNull()
                ?: extractedParams["coin_amount"]?.toIntOrNull()
                ?: amountVal.toInt()
            val pricePerCoin = extractedParams["price_per_coin"]?.toDoubleOrNull() ?: 0.0
            val boxEpoch = extractedParams["box_installation_epoch"]?.toLongOrNull() ?: 0L
            val phoneEpoch = extractedParams["phone_pairing_epoch"]?.toLongOrNull() ?: 0L

            Log.i(TAG, "Processing HTTP payment request: tx_id=$effectiveTxId, seconds=$secondsVal, amount=$amountVal, opKind=$opKindStr")

            val result = if (secondsVal < 0 || opKindStr == "MANUAL_DEDUCTION") {
                delegate.onDeductPayment(
                    seconds = Math.abs(secondsVal),
                    txId = effectiveTxId,
                    operationKind = "MANUAL_DEDUCTION",
                    boxInstallationEpoch = boxEpoch,
                    phonePairingEpoch = phoneEpoch
                )
            } else {
                delegate.onCreditPayment(
                    txId = effectiveTxId,
                    seconds = secondsVal,
                    amount = amountVal,
                    operationKind = opKindStr,
                    coinAmount = coinAmount,
                    pricePerCoin = pricePerCoin,
                    boxInstallationEpoch = boxEpoch,
                    phonePairingEpoch = phoneEpoch
                )
            }

            if (result == PaymentResult.APPLIED || result == PaymentResult.ALREADY_APPLIED) {
                val statusStr = if (result == PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
                val ackTs = System.currentTimeMillis().toString()
                val targetAckDev = expectedDevId.ifBlank { effectiveDevId }
                val ackSig = KioskSecurity.calculateAckSignature(
                    deviceId = targetAckDev,
                    txId = effectiveTxId,
                    amount = amountVal.toInt(),
                    seconds = secondsVal,
                    ts = ackTs,
                    status = statusStr,
                    secret = secretKey
                )
                val respBody = "$statusStr:tx_id=$effectiveTxId:device_id=$targetAckDev:amount=${amountVal.toInt()}:seconds=$secondsVal:ts=$ackTs:v_sig=$ackSig"
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", respBody)
            } else {
                Log.w(TAG, "HTTP payment failed with result: $result for $effectiveTxId")
                return when (result) {
                    PaymentResult.CONFLICT -> NanoHTTPD.newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "TX_CONFLICT")
                    PaymentResult.NOT_ELIGIBLE -> NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "NOT_ELIGIBLE")
                    else -> NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "DATABASE_ERROR")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTP payment request: ${e.message}", e)
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERROR: ${e.message}")
        }
    }

    private fun parseQueryParams(query: String, outMap: MutableMap<String, String>) {
        if (query.isBlank()) return
        val pairs = query.split('&')
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = if (idx < pair.length - 1) URLDecoder.decode(pair.substring(idx + 1), "UTF-8") else ""
                outMap[key] = value
            }
        }
    }
}
