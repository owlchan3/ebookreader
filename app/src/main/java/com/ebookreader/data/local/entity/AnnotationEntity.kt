package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val selectedText: String,
    val pageIndex: Int,
    val style: String,
    val color: Int,
    val note: String = "",
    val createdTimestamp: Long = System.currentTimeMillis(),
)
