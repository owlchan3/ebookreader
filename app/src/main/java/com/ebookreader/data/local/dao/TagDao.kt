package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ebookreader.data.local.entity.BookTagCrossRef
import com.ebookreader.data.local.entity.TagEntity
import com.ebookreader.data.local.entity.TagGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    abstract fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :tagId")
    abstract suspend fun getTagById(tagId: Long): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagIgnore(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    abstract suspend fun getTagByName(name: String): TagEntity?

    @Update
    abstract suspend fun updateTag(tag: TagEntity)

    @Delete
    abstract suspend fun deleteTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addTagToBookCrossRef(crossRef: BookTagCrossRef)

    suspend fun addTagToBook(bookId: Long, tagId: Long) {
        addTagToBookCrossRef(BookTagCrossRef(bookId, tagId))
    }

    @Delete
    abstract suspend fun removeTagFromBookCrossRef(crossRef: BookTagCrossRef)

    suspend fun removeTagFromBook(bookId: Long, tagId: Long) {
        removeTagFromBookCrossRef(BookTagCrossRef(bookId, tagId))
    }

    @Transaction
    @Query("SELECT * FROM tags WHERE id IN (SELECT tagId FROM book_tag_cross_ref WHERE bookId = :bookId)")
    abstract fun getTagsForBook(bookId: Long): Flow<List<TagEntity>>

    @Query("SELECT tagId FROM book_tag_cross_ref WHERE bookId = :bookId")
    abstract suspend fun getTagIdsForBook(bookId: Long): List<Long>

    @Query("SELECT bookId, tagId FROM book_tag_cross_ref")
    abstract suspend fun getAllBookTagIds(): List<BookTagCrossRef>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    abstract suspend fun getAllTagsSync(): List<TagEntity>

    suspend fun getTagsForBookSync(bookId: Long): List<TagEntity> {
        return getAllTagsSync().filter { tag -> getTagIdsForBook(bookId).contains(tag.id) }
    }

    @Query("SELECT * FROM tag_groups ORDER BY name COLLATE NOCASE ASC")
    abstract fun getAllTagGroups(): Flow<List<TagGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTagGroup(group: TagGroupEntity): Long

    @Update
    abstract suspend fun updateTagGroup(group: TagGroupEntity)

    @Delete
    abstract suspend fun deleteTagGroup(group: TagGroupEntity)

    @Query("SELECT * FROM tag_groups WHERE isTab = 1")
    abstract fun getTabTagGroups(): Flow<List<TagGroupEntity>>
}
