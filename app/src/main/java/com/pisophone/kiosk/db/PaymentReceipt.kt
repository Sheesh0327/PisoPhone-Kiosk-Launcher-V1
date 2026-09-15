package com.pisophone.kiosk.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_receipts",
    indices = [Index(value = ["txId"], unique = true)]
)
data class PaymentReceipt(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val txId: String,
    val secondsCredited: Int,
    val amount: Double,
    val acceptanceTimestamp: Long = System.currentTimeMillis()
)
