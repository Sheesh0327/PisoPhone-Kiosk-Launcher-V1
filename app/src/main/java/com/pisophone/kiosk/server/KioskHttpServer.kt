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
    fun onHeartbeat(clientIp: String?)
    fun getStatusJson(): JSONObject
    fun getSessionTimeRemaining(): Int
    fun getAppState(): Int
    fun getAuditEventsJson(): String
    fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult
    fun onDeductTime(seconds: Int, txId: String? = null)
    fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int? = null)
    fun onTriggerAction(action: String, slotNum: Int? = null)
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

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        // Public status & heartbeat endpoints (no prior AES decryption required)
        if (uri == "/heartbeat" || uri == "/ping") {
            val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
            delegate.onHeartbeat(clientIp)
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        }

        if (uri == "/identify" || uri == "/status") {
            val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"]
            delegate.onHeartbeat(clientIp)
            val json = delegate.getStatusJson()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }

        if (uri == "/challenge" || uri == "/heartbeat_challenge") {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        }

        if (uri == "/get_time") {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", delegate.getSessionTimeRemaining().toString())
        }

        if (uri == "/state") {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", delegate.getAppState().toString())
        }

        if (uri == "/audit") {
            val auditJson = delegate.getAuditEventsJson()
            return newFixedLengthResponse(Response.Status.OK, "application/json", auditJson)
        }

        val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"] ?: "unknown"
        if (isRateLimited(clientIp)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Rate limit exceeded")
        }

        val secretKey = delegate.getSecretKey()
        val payload = params["payload"]
        if (payload.isNullOrBlank()) {
            Log.w(TAG, "Rejected unauthenticated request to protected endpoint: $uri")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Encrypted payload required")
        }

        val hmac = params["hmac"]
        if (hmac.isNullOrBlank()) {
            Log.w(TAG, "Rejected request without HMAC signature: $uri")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "HMAC signature required for integrity")
        }

        val expectedHmac = KioskSecurity.calculateHmac(payload, secretKey)
        if (!KioskSecurity.constantTimeEquals(hmac.trim().lowercase(), expectedHmac.trim().lowercase())) {
            Log.w(TAG, "Rejected payload with invalid HMAC signature: $uri")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "HMAC verification failed")
        }

        val decryptedStr = KioskSecurity.decrypt(payload, secretKey)
        if (decryptedStr.isBlank()) {
            Log.w(TAG, "Rejected payload with invalid AES key or corrupted signature: $uri")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Decryption failed")
        }
        val decryptedParams = parseQueryString(decryptedStr)

        // Validate timestamp freshness (Replay protection Layer 1)
        val ts = decryptedParams["ts"]?.trim()?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val skew = Math.abs(now - ts)
        if (ts <= 0L || skew > MAX_TIMESTAMP_SKEW_MS) {
            Log.w(TAG, "Rejecting $uri request: Stale or invalid timestamp ($ts, now=$now, skew=${skew}ms)")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "STALE_TIMESTAMP")
        }

        // Replay Protection check: verify unique tx_id (Layer 2)
        val txId = (decryptedParams["tx_id"] ?: decryptedParams["nonce"])?.trim()
        if (uri == "/add_time" || uri == "/coin") {
            if (!delegate.isReady()) {
                Log.w(TAG, "Rejecting payment request to $uri: Server initialization in progress")
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "INITIALIZING")
            }
            if (txId.isNullOrBlank()) {
                Log.w(TAG, "Rejecting coin credit: Missing tx_id in payload")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "MISSING_TX_ID")
            }
        }

        return when (uri) {
            "/add_time", "/coin" -> {
                val hasMinutes = decryptedParams.containsKey("minutes")
                val hasSeconds = decryptedParams.containsKey("seconds")
                val minutesLong = decryptedParams["minutes"]?.toLongOrNull()
                val secondsParamLong = decryptedParams["seconds"]?.toLongOrNull()
                if ((hasMinutes && minutesLong == null) || (hasSeconds && secondsParamLong == null) || (!hasMinutes && !hasSeconds)) {
                    Log.w(TAG, "Rejecting coin credit: Invalid time parameters")
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_PAYMENT")
                }
                val rawSecondsLong = secondsParamLong ?: ((minutesLong ?: 0L) * 60L)

                val hasAmount = decryptedParams.containsKey("amount")
                val amountParam = decryptedParams["amount"]?.toDoubleOrNull()
                if (hasAmount && (amountParam == null || amountParam.isNaN() || amountParam.isInfinite())) {
                    Log.w(TAG, "Rejecting coin credit: Invalid amount parameter")
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_AMOUNT")
                }
                val amount = amountParam ?: 1.0
                if (amount < 0.0) {
                    Log.w(TAG, "Rejecting coin credit: Negative amount")
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_AMOUNT")
                }

                if (rawSecondsLong > 0) {
                    if (rawSecondsLong > Int.MAX_VALUE.toLong()) {
                        Log.w(TAG, "Rejecting coin credit: seconds parameter exceeds Int.MAX_VALUE ($rawSecondsLong)")
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                    }
                    val seconds = rawSecondsLong.toInt()
                    val result = try {
                        delegate.creditPayment(txId!!, seconds, amount)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during creditPayment for $txId: ${e.message}", e)
                        PaymentResult.FAILED
                    }
                    when (result) {
                        PaymentResult.APPLIED -> {
                            newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                        }
                        PaymentResult.ALREADY_APPLIED -> {
                            // Acknowledge duplicate without extending time so ESP32 clears retry queue
                            newFixedLengthResponse(Response.Status.OK, "text/plain", "ALREADY_PROCESSED")
                        }
                        PaymentResult.CONFLICT -> {
                            Log.w(TAG, "Payment rejected due to conflicting values for $txId")
                            newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "CONFLICT")
                        }
                        PaymentResult.NOT_ELIGIBLE -> {
                            Log.w(TAG, "Payment rejected: device not eligible for $txId")
                            newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "NOT_ELIGIBLE")
                        }
                        PaymentResult.FAILED -> {
                            Log.e(TAG, "Payment failed to commit to database for $txId")
                            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "SERVICE_UNAVAILABLE")
                        }
                    }
                } else if (rawSecondsLong < 0) {
                    val positiveSecondsLong = if (rawSecondsLong == Long.MIN_VALUE) Long.MAX_VALUE else -rawSecondsLong
                    if (positiveSecondsLong > Int.MAX_VALUE.toLong()) {
                        Log.w(TAG, "Rejecting deduction: seconds parameter magnitude exceeds Int.MAX_VALUE ($rawSecondsLong)")
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                    }
                    val positiveSeconds = positiveSecondsLong.toInt()
                    val deductTxId = txId ?: "deduct_${System.currentTimeMillis()}"
                    delegate.onDeductTime(positiveSeconds, deductTxId)
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                } else {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "INVALID_SECONDS")
                }
            }
            "/config" -> {
                val price = decryptedParams["price"]?.toDoubleOrNull()
                val minutes = decryptedParams["minutes"]?.toIntOrNull()
                val devName = decryptedParams["device_name"]?.trim()
                val pin = decryptedParams["admin_pin"]
                val slot = decryptedParams["slot"]?.toIntOrNull() ?: decryptedParams["slot_num"]?.toIntOrNull()
                delegate.onConfigUpdated(price, minutes, devName, pin, slot)
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/trigger_action" -> {
                val actionType = decryptedParams["action"] ?: ""
                val slot = decryptedParams["slot"]?.toIntOrNull() ?: decryptedParams["slot_num"]?.toIntOrNull()
                delegate.onTriggerAction(actionType, slot)
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/emergency_adb" -> {
                delegate.onTriggerAction("enable_adb")
                newFixedLengthResponse(Response.Status.OK, "text/plain", "RECOVERY_TRIGGERED")
            }
            "/recovery" -> {
                delegate.onTriggerAction("emergency_recovery")
                newFixedLengthResponse(Response.Status.OK, "text/plain", "RECOVERY_TRIGGERED")
            }
            "/crash" -> {
                val crashText = delegate.getCrashLog()
                if (crashText != null) {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", crashText)
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No crash log found")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}
