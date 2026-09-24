package com.pisophone.kiosk.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

interface Esp32ConnectionDelegate {
    fun getDeviceId(): String
    fun getSecretKey(): String
    fun getAppState(): Int
    fun getSessionTimeRemaining(): Int
    fun getRealTimeBatteryInfo(): Pair<Int, Boolean>
    fun onEsp32Paired(ip: String)
    fun onOnlineStatusChanged(isOnline: Boolean, mac: String?)
    fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String? = null, slotNum: Int? = null)
    fun onCoinMessageReceived(
        seconds: Int,
        amount: Double,
        txId: String?,
        operationKind: String,
        coinAmount: Int,
        pricePerCoin: Double,
        boxInstallationEpoch: Long,
        phonePairingEpoch: Long
    ): com.pisophone.kiosk.repository.PaymentResult
    fun onSlotBusy()
    fun onArmSuccess()
    fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String)
    fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long)
    fun onSlotRestored(slotNum: Int = 0)
    fun onArenaModeSynced(active: Boolean, role: Int, stake: Int) {}
}

/**
 * Manages active communication with the ESP32 Master kiosk box:
 * - HTTP Telemetry & Config Sync Loop (Port 80) via [Esp32HeartbeatTracker]
 * - WebSocket Slot Arming & Encrypted Coin Event Listener (Port 81)
 * - Direct pairing via /api/slots/pair_request delegated to [Esp32DirectPairing]
 */
