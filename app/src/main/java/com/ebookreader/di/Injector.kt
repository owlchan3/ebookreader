package com.ebookreader.di

import android.content.Context
import com.ebookreader.data.dictionary.DictionaryService
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.data.ml.KeywordExtractor
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.data.network.CloudTtsClient
import com.ebookreader.data.network.DeepSeekClient
import com.ebookreader.data.repository.AnnotationRepositoryImpl
import com.ebookreader.data.repository.BookRepositoryImpl
import com.ebookreader.data.repository.BookmarkRepositoryImpl
import com.ebookreader.data.repository.ChatRepositoryImpl
import com.ebookreader.data.repository.KeywordRepositoryImpl
import com.ebookreader.data.repository.TagRepositoryImpl
import com.ebookreader.domain.repository.AnnotationRepository
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.BookmarkRepository
import com.ebookreader.domain.repository.ChatRepository
import com.ebookreader.domain.repository.KeywordRepository
import com.ebookreader.domain.repository.TagRepository

object Injector {
    private var database: AppDatabase? = null
    private var bookRepository: BookRepository? = null
    private var tagRepository: TagRepository? = null
    private var bookmarkRepository: BookmarkRepository? = null
    private var annotationRepository: AnnotationRepository? = null
    private var chatRepository: ChatRepository? = null
    private var keywordRepository: KeywordRepository? = null
    private var keywordExtractor: KeywordExtractor? = null
    private var apiKeyManager: ApiKeyManager? = null
    private var deepSeekClient: DeepSeekClient? = null
    private var cloudTtsClient: CloudTtsClient? = null
    private var dictionaryService: DictionaryService? = null

    fun init(context: Context) {
        database = AppDatabase.getInstance(context)
        bookRepository = BookRepositoryImpl(database!!.bookDao())
        tagRepository = TagRepositoryImpl(database!!.tagDao())
        bookmarkRepository = BookmarkRepositoryImpl(database!!.bookmarkDao())
        annotationRepository = AnnotationRepositoryImpl(database!!.annotationDao())
        apiKeyManager = ApiKeyManager(context)
        deepSeekClient = DeepSeekClient(apiKeyManager!!)
        cloudTtsClient = CloudTtsClient(apiKeyManager!!)
        chatRepository = ChatRepositoryImpl(database!!.chatDao(), database!!.bookChunkDao(), database!!.chunkEmbeddingDao(), context)
        keywordRepository = KeywordRepositoryImpl(database!!.bookKeywordDao())
        keywordExtractor = KeywordExtractor(context, chatRepository!!, database!!.bookChunkDao())
        dictionaryService = DictionaryService(context)
    }

    fun bookRepository(): BookRepository = bookRepository
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun tagRepository(): TagRepository = tagRepository
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun bookmarkRepository(): BookmarkRepository = bookmarkRepository
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun annotationRepository(): AnnotationRepository = annotationRepository
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun chatRepository(): ChatRepository = chatRepository
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun keywordRepository(): KeywordRepository = keywordRepository
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun keywordExtractor(): KeywordExtractor = keywordExtractor
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun apiKeyManager(): ApiKeyManager = apiKeyManager
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun deepSeekClient(): DeepSeekClient = deepSeekClient
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun cloudTtsClient(): CloudTtsClient = cloudTtsClient
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun appDatabase(): AppDatabase = database
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")

    fun dictionaryService(): DictionaryService = dictionaryService
        ?: throw IllegalStateException("Injector not initialized. Call Injector.init(context) first.")
}
