package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.mapper.toDomain
import com.ebookreader.data.mapper.toEntity
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookmarkRepositoryImpl(private val bookmarkDao: BookmarkDao) : BookmarkRepository {

    override fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>> =
        bookmarkDao.getBookmarksForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getBookmarkById(bookmarkId: Long): Bookmark? =
        bookmarkDao.getBookmarkById(bookmarkId)?.toDomain()

    override suspend fun insertBookmark(bookmark: Bookmark): Long =
        bookmarkDao.insertBookmark(bookmark.toEntity())

    override suspend fun deleteBookmark(bookmarkId: Long) =
        bookmarkDao.deleteBookmark(bookmarkId)

    override suspend fun updateNote(bookmarkId: Long, note: String) =
        bookmarkDao.updateNote(bookmarkId, note)

    override suspend fun isBookmarked(bookId: Long, locatorJson: String): Boolean =
        bookmarkDao.isBookmarked(bookId, locatorJson)

    override suspend fun getByBookAndLocator(bookId: Long, locatorJson: String): Bookmark? =
        bookmarkDao.getByBookAndLocator(bookId, locatorJson)?.toDomain()
}
