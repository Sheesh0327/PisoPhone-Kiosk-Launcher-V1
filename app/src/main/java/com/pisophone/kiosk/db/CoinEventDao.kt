package com.pisophone.kiosk.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CoinEventDao {
    @Query("SELECT * FROM coin_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<CoinEvent>>
    
    @Query("SELECT * FROM coin_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestEvents(limit: Int = 100): List<CoinEvent>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: CoinEvent)
}
