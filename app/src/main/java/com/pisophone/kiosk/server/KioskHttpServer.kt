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
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
        }
        val parms = session.parms
        val price = parms["price"]?.toDoubleOrNull() ?: parms["price_per_coin"]?.toDoubleOrNull()
        val minutes = parms["minutes"]?.toIntOrNull() ?: parms["minutes_per_coin"]?.toIntOrNull()
        val alias = parms["alias"] ?: parms["device_alias"]
        val pin = parms["admin_pin"] ?: parms["pin"]
        val slotNum = parms["slot"]?.toIntOrNull() ?: parms["slot_num"]?.toIntOrNull()

        delegate.onConfigSynced(price, minutes, alias, pin, slotNum)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    private fun handleTriggerAction(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
        }
        val parms = session.parms
        val action = parms["action"] ?: ""
        val handled = delegate.onTriggerAction(action, parms)
        return if (handled) {
            newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "UNKNOWN_ACTION")
        }
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val devId = delegate.getDeviceId()
        val ready = delegate.isInitialized()
        val json = "{\"status\":\"ok\",\"device_id\":\"$devId\",\"ready\":$ready}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
}
