package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.BookChunkEntity

@Dao
interface BookChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<BookChunkEntity>)

    @Query("SELECT * FROM book_chunks WHERE bookId = :bookId ORDER BY chunkIndex ASC")
    suspend fun getChunksForBook(bookId: Long): List<BookChunkEntity>

    /** 分页读取书籍索引块（关键词提取流式扫描用，避免整本正文一次载入导致 OOM）。 */
    @Query("SELECT * FROM book_chunks WHERE bookId = :bookId ORDER BY chunkIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun getChunksForBookPaged(bookId: Long, limit: Int, offset: Int): List<BookChunkEntity>

    /** 书籍正文总字符数（用于流式扫描前估算布隆过滤器容量）。 */
    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM book_chunks WHERE bookId = :bookId")
    suspend fun getBookTotalChars(bookId: Long): Long

    @Query("SELECT id FROM book_chunks WHERE bookId = :bookId AND fileModified = :fileModified LIMIT 1")
    suspend fun hasCurrentIndex(bookId: Long, fileModified: Long): Long?

    @Query("DELETE FROM book_chunks WHERE bookId = :bookId")
    suspend fun deleteChunksForBook(bookId: Long)

    @Query("SELECT COUNT(*) FROM book_chunks WHERE bookId = :bookId")
    suspend fun getChunkCount(bookId: Long): Int

    @Query("DELETE FROM book_chunks")
    suspend fun deleteAllChunks()

    @Query("SELECT * FROM book_chunks WHERE bookId = :bookId AND eventSummary = '' ORDER BY chunkIndex ASC LIMIT :limit")
    suspend fun getChunksWithoutSummary(bookId: Long, limit: Int = 10): List<BookChunkEntity>

    @Query("UPDATE book_chunks SET eventSummary = :summary WHERE id = :chunkId")
    suspend fun updateEventSummary(chunkId: Long, summary: String)

    /** Get all distinct chapter titles for a book, ordered by their first occurrence. */
    @Query("SELECT chapterTitle FROM book_chunks WHERE bookId = :bookId AND chapterTitle != '' GROUP BY chapterTitle ORDER BY MIN(chunkIndex) ASC")
    suspend fun getDistinctChapters(bookId: Long): List<String>

    /** Get all chunks belonging to specific chapters, ordered by chunkIndex. */
    @Query("SELECT * FROM book_chunks WHERE bookId = :bookId AND chapterTitle IN (:chapterTitles) ORDER BY chunkIndex ASC")
    suspend fun getChunksByChapters(bookId: Long, chapterTitles: List<String>): List<BookChunkEntity>

    /** 每本书的索引块数（用于「删除某本书的索引」展示）。 */
    @Query("SELECT bookId, COUNT(*) AS count FROM book_chunks GROUP BY bookId")
    suspend fun getChunkCountsPerBook(): List<BookChunkCount>

    /** 索引总字节数（正文 + 事件摘要，用于存储详情）。 */
    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) + COALESCE(SUM(LENGTH(eventSummary)), 0) FROM book_chunks")
    suspend fun getTotalIndexBytes(): Long
}

data class BookChunkCount(val bookId: Long, val count: Int)
