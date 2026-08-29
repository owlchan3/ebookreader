package com.ebookreader.domain.repository

import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.TagTerm
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(): Flow<List<Book>>
    suspend fun getBookById(bookId: Long): Book?
    suspend fun getBookByFilePath(filePath: String): Book?
    fun searchBooks(query: String): Flow<List<Book>>
    fun getBooksByTitle(): Flow<List<Book>>
    fun getBooksByAuthor(): Flow<List<Book>>
    fun getBooksByDateAdded(): Flow<List<Book>>
    fun getBooksByAllTags(tagIds: List<Long>, count: Int): Flow<List<Book>>
    fun getBooksByAnyTags(tagIds: List<Long>): Flow<List<Book>>
    fun getBooksByTagTerms(terms: List<TagTerm>): Flow<List<Book>>
    fun getBooksByTagGroups(groups: List<List<Long>>): Flow<List<Book>>
    fun getBooksByTagGroupsWithNegation(
        positiveGroups: List<List<Long>>,
        negatedTagIds: Set<Long>,
    ): Flow<List<Book>>
    suspend fun insertBook(book: Book): Long
    suspend fun updateBook(book: Book)
    suspend fun deleteBooks(bookIds: List<Long>)
    suspend fun updateReadingProgress(bookId: Long, page: Int, totalPages: Int, locator: String?, timestamp: Long)
    suspend fun updateFilePath(bookId: Long, filePath: String)
    suspend fun addReadingTime(bookId: Long, seconds: Long)
    suspend fun countBooksByBaseName(baseName: String): Int
    fun getRelatedBooks(bookId: Long): Flow<List<Book>>
    suspend fun addManualRelation(bookId: Long, relatedBookId: Long)
    suspend fun removeBookRelation(bookId: Long, relatedBookId: Long)
    suspend fun syncAutoRelationsForBook(bookId: Long)
    fun searchBooksExcluding(bookId: Long, relatedIds: List<Long>, query: String): Flow<List<Book>>
}
