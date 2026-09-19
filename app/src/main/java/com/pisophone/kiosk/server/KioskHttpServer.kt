package com.pisophone.kiosk.server

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface KioskServerDelegate {
    fun isReady(): Boolean = true
    fun getSecretKey(): String
    fun getDeviceId(): String = ""
    fun onHeartbeat(clientIp: String?)
    fun getStatusJson(): JSONObject
    fun getSessionTimeRemaining(): Int
    fun getAppState(): Int
    fun getAuditEventsJson(): String
    fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult
    fun onDeductTime(seconds: Int, txId: String? = null)
    fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int? = null)
    fun onTriggerAction(action: String, slotNum: Int? = null, extra: Map<String, String>? = null)
    fun getCrashLog(): String?
}

class KioskHttpServer(
    private val context: Context,
    private val port: Int,
    private val delegate: KioskServerDelegate
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "KioskHttpServer"
        private const val RATE_LIMIT_WINDOW_MS = 60000L
        private const val MAX_REQUESTS_PER_WINDOW = 60
        private const val MAX_TIMESTAMP_SKEW_MS = 60000L
    }

    private val rateLimits = ConcurrentHashMap<String, MutableList<Long>>()

    private fun parseQueryString(queryString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (queryString.isBlank()) return result
        val pairs = queryString.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun isRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = rateLimits.getOrPut(ip) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > RATE_LIMIT_WINDOW_MS }
            if (timestamps.size >= MAX_REQUESTS_PER_WINDOW) {
                return true
            }
            timestamps.add(now)
            return false
        }
    }

    private fun createResponse(status: Response.IStatus, mimeType: String, txt: String): Response {
        val bytes = txt.toByteArray(Charsets.UTF_8)
        val response = newFixedLengthResponse(status, mimeType, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        response.setGzipEncoding(false)
        response.addHeader("Connection", "close")
        return response
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        // Public status & heartbeat endpoints (no prior AES decryption required)
        if (uri == "/heartbeat" || uri == "/ping") {
            val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
            delegate.onHeartbeat(clientIp)
            return createResponse(Response.Status.OK, "text/plain", "OK")
        }

        if (uri == "/identify" || uri == "/status") {
            val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
            delegate.onHeartbeat(clientIp)
            val json = delegate.getStatusJson()
            return createResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/challenge" || uri == "/heartbeat_challenge") {
            return createResponse(Response.Status.OK, "text/plain", "OK")
        }

        if (uri == "/get_time") {
            return createResponse(Response.Status.OK, "text/plain", delegate.getSessionTimeRemaining().toString())
        }

        if (uri == "/state") {
            return createResponse(Response.Status.OK, "text/plain", delegate.getAppState().toString())
        }

        if (uri == "/audit") {
            val auditJson = delegate.getAuditEventsJson()
            return createResponse(Response.Status.OK, "application/json", auditJson)
        }

        val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"] ?: "unknown"
        if (isRateLimited(clientIp)) {
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "Rate limit exceeded")
        }

        val secretKey = delegate.getSecretKey()
        val payload = params["payload"]
        if (payload.isNullOrBlank()) {
            Log.w(TAG, "Rejected unauthenticated request to protected endpoint: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "Encrypted payload required")
        }

        val hmac = params["hmac"]
        if (hmac.isNullOrBlank()) {
            Log.w(TAG, "Rejected request without HMAC signature: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "HMAC signature required for integrity")
        }

        val outerDeviceId = params["device_id"]?.trim() ?: ""
        val outerTxId = params["tx_id"]?.trim() ?: ""
        val outerTs = params["ts"]?.trim() ?: ""

        if (!KioskSecurity.verifyHttpReqSignature(
                method = session.method.name,
                endpoint = uri,
                recipient = outerDeviceId,
                txId = outerTxId,
                ts = outerTs,
                payload = payload,
                sig = hmac,
                secret = secretKey
            )) {
            Log.w(TAG, "Rejected payload with invalid HttpReq signature: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "HMAC verification failed")
        }

        val decryptedStr = KioskSecurity.decrypt(payload, secretKey)
        if (decryptedStr.isBlank()) {
            Log.w(TAG, "Rejected payload with invalid AES key or corrupted signature: $uri")
            return createResponse(Response.Status.UNAUTHORIZED, "text/plain", "Decryption failed")
        }
        val decryptedParams = parseQueryString(decryptedStr)

        val decTxId = (decryptedParams["tx_id"] ?: decryptedParams["nonce"])?.trim() ?: ""
        val decDeviceId = decryptedParams["device_id"]?.trim() ?: ""
        val decTs = decryptedParams["ts"]?.trim() ?: ""

        if (outerTxId.isNotBlank() && !outerTxId.equals(decTxId, ignoreCase = true)) {
            Log.w(TAG, "Rejecting request: outer tx_id ($outerTxId) does not match decrypted tx_id ($decTxId)")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "MUTATED_TRANSACTION_ID")
        }
        if (outerDeviceId.isNotBlank() && !outerDeviceId.equals(decDeviceId, ignoreCase = true)) {
            Log.w(TAG, "Rejecting request: outer device_id ($outerDeviceId) does not match decrypted device_id ($decDeviceId)")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "MUTATED_DEVICE_ID")
        }
        if (outerTs.isNotBlank() && !outerTs.equals(decTs, ignoreCase = true)) {
            Log.w(TAG, "Rejecting request: outer ts ($outerTs) does not match decrypted ts ($decTs)")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "MUTATED_TIMESTAMP")
        }

        // Validate timestamp freshness (Replay protection Layer 1)
        val ts = decTs.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val skew = Math.abs(now - ts)
        if (ts <= 0L || skew > MAX_TIMESTAMP_SKEW_MS) {
            Log.w(TAG, "Rejecting $uri request: Stale or invalid timestamp ($ts, now=$now, skew=${skew}ms)")
            return createResponse(Response.Status.BAD_REQUEST, "text/plain", "STALE_TIMESTAMP")
        }

        // Replay Protection check: verify unique tx_id (Layer 2)
        val txId = decTxId.ifBlank { null }
        if (uri == "/add_time" || uri == "/coin") {
            if (!delegate.isReady()) {
                Log.w(TAG, "Rejecting payment request to $uri: Server initialization in progress")
                return createResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "INITIALIZING")
            }
            if (txId.isNullOrBlank()) {
                Log.w(TAG, "Rejecting coin credit: Missing tx_id in payload")
                return createResponse(Response.Status.BAD_REQUEST, "text/plain", "MISSING_TX_ID")
            }
        }

        return when (uri) {
            "/add_time", "/coin" -> {
                val targetDev = decDeviceId
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

                if (rawSecondsLong > 0) {
                    if (rawSecondsLong > Int.MAX_VALUE.toLong()) {
                        Log.w(TAG, "Rejecting coin credit: seconds parameter exceeds Int.MAX_VALUE ($rawSecondsLong)")
                        return createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                    }
                    val seconds = rawSecondsLong.toInt()
                    val result = try {
                        delegate.creditPayment(txId!!, seconds, amount)
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

                    when (result) {
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
                    delegate.onDeductTime(positiveSeconds, deductTxId)
                    
                    val ackResp = if (targetDev.isNotBlank()) {
                        val ackNow = System.currentTimeMillis()
                        val statusStr = "OK"
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
                        "OK"
                    }
                    createResponse(Response.Status.OK, "text/plain", ackResp)
                } else {
                    createResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                }
            }
            "/config" -> {
                val price = decryptedParams["price"]?.toDoubleOrNull()
                val minutes = decryptedParams["minutes"]?.toIntOrNull()
                val devName = decryptedParams["device_name"]?.trim()
                val pin = decryptedParams["admin_pin"]
                val slot = decryptedParams["slot"]?.toIntOrNull() ?: decryptedParams["slot_num"]?.toIntOrNull()
                delegate.onConfigUpdated(price, minutes, devName, pin, slot)
                createResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/trigger_action" -> {
                val actionType = decryptedParams["action"] ?: ""
                val slot = decryptedParams["slot"]?.toIntOrNull() ?: decryptedParams["slot_num"]?.toIntOrNull()
                delegate.onTriggerAction(actionType, slot, decryptedParams)
                createResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/emergency_adb" -> {
                delegate.onTriggerAction("enable_adb", null, null)
                createResponse(Response.Status.OK, "text/plain", "RECOVERY_TRIGGERED")
            }
            "/recovery" -> {
                delegate.onTriggerAction("emergency_recovery", null, null)
                createResponse(Response.Status.OK, "text/plain", "RECOVERY_TRIGGERED")
            }
            "/crash" -> {
                val crashText = delegate.getCrashLog()
                if (crashText != null) {
                    createResponse(Response.Status.OK, "text/plain", crashText)
                } else {
                    createResponse(Response.Status.NOT_FOUND, "text/plain", "No crash log found")
                }
            }
            else -> createResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}