class Esp32ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: Esp32ConnectionDelegate
) {
    companion object {
        private const val TAG = "Esp32ConnectionManager"
        const val DEFAULT_STATIC_ESP32_IP = Esp32DirectPairing.DEFAULT_STATIC_ESP32_IP
        const val DEFAULT_WEB_PORT = Esp32DirectPairing.DEFAULT_WEB_PORT
        private const val ESP32_WS_PORT = 81
        private const val DRAIN_SAFETY_TIMEOUT_MS = 15000L

        fun getEsp32HostAndPort(rawIp: String?): Pair<String, Int> =
            Esp32DirectPairing.getEsp32HostAndPort(rawIp)

        fun getLocalIpAddress(): String =
            Esp32DirectPairing.getLocalIpAddress()

        fun isEsp32MacMatching(expectedMac: String?, candidateMac: String?): Boolean =
            Esp32DirectPairing.isEsp32MacMatching(expectedMac, candidateMac)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val directPairing = Esp32DirectPairing(context, scope, httpClient, delegate)

    private val heartbeatTracker = Esp32HeartbeatTracker(
        context = context,
        scope = scope,
        httpClient = httpClient,
        delegate = delegate,
        onTriggerDirectPairing = { targetIp -> sendDirectPairingRequest(targetIp) }
    )

    private var esp32Ip: String? = KioskSecurity.getConfiguredEsp32Ip(context)
    private val connectionLock = Any()
    private var currentAttemptId = 0L
    private var activeWebSocket: WebSocket? = null
    private var isDraining: Boolean = false
    private var drainJob: Job? = null

    fun getEsp32Ip(): String? = esp32Ip

    fun setEsp32Ip(ip: String?) {
        esp32Ip = ip
    }

    fun markHeartbeatReceived() {
        heartbeatTracker.markHeartbeatReceived()
    }

    fun sendDirectPairingRequest(
        targetIp: String? = null,
        targetMac: String? = null,
        onResult: ((Boolean, String?) -> Unit)? = null
    ): Job {
        return directPairing.sendDirectPairingRequest(
            targetIp = targetIp,
            targetMac = targetMac,
            currentEsp32Ip = esp32Ip,
            onSuccess = { host, _ ->
                esp32Ip = host
                heartbeatTracker.markHeartbeatReceived()
            },
            onResult = onResult
        )
    }

    fun unpair(onResult: ((Boolean, String?) -> Unit)? = null): Job {
        return directPairing.unpair(
            currentEsp32Ip = esp32Ip,
            onLocalStateReset = {
                closeSession(sendUnarmToEsp = false)
                esp32Ip = null
            },
            onResult = onResult
        )
    }

    fun startHeartbeatLoop(deviceIpProvider: () -> String) {
        heartbeatTracker.startHeartbeatLoop(
            esp32IpProvider = { esp32Ip },
            deviceIpProvider = deviceIpProvider
        )
    }

    fun closeSession(sendUnarmToEsp: Boolean = false, command: String = "DONE") {
        synchronized(connectionLock) {
            val ws = activeWebSocket
            if (ws == null) {
                isDraining = false
                drainJob?.cancel()
                drainJob = null
                return
            }

            if (sendUnarmToEsp) {
                if (isDraining) {
                    Log.d(TAG, "closeSession(sendUnarmToEsp=true) invoked while already draining; skipping duplicate send.")
                    return
                }
                isDraining = true
                val cmdToSend = if (command.isBlank()) "DONE" else command.trim().uppercase()
                try {
                    val enqueued = ws.send(cmdToSend)
                    Log.d(TAG, "Sent '$cmdToSend' to ESP32 WebSocket (enqueued=$enqueued). Entering draining state (safety timeout: ${DRAIN_SAFETY_TIMEOUT_MS}ms).")
                    if (!enqueued) {
                        Log.w(TAG, "Failed to send $cmdToSend to ESP32: socket send buffer full or closing")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send $cmdToSend to ESP32: ${e.message}")
                }
                drainJob?.cancel()
                val targetAttemptId = currentAttemptId
                drainJob = scope.launch(Dispatchers.IO) {
                    try {
                        delay(DRAIN_SAFETY_TIMEOUT_MS)
                        Log.w(TAG, "Drain safety timeout reached (${DRAIN_SAFETY_TIMEOUT_MS}ms) without ESP32 closure; closing WebSocket.")
                        forceCloseWebSocketIfAttemptCurrent(ws, "Drain safety timeout", targetAttemptId)
                    } catch (e: kotlinx.coroutines.CancellationException) {}
                }
            } else {
                forceCloseWebSocketLocked(ws, "Session aborted", currentAttemptId)
            }
        }
    }

    fun disarmSlot(command: String = "CANCEL") {
        closeSession(sendUnarmToEsp = true, command = command)
    }

    private fun forceCloseWebSocket(ws: WebSocket?, reason: String) {
        synchronized(connectionLock) {
            forceCloseWebSocketLocked(ws, reason, currentAttemptId)
        }
    }

    private fun forceCloseWebSocketIfAttemptCurrent(ws: WebSocket?, reason: String, callerAttemptId: Long) {
        synchronized(connectionLock) {
            if (callerAttemptId != currentAttemptId && ws !== activeWebSocket) {
                Log.d(TAG, "Ignoring forceClose for obsolete WebSocket attempt ($callerAttemptId vs current $currentAttemptId)")
                return
            }
            forceCloseWebSocketLocked(ws, reason, callerAttemptId)
        }
    }

    private fun forceCloseWebSocketLocked(ws: WebSocket?, reason: String, callerAttemptId: Long) {
        val targetWs = ws ?: activeWebSocket
        if (targetWs != null) {
            val isActiveTarget = (targetWs === activeWebSocket)
            if (isActiveTarget) {
                activeWebSocket = null
                drainJob?.cancel()
                drainJob = null
                isDraining = false
            }
            try {
                if (!targetWs.close(1000, reason)) {
                    targetWs.cancel()
                }
            } catch (e: Exception) {
                targetWs.cancel()
            }
            Log.d(TAG, "WebSocket forced closed (reason: '$reason', attempt: $callerAttemptId, wasActive: $isActiveTarget)")
        }
    }

    fun armSlot(timeoutSeconds: Int = 60) {
        scope.launch(Dispatchers.IO) {
            val targetIp = esp32Ip ?: KioskSecurity.getConfiguredEsp32Ip(context).ifBlank { DEFAULT_STATIC_ESP32_IP }
            val (host, _) = getEsp32HostAndPort(targetIp)
            val thisAttemptId: Long

            synchronized(connectionLock) {
                currentAttemptId++
                thisAttemptId = currentAttemptId

                activeWebSocket?.let { oldWs ->
                    Log.i(TAG, "Closing previous active WebSocket connection before new arm attempt ($thisAttemptId)")
                    try { oldWs.close(1000, "Arming new session") } catch (e: Exception) {}
                    try { oldWs.cancel() } catch (e: Exception) {}
                }
                activeWebSocket = null
                drainJob?.cancel()
                drainJob = null
                isDraining = false
            }

            val deviceId = delegate.getDeviceId()
            val wsUrl = "ws://$host:$ESP32_WS_PORT/ws/arm?device_id=$deviceId&timeout=$timeoutSeconds"
            val request = Request.Builder().url(wsUrl).build()

            val webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(connectionLock) {
                        if (thisAttemptId != currentAttemptId) {
                            Log.w(TAG, "Obsolete WebSocket connected (attempt $thisAttemptId vs $currentAttemptId). Discarding.")
                            try { webSocket.close(1000, "Obsolete attempt") } catch (e: Exception) {}
                            return
                        }
                        activeWebSocket = webSocket
                        isDraining = false
                    }
                    Log.i(TAG, "Connected to ESP32 WebSocket for arming (Attempt #$thisAttemptId)")
                    delegate.onArmSuccess()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(connectionLock) {
                        if (thisAttemptId != currentAttemptId && webSocket !== activeWebSocket) {
                            Log.w(TAG, "Ignoring incoming message from obsolete WebSocket (attempt $thisAttemptId vs $currentAttemptId)")
                            return
                        }
                    }

                    Esp32CoinMessageProcessor.processMessage(
                        text = text,
                        delegate = delegate,
                        webSocket = webSocket,
                        attemptId = thisAttemptId,
                        onCoinProcessed = {
                            // Coin credited successfully
                        }
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "ESP32 WebSocket onClosing: $code / $reason (Attempt #$thisAttemptId)")
                    webSocket.close(1000, null)
                    forceCloseWebSocketIfAttemptCurrent(webSocket, "Server closing: $reason", thisAttemptId)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "ESP32 WebSocket onClosed: $code / $reason (Attempt #$thisAttemptId)")
                    forceCloseWebSocketIfAttemptCurrent(webSocket, "Server closed: $reason", thisAttemptId)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "ESP32 WebSocket onFailure (Attempt #$thisAttemptId): ${t.message}")
                    forceCloseWebSocketIfAttemptCurrent(webSocket, "Transport failure: ${t.message}", thisAttemptId)
                }
            })
        }
    }

    fun shutdown() {
        heartbeatTracker.stop()
        closeSession(sendUnarmToEsp = false)
        try {
            okHttpClient.dispatcher.executorService.shutdown()
            httpClient.dispatcher.executorService.shutdown()
        } catch (e: Exception) {}
    }
}
