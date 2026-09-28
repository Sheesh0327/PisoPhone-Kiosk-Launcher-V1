package com.pisophone.kiosk.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.repository.PaymentRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoinProcessorUnitTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var paymentRepo: PaymentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        paymentRepo = PaymentRepository(db = db, isEligible = { true })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRejectsMissingOrBlankTxId() {
        var creditsApplied = 0
        val processor = CoinProcessor(
            context = context,
            paymentRepo = paymentRepo,
            onCreditsApplied = { _, _ -> creditsApplied++ },
            onFeedbackTrigger = {}
        )

        val resNull = processor.processCoinCredit(seconds = 300, source = "test", txId = null)
        assertFalse("Coin credit without txId must be rejected", resNull)
        assertEquals("No credits applied for null txId", 0, creditsApplied)

        val resBlank = processor.processCoinCredit(seconds = 300, source = "test", txId = "   ")
        assertFalse("Coin credit with blank txId must be rejected", resBlank)
        assertEquals("No credits applied for blank txId", 0, creditsApplied)
    }

    @Test
    fun testDeduplicatesSameTxId() {
        var creditsApplied = 0
        var totalSeconds = 0
        val processor = CoinProcessor(
            context = context,
            paymentRepo = paymentRepo,
            onCreditsApplied = { sec, _ ->
                creditsApplied++
                totalSeconds += sec
            },
            onFeedbackTrigger = {}
        )

        val txId = "tx-unique-12345"
        val firstResult = processor.processCoinCredit(seconds = 600, source = "ESP32 WS", txId = txId, amount = 5.0)
        assertTrue("First credit attempt should succeed", firstResult)
        assertEquals("Credit applied once", 1, creditsApplied)
        assertEquals("Seconds applied correctly", 600, totalSeconds)

        val replayResult = processor.processCoinCredit(seconds = 600, source = "ESP32 WS", txId = txId, amount = 5.0)
        assertTrue("Duplicate credit returns true for idempotency but does not re-credit", replayResult)
        assertEquals("Credit counter still 1 after replay", 1, creditsApplied)
        assertEquals("Total seconds unchanged after replay", 600, totalSeconds)
    }

    @Test
    fun testCommitPublishSoundOrderAndDuplicateSuppression() {
        val eventLog = mutableListOf<String>()
        val txId = "tx-order-check-001"

        val processor = CoinProcessor(
            context = context,
            paymentRepo = paymentRepo,
            onCreditsApplied = { _, _ ->
                // Check if payment was committed to database BEFORE publish
                val committedReceipt = db.paymentDao().getReceiptByTxId(txId)
                if (committedReceipt != null) {
                    eventLog.add("COMMITTED_BEFORE_PUBLISH")
                }
                eventLog.add("PUBLISH_STATE")
            },
            onFeedbackTrigger = {
                eventLog.add("PLAY_SOUND_AND_FEEDBACK")
            }
        )

        val applied = processor.processCoinCredit(seconds = 300, source = "COIN", txId = txId, amount = 1.0)
        assertTrue("Payment applied", applied)
        assertEquals(
            "Order must strictly be: Commit in DB -> Publish state -> Play sound/UI feedback",
            listOf("COMMITTED_BEFORE_PUBLISH", "PUBLISH_STATE", "PLAY_SOUND_AND_FEEDBACK"),
            eventLog
        )

        // Clear event log and replay same txId
        eventLog.clear()
        val replayed = processor.processCoinCredit(seconds = 300, source = "COIN", txId = txId, amount = 1.0)
        assertTrue("Duplicate returns true", replayed)
        assertTrue("No publish or sound/feedback should trigger on duplicate", eventLog.isEmpty())
    }
}
