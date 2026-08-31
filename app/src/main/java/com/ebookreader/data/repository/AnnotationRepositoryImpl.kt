package com.ebookreader.data.repository

import com.ebookreader.data.local.dao.AnnotationDao
import com.ebookreader.data.mapper.toDomain
import com.ebookreader.data.mapper.toEntity
import com.ebookreader.domain.model.Annotation
import com.ebookreader.domain.model.AnnotationStyle
import com.ebookreader.domain.repository.AnnotationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AnnotationRepositoryImpl(private val annotationDao: AnnotationDao) : AnnotationRepository {

    override fun getAnnotationsForBook(bookId: Long): Flow<List<Annotation>> =
        annotationDao.getAnnotationsForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getAnnotationById(annotationId: Long): Annotation? =
        annotationDao.getAnnotationById(annotationId)?.toDomain()

    override suspend fun insertAnnotation(annotation: Annotation): Long =
        annotationDao.insertAnnotation(annotation.toEntity())

    override suspend fun deleteAnnotation(annotationId: Long) =
        annotationDao.deleteAnnotation(annotationId)

    override suspend fun updateAnnotation(annotationId: Long, note: String, style: AnnotationStyle, color: Int) =
        annotationDao.updateAnnotation(annotationId, note, style.name, color)
}
