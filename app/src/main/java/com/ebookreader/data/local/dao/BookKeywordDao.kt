package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.BookKeywordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookKeywordDao {

    @Query("SELECT * FROM book_keywords WHERE bookId = :bookId ORDER BY rank ASC")
    fun getKeywordsForBook(bookId: Long): Flow<List<BookKeywordEntity>>

    @Query("SELECT * FROM book_keywords WHERE bookId = :bookId ORDER BY rank ASC")
    suspend fun getKeywordsForBookOnce(bookId: Long): List<BookKeywordEntity>

    /** 全部书籍的关键词（按 bookId、rank 排序），用于推荐画像一次性聚合。 */
    @Query("SELECT * FROM book_keywords ORDER BY bookId ASC, rank ASC")
    suspend fun getAllKeywords(): List<BookKeywordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeywords(keywords: List<BookKeywordEntity>)

    @Query("DELETE FROM book_keywords WHERE bookId = :bookId")
    suspend fun deleteKeywordsForBook(bookId: Long)
}
