package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.DailyReadingSessionEntity

@Dao
interface DailyReadingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: DailyReadingSessionEntity)

    @Query("SELECT * FROM daily_reading_sessions WHERE date >= :fromDate ORDER BY date ASC")
    suspend fun getSessionsSince(fromDate: String): List<DailyReadingSessionEntity>

    @Query("SELECT * FROM daily_reading_sessions WHERE date = :date LIMIT 1")
    suspend fun getSessionByDate(date: String): DailyReadingSessionEntity?

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM daily_reading_sessions")
    suspend fun getTotalReadingSeconds(): Long
}
