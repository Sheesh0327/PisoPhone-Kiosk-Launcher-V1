package com.pisophone.kiosk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pisophone.kiosk.db.CoinEvent
import com.pisophone.kiosk.repository.CoinEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles deduplication, anti-jitter, repository event logging, and feedback effects
 * for all validated coin and top-up transactions.
 */
class CoinProcessor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val coinEventRepo: CoinEventRepository,
    private val onCreditsApplied: (seconds: Int, pesoAmount: Int) -> Unit,
    private val onFeedbackTrigger: () -> Unit
) {
    companion object {
        private const val TAG = "CoinProcessor"
        private const val JITTER_DEBOUNCE_MS = 250L
        private const val TX_EXPIRATION_MS = 120_000L // 2 minutes
    }

    private val processedCoinTxIds = ConcurrentHashMap<String, Long>()
    private var lastCoinCreditedTime = 0L

    fun restoreProcessedTxIds(txSet: Set<String>) {
        val now = System.currentTimeMillis()
        txSet.forEach { tx ->
            processedCoinTxIds[tx] = now
        }
    }

    fun getProcessedTxSet(): Set<String> {
        return processedCoinTxIds.keys.toSet()
    }

    @Synchronized
    fun processCoinCredit(
        seconds: Int,
        source: String,
        txId: String?,
        amount: Double = 1.0,
        isStartupPhase: Boolean = false
    ): Boolean {
        if (!com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)) {
            Log.w(TAG, "Rejecting coin credit: Slot lockdown active.")
            return false
        }

        if (txId.isNullOrBlank()) {
            Log.w(TAG, "Rejecting unauthenticated coin credit from $source: Missing mandatory transaction ID.")
            return false
        }

        val now = System.currentTimeMillis()

        // Clean up expired transaction IDs older than 2 minutes
        val txIterator = processedCoinTxIds.entries.iterator()
        while (txIterator.hasNext()) {
            val entry = txIterator.next()
            if (now - entry.value > TX_EXPIRATION_MS) {
                txIterator.remove()
            }
        }

        // Transaction ID Deduplication
        if (processedCoinTxIds.containsKey(txId)) {
            Log.d(TAG, "Coin transaction $txId already credited, ignoring duplicate.")
            return true
        }
        processedCoinTxIds[txId] = now

        lastCoinCreditedTime = now
        val pesoVal = if (amount >= 1.0) amount.toInt() else 1
        Log.d(TAG, "Coin credited: +$seconds seconds (₱$pesoVal) via $source (txId=$txId)")

        // Notify Service to apply state and timer updates
        onCreditsApplied(seconds, pesoVal)

        // Persist audit record in SQLite Room DB
        scope.launch(Dispatchers.IO) {
            try {
                coinEventRepo.insertEvent(
                    CoinEvent(
                        txId = txId,
                        secondsAdded = seconds,
                        source = "$source (₱$pesoVal)"
                    )
                )
                coinEventRepo.deleteOldEvents(500)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log coin event to audit ledger: ${e.message}")
            }
        }

        // Trigger Audio, Haptics, and Torch Feedback
        onFeedbackTrigger()

        // Show toast confirmation
        Handler(Looper.getMainLooper()).post {
            val addedMins = seconds / 60
            Toast.makeText(context, "₱$pesoVal coin accepted! (+${addedMins}m)", Toast.LENGTH_SHORT).show()
        }
        return true
    }
}
