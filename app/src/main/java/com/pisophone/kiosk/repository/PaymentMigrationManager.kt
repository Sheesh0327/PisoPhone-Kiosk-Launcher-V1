package com.pisophone.kiosk.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.db.AppMetadata
import com.pisophone.kiosk.db.PaidSessionState
import com.pisophone.kiosk.db.PaymentReceipt
import com.pisophone.kiosk.security.KioskSecurity

/**
 * Handles database bootstrapping, legacy SharedPreferences state migration,
 * transaction recovery, and boot-count/monotonic clock detection.
 */
class PaymentMigrationManager(
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "PaymentMigrationManager"
        const val PREFS_NAME = "kiosk_persistent_state"
        const val KEY_MIGRATION_MARKER = "legacy_paid_state_migrated_v4"
        const val KEY_MIGRATION_MARKER_PREFS = "legacy_paid_state_migrated_v3"
        const val KEY_BOOT_COUNT = "BOOT_COUNT"
    }

    private val paymentDao = db.paymentDao()

    suspend fun recoverUncommittedTransactions(nowMonotonic: Long = SystemClock.elapsedRealtime()) {
        val currentState = paymentDao.getSessionState()
        if (currentState != null) {
            var sanitizedRemaining = currentState.sessionTimeRemaining
            var sanitizedDeadline = currentState.sessionExpiryDeadlineMs
            var needsUpdate = false

            if (sanitizedRemaining < 0) {
                sanitizedRemaining = 0
                sanitizedDeadline = 0L
                needsUpdate = true
            }
            if (sanitizedDeadline < 0L) {
                sanitizedDeadline = 0L
                sanitizedRemaining = 0
                needsUpdate = true
            }

            if (needsUpdate) {
                paymentDao.updateSessionState(
                    currentState.copy(
                        sessionTimeRemaining = sanitizedRemaining,
                        sessionExpiryDeadlineMs = sanitizedDeadline,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = currentState.revision + 1L
                    )
                )
            }
        }
    }

    suspend fun restoreSessionState(
        ctx: Context?,
        onSessionStateChanged: ((snapshot: SessionSnapshot) -> Unit)?
    ): RestoredSessionState {
        if (ctx != null) {
            migrateAndInitialize(ctx)
        }

        val encryptedPrefs = ctx?.let { KioskSecurity.getEncryptedPreferences(it) }

        val (snapshot, isReboot) = db.withTransaction {
            val nowMonotonic = SystemClock.elapsedRealtime()
            recoverUncommittedTransactions(nowMonotonic)

            val currentBootCount = if (ctx != null) {
                try {
                    android.provider.Settings.Global.getInt(
                        ctx.contentResolver,
                        android.provider.Settings.Global.BOOT_COUNT,
                        -1
                    )
                } catch (e: Exception) {
                    -1
                }
            } else {
                -1
            }
            val lastSavedBootCountStr = paymentDao.getMetadata(KEY_BOOT_COUNT)
            val lastSavedBootCount = if (lastSavedBootCountStr != null) {
                lastSavedBootCountStr.toIntOrNull() ?: -1
            } else {
                encryptedPrefs?.getInt(KEY_BOOT_COUNT, -1) ?: -1
            }
            val isBootCountChanged = if (currentBootCount != -1) {
                val changed = lastSavedBootCount != -1 && currentBootCount != lastSavedBootCount
                paymentDao.setMetadata(AppMetadata(KEY_BOOT_COUNT, currentBootCount.toString()))
                changed
            } else {
                false
            }

            val paidState = paymentDao.getSessionState()
            if (paidState == null) {
                return@withTransaction Pair(SessionSnapshot(0L, 0, 0L), false)
            }

            val savedDeadline = paidState.sessionExpiryDeadlineMs
            val savedTime = paidState.sessionTimeRemaining
            val lastSavedElapsed = paidState.lastSavedElapsedRealtime

            val monotonicRebootDetected = lastSavedElapsed > 0L && nowMonotonic < lastSavedElapsed
            val rebootDetected = isBootCountChanged || monotonicRebootDetected
            if (currentBootCount == -1 && monotonicRebootDetected) {
                val nextCount = (lastSavedBootCount.takeIf { it >= 0 } ?: 0) + 1
                paymentDao.setMetadata(AppMetadata(KEY_BOOT_COUNT, nextCount.toString()))
            }

            val effectiveRemainingSec: Int
            val effectiveDeadline: Long
            var updatedRevision = paidState.revision

            if (rebootDetected) {
                effectiveRemainingSec = maxOf(0, savedTime)
                effectiveDeadline = if (effectiveRemainingSec > 0) nowMonotonic + (effectiveRemainingSec * 1000L) else 0L
                updatedRevision += 1L
                paymentDao.updateSessionState(
                    PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = effectiveRemainingSec,
                        sessionExpiryDeadlineMs = effectiveDeadline,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = updatedRevision
                    )
                )
            } else if (savedDeadline > nowMonotonic) {
                effectiveDeadline = savedDeadline
                effectiveRemainingSec = ((savedDeadline - nowMonotonic) / 1000L).toInt()
            } else if (savedDeadline != 0L) {
                effectiveRemainingSec = 0
                effectiveDeadline = 0L
                updatedRevision += 1L
                paymentDao.updateSessionState(
                    PaidSessionState(
                        id = 1,
                        sessionTimeRemaining = 0,
                        sessionExpiryDeadlineMs = 0L,
                        lastSavedElapsedRealtime = nowMonotonic,
                        revision = updatedRevision
                    )
                )
            } else {
                effectiveRemainingSec = maxOf(0, savedTime)
                effectiveDeadline = 0L
            }

            Pair(SessionSnapshot(effectiveDeadline, effectiveRemainingSec, updatedRevision), rebootDetected)
        }

        onSessionStateChanged?.invoke(snapshot)
        return RestoredSessionState(snapshot.remainingSeconds, snapshot.deadlineMs, isReboot, snapshot.revision)
    }

    suspend fun migrateAndInitialize(ctx: Context) {
        val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ctx.applicationContext.createDeviceProtectedStorageContext()
        } else {
            ctx.applicationContext
        }
        val prefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

            paymentDao.setMetadata(AppMetadata(KEY_MIGRATION_MARKER, "true"))
        }

        prefs.edit().putBoolean(KEY_MIGRATION_MARKER_PREFS, true).commit()
        Log.i(TAG, "Migration completed atomically.")
    }
}
