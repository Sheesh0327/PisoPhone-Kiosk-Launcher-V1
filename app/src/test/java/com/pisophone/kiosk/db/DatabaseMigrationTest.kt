package com.pisophone.kiosk.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    private val TEST_DB = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    @Throws(IOException::class)
    fun testMigration4To7() {
        // 1. Create version 4 database
        val db4 = helper.createDatabase(TEST_DB, 4)

        // Seed v4 receipt
        val receiptValues = ContentValues().apply {
            put("txId", "TX_SEEDED_V4")
            put("secondsCredited", 1800)
            put("amount", 5.0)
            put("acceptanceTimestamp", 1000000L)
        }
        db4.insert("payment_receipts", SQLiteDatabase.CONFLICT_FAIL, receiptValues)

        // Seed v4 paid session state
        val stateValues = ContentValues().apply {
            put("id", 1)
            put("sessionTimeRemaining", 1800)
            put("sessionExpiryDeadlineMs", 5000000L)
            put("lastSavedElapsedRealtime", 2000000L)
            put("revision", 4L)
        }
        db4.insert("paid_session_state", SQLiteDatabase.CONFLICT_FAIL, stateValues)

        // Seed v4 metadata
        val metaValues = ContentValues().apply {
            put("key", "test_meta_key_v4")
            put("value", "test_meta_val_v4")
        }
        db4.insert("app_metadata", SQLiteDatabase.CONFLICT_FAIL, metaValues)

        db4.close()

        // 2. Run migrations 4 -> 5 -> 6 -> 7 and validate schema
        val db7 = helper.runMigrationsAndValidate(TEST_DB, 7, true, *AppDatabase.ALL_MIGRATIONS)

        // Verify that seeded receipt survived with default values for new columns
        val cursor = db7.query("SELECT * FROM payment_receipts WHERE txId = 'TX_SEEDED_V4'")
        assertTrue("Seeded receipt must survive migration", cursor.moveToFirst())
        assertEquals("TX_SEEDED_V4", cursor.getString(cursor.getColumnIndexOrThrow("txId")))
        assertEquals(1800, cursor.getInt(cursor.getColumnIndexOrThrow("secondsCredited")))
        assertEquals(5.0, cursor.getDouble(cursor.getColumnIndexOrThrow("amount")), 0.0001)
        assertEquals("CREDIT", cursor.getString(cursor.getColumnIndexOrThrow("operationKind")))
        assertEquals(0.0, cursor.getDouble(cursor.getColumnIndexOrThrow("coinAmount")), 0.0001)
        assertEquals(0.0, cursor.getDouble(cursor.getColumnIndexOrThrow("pricePerCoin")), 0.0001)
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("boxInstallationEpoch")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("phonePairingEpoch")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("recordSchemaVersion")))
        cursor.close()

        // Verify session state survived
        val stateCursor = db7.query("SELECT * FROM paid_session_state WHERE id = 1")
        assertTrue("Session state must survive migration", stateCursor.moveToFirst())
        assertEquals(1800, stateCursor.getInt(stateCursor.getColumnIndexOrThrow("sessionTimeRemaining")))
        assertEquals(5000000L, stateCursor.getLong(stateCursor.getColumnIndexOrThrow("sessionExpiryDeadlineMs")))
        assertEquals(4L, stateCursor.getLong(stateCursor.getColumnIndexOrThrow("revision")))
        stateCursor.close()

        // Verify metadata survived
        val metaCursor = db7.query("SELECT value FROM app_metadata WHERE `key` = 'test_meta_key_v4'")
        assertTrue("Metadata must survive migration", metaCursor.moveToFirst())
        assertEquals("test_meta_val_v4", metaCursor.getString(0))
        metaCursor.close()

        db7.close()

        // 3. Open migrated DB with Room and PaymentRepository
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val repo = PaymentRepository(roomDb, context)

        // Verify retry of seeded transaction returns ALREADY_APPLIED
        val duplicateResult = repo.creditPaymentBlocking("TX_SEEDED_V4", 1800, 5.0)
        assertEquals(PaymentResult.ALREADY_APPLIED, duplicateResult)

        // Verify a new transaction credits successfully
        val newTxResult = repo.creditPaymentBlocking("TX_NEW_V7", 3600, 10.0)
        assertEquals(PaymentResult.APPLIED, newTxResult)

        val receipt = roomDb.paymentDao().getReceiptByTxId("TX_NEW_V7")
        assertNotNull(receipt)
        assertEquals(3600, receipt?.secondsCredited)

        roomDb.close()

        // 4. Reopen database and ensure all data remains intact
        val reopenedDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        assertNotNull(reopenedDb.paymentDao().getReceiptByTxId("TX_SEEDED_V4"))
        assertNotNull(reopenedDb.paymentDao().getReceiptByTxId("TX_NEW_V7"))
        assertEquals("test_meta_val_v4", reopenedDb.paymentDao().getMetadata("test_meta_key_v4"))
        reopenedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun testMigration5To7() {
        // 1. Create version 5 database
        val db5 = helper.createDatabase(TEST_DB, 5)

        val receiptValues = ContentValues().apply {
            put("txId", "TX_SEEDED_V5")
            put("secondsCredited", 3600)
            put("amount", 10.0)
            put("acceptanceTimestamp", 2000000L)
            put("operationKind", "BONUS")
            put("coinAmount", 2)
            put("pricePerCoin", 5.0)
            put("boxInstallationEpoch", 999999L)
            put("phonePairingEpoch", 888888L)
            put("recordSchemaVersion", 2)
        }
        db5.insert("payment_receipts", SQLiteDatabase.CONFLICT_FAIL, receiptValues)

        val stateValues = ContentValues().apply {
            put("id", 1)
            put("sessionTimeRemaining", 3600)
            put("sessionExpiryDeadlineMs", 8000000L)
            put("lastSavedElapsedRealtime", 3000000L)
            put("revision", 5L)
        }
        db5.insert("paid_session_state", SQLiteDatabase.CONFLICT_FAIL, stateValues)

        db5.close()

        // 2. Run migrations 5 -> 6 -> 7
        val db7 = helper.runMigrationsAndValidate(TEST_DB, 7, true, *AppDatabase.ALL_MIGRATIONS)

        val cursor = db7.query("SELECT * FROM payment_receipts WHERE txId = 'TX_SEEDED_V5'")
        assertTrue(cursor.moveToFirst())
        assertEquals("BONUS", cursor.getString(cursor.getColumnIndexOrThrow("operationKind")))
        assertEquals(2.0, cursor.getDouble(cursor.getColumnIndexOrThrow("coinAmount")), 0.0001)
        assertEquals(5.0, cursor.getDouble(cursor.getColumnIndexOrThrow("pricePerCoin")), 0.0001)
        assertEquals(999999L, cursor.getLong(cursor.getColumnIndexOrThrow("boxInstallationEpoch")))
        assertEquals(888888L, cursor.getLong(cursor.getColumnIndexOrThrow("phonePairingEpoch")))
        assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("recordSchemaVersion")))
        cursor.close()

        db7.close()

        // 3. Open with Room
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val repo = PaymentRepository(roomDb, context)
        assertEquals(PaymentResult.ALREADY_APPLIED, repo.creditPaymentBlocking("TX_SEEDED_V5", 3600, 10.0))
        assertEquals(PaymentResult.APPLIED, repo.creditPaymentBlocking("TX_NEW_AFTER_V5", 1800, 5.0))
        roomDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun testMigration6To7() {
        // 1. Create version 6 database
        val db6 = helper.createDatabase(TEST_DB, 6)

        val receiptValues = ContentValues().apply {
            put("txId", "TX_SEEDED_V6")
            put("secondsCredited", 5400)
            put("amount", 15.0)
            put("acceptanceTimestamp", 3000000L)
            put("operationKind", "CREDIT")
            put("coinAmount", 3)
            put("pricePerCoin", 5.0)
            put("boxInstallationEpoch", 111111L)
            put("phonePairingEpoch", 222222L)
            put("recordSchemaVersion", 1)
        }
        db6.insert("payment_receipts", SQLiteDatabase.CONFLICT_FAIL, receiptValues)

        val stateValues = ContentValues().apply {
            put("id", 1)
            put("sessionTimeRemaining", 5400)
            put("sessionExpiryDeadlineMs", 9000000L)
            put("lastSavedElapsedRealtime", 4000000L)
            put("revision", 6L)
        }
        db6.insert("paid_session_state", SQLiteDatabase.CONFLICT_FAIL, stateValues)

        db6.close()

        // 2. Run migrations 6 -> 7
        val db7 = helper.runMigrationsAndValidate(TEST_DB, 7, true, *AppDatabase.ALL_MIGRATIONS)

        val cursor = db7.query("SELECT * FROM payment_receipts WHERE txId = 'TX_SEEDED_V6'")
        assertTrue(cursor.moveToFirst())
        assertEquals(5400, cursor.getInt(cursor.getColumnIndexOrThrow("secondsCredited")))
        cursor.close()

        db7.close()

        // 3. Open with Room
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val repo = PaymentRepository(roomDb, context)
        assertEquals(PaymentResult.ALREADY_APPLIED, repo.creditPaymentBlocking("TX_SEEDED_V6", 5400, 15.0))
        assertEquals(PaymentResult.APPLIED, repo.creditPaymentBlocking("TX_NEW_AFTER_V6", 1800, 5.0))
        roomDb.close()
    }

    @Test
    fun testFreshVersion7Installation() {
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val repo = PaymentRepository(roomDb, context)
        val result = repo.creditPaymentBlocking("TX_FRESH_V7", 1800, 5.0)
        assertEquals(PaymentResult.APPLIED, result)

        val receipt = roomDb.paymentDao().getReceiptByTxId("TX_FRESH_V7")
        assertNotNull(receipt)
        assertEquals("CREDIT", receipt?.operationKind)

        val duplicate = repo.creditPaymentBlocking("TX_FRESH_V7", 1800, 5.0)
        assertEquals(PaymentResult.ALREADY_APPLIED, duplicate)

        roomDb.close()
    }

    @Test
    fun testHistoricalMigrationFromManualV1() {
        // Create manual SQLite DB representing version 1
        val sqliteDb = context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null)
        sqliteDb.version = 1
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS `coin_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txId` TEXT NOT NULL, `secondsAdded` INTEGER NOT NULL, `source` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
        sqliteDb.execSQL("INSERT INTO `coin_events` (`txId`, `secondsAdded`, `source`, `timestamp`) VALUES ('TX_HISTORICAL_V1', 1800, 'Coin', 500000)")
        sqliteDb.close()

        // Open and migrate through full chain to 7
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val receipt = roomDb.paymentDao().getReceiptByTxId("TX_HISTORICAL_V1")
        assertNotNull("Receipt migrated from v1 coin_events must exist in payment_receipts", receipt)
        assertEquals(1800, receipt?.secondsCredited)

        val repo = PaymentRepository(roomDb, context)
        assertEquals(PaymentResult.ALREADY_APPLIED, repo.creditPaymentBlocking("TX_HISTORICAL_V1", 1800, 0.0))
        assertEquals(PaymentResult.APPLIED, repo.creditPaymentBlocking("TX_NEW_AFTER_V1", 1800, 5.0))

        roomDb.close()
    }
}
