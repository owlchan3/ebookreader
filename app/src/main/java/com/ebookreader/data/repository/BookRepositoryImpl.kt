package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.entity.BookLinkStatus
import com.ebookreader.data.local.entity.BookRelationCrossRef
import com.ebookreader.data.mapper.toDomain
import com.ebookreader.data.mapper.toEntity
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.TagTerm
import com.ebookreader.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class BookRepositoryImpl(private val bookDao: BookDao) : BookRepository {

    override fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks().map { list -> list.map { it.toDomain() } }

    override suspend fun getBookById(bookId: Long): Book? =
        bookDao.getBookById(bookId)?.toDomain()

    override suspend fun getBookByFilePath(filePath: String): Book? =
        bookDao.getBookByFilePath(filePath)?.toDomain()

    override fun searchBooks(query: String): Flow<List<Book>> =
        bookDao.searchBooks(query).map { list -> list.map { it.toDomain() } }

    override fun getBooksByTitle(): Flow<List<Book>> =
        bookDao.getBooksByTitle().map { list -> list.map { it.toDomain() } }

    override fun getBooksByAuthor(): Flow<List<Book>> =
        bookDao.getBooksByAuthor().map { list -> list.map { it.toDomain() } }

    override fun getBooksByDateAdded(): Flow<List<Book>> =
        bookDao.getBooksByDateAdded().map { list -> list.map { it.toDomain() } }

    override fun getBooksByAllTags(tagIds: List<Long>, count: Int): Flow<List<Book>> =
        bookDao.getBooksByAllTags(tagIds, count).map { list -> list.map { it.toDomain() } }

    override fun getBooksByAnyTags(tagIds: List<Long>): Flow<List<Book>> =
        bookDao.getBooksByAnyTags(tagIds).map { list -> list.map { it.toDomain() } }

    override suspend fun insertBook(book: Book): Long =
        bookDao.insertBook(book.toEntity())

    override suspend fun updateBook(book: Book) =
        bookDao.updateBook(book.toEntity())

    override suspend fun deleteBooks(bookIds: List<Long>) =
        bookDao.deleteBooksByIds(bookIds)

    override suspend fun updateReadingProgress(bookId: Long, page: Int, totalPages: Int, locator: String?, timestamp: Long) =
        bookDao.updateReadingProgress(bookId, page, totalPages, locator, timestamp)

    override suspend fun updateFilePath(bookId: Long, filePath: String) =
        bookDao.updateFilePath(bookId, filePath)

    override suspend fun addReadingTime(bookId: Long, seconds: Long) =
        bookDao.addReadingTime(bookId, seconds)

    override suspend fun countBooksByBaseName(baseName: String): Int =
        bookDao.countBooksByBaseName(baseName)

    override fun getBooksByTagGroups(groups: List<List<Long>>): Flow<List<Book>> {
        if (groups.isEmpty()) return getAllBooks()
        return combine(
            bookDao.getAllBooks(),
            bookDao.observeAllBookTagRelations()
        ) { books, relations ->
            val bookTagIds = relations.groupBy({ it.bookId }, { it.tagId })
                .mapValues { it.value.toSet() }
            books.filter { book ->
                val tags = bookTagIds[book.id] ?: emptySet()
                // AND of OR-groups: every group must have at least one matching tag
                groups.all { group -> group.any { tagId -> tagId in tags } }
            }.map { it.toDomain() }
        }
    }

    override fun getBooksByTagGroupsWithNegation(
        positiveGroups: List<List<Long>>,
        negatedTagIds: Set<Long>,
    ): Flow<List<Book>> {
        if (positiveGroups.isEmpty() && negatedTagIds.isEmpty()) return getAllBooks()
        return combine(
            bookDao.getAllBooks(),
            bookDao.observeAllBookTagRelations()
        ) { books, relations ->
            val bookTagIds = relations.groupBy({ it.bookId }, { it.tagId })
                .mapValues { it.value.toSet() }
            books.filter { book ->
                val tags = bookTagIds[book.id] ?: emptySet()
                // Book must NOT have any negated tags
                if (negatedTagIds.isNotEmpty() && negatedTagIds.any { it in tags }) return@filter false
                // Book must match all positive groups (AND of OR-groups)
                if (positiveGroups.isNotEmpty()) {
                    positiveGroups.all { group -> group.any { tagId -> tagId in tags } }
                } else true
            }.map { it.toDomain() }
        }
    }

    override fun getBooksByTagTerms(terms: List<TagTerm>): Flow<List<Book>> {
        if (terms.isEmpty()) return getAllBooks()
        return combine(
            bookDao.getAllBooks(),
            bookDao.observeAllBookTagRelations()
        ) { books, relations ->
            val bookTagIds = relations.groupBy({ it.bookId }, { it.tagId })
                .mapValues { it.value.toSet() }
            books.filter { book ->
                val tags = bookTagIds[book.id] ?: emptySet()
                evaluateTagTerms(terms, tags)
            }.map { it.toDomain() }
        }
    }

    override fun getRelatedBooks(bookId: Long): Flow<List<Book>> =
        bookDao.getRelatedBooks(bookId, BookLinkStatus.BLOCKED).map { list -> list.map { it.toDomain() } }

    override suspend fun addManualRelation(bookId: Long, relatedBookId: Long) {
        if (bookId == relatedBookId) return
        val first = minOf(bookId, relatedBookId)
        val second = maxOf(bookId, relatedBookId)
        bookDao.insertBookRelation(BookRelationCrossRef(first, second, BookLinkStatus.MANUAL))
    }

    override suspend fun removeBookRelation(bookId: Long, relatedBookId: Long) {
        val first = minOf(bookId, relatedBookId)
        val second = maxOf(bookId, relatedBookId)
        val existing = bookDao.getBookRelation(first, second)
        if (existing != null && existing.linkStatus == BookLinkStatus.AUTO) {
            // Auto link — mark as BLOCKED so it won't be recreated
            bookDao.insertBookRelation(BookRelationCrossRef(first, second, BookLinkStatus.BLOCKED))
        } else {
            // Manual link or blocked — delete entirely
            bookDao.deleteBookRelation(first, second)
        }
    }

    override suspend fun syncAutoRelationsForBook(bookId: Long) {
        val book = bookDao.getBookById(bookId) ?: return
        val authorParts = splitAuthors(book.author)
            .filter { it != "未知作者" && it != "佚名" && it != "Unknown" && it != "anonymous" }
        if (authorParts.isEmpty()) return

        // Delete all existing AUTO links for this book
        bookDao.deleteAutoRelationsForBook(bookId, BookLinkStatus.AUTO)

        // Find candidates by matching each author part
        val candidates = mutableSetOf<Long>()
        for (part in authorParts) {
            if (part.length < 2) continue
            val matches = bookDao.getBooksByAuthorMatch(bookId, part)
            candidates.addAll(matches.map { it.id })
        }

        // Insert AUTO links for candidates that don't already have MANUAL/BLOCKED
        for (candidateId in candidates) {
            val first = minOf(bookId, candidateId)
            val second = maxOf(bookId, candidateId)
            val existing = bookDao.getBookRelation(first, second)
            if (existing == null || existing.linkStatus == BookLinkStatus.AUTO) {
                bookDao.insertBookRelation(BookRelationCrossRef(first, second, BookLinkStatus.AUTO))
            }
        }
    }

    override fun searchBooksExcluding(bookId: Long, relatedIds: List<Long>, query: String): Flow<List<Book>> =
        bookDao.searchBooksExcluding(listOf(bookId) + relatedIds, query).map { list -> list.map { it.toDomain() } }

    companion object {
        fun splitAuthors(author: String): List<String> =
            author.split(",", "，", "&", "/", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        fun evaluateTagTerms(terms: List<TagTerm>, bookTagIds: Set<Long>): Boolean {
            if (terms.isEmpty()) return true
            // Split into OR groups: consecutive AND-connected terms form a group
            val orGroups = mutableListOf<Set<Long>>()
            var current = mutableSetOf(terms.first().tagId)
            for (i in 1 until terms.size) {
                if (terms[i].operator == "OR") {
                    orGroups.add(current.toSet())
                    current = mutableSetOf()
                }
                current.add(terms[i].tagId)
            }
            orGroups.add(current.toSet())
            // Book must match at least one OR group (all tags within each group)
            return orGroups.any { group -> bookTagIds.containsAll(group) }
        }
    }
}
