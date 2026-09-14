package com.pisophone.kiosk.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.CoinEvent
import com.pisophone.kiosk.db.CoinEventDao
import com.pisophone.kiosk.repository.CoinEventRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CoinProcessorUnitTest {

    private lateinit var context: Context
    private val testScope = TestScope()

    private val fakeDao = object : CoinEventDao {
        val insertedEvents = mutableListOf<CoinEvent>()
        override fun getAllEvents(): Flow<List<CoinEvent>> = flowOf(insertedEvents)
        override suspend fun getLatestEvents(limit: Int): List<CoinEvent> = insertedEvents.takeLast(limit)
        override suspend fun insertEvent(event: CoinEvent) {
            insertedEvents.add(event)
        }
        override suspend fun deleteOldEvents(keepLimit: Int) {
            if (insertedEvents.size > keepLimit) {
                val excess = insertedEvents.size - keepLimit
                repeat(excess) { insertedEvents.removeAt(0) }
            }
        }
    }

    private lateinit var repository: CoinEventRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = CoinEventRepository(fakeDao)
    }

    @Test
    fun testRejectsMissingOrBlankTxId() {
        var creditsApplied = 0
        val processor = CoinProcessor(
            context = context,
            scope = testScope,
            coinEventRepo = repository,
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
            scope = testScope,
            coinEventRepo = repository,
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
}
