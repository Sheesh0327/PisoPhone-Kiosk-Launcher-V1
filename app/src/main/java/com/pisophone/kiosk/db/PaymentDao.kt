package com.pisophone.kiosk.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PaymentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertReceipt(receipt: PaymentReceipt): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertReceiptIgnore(receipt: PaymentReceipt): Long

    @Query("SELECT * FROM payment_receipts WHERE txId = :txId LIMIT 1")
    fun getReceiptByTxId(txId: String): PaymentReceipt?

    @Query("SELECT COUNT(*) FROM payment_receipts WHERE txId = :txId")
    fun countReceipt(txId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun updateSessionState(state: PaidSessionState)
    
    @Query("SELECT * FROM paid_session_state WHERE id = 1")
    fun getSessionState(): PaidSessionState?
}
