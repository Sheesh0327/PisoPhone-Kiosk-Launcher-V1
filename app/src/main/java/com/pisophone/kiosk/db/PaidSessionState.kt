package com.pisophone.kiosk.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paid_session_state")
data class PaidSessionState(
    @PrimaryKey val id: Int = 1, // Only one record
    val sessionTimeRemaining: Int,
    val sessionExpiryDeadlineMs: Long,
    val lastSavedElapsedRealtime: Long
)
