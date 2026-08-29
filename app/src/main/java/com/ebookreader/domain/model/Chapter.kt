package com.ebookreader.domain.model

data class Chapter(
    val id: Long = 0,
    val bookId: Long,
    val title: String,
    val startLocatorJson: String,
    val orderIndex: Int = 0,
    val isUserCreated: Boolean = false,
    val regexPattern: String? = null,
)
