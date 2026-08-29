package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.DailyBookReadingEntity

@Dao
interface DailyBookReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: DailyBookReadingEntity)

    @Query("SELECT * FROM daily_book_reading WHERE date = :date AND bookId = :bookId LIMIT 1")
    suspend fun getByDateAndBook(date: String, bookId: Long): DailyBookReadingEntity?

    @Query("SELECT * FROM daily_book_reading WHERE date = :date ORDER BY seconds DESC")
    suspend fun getByDate(date: String): List<DailyBookReadingEntity>
}
