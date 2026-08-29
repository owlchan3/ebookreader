package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_reading_sessions",
    indices = [Index(value = ["date"], unique = true)],
)
data class DailyReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val seconds: Long,
)
