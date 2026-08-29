package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 某本书在某一日的阅读时长（秒），用于统计「当日阅读最久的书」。 */
@Entity(
    tableName = "daily_book_reading",
    indices = [Index(value = ["date", "bookId"], unique = true)],
)
data class DailyBookReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val bookId: Long,
    val seconds: Long,
)
