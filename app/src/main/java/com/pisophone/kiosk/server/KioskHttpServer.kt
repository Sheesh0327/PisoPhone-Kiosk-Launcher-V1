package com.pisophone.kiosk.server

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.security.HardwareLockManager
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
    fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?)
    fun onTriggerAction(action: String)
    fun getCrashLog(): String?
}

class KioskHttpServer(
    private val context: Context,
    private val port: Int,
    private val delegate: KioskServerDelegate
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "KioskHttpServer"
        private const val CHALLENGE_EXPIRY_MS = 15000L
        private const val RATE_LIMIT_WINDOW_MS = 60000L
        private const val MAX_REQUESTS_PER_WINDOW = 60
    }

    private val activeChallenges = ConcurrentHashMap<String, Long>()
    private val rateLimits = ConcurrentHashMap<String, MutableList<Long>>()

    fun generateChallenge(): String {
        val randomBytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(randomBytes)
        val token = randomBytes.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        activeChallenges[token] = now
        
        // Clean up expired challenges older than 15s
        val iterator = activeChallenges.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > CHALLENGE_EXPIRY_MS) {
                iterator.remove()
            }
        }
        return token
    }

    fun verifyChallengeAndSignature(challenge: String, signature: String): Boolean {
        val issueTime = activeChallenges.remove(challenge) ?: return false
        if (System.currentTimeMillis() - issueTime > CHALLENGE_EXPIRY_MS) {
            return false
        }
        val expectedSignature = KioskSecurity.calculateHmac(challenge, delegate.getSecretKey())
        return KioskSecurity.constantTimeEquals(signature, expectedSignature)
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

        // Public status & heartbeat endpoints (no prior HMAC challenge exchange needed)
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
            return newFixedLengthResponse(Response.Status.OK, "text/plain", generateChallenge())
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

        // Protected action endpoints (require cryptographic HMAC authentication)
        if (!HardwareLockManager.isHardwareAuthorized(context)) {
            Log.e(TAG, "Rejecting HTTP action: Hardware lock is active on unauthorized device.")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Hardware lock active on unauthorized device")
        }

        val clientIp = session.headers["remote-addr"] ?: session.headers["http-client-ip"] ?: "unknown"
        if (isRateLimited(clientIp)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Rate limit exceeded")
        }

        if (uri == "/emergency_adb" || uri == "/recovery") {
            val challenge = params["challenge"]
            val signature = params["signature"] ?: params["sig"]
            val isAuthorized = (challenge != null && signature != null && verifyChallengeAndSignature(challenge, signature))
            if (isAuthorized) {
                if (uri == "/emergency_adb") {
                    delegate.onTriggerAction("enable_adb")
                } else {
                    delegate.onTriggerAction("emergency_recovery")
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "RECOVERY_TRIGGERED")
            } else {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid signature")
            }
        }

        if (uri == "/coin") {
            val txId = params["tx_id"] ?: params["nonce"] ?: UUID.randomUUID().toString()
            val seconds = params["seconds"]?.toIntOrNull() ?: 1800
            val challenge = params["challenge"]
            val signature = params["signature"] ?: params["sig"]

            if (challenge != null && signature != null && verifyChallengeAndSignature(challenge, signature)) {
                val amount = params["amount"]?.toDoubleOrNull() ?: 5.0
                delegate.onCoinCredited(seconds, "HTTP /coin", txId, amount)
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            } else {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid signature or challenge")
            }
        }

        val challenge = params["challenge"]
        val signature = params["signature"]
        if (challenge == null || signature == null || !verifyChallengeAndSignature(challenge, signature)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid challenge")
        }

        return when (uri) {
            "/add_time" -> {
                val minutes = params["minutes"]?.toIntOrNull() ?: 0
                val secondsParam = params["seconds"]?.toIntOrNull()
                val seconds = secondsParam ?: (minutes * 60)
                val amount = params["amount"]?.toDoubleOrNull() ?: 1.0
                val txId = params["tx_id"]
                if (seconds > 0) {
                    delegate.onCoinCredited(seconds, "HTTP /add_time", if (!txId.isNullOrBlank()) txId else null, amount)
                } else if (seconds < 0) {
                    delegate.onDeductTime(seconds)
                }
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/config" -> {
                val price = params["price"]?.toDoubleOrNull()
                val minutes = params["minutes"]?.toIntOrNull()
                val devName = params["device_name"]?.trim()
                val pin = params["admin_pin"]
                delegate.onConfigUpdated(price, minutes, devName, pin)
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }
            "/trigger_action" -> {
                val actionType = params["action"] ?: ""
                delegate.onTriggerAction(actionType)
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
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
