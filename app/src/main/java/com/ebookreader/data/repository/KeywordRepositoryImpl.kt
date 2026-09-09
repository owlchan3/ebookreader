package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.BookKeywordDao
import com.ebookreader.data.mapper.toDomain
import com.ebookreader.data.mapper.toEntity
import com.ebookreader.domain.model.Keyword
import com.ebookreader.domain.repository.KeywordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class KeywordRepositoryImpl(private val dao: BookKeywordDao) : KeywordRepository {

    override fun getKeywordsForBook(bookId: Long): Flow<List<Keyword>> =
        dao.getKeywordsForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getKeywordsForBookOnce(bookId: Long): List<Keyword> =
        dao.getKeywordsForBookOnce(bookId).map { it.toDomain() }

    override suspend fun getAllKeywordsByBook(): Map<Long, List<Keyword>> =
        dao.getAllKeywords().groupBy { it.bookId }
            .mapValues { (_, list) -> list.sortedBy { it.rank }.map { it.toDomain() } }

    override suspend fun replaceKeywords(bookId: Long, keywords: List<Keyword>) {
        dao.deleteKeywordsForBook(bookId)
        if (keywords.isNotEmpty()) {
            dao.insertKeywords(keywords.map { it.toEntity(bookId) })
        }
    }

    override suspend fun deleteKeywordsForBook(bookId: Long) {
        dao.deleteKeywordsForBook(bookId)
    }
}
