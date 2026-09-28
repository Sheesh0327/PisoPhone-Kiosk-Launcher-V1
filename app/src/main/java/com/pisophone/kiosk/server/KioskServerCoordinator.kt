package com.pisophone.kiosk.server

import android.util.Log
import com.pisophone.kiosk.repository.PaymentResult
import fi.iki.elonen.NanoHTTPD

class KioskServerCoordinator(
    private val delegate: KioskServerDelegate,
    private val defaultPort: Int = 8080
) {
    companion object {
        private const val TAG = "KioskServerCoordinator"
    }

    private var server: KioskHttpServer? = null
    private val lock = Any()

    fun isServerRunning(): Boolean {
        synchronized(lock) {
            return server != null && server?.isAlive == true
        }
    }

    fun startServer(port: Int = defaultPort) {
        synchronized(lock) {
            if (server != null && server?.isAlive == true) {
                Log.d(TAG, "KioskHttpServer is already running on port $port")
                return
            }
            try {
                server?.stop()
                server = null
                val newServer = KioskHttpServer(port, delegate)
                newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = newServer
                Log.i(TAG, "KioskHttpServer successfully started and listening on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start KioskHttpServer on port $port: ${e.message}", e)
            }
        }
    }

    fun stopServer() {
        synchronized(lock) {
            try {
                server?.stop()
                Log.i(TAG, "KioskHttpServer stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping KioskHttpServer: ${e.message}")
            } finally {
                server = null
            }
        }
    }

    // Convenience delegates for testing and cross-channel compatibility
    fun creditPayment(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String = "COIN",
        coinAmount: Int = amount.toInt(),
        pricePerCoin: Double = 0.0,
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult {
        return delegate.onCreditPayment(
            txId = txId,
            seconds = seconds,
            amount = amount,
            operationKind = operationKind,
            coinAmount = coinAmount,
            pricePerCoin = pricePerCoin,
            boxInstallationEpoch = boxInstallationEpoch,
            phonePairingEpoch = phonePairingEpoch
        )
    }

    fun onDeductTime(
        seconds: Int,
        txId: String? = null,
        operationKind: String = "MANUAL_DEDUCTION",
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult {
        return delegate.onDeductPayment(
            seconds = seconds,
            txId = txId,
            operationKind = operationKind,
            boxInstallationEpoch = boxInstallationEpoch,
            phonePairingEpoch = phonePairingEpoch
        )
    }
}
