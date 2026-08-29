package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Link status constants for book relations. */
object BookLinkStatus {
    const val AUTO = 0
    const val MANUAL = 1
    const val BLOCKED = 2
}

@Entity(
    tableName = "book_relation_cross_ref",
    primaryKeys = ["bookId", "relatedBookId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["relatedBookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("relatedBookId")],
)
data class BookRelationCrossRef(
    val bookId: Long,
    val relatedBookId: Long,
    val linkStatus: Int, // BookLinkStatus.AUTO / MANUAL / BLOCKED
)
