package com.pisophone.kiosk.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.AppMetadata
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.db.PaymentReceipt

object PaymentMigrationHelper {
    private const val TAG = "PaymentMigrationHelper"
    const val PREFS_NAME = "kiosk_persistent_state"
    const val KEY_MIGRATION_MARKER = "legacy_paid_state_migrated_v4"
    const val KEY_MIGRATION_MARKER_PREFS = "legacy_paid_state_migrated_v3"

    suspend fun migrateAndInitialize(ctx: Context, db: AppDatabase) {
        val paymentDao = db.paymentDao()
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ctx.applicationContext.createDeviceProtectedStorageContext()
        } else {
            ctx.applicationContext
        }
        val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Fast-path check
        val isAlreadyMigratedMeta = paymentDao.getMetadata(KEY_MIGRATION_MARKER) == "true"
        val isLegacyPrefsMigrated = prefs.getBoolean(KEY_MIGRATION_MARKER_PREFS, false)
        if (isAlreadyMigratedMeta || isLegacyPrefsMigrated) {
            if (!isAlreadyMigratedMeta) {
                db.withTransaction {
                    paymentDao.setMetadata(AppMetadata(KEY_MIGRATION_MARKER, "true"))
                }
            }
            return
        }

        db.withTransaction {
            if (paymentDao.getMetadata(KEY_MIGRATION_MARKER) == "true") {
                return@withTransaction
            }

            // 1. Reconcile coin_events without silencing exceptions
            val existingEvents = db.coinEventDao().getLatestEvents(limit = 1000)
            for (ev in existingEvents) {
                paymentDao.insertReceiptIgnore(
                    PaymentReceipt(
                        txId = ev.txId,
                        secondsCredited = ev.secondsAdded,
                        amount = 0.0,
                        acceptanceTimestamp = ev.timestamp
                    )
                )
            }

            // 2. Reconcile processed_tx_ids without adding credit
            val processedTxIds = prefs.getStringSet("processed_tx_ids", emptySet()) ?: emptySet()
            for (txId in processedTxIds) {
                if (paymentDao.getReceiptByTxId(txId) == null) {
                    paymentDao.insertReceiptIgnore(
                        PaymentReceipt(
                            txId = txId,
                            secondsCredited = 0,
                            amount = 0.0,
                            acceptanceTimestamp = System.currentTimeMillis()
                        )
                    )
                }
            }

            // 3. Import legacy balance ONLY if authoritative Room session state does NOT exist
            val currentState = paymentDao.getSessionState()
            if (currentState == null) {
                val legacyRemaining = prefs.getInt("session_time_remaining", 0)
                val legacyDeadline = prefs.getLong("session_expiry_deadline_ms", 0L)
                val legacyLastElapsed = prefs.getLong("last_saved_elapsed_realtime", 0L)
                val nowMonotonic = SystemClock.elapsedRealtime()

                val isReboot = legacyLastElapsed > 0L && nowMonotonic < legacyLastElapsed
                val effectiveRemaining: Int
                val effectiveDeadline: Long

                if (!isReboot) {
                    if (legacyDeadline > nowMonotonic) {
                        effectiveDeadline = legacyDeadline
                        effectiveRemaining = ((legacyDeadline - nowMonotonic) / 1000L).toInt()
                    } else if (legacyDeadline != 0L) {
                        effectiveDeadline = 0L
                        effectiveRemaining = 0
                    } else if (legacyRemaining > 0) {
                        effectiveRemaining = legacyRemaining
                        effectiveDeadline = nowMonotonic + (legacyRemaining * 1000L)
                    } else {
                        effectiveRemaining = 0
                        effectiveDeadline = 0L
                    }
                } else {
                    if (legacyRemaining > 0) {
                        effectiveRemaining = legacyRemaining
                        effectiveDeadline = nowMonotonic + (legacyRemaining * 1000L)
                    } else {
                        effectiveRemaining = 0
                        effectiveDeadline = 0L
                    }
                }

                paymentDao.updateSessionState(
                    PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = effectiveRemaining,
                        sessionExpiryDeadlineMs = effectiveDeadline,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = 1L
                    )
                )
                Log.i(TAG, "Migrated legacy session state: ${effectiveRemaining}s (deadline=$effectiveDeadline)")
            } else {
                Log.i(TAG, "Authoritative Room session state already exists (rev=${currentState.revision}). Preserving.")
            }

            // 4. Mark migration completed atomically inside Room metadata
            paymentDao.setMetadata(AppMetadata(KEY_MIGRATION_MARKER, "true"))
        }

        prefs.edit().putBoolean(KEY_MIGRATION_MARKER_PREFS, true).commit()
        Log.i(TAG, "Migration completed atomically.")
    }
}
