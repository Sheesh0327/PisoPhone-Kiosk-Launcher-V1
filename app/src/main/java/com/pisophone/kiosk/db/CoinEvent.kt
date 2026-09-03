package com.pisophone.kiosk.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "coin_events",
    indices = [Index(value = ["txId"], unique = true)]
)
data class CoinEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val txId: String,
    val secondsAdded: Int,
    val source: String,
    val timestamp: Long = System.currentTimeMillis()
)
