package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.AnnotationEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AnnotationDao {

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdTimestamp DESC")
    abstract fun getAnnotationsForBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE id = :annotationId")
    abstract suspend fun getAnnotationById(annotationId: Long): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAnnotation(annotation: AnnotationEntity): Long

    @Delete
    abstract suspend fun deleteAnnotationEntity(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    abstract suspend fun deleteAnnotationById(annotationId: Long)

    suspend fun deleteAnnotation(annotationId: Long) {
        deleteAnnotationById(annotationId)
    }

    @Query("UPDATE annotations SET note = :note, style = :style, color = :color WHERE id = :annotationId")
    abstract suspend fun updateAnnotation(annotationId: Long, note: String, style: String, color: Int)
}
