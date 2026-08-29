package com.ebookreader.domain.repository

import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup
import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun getAllTags(): Flow<List<Tag>>
    /** 确保「已阅」特殊标签存在，返回其 id。 */
    suspend fun ensureReadTagExists(): Long
    suspend fun getTagById(tagId: Long): Tag?
    suspend fun insertTag(tag: Tag): Long
    suspend fun updateTag(tag: Tag)
    suspend fun deleteTag(tag: Tag)
    fun getTagsForBook(bookId: Long): Flow<List<Tag>>
    suspend fun getTagsForBookSync(bookId: Long): List<Tag>
    suspend fun getAllBookTags(): Map<Long, List<Tag>>
    suspend fun addTagToBook(bookId: Long, tagId: Long)
    suspend fun removeTagFromBook(bookId: Long, tagId: Long)
    fun getAllTagGroups(): Flow<List<TagGroup>>
    suspend fun insertTagGroup(group: TagGroup): Long
    suspend fun updateTagGroup(group: TagGroup)
    suspend fun deleteTagGroup(group: TagGroup)
    fun getTabTagGroups(): Flow<List<TagGroup>>
}
