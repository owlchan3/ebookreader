package com.ebookreader.domain.model

data class Book(
    val id: Long = 0,
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
    val addedTimestamp: Long = 0,
    val lastReadTimestamp: Long = 0,
    val fileSize: Long = 0,
    val originalFilePath: String? = null,
)
