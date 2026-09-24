package com.pisophone.kiosk.server

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import fi.iki.elonen.NanoHTTPD.Response

object KioskHttpPaymentHandler {
    private const val TAG = "KioskHttpPaymentHandler"

    fun handlePayment(
        context: Context,
        delegate: KioskServerDelegate,
        decryptedParams: Map<String, String>,
        txId: String?,
        secretKey: String,
        createResponse: (Response.IStatus, String, String) -> Response
    ): Response {
        val targetDev = decryptedParams["device_id"]?.trim() ?: ""
        val myDeviceId = delegate.getDeviceId().ifBlank { KioskSecurity.getHardwareId(context) }
        if (targetDev.isNotBlank() && !targetDev.equals(myDeviceId, ignoreCase = true)) {
            Log.w(TAG, "Rejecting payment request: Recipient mismatch (target='$targetDev', local='$myDeviceId')")
            return createResponse(Response.Status.FORBIDDEN, "text/plain", "MISMATCHED_RECIPIENT")
        }

        val hasMinutes = decryptedParams.containsKey("minutes")
        val hasSeconds = decryptedParams.containsKey("seconds")
        val minutesLong = decryptedParams["minutes"]?.toLongOrNull()
        val secondsParamLong = decryptedParams["seconds"]?.toLongOrNull()
        if ((hasMinutes && minutesLong == null) || (hasSeconds && secondsParamLong == null) || (!hasMinutes && !hasSeconds)) {
            Log.w(TAG, "Rejecting coin credit: Invalid time parameters")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_PAYMENT")
        }
        val rawSecondsLong = secondsParamLong ?: ((minutesLong ?: 0L) * 60L)

        val hasAmount = decryptedParams.containsKey("amount")
        val amountParam = decryptedParams["amount"]?.toDoubleOrNull()
        if (hasAmount && (amountParam == null || amountParam.isNaN() || amountParam.isInfinite())) {
            Log.w(TAG, "Rejecting coin credit: Invalid amount parameter")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_AMOUNT")
        }
        val amount = amountParam ?: 1.0
        if (amount < 0.0) {
            Log.w(TAG, "Rejecting coin credit: Negative amount")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_AMOUNT")
        }

        val amountPulses = amount.toInt()
        val rawOpKind = decryptedParams["op_kind"] ?: decryptedParams["operation_kind"] ?: ""
        val opKindStr = when (rawOpKind) {
            "1" -> "COIN"
            "2" -> "QUICK_ADJUST"
            "3" -> "MANUAL_DEDUCTION"
            "4" -> "MATCH_TRANSFER"
            "5" -> "CONTROLLER"
            else -> if (rawOpKind.isNotBlank()) rawOpKind else (if (amount > 0.0) "COIN" else (if (rawSecondsLong >= 0) "QUICK_ADJUST" else "MANUAL_DEDUCTION"))
        }
        val pricePerCoin = decryptedParams["price_per_coin"]?.toDoubleOrNull()
            ?: decryptedParams["price"]?.toDoubleOrNull() ?: 0.0
        val boxEpoch = decryptedParams["box_installation_epoch"]?.toLongOrNull()
            ?: decryptedParams["box_epoch"]?.toLongOrNull() ?: 0L
        val phoneEpoch = decryptedParams["phone_pairing_epoch"]?.toLongOrNull()
            ?: decryptedParams["phone_epoch"]?.toLongOrNull() ?: 0L

        if (rawSecondsLong > 0) {
            if (rawSecondsLong > Int.MAX_VALUE.toLong()) {
                Log.w(TAG, "Rejecting coin credit: seconds parameter exceeds Int.MAX_VALUE ($rawSecondsLong)")
                return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
            }
            val seconds = rawSecondsLong.toInt()
            val creditOpKind = if (opKindStr.isBlank() || opKindStr == "MANUAL_DEDUCTION") (if (amount > 0.0) "COIN" else "QUICK_ADJUST") else opKindStr
            val result = try {
                delegate.creditPayment(
                    txId = txId!!,
                    seconds = seconds,
                    amount = amount,
                    operationKind = creditOpKind,
                    coinAmount = amountPulses,
                    pricePerCoin = pricePerCoin,
                    boxInstallationEpoch = boxEpoch,
                    phonePairingEpoch = phoneEpoch
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during creditPayment for $txId: ${e.message}", e)
                PaymentResult.FAILED
            }

            val ackResp = if (targetDev.isNotBlank()) {
                val ackNow = System.currentTimeMillis()
                val statusStr = if (result == PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
                val ackSig = KioskSecurity.calculateAckSignature(
                    deviceId = targetDev,
                    txId = txId!!,
                    amount = amountPulses,
                    seconds = seconds,
                    ts = ackNow.toString(),
                    status = statusStr,
                    secret = secretKey
                )
                "$statusStr:tx_id=$txId:device_id=$targetDev:amount=$amountPulses:seconds=$seconds:ts=$ackNow:v_sig=$ackSig"
            } else {
                if (result == PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
            }

            return when (result) {
                PaymentResult.APPLIED, PaymentResult.ALREADY_APPLIED -> {
                    createResponse(Response.Status.OK, "text/plain", ackResp)
                }
                PaymentResult.CONFLICT -> {
                    Log.w(TAG, "Payment rejected due to conflicting values for $txId")
                    createResponse(Response.Status.CONFLICT, "text/plain", "CONFLICT")
                }
                PaymentResult.NOT_ELIGIBLE -> {
                    Log.w(TAG, "Payment rejected: device not eligible for $txId")
                    createResponse(Response.Status.FORBIDDEN, "text/plain", "NOT_ELIGIBLE")
                }
                PaymentResult.FAILED -> {
                    Log.e(TAG, "Payment failed to commit to database for $txId")
                    createResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "SERVICE_UNAVAILABLE")
                }
            }
        } else if (rawSecondsLong < 0) {
            val positiveSecondsLong = if (rawSecondsLong == Long.MIN_VALUE) Long.MAX_VALUE else -rawSecondsLong
            if (positiveSecondsLong > Int.MAX_VALUE.toLong()) {
                Log.w(TAG, "Rejecting deduction: seconds parameter magnitude exceeds Int.MAX_VALUE ($rawSecondsLong)")
                return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
            }
            val positiveSeconds = positiveSecondsLong.toInt()
            val deductTxId = txId ?: "deduct_${System.currentTimeMillis()}"
            val deductOpKind = if (opKindStr.isBlank() || opKindStr == "COIN") "MANUAL_DEDUCTION" else opKindStr
            val result = try {
                delegate.onDeductTime(
                    seconds = positiveSeconds,
                    txId = deductTxId,
                    operationKind = deductOpKind,
                    boxInstallationEpoch = boxEpoch,
                    phonePairingEpoch = phoneEpoch
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during onDeductTime for $deductTxId: ${e.message}", e)
                PaymentResult.FAILED
            }

            val ackResp = if (targetDev.isNotBlank()) {
                val ackNow = System.currentTimeMillis()
                val statusStr = if (result == PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
                val ackSig = KioskSecurity.calculateAckSignature(
                    deviceId = targetDev,
                    txId = deductTxId,
                    amount = 0,
                    seconds = rawSecondsLong.toInt(),
                    ts = ackNow.toString(),
                    status = statusStr,
                    secret = secretKey
                )
                "$statusStr:tx_id=$deductTxId:device_id=$targetDev:amount=0:seconds=${rawSecondsLong.toInt()}:ts=$ackNow:v_sig=$ackSig"
            } else {
                if (result == PaymentResult.ALREADY_APPLIED) "ALREADY_PROCESSED" else "OK"
            }

            return when (result) {
                PaymentResult.APPLIED, PaymentResult.ALREADY_APPLIED -> {
                    createResponse(Response.Status.OK, "text/plain", ackResp)
                }
                PaymentResult.CONFLICT -> {
                    Log.w(TAG, "Deduction rejected due to conflicting values for $deductTxId")
                    createResponse(Response.Status.CONFLICT, "text/plain", "CONFLICT")
                }
                PaymentResult.NOT_ELIGIBLE -> {
                    Log.w(TAG, "Deduction rejected: device not eligible for $deductTxId")
                    createResponse(Response.Status.FORBIDDEN, "text/plain", "NOT_ELIGIBLE")
                }
                PaymentResult.FAILED -> {
                    Log.e(TAG, "Deduction failed to commit to database for $deductTxId")
                    createResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "SERVICE_UNAVAILABLE")
                }
            }
        } else {
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
        }
    }
}
