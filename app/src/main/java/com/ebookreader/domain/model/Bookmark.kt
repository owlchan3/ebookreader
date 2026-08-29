package com.ebookreader.domain.model

data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val title: String,
    val note: String = "",
    val locatorJson: String,
    val createdTimestamp: Long = 0,
)
