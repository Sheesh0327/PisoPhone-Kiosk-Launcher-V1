package com.pisophone.kiosk.repository

import com.pisophone.kiosk.db.CoinEvent
import com.pisophone.kiosk.db.CoinEventDao

class CoinEventRepository(private val coinEventDao: CoinEventDao) {
    suspend fun getLatestEvents(limit: Int = 100): List<CoinEvent> = coinEventDao.getLatestEvents(limit)

    suspend fun insertEvent(event: CoinEvent) {
        coinEventDao.insertEvent(event)
    }

    suspend fun deleteOldEvents(keepLimit: Int = 500) {
        coinEventDao.deleteOldEvents(keepLimit)
    }
}
