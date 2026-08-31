package com.ebookreader.domain.repository

import com.ebookreader.domain.model.Annotation
import com.ebookreader.domain.model.AnnotationStyle
import kotlinx.coroutines.flow.Flow

interface AnnotationRepository {
    fun getAnnotationsForBook(bookId: Long): Flow<List<Annotation>>
    suspend fun getAnnotationById(annotationId: Long): Annotation?
    suspend fun insertAnnotation(annotation: Annotation): Long
    suspend fun deleteAnnotation(annotationId: Long)
    suspend fun updateAnnotation(annotationId: Long, note: String, style: AnnotationStyle, color: Int)
}
