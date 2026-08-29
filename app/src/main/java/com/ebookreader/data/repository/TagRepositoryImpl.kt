package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.TagDao
import com.ebookreader.data.local.entity.TagEntity
import com.ebookreader.data.mapper.toDomain
import com.ebookreader.data.mapper.toEntity
import com.ebookreader.domain.model.READ_TAG_NAME
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup
import com.ebookreader.domain.repository.TagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

class TagRepositoryImpl(private val tagDao: TagDao) : TagRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 确保「已阅」特殊标签存在（幂等），供统计与标记使用。
        scope.launch { ensureReadTagExists() }
    }

    override suspend fun ensureReadTagExists(): Long {
        tagDao.getTagByName(READ_TAG_NAME)?.let { return it.id }
        val id = tagDao.insertTagIgnore(TagEntity(name = READ_TAG_NAME))
        return if (id != -1L) id else tagDao.getTagByName(READ_TAG_NAME)!!.id
    }

    override fun getAllTags(): Flow<List<Tag>> =
        tagDao.getAllTags().map { list -> list.map { it.toDomain() } }

    override suspend fun getTagById(tagId: Long): Tag? =
        tagDao.getTagById(tagId)?.toDomain()

    override suspend fun insertTag(tag: Tag): Long =
        tagDao.insertTag(tag.toEntity())

    override suspend fun updateTag(tag: Tag) =
        tagDao.updateTag(tag.toEntity())

    override suspend fun deleteTag(tag: Tag) =
        tagDao.deleteTag(tag.toEntity())

    override fun getTagsForBook(bookId: Long): Flow<List<Tag>> =
        tagDao.getTagsForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getTagsForBookSync(bookId: Long): List<Tag> =
        tagDao.getTagsForBookSync(bookId).map { it.toDomain() }

    override suspend fun getAllBookTags(): Map<Long, List<Tag>> {
        val allTags = tagDao.getAllTagsSync().map { it.toDomain() }
        val byId = allTags.associateBy { it.id }
        return tagDao.getAllBookTagIds()
            .groupBy({ it.bookId }, { byId[it.tagId] })
            .mapValues { (_, tags) -> tags.filterNotNull() }
    }

    override suspend fun addTagToBook(bookId: Long, tagId: Long) =
        tagDao.addTagToBook(bookId, tagId)

    override suspend fun removeTagFromBook(bookId: Long, tagId: Long) =
        tagDao.removeTagFromBook(bookId, tagId)

    override fun getAllTagGroups(): Flow<List<TagGroup>> =
        tagDao.getAllTagGroups().map { list ->
            val allTags = tagDao.getAllTagsSync().map { it.toDomain() }
            list.map { entity ->
                val tagIds: List<Long> = try {
                    Json.decodeFromString(entity.tagIdsJson)
                } catch (_: Exception) { emptyList() }
                val tags = allTags.filter { t -> tagIds.contains(t.id) }
                entity.toDomain(tags)
            }
        }

    override suspend fun insertTagGroup(group: TagGroup): Long =
        tagDao.insertTagGroup(group.toEntity())

    override suspend fun updateTagGroup(group: TagGroup) =
        tagDao.updateTagGroup(group.toEntity())

    override suspend fun deleteTagGroup(group: TagGroup) =
        tagDao.deleteTagGroup(group.toEntity())

    override fun getTabTagGroups(): Flow<List<TagGroup>> =
        tagDao.getTabTagGroups().map { list ->
            val allTags = tagDao.getAllTagsSync().map { it.toDomain() }
            list.map { entity ->
                val tagIds: List<Long> = try {
                    Json.decodeFromString(entity.tagIdsJson)
                } catch (_: Exception) { emptyList() }
                val tags = allTags.filter { t -> tagIds.contains(t.id) }
                entity.toDomain(tags)
            }
        }
}
