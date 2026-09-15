package com.pisophone.kiosk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CoinEvent::class, PaymentReceipt::class, PaidSessionState::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun coinEventDao(): CoinEventDao
    abstract fun paymentDao(): PaymentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_coin_events_txId` ON `coin_events` (`txId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `payment_receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsCredited` INTEGER NOT NULL, `amount` REAL NOT NULL, `acceptanceTimestamp` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_receipts_txId` ON `payment_receipts` (`txId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `paid_session_state` (`id` INTEGER NOT NULL, `sessionTimeRemaining` INTEGER NOT NULL, `sessionExpiryDeadlineMs` INTEGER NOT NULL, `lastSavedElapsedRealtime` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT OR IGNORE INTO `payment_receipts` (`txId`, `secondsCredited`, `amount`, `acceptanceTimestamp`) SELECT `txId`, `secondsAdded`, 0.0, `timestamp` FROM `coin_events`")
            }
        }

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_coin_events_txId` ON `coin_events` (`txId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `payment_receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsCredited` INTEGER NOT NULL, `amount` REAL NOT NULL, `acceptanceTimestamp` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_receipts_txId` ON `payment_receipts` (`txId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `paid_session_state` (`id` INTEGER NOT NULL, `sessionTimeRemaining` INTEGER NOT NULL, `sessionExpiryDeadlineMs` INTEGER NOT NULL, `lastSavedElapsedRealtime` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT OR IGNORE INTO `payment_receipts` (`txId`, `secondsCredited`, `amount`, `acceptanceTimestamp`) SELECT `txId`, `secondsAdded`, 0.0, `timestamp` FROM `coin_events`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) context.applicationContext.createDeviceProtectedStorageContext() else context.applicationContext
                val instance = Room.databaseBuilder(
                    deviceContext,
                    AppDatabase::class.java,
                    "kiosk_audit_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_1_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
