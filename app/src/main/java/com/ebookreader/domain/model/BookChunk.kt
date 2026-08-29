package com.ebookreader.domain.model

/** A chunk of book text used for retrieval-augmented generation. */
data class BookChunk(
    val id: Long = 0,
    val bookId: Long,
    val chunkIndex: Int,
    val chapterTitle: String = "",
    val content: String,
    val charOffset: Int,
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val eventSummary: String = "",
)
