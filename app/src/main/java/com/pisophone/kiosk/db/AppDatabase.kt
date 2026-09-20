package com.pisophone.kiosk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CoinEvent::class, PaymentReceipt::class, PaidSessionState::class, AppMetadata::class], version = 6, exportSchema = true)
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `paid_session_state` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `payment_receipts` ADD COLUMN `operationKind` TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL("ALTER TABLE `payment_receipts` ADD COLUMN `coinAmount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `payment_receipts` ADD COLUMN `pricePerCoin` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `payment_receipts` ADD COLUMN `boxInstallationEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `payment_receipts` ADD COLUMN `phonePairingEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `payment_receipts` ADD COLUMN `recordSchemaVersion` INTEGER NOT NULL DEFAULT 4")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `payment_receipts` SET `operationKind` = 'LEGACY' WHERE `recordSchemaVersion` < 5 AND `operationKind` = 'COIN' AND `coinAmount` = 0")
                db.execSQL("UPDATE `payment_receipts` SET `recordSchemaVersion` = 4 WHERE `recordSchemaVersion` < 5")
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_1_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
