package com.pisophone.kiosk.repository

import com.pisophone.kiosk.db.CoinEvent
import com.pisophone.kiosk.db.CoinEventDao
import kotlinx.coroutines.flow.Flow

class CoinEventRepository(private val coinEventDao: CoinEventDao) {
    val allEvents: Flow<List<CoinEvent>> = coinEventDao.getAllEvents()
    
    suspend fun getLatestEvents(limit: Int = 100): List<CoinEvent> {
        return coinEventDao.getLatestEvents(limit)
    }

    suspend fun insertEvent(event: CoinEvent) {
        coinEventDao.insertEvent(event)
    }

    suspend fun deleteOldEvents(keepLimit: Int = 500) {
        coinEventDao.deleteOldEvents(keepLimit)
    }
}
