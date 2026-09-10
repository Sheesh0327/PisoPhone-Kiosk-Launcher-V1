package com.pisophone.kiosk.server

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface KioskServerDelegate {
    fun getSecretKey(): String
    fun onHeartbeat(clientIp: String?)
    fun getStatusJson(): JSONObject
    fun getSessionTimeRemaining(): Int
    fun getAppState(): Int
    fun getAuditEventsJson(): String
    fun onCoinCredited(seconds: Int, source: String, txId: String?, amount: Double): Boolean
    fun onDeductTime(seconds: Int)
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
        private const val MAX_TRACKED_TX = 200
    }

    private val rateLimits = ConcurrentHashMap<String, MutableList<Long>>()
    private val processedTxIds = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    private fun isTxIdProcessed(txId: String): Boolean {
        return processedTxIds.contains(txId)
    }

    private fun markTxIdProcessed(txId: String) {
        synchronized(processedTxIds) {
            if (processedTxIds.size >= MAX_TRACKED_TX) {
                val iterator = processedTxIds.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            processedTxIds.add(txId)
        }
    }

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
        val decryptedStr = KioskSecurity.decrypt(payload, secretKey)
        if (decryptedStr.isBlank()) {
            Log.w(TAG, "Rejected payload with invalid AES key or corrupted signature: $uri")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Decryption failed")
        }
        val decryptedParams = parseQueryString(decryptedStr)

        // Replay Protection check: verify unique tx_id
        val txId = decryptedParams["tx_id"] ?: decryptedParams["nonce"]
        if (!txId.isNullOrBlank()) {
            if (isTxIdProcessed(txId)) {
                Log.w(TAG, "Rejecting replayed or duplicate transaction: $txId")
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "ALREADY_PROCESSED")
            }
            markTxIdProcessed(txId)
        }

        // Protected action endpoints require active hardware license/authorization
        if (!KioskActivationManager.isHardwareAuthorized(context)) {
            Log.e(TAG, "Rejecting HTTP action: Hardware lock is active on unauthorized device.")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Hardware lock active on unauthorized device")
        }

        return when (uri) {
            "/add_time", "/coin" -> {
                val minutes = decryptedParams["minutes"]?.toIntOrNull() ?: 0
                val secondsParam = decryptedParams["seconds"]?.toIntOrNull()
                val seconds = secondsParam ?: (minutes * 60)
                val amount = decryptedParams["amount"]?.toDoubleOrNull() ?: 1.0
                if (seconds > 0) {
                    delegate.onCoinCredited(seconds, "HTTP /add_time", if (!txId.isNullOrBlank()) txId else null, amount)
                } else if (seconds < 0) {
                    delegate.onDeductTime(seconds)
                }
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
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
