package com.ebookreader.domain.model

data class Conversation(
    val id: Long = 0,
    val bookId: Long,
    val title: String,
    val createdTimestamp: Long = 0,
    val updatedTimestamp: Long = 0,
)
