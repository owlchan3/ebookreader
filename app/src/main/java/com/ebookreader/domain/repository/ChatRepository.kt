package com.ebookreader.domain.repository

import com.ebookreader.domain.model.BookChunk
import com.ebookreader.domain.model.ChatMessage
import com.ebookreader.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getConversationsForBook(bookId: Long): Flow<List<Conversation>>
    suspend fun createConversation(bookId: Long, title: String): Long
    suspend fun deleteConversation(conversationId: Long)
    suspend fun updateConversationTimestamp(conversationId: Long)

    fun getMessages(conversationId: Long): Flow<List<ChatMessage>>
    suspend fun getMessagesOnce(conversationId: Long): List<ChatMessage>
    suspend fun addMessage(conversationId: Long, role: String, content: String): Long

    suspend fun getBookContent(bookId: Long): String
    suspend fun getBookTextSample(bookId: Long, maxChars: Int): String
    suspend fun countCharacters(filePath: String, format: String): Long
    suspend fun exportConversation(conversationId: Long): String

    /** Index a book into chunks. Returns true if new indexing was performed. */
    suspend fun ensureIndexed(bookId: Long): Boolean

    /** Get the total number of chunks for a book. */
    suspend fun getChunkCount(bookId: Long): Int

    /**
     * Vectorize any chunks of [bookId] that don't have an embedding yet.
     * Returns the number of chunks embedded in this call (0 when the model is
     * unavailable or nothing is pending).
     */
    suspend fun ensureEmbedded(
        bookId: Long,
        onProgress: ((embedded: Int, total: Int, status: String) -> Unit)? = null,
    ): Int

    /** Re-embed specific chunks (e.g. after their event summary changed), REPLACE semantics. */
    suspend fun reembedChunks(bookId: Long, chunkIds: List<Long>): Int

    /** Number of chunks of [bookId] that already have an embedding (for readiness display). */
    suspend fun getChunkEmbeddingCount(bookId: Long): Int

    /** Human-readable embedding-model status (for the diagnostic panel). */
    suspend fun embeddingModelStatus(): String

    /** Embed arbitrary texts into L2-normalized vectors (empty list when the model is unavailable). */
    suspend fun embedTexts(texts: List<String>): List<FloatArray>

    /** Semantic/vector search across chunks of specified books, falling back to BM25. */
    suspend fun searchChunks(
        bookIds: Set<Long>,
        queries: List<String>,
        topK: Int = 20,
        preferEarlierChunks: Boolean = false,
    ): List<BookChunk>

    /** Get chunks that haven't had event summaries generated yet. */
    suspend fun getUnsummarizedChunks(bookId: Long, limit: Int = 10): List<BookChunk>

    /** Update event summary for a single chunk. */
    suspend fun updateChunkEventSummary(chunkId: Long, summary: String)

    /** Get distinct chapter titles for a book, ordered by first occurrence. */
    suspend fun getDistinctChapters(bookId: Long): List<String>

    /** Get all chunks belonging to specific chapters (full content, not BM25-filtered). */
    suspend fun getChunksByChapters(bookId: Long, chapterTitles: List<String>): List<BookChunk>
}
