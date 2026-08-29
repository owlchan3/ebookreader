package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdTimestamp DESC")
    abstract fun getBookmarksForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :bookmarkId")
    abstract suspend fun getBookmarkById(bookmarkId: Long): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Delete
    abstract suspend fun deleteBookmarkEntity(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    abstract suspend fun deleteBookmarkById(bookmarkId: Long)

    suspend fun deleteBookmark(bookmarkId: Long) {
        deleteBookmarkById(bookmarkId)
    }

    @Query("UPDATE bookmarks SET note = :note WHERE id = :bookmarkId")
    abstract suspend fun updateNote(bookmarkId: Long, note: String)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE bookId = :bookId AND locatorJson = :locatorJson")
    abstract suspend fun countByBookAndLocator(bookId: Long, locatorJson: String): Int

    suspend fun isBookmarked(bookId: Long, locatorJson: String): Boolean {
        return countByBookAndLocator(bookId, locatorJson) > 0
    }

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND locatorJson = :locatorJson LIMIT 1")
    abstract suspend fun getByBookAndLocator(bookId: Long, locatorJson: String): BookmarkEntity?
}
