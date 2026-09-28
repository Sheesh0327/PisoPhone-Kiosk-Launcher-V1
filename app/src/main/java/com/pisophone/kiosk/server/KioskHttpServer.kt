package com.pisophone.kiosk.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD

class KioskHttpServer(
    port: Int = 8080,
    private val delegate: KioskServerDelegate,
    private val paymentHandler: KioskHttpPaymentHandler = KioskHttpPaymentHandler(delegate)
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "KioskHttpServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Incoming HTTP ${session.method} request: $uri")

        return try {
            when {
                uri.startsWith("/add_time") || uri.startsWith("/coin") -> {
                    paymentHandler.handlePaymentRequest(session)
                }
                uri.startsWith("/challenge") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "nonce=${System.currentTimeMillis()}")
                }
                uri.startsWith("/config") -> {
                    handleConfig(session)
                }
                uri.startsWith("/trigger_action") -> {
                    handleTriggerAction(session)
                }
                uri.startsWith("/status") || uri.startsWith("/identify") -> {
                    handleStatus(session)
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception serving $uri: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal Error: ${e.message}")
        }
    }

    private fun handleConfig(session: IHTTPSession): Response {
        val decryptedParms = decryptAndVerifySession(session)
            ?: return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "UNAUTHORIZED")

        val price = decryptedParms["price"]?.toDoubleOrNull() ?: decryptedParms["price_per_coin"]?.toDoubleOrNull()
        val minutes = decryptedParms["minutes"]?.toIntOrNull() ?: decryptedParms["minutes_per_coin"]?.toIntOrNull()
        val alias = decryptedParms["alias"] ?: decryptedParms["device_alias"]
        val pin = decryptedParms["admin_pin"] ?: decryptedParms["pin"]
        val slotNum = decryptedParms["slot"]?.toIntOrNull() ?: decryptedParms["slot_num"]?.toIntOrNull()

        delegate.onConfigSynced(price, minutes, alias, pin, slotNum)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    private fun handleTriggerAction(session: IHTTPSession): Response {
        val decryptedParms = decryptAndVerifySession(session)
            ?: return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "UNAUTHORIZED")

        val action = decryptedParms["action"] ?: ""
        val handled = delegate.onTriggerAction(action, decryptedParms)
        return if (handled) {
            newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "UNKNOWN_ACTION")
        }
    }

    private fun decryptAndVerifySession(session: IHTTPSession): Map<String, String>? {
        try {
            if (session.method == Method.POST) {
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

            if (payload.isNullOrBlank()) {
                Log.w(TAG, "Rejected HTTP request: Missing payload envelope for ${session.uri}")
                return null
            }

            val tsVal = ts.toLongOrNull() ?: 0L
            val currentMs = System.currentTimeMillis()
            if (Math.abs(currentMs - tsVal) > 60000L) {
                Log.w(TAG, "Rejected HTTP request: Timestamp skew ($currentMs vs $tsVal) for ${session.uri}")
                return null
            }

            val targetDev = if (deviceId.isNotBlank()) deviceId else expectedDevId
            val validSig = com.pisophone.kiosk.protocol.KioskProtocol.verifyHttpReqSignature(
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
                Log.w(TAG, "Rejected HTTP request: Invalid signature for ${session.uri}")
                return null
            }

            val decrypted = com.pisophone.kiosk.security.KioskSecurity.decrypt(payload, secretKey)
            val extractedParams = HashMap<String, String>()
            parseQueryParams(decrypted, extractedParams)
            return extractedParams
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting/verifying request for ${session.uri}: ${e.message}", e)
            return null
        }
    }

    private fun parseQueryParams(query: String, outMap: MutableMap<String, String>) {
        if (query.isBlank()) return
        val pairs = query.split('&')
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = if (idx < pair.length - 1) java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8") else ""
                outMap[key] = value
            }
        }
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val devId = delegate.getDeviceId()
        val ready = delegate.isInitialized()
        val json = "{\"status\":\"ok\",\"device_id\":\"$devId\",\"ready\":$ready}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
}
