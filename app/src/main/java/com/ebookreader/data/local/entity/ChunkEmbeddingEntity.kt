package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dense vector embedding for a single [BookChunkEntity] chunk.
 *
 * One row per chunk; [vector] is the L2-normalized float32 embedding stored as
 * little-endian bytes. Lives in its own table so it can be regenerated without
 * touching the chunk text, and so the chunk text stays queryable while the
 * embedding model is loading or unavailable (BM25 fallback).
 */
@Entity(
    tableName = "chunk_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = BookChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["chunkId"]),
    ],
)
data class ChunkEmbeddingEntity(
    @PrimaryKey val chunkId: Long,
    val bookId: Long,
    val dim: Int,
    val vector: ByteArray,
)
