package com.ebookreader.data.mapper

import com.ebookreader.data.local.entity.AnnotationEntity
import com.ebookreader.data.local.entity.BookChunkEntity
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.ChapterEntity
import com.ebookreader.data.local.entity.ChatMessageEntity
import com.ebookreader.data.local.entity.ConversationEntity
import com.ebookreader.data.local.entity.TagEntity
import com.ebookreader.data.local.entity.TagGroupEntity
import com.ebookreader.domain.model.Annotation
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookChunk
import com.ebookreader.domain.model.AnnotationStyle
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.model.Chapter
import com.ebookreader.domain.model.ChatMessage
import com.ebookreader.domain.model.Conversation
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    description = description,
    coverPath = coverPath,
    filePath = filePath,
    format = format,
    totalPages = totalPages,
    currentPage = currentPage,
    currentLocator = currentLocator,
    totalReadingTime = totalReadingTime,
    addedTimestamp = addedTimestamp,
    lastReadTimestamp = lastReadTimestamp,
    fileSize = fileSize,
    originalFilePath = originalFilePath,
)

fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    description = description,
    coverPath = coverPath,
    filePath = filePath,
    format = format,
    totalPages = totalPages,
    currentPage = currentPage,
    currentLocator = currentLocator,
    totalReadingTime = totalReadingTime,
    addedTimestamp = addedTimestamp,
    lastReadTimestamp = lastReadTimestamp,
    fileSize = fileSize,
    originalFilePath = originalFilePath,
)

fun TagEntity.toDomain(): Tag = Tag(
    id = id,
    name = name,
    createdTimestamp = createdTimestamp,
)

fun Tag.toEntity(): TagEntity = TagEntity(
    id = id,
    name = name,
    createdTimestamp = createdTimestamp,
)

fun TagGroupEntity.toDomain(tags: List<Tag> = emptyList()): TagGroup = TagGroup(
    id = id,
    name = name,
    logicType = logicType,
    isTab = isTab,
    tags = tags,
    query = query,
)

fun TagGroup.toEntity(): TagGroupEntity = TagGroupEntity(
    id = id,
    name = name,
    logicType = logicType,
    isTab = isTab,
    tagIdsJson = tags.map { it.id }.toString(),
    query = query,
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    title = title,
    note = note,
    locatorJson = locatorJson,
    createdTimestamp = createdTimestamp,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    title = title,
    note = note,
    locatorJson = locatorJson,
    createdTimestamp = createdTimestamp,
)

fun AnnotationEntity.toDomain(): Annotation = Annotation(
    id = id,
    bookId = bookId,
    locatorJson = locatorJson,
    selectedText = selectedText,
    pageIndex = pageIndex,
    style = runCatching { AnnotationStyle.valueOf(style) }.getOrDefault(AnnotationStyle.HIGHLIGHT),
    color = color,
    note = note,
    createdTimestamp = createdTimestamp,
)

fun Annotation.toEntity(): AnnotationEntity = AnnotationEntity(
    id = id,
    bookId = bookId,
    locatorJson = locatorJson,
    selectedText = selectedText,
    pageIndex = pageIndex,
    style = style.name,
    color = color,
    note = note,
    createdTimestamp = createdTimestamp,
)

fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    title = title,
    startLocatorJson = startLocatorJson,
    orderIndex = orderIndex,
    isUserCreated = isUserCreated,
    regexPattern = regexPattern,
)

fun Chapter.toEntity(): ChapterEntity = ChapterEntity(
    id = id,
    bookId = bookId,
    title = title,
    startLocatorJson = startLocatorJson,
    orderIndex = orderIndex,
    isUserCreated = isUserCreated,
    regexPattern = regexPattern,
)

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    bookId = bookId,
    title = title,
    createdTimestamp = createdTimestamp,
    updatedTimestamp = updatedTimestamp,
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    bookId = bookId,
    title = title,
    createdTimestamp = createdTimestamp,
    updatedTimestamp = updatedTimestamp,
)

fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
)

fun ChatMessage.toEntity(): ChatMessageEntity = ChatMessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
)

fun BookChunkEntity.toDomain(
    bookTitle: String = "",
    bookAuthor: String = "",
): BookChunk = BookChunk(
    id = id,
    bookId = bookId,
    chunkIndex = chunkIndex,
    chapterTitle = chapterTitle,
    content = content,
    charOffset = charOffset,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    eventSummary = eventSummary,
)

fun BookChunk.toEntity(): BookChunkEntity = BookChunkEntity(
    id = id,
    bookId = bookId,
    chunkIndex = chunkIndex,
    chapterTitle = chapterTitle,
    content = content,
    charOffset = charOffset,
    fileModified = 0L, // caller should set this
    eventSummary = eventSummary,
)
