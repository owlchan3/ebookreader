package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.BookDecompositionEntity

@Dao
interface BookDecompositionDao {

    @Query("SELECT * FROM book_decomposition WHERE bookId = :bookId")
    suspend fun getByBookId(bookId: Long): BookDecompositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookDecompositionEntity)

    @Query("DELETE FROM book_decomposition WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)

    /** 已拆完（status=done）的书籍 id 列表，用于参考书导入弹窗标记"已拆书"。 */
    @Query("SELECT bookId FROM book_decomposition WHERE status = 'done'")
    suspend fun getDoneBookIds(): List<Long>

    /** 拆书结果总字节数（用于存储详情）。 */
    @Query("SELECT COALESCE(SUM(LENGTH(bookSummary)) + SUM(LENGTH(outlineJson)) + SUM(LENGTH(chapterSummariesJson)) + SUM(LENGTH(charactersJson)) + SUM(LENGTH(timelineJson)) + SUM(LENGTH(quotesJson)) + SUM(LENGTH(characterBiosJson)) + SUM(LENGTH(worldSettingJson)) + SUM(LENGTH(chapterOverviewJson)) + SUM(LENGTH(extendedReadingJson)), 0) FROM book_decomposition")
    suspend fun getTotalDecompositionBytes(): Long
}
