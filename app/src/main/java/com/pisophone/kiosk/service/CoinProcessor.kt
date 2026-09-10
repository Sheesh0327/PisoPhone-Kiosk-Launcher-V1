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
        txId: String? = null,
        amount: Double = 1.0,
        isStartupPhase: Boolean = false
    ): Boolean {
        if (!com.pisophone.kiosk.security.KioskActivationManager.isAppAllowedToRun(context)) {
            Log.w(TAG, "Rejecting coin credit: Slot lockdown active.")
            return false
        }

        val now = System.currentTimeMillis()

        // Boot startup noise guard
        if (isStartupPhase && txId.isNullOrBlank()) {
            Log.d(TAG, "Discarded boot pulse noise from $source during startup phase.")
            return false
        }

        // Clean up expired transaction IDs older than 2 minutes
        val txIterator = processedCoinTxIds.entries.iterator()
        while (txIterator.hasNext()) {
            val entry = txIterator.next()
            if (now - entry.value > TX_EXPIRATION_MS) {
                txIterator.remove()
            }
        }

        // 1. Transaction ID Deduplication
        if (!txId.isNullOrBlank()) {
            if (processedCoinTxIds.containsKey(txId)) {
                Log.d(TAG, "Coin transaction $txId already credited, ignoring duplicate.")
                return true
            }
            processedCoinTxIds[txId] = now
        } else {
            // 2. Hardware / Network Jitter Debounce Lockout (250ms) ONLY if no txId
            if (now - lastCoinCreditedTime < JITTER_DEBOUNCE_MS) {
                Log.d(TAG, "Duplicate coin burst (<250ms) from $source discarded.")
                return false
            }
        }

        lastCoinCreditedTime = now
        val pesoVal = if (amount >= 1.0) amount.toInt() else 1
        Log.d(TAG, "Coin credited: +$seconds seconds (₱$pesoVal) via $source (txId=${txId ?: "none"})")

        // Notify Service to apply state and timer updates
        onCreditsApplied(seconds, pesoVal)

        // Persist audit record in SQLite Room DB
        val eventTxId = txId ?: UUID.randomUUID().toString()
        scope.launch(Dispatchers.IO) {
            try {
                coinEventRepo.insertEvent(
                    CoinEvent(
                        txId = eventTxId,
                        secondsAdded = seconds,
                        source = "$source (₱$pesoVal)"
                    )
                )
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
