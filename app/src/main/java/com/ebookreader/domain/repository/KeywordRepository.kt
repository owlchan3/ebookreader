package com.ebookreader.domain.repository

import com.ebookreader.domain.model.Keyword
import kotlinx.coroutines.flow.Flow

interface KeywordRepository {
    fun getKeywordsForBook(bookId: Long): Flow<List<Keyword>>
    suspend fun getKeywordsForBookOnce(bookId: Long): List<Keyword>
    suspend fun getAllKeywordsByBook(): Map<Long, List<Keyword>>
    suspend fun replaceKeywords(bookId: Long, keywords: List<Keyword>)
    suspend fun deleteKeywordsForBook(bookId: Long)
}
