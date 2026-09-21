package com.ebookreader.domain.repository

import com.ebookreader.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>
    suspend fun getBookmarkById(bookmarkId: Long): Bookmark?
    suspend fun insertBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(bookmarkId: Long)
    suspend fun updateNote(bookmarkId: Long, note: String)
    /** 一次性把旧版「N / M」形式的书签标题改写为百分比；幂等。 */
    suspend fun migrateTitlesToPercent()
    suspend fun isBookmarked(bookId: Long, locatorJson: String): Boolean
    suspend fun getByBookAndLocator(bookId: Long, locatorJson: String): Bookmark?
}
