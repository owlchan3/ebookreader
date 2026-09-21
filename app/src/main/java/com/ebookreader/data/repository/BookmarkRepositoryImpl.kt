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

    /**
     * 旧版书签标题是创建时的页码标签（如 `189 / 1987`），改版后位置提示统一用百分比。
     * 这里把已入库的旧标题就地换算，避免列表回退显示时口径不一致。
     * 只处理能完整匹配「数字 / 数字」的标题，其余（如 `书签 3`）原样保留。
     */
    override suspend fun migrateTitlesToPercent() {
        val legacy = bookmarkDao.getBookmarksWithSlashTitle()
        for (bookmark in legacy) {
            val match = LEGACY_PAGE_LABEL.find(bookmark.title.trim()) ?: continue
            val page = match.groupValues[1].toIntOrNull() ?: continue
            val total = match.groupValues[2].toIntOrNull() ?: continue
            if (total <= 0) continue
            bookmarkDao.updateTitle(bookmark.id, "${page.coerceAtMost(total) * 100 / total}%")
        }
    }

    private companion object {
        val LEGACY_PAGE_LABEL = Regex("""^(\d+)\s*/\s*(\d+)$""")
    }

    override suspend fun isBookmarked(bookId: Long, locatorJson: String): Boolean =
        bookmarkDao.isBookmarked(bookId, locatorJson)

    override suspend fun getByBookAndLocator(bookId: Long, locatorJson: String): Bookmark? =
        bookmarkDao.getByBookAndLocator(bookId, locatorJson)?.toDomain()
}
