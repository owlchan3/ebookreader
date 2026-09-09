package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.ChunkEmbeddingEntity

@Dao
interface ChunkEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<ChunkEmbeddingEntity>)

    /** Ids of chunks that already have an embedding, for incremental vectorization. */
    @Query("SELECT chunkId FROM chunk_embeddings WHERE bookId = :bookId")
    suspend fun getEmbeddedChunkIds(bookId: Long): List<Long>

    /** All embeddings for the given books (for brute-force cosine scoring). */
    @Query("SELECT * FROM chunk_embeddings WHERE bookId IN (:bookIds)")
    suspend fun getEmbeddingsForBooks(bookIds: Set<Long>): List<ChunkEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM chunk_embeddings WHERE bookId = :bookId")
    suspend fun getEmbeddingCount(bookId: Long): Int

    @Query("DELETE FROM chunk_embeddings")
    suspend fun deleteAllEmbeddings()
}
