package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val description: String = "",
    val coverPath: String? = null,
    val filePath: String,
    val format: String,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val currentLocator: String? = null,
    val totalReadingTime: Long = 0,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastReadTimestamp: Long = 0,
    val fileSize: Long = 0,
    val originalFilePath: String? = null,
)
