package com.pisophone.kiosk.service

import android.os.SystemClock
import android.util.Log
import com.pisophone.kiosk.overlay.KioskOverlayCoordinator
import com.pisophone.kiosk.repository.PaymentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KioskEngineHealthMonitor(
    private val scope: CoroutineScope,
    private val stateManager: KioskStateManager,
    private val paymentRepo: PaymentRepository,
    private val overlayCoordinator: KioskOverlayCoordinator,
    private val supervisor: KioskSessionSupervisor
) {
    companion object {
        private const val TAG = "KioskEngineHealth"
    }

    fun start() {
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(10_000L)
                try {
                    supervisor.ensureRunning()
                    val appState = stateManager.appState.value
                    if (appState == 2 || appState == 3) {
                        val deadline = stateManager.sessionExpiryDeadlineMs.value
                        val nowMonotonic = SystemClock.elapsedRealtime()
                        if (deadline > 0L && nowMonotonic >= deadline) {
                            val expiryResult = paymentRepo.expireSessionIfDueBlocking()
                            if (expiryResult.didExpire) {
                                val applied = stateManager.applySessionUpdate(
                                    deadlineMs = expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    remainingSeconds = expiryResult.sessionState.sessionTimeRemaining,
                                    revision = expiryResult.sessionState.revision,
                                    targetAppState = 0
                                )
                                if (applied) {
                                    Log.w(TAG, "Health monitor: Session deadline expired ($deadline <= $nowMonotonic). Forcing lock state.")
                                    stateManager.saveState()
                                } else {
                                    Log.d(TAG, "Health monitor: Skipping stale expiration lock because newer revision is active")
                                }
                            } else {
                                stateManager.applySessionUpdate(
                                    expiryResult.sessionState.sessionExpiryDeadlineMs,
                                    expiryResult.sessionState.sessionTimeRemaining,
                                    expiryResult.sessionState.revision
                                )
                            }
                        }
                    }

                    val currentAppState = stateManager.appState.value
                    if (currentAppState == 0 || currentAppState == 1) {
                        if (!overlayCoordinator.isOverlayHealthy()) {
                            Log.w(TAG, "Health monitor: Overlay missing or detached while locked/armed (AppState: $currentAppState). Rebuilding...")
                            overlayCoordinator.remove()
                            overlayCoordinator.setupOverlay()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Health monitor check failed: ${e.message}")
                }
            }
        }
    }

    fun checkHttpLoopbackHealth(serverPort: Int): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:$serverPort/ping")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
