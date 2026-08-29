package com.ebookreader.domain.model

data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val timestamp: Long = 0,
)
