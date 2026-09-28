package com.pisophone.kiosk.service

import android.content.Context
import android.util.Log
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult

/**
 * Processor delegating coin credit operations to PaymentRepository.
 * Follows the lifecycle: Commit payment -> publish committed session state -> play sound/update UI.
 */
class CoinProcessor(
    private val context: Context,
    private val paymentRepo: PaymentRepository,
    private val onCreditsApplied: ((Int, Int) -> Unit)? = null,
    private val onFeedbackTrigger: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "CoinProcessor"
    }

    /**
     * Processes coin credit strictly following:
     * 1. Commit payment to database
     * 2. Publish committed session state
     * 3. Play sound / trigger UI feedback
     *
     * Only newly applied payments trigger the coin sound or new-credit notification.
     * Duplicate receipts return true without repeating feedback or time extension.
     */
    fun processCoinCredit(
        seconds: Int,
        source: String,
        txId: String?,
        amount: Double = 1.0,
        isStartupPhase: Boolean = false
    ): Boolean {
        if (txId.isNullOrBlank()) {
            Log.w(TAG, "Rejecting coin credit: Missing mandatory transaction ID.")
            return false
        }

        // 1. Commit payment in atomic transaction
        val result = paymentRepo.creditPaymentBlocking(txId, seconds, amount)

        return when (result) {
            PaymentResult.APPLIED -> {
                // 2. Publish committed session state
                onCreditsApplied?.invoke(seconds, amount.toInt())
                // 3. Play sound / update UI
                onFeedbackTrigger?.invoke()
                true
            }
            PaymentResult.ALREADY_APPLIED -> {
                // Acknowledged without sound or re-crediting
                Log.d(TAG, "Payment $txId already applied; acknowledging without sound or UI feedback.")
                true
            }
            PaymentResult.CONFLICT,
            PaymentResult.NOT_ELIGIBLE,
            PaymentResult.FAILED -> {
                false
            }
        }
    }
}
