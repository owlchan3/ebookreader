package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookRelationCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastReadTimestamp DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun getBookByFilePath(filePath: String): BookEntity?

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC")
    fun getBooksByTitle(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY author COLLATE NOCASE ASC")
    fun getBooksByAuthor(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY addedTimestamp DESC")
    fun getBooksByDateAdded(): Flow<List<BookEntity>>

    @Query("""
        SELECT DISTINCT b.* FROM books b
        INNER JOIN book_tag_cross_ref bt ON b.id = bt.bookId
        WHERE bt.tagId IN (:tagIds)
        GROUP BY b.id HAVING COUNT(DISTINCT bt.tagId) = :count
    """)
    fun getBooksByAllTags(tagIds: List<Long>, count: Int): Flow<List<BookEntity>>

    @Query("""
        SELECT DISTINCT b.* FROM books b
        INNER JOIN book_tag_cross_ref bt ON b.id = bt.bookId
        WHERE bt.tagId IN (:tagIds)
    """)
    fun getBooksByAnyTags(tagIds: List<Long>): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteBooksByIds(bookIds: List<Long>)

    @Query("UPDATE books SET currentPage = :page, totalPages = :totalPages, currentLocator = :locator, lastReadTimestamp = :timestamp WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: Long, page: Int, totalPages: Int, locator: String?, timestamp: Long)

    @Query("UPDATE books SET filePath = :filePath WHERE id = :bookId")
    suspend fun updateFilePath(bookId: Long, filePath: String)

    @Query("UPDATE books SET totalReadingTime = totalReadingTime + :seconds WHERE id = :bookId")
    suspend fun addReadingTime(bookId: Long, seconds: Long)

    @Query("UPDATE books SET totalCharacters = :totalCharacters WHERE id = :bookId")
    suspend fun updateTotalCharacters(bookId: Long, totalCharacters: Long)

    @Query("SELECT * FROM book_tag_cross_ref")
    fun observeAllBookTagRelations(): Flow<List<com.ebookreader.data.local.entity.BookTagCrossRef>>

    @Query("SELECT bookId FROM book_tag_cross_ref WHERE tagId = :tagId")
    fun observeBookIdsByTag(tagId: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM books WHERE filePath LIKE '%' || :baseName || '.%' OR filePath LIKE '%' || :baseName || '_fixed.%'")
    suspend fun countBooksByBaseName(baseName: String): Int

    // --- Book relation queries ---

    @Query("SELECT * FROM book_relation_cross_ref")
    fun observeAllBookRelations(): Flow<List<BookRelationCrossRef>>

    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN book_relation_cross_ref r ON b.id = r.relatedBookId
        WHERE r.bookId = :bookId AND r.linkStatus != :blockedStatus
        UNION
        SELECT b.* FROM books b
        INNER JOIN book_relation_cross_ref r ON b.id = r.bookId
        WHERE r.relatedBookId = :bookId AND r.linkStatus != :blockedStatus
    """
    )
    fun getRelatedBooks(bookId: Long, blockedStatus: Int): Flow<List<BookEntity>>

    @Query(
        """
        SELECT * FROM book_relation_cross_ref
        WHERE (bookId = :bookA AND relatedBookId = :bookB)
           OR (bookId = :bookB AND relatedBookId = :bookA)
        LIMIT 1
    """
    )
    suspend fun getBookRelation(bookA: Long, bookB: Long): BookRelationCrossRef?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookRelation(relation: BookRelationCrossRef)

    @Query(
        """
        DELETE FROM book_relation_cross_ref
        WHERE (bookId = :bookA AND relatedBookId = :bookB)
           OR (bookId = :bookB AND relatedBookId = :bookA)
    """
    )
    suspend fun deleteBookRelation(bookA: Long, bookB: Long)

    @Query(
        """
        DELETE FROM book_relation_cross_ref
        WHERE (bookId = :bookId OR relatedBookId = :bookId) AND linkStatus = :autoStatus
    """
    )
    suspend fun deleteAutoRelationsForBook(bookId: Long, autoStatus: Int)

    @Query(
        """
        SELECT * FROM books
        WHERE id != :bookId AND LOWER(author) LIKE '%' || LOWER(:authorPart) || '%'
    """
    )
    suspend fun getBooksByAuthorMatch(bookId: Long, authorPart: String): List<BookEntity>

    @Query(
        """
        SELECT * FROM books WHERE id NOT IN (:excludeIds)
        AND (LOWER(title) LIKE '%' || LOWER(:query) || '%'
             OR LOWER(author) LIKE '%' || LOWER(:query) || '%')
        ORDER BY title COLLATE NOCASE ASC
        LIMIT 50
    """
    )
    fun searchBooksExcluding(excludeIds: List<Long>, query: String): Flow<List<BookEntity>>
}
