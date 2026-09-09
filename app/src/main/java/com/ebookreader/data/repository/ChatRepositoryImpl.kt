package com.ebookreader.data.repository

import android.app.Application
import android.content.Context
import com.ebookreader.data.importer.BookImporter
import com.ebookreader.data.local.dao.BookChunkDao
import com.ebookreader.data.local.dao.ChatDao
import com.ebookreader.data.local.dao.ChunkEmbeddingDao
import com.ebookreader.data.local.entity.ChunkEmbeddingEntity
import com.ebookreader.data.mapper.toDomain
import com.ebookreader.data.mapper.toEntity
import com.ebookreader.data.ml.EmbeddingModel
import com.ebookreader.domain.model.BookChunk
import com.ebookreader.domain.model.ChatMessage
import com.ebookreader.domain.model.Conversation
import com.ebookreader.domain.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.ln
import kotlin.math.sqrt

class ChatRepositoryImpl(
    private val chatDao: ChatDao,
    private val bookChunkDao: BookChunkDao,
    private val chunkEmbeddingDao: ChunkEmbeddingDao,
    private val context: Context,
) : ChatRepository {

    /** Loaded lazily on first use (background IO thread), so app startup isn't blocked by the model. */
    private val embeddingModel: EmbeddingModel by lazy { EmbeddingModel(context) }

    /** 序列化对 ONNX 会话的访问：OrtSession.run 非线程安全，且多入口可能并发调用 embed。 */
    private val embedMutex = Mutex()

    /** 序列化分块索引：阅读页与对话页都可能触发 ensureIndexed，delete+insert 非原子。 */
    private val indexMutex = Mutex()

    /** 切分算法升级后，一次性清空旧索引，强制重新分块。 */
    private suspend fun invalidateStaleIndex() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(VERSION_KEY, 0)
        if (stored != CHUNK_INDEX_VERSION) {
            bookChunkDao.deleteAllChunks()
            prefs.edit().putInt(VERSION_KEY, CHUNK_INDEX_VERSION).apply()
        }
    }

    /** 嵌入模型升级后一次性清空旧向量，触发按需重新向量化。 */
    private suspend fun invalidateStaleEmbeddings() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(EMBEDDING_VERSION_KEY, 0)
        if (stored != EMBEDDING_VERSION) {
            chunkEmbeddingDao.deleteAllEmbeddings()
            prefs.edit().putInt(EMBEDDING_VERSION_KEY, EMBEDDING_VERSION).apply()
        }
    }

    // ── Conversations ──────────────────────────────────────────────────────

    override fun getConversationsForBook(bookId: Long): Flow<List<Conversation>> =
        chatDao.getConversationsForBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun createConversation(bookId: Long, title: String): Long {
        val conv = com.ebookreader.data.local.entity.ConversationEntity(
            bookId = bookId,
            title = title,
            createdTimestamp = System.currentTimeMillis(),
            updatedTimestamp = System.currentTimeMillis(),
        )
        return chatDao.insertConversation(conv)
    }

    override suspend fun deleteConversation(conversationId: Long) {
        val entity = chatDao.getConversationById(conversationId) ?: return
        chatDao.deleteConversation(entity)
    }

    override suspend fun updateConversationTimestamp(conversationId: Long) {
        chatDao.touchConversation(conversationId)
    }

    // ── Messages ───────────────────────────────────────────────────────────

    override fun getMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatDao.getMessagesForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun getMessagesOnce(conversationId: Long): List<ChatMessage> =
        chatDao.getMessagesForConversationOnce(conversationId).map { it.toDomain() }

    override suspend fun addMessage(conversationId: Long, role: String, content: String): Long {
        val msg = com.ebookreader.data.local.entity.ChatMessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
        )
        val id = chatDao.insertMessage(msg)
        chatDao.touchConversation(conversationId)
        return id
    }

    // ── Book content extraction ────────────────────────────────────────────

    override suspend fun getBookContent(bookId: Long): String = withContext(Dispatchers.IO) {
        val book = resolveBook(bookId) ?: return@withContext ""
        extractContent(book.filePath, book.format)
    }

    override suspend fun getBookTextSample(bookId: Long, maxChars: Int): String = withContext(Dispatchers.IO) {
        val book = resolveBook(bookId) ?: return@withContext ""
        val full = extractContent(book.filePath, book.format)
        full.take(maxChars)
    }

    override suspend fun countCharacters(filePath: String, format: String): Long = withContext(Dispatchers.IO) {
        extractContent(filePath, format).count { !it.isWhitespace() }.toLong()
    }

    // ── Export ─────────────────────────────────────────────────────────────

    override suspend fun exportConversation(conversationId: Long): String {
        val conv = chatDao.getConversationById(conversationId) ?: return ""
        val messages = chatDao.getMessagesForConversationOnce(conversationId)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("=== ${conv.title} ===")
        sb.appendLine("创建时间: ${dateFormat.format(Date(conv.createdTimestamp))}")
        sb.appendLine("导出时间: ${dateFormat.format(Date())}")
        sb.appendLine()
        for (msg in messages) {
            val roleLabel = when (msg.role) {
                "user" -> "用户"
                "assistant" -> "AI"
                "system" -> "系统"
                else -> msg.role
            }
            sb.appendLine("[$roleLabel] ${dateFormat.format(Date(msg.timestamp))}")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    // ── Chunk indexing ─────────────────────────────────────────────────────

    companion object {
        /** Chunk target size in characters. */
        private const val CHUNK_SIZE = 1200
        /** Overlap between consecutive chunks in characters. */
        private const val CHUNK_OVERLAP = 200
        /** 章节切分算法版本。改动切分逻辑时 +1，使已索引的书自动重建索引。 */
        private const val CHUNK_INDEX_VERSION = 7
        private const val EMBEDDING_VERSION = 2
        private const val PREFS_NAME = "chunk_index_meta"
        private const val VERSION_KEY = "chunk_index_version"
        private const val EMBEDDING_VERSION_KEY = "embedding_model_version"
    }

    override suspend fun ensureIndexed(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            invalidateStaleIndex()
            val book = resolveBook(bookId) ?: return@withLock false
            val file = File(book.filePath)
            if (!file.exists()) return@withLock false

            val fileModified = file.lastModified()
            // Check if we already have a valid index
            val existing = bookChunkDao.hasCurrentIndex(bookId, fileModified)
            if (existing != null) {
                Timber.d("Book $bookId already indexed: ${bookChunkDao.getChunkCount(bookId)} chunks")
                return@withLock false // no re-index needed
            }

            // Need to index: extract full content, then chunk
            Timber.i("Indexing book $bookId: ${book.title} (${book.format})")
            val fullText = extractContent(file.path, book.format)
            if (fullText.isBlank()) {
                Timber.w("Cannot index book $bookId: empty content")
                return@withLock false
            }

            // Delete old chunks for this book
            bookChunkDao.deleteChunksForBook(bookId)

            // Detect chapter boundaries for EPUB/XHTML（EPUB 直接读取导航目录，与阅读器目录完全一致）
            val rawChapters = extractChapters(file, book.format, fullText)

            // Chunk each chapter with overlap
            val chunks = mutableListOf<com.ebookreader.data.local.entity.BookChunkEntity>()
            var globalOffset = 0
            var chunkIndex = 0

            for ((chapterTitle, chapterText) in rawChapters) {
                val paragraphs = smartSplit(chapterText, CHUNK_SIZE - CHUNK_OVERLAP)
                for (par in paragraphs) {
                    if (par.isBlank()) continue
                    val start = 0
                    var pos = start
                    while (pos < par.length) {
                        val end = (pos + CHUNK_SIZE).coerceAtMost(par.length)
                        val slice = par.substring(pos, end)
                        chunks.add(
                            com.ebookreader.data.local.entity.BookChunkEntity(
                                bookId = bookId,
                                chunkIndex = chunkIndex,
                                chapterTitle = chapterTitle,
                                content = slice,
                                charOffset = globalOffset + pos,
                                fileModified = fileModified,
                            )
                        )
                        chunkIndex++
                        // Next chunk start with overlap
                        pos += CHUNK_SIZE - CHUNK_OVERLAP
                        if (pos >= par.length) break
                    }
                    globalOffset += par.length + 1 // +1 for separator
                }
            }

            if (chunks.isNotEmpty()) {
                bookChunkDao.insertChunks(chunks)
                Timber.i("Indexed book $bookId: ${chunks.size} chunks")
            }
            true
        }
    }

    override suspend fun ensureEmbedded(
        bookId: Long,
        onProgress: ((embedded: Int, total: Int, status: String) -> Unit)?,
    ): Int = withContext(Dispatchers.IO) {
        embedMutex.withLock {
            invalidateStaleEmbeddings()
            val model = embeddingModel
            if (!model.isAvailable()) return@withLock 0

            val chunks = bookChunkDao.getChunksForBook(bookId)
            if (chunks.isEmpty()) return@withLock 0
            val embeddedIds = chunkEmbeddingDao.getEmbeddedChunkIds(bookId).toSet()
            val pending = chunks.filter { it.id !in embeddedIds }
            val total = chunks.size
            if (pending.isEmpty()) {
                onProgress?.invoke(total, total, model.statusText())
                return@withLock 0
            }

            val batchSize = 32
            var count = 0
            var skipped = 0
            for (batch in pending.chunked(batchSize)) {
                val vectors = model.embed(batch.map { embedText(it) })
                if (vectors.size == batch.size) {
                    val entities = batch.zip(vectors).map { (chunk, vec) ->
                        ChunkEmbeddingEntity(
                            chunkId = chunk.id,
                            bookId = chunk.bookId,
                            dim = vec.size,
                            vector = floatArrayToBytes(vec),
                        )
                    }
                    chunkEmbeddingDao.insertEmbeddings(entities)
                    count += entities.size
                } else {
                    // 整批失败（如内存/并发）时逐块重试，跳过坏块，避免整批卡死。
                    Timber.w("ensureEmbedded: 批处理失败(${vectors.size}/${batch.size})，逐块重试；模型=${model.statusText()}")
                    var consecutiveFailures = 0
                    for ((i, chunk) in batch.withIndex()) {
                        val one = model.embed(listOf(embedText(chunk)))
                        if (one.size == 1) {
                            chunkEmbeddingDao.insertEmbeddings(
                                listOf(
                                    ChunkEmbeddingEntity(
                                        chunkId = chunk.id,
                                        bookId = chunk.bookId,
                                        dim = one[0].size,
                                        vector = floatArrayToBytes(one[0]),
                                    )
                                )
                            )
                            count++
                            consecutiveFailures = 0
                        } else {
                            skipped++
                            consecutiveFailures++
                            if (consecutiveFailures >= 2) {
                                skipped += (batch.size - i - 1)
                                break
                            }
                        }
                    }
                }
                onProgress?.invoke(embeddedIds.size + count, total, model.statusText())
            }
            if (count > 0 || skipped > 0) Timber.i("Embedded $count (skipped $skipped) chunks for book $bookId")
            count
        }
    }

    /**
     * 向量化时实际嵌入的文本：章节标题 + 事件摘要 + 正文拼接。
     * 相比只嵌入正文，标题/摘要把「人物关系、情节脉络」这类高语义信号也纳入向量，
     * 使抽象/聚合类问题（如「有哪些角色」）在语义检索下同样能命中（对齐 BM25 的 3:1 权重）。
     */
    private fun embedText(entity: com.ebookreader.data.local.entity.BookChunkEntity): String {
        val parts = mutableListOf<String>()
        if (entity.chapterTitle.isNotBlank()) parts.add(entity.chapterTitle)
        if (entity.eventSummary.isNotBlank()) parts.add(entity.eventSummary)
        if (entity.content.isNotBlank()) parts.add(entity.content)
        return parts.joinToString(" ")
    }

    /** 重新向量化指定 chunk（用于摘要生成后把摘要信号回填进向量）。REPLACE 语义，无则插入。 */
    override suspend fun reembedChunks(bookId: Long, chunkIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (chunkIds.isEmpty()) return@withContext 0
        embedMutex.withLock {
            val model = embeddingModel
            if (!model.isAvailable()) return@withLock 0
            val idSet = chunkIds.toSet()
            val chunks = bookChunkDao.getChunksForBook(bookId).filter { it.id in idSet }
            if (chunks.isEmpty()) return@withLock 0

            var count = 0
            for (batch in chunks.chunked(32)) {
                val vectors = model.embed(batch.map { embedText(it) })
                if (vectors.size == batch.size) {
                    val entities = batch.zip(vectors).map { (chunk, vec) ->
                        ChunkEmbeddingEntity(
                            chunkId = chunk.id,
                            bookId = chunk.bookId,
                            dim = vec.size,
                            vector = floatArrayToBytes(vec),
                        )
                    }
                    chunkEmbeddingDao.insertEmbeddings(entities)
                    count += entities.size
                }
            }
            count
        }
    }

    override suspend fun getChunkEmbeddingCount(bookId: Long): Int =
        chunkEmbeddingDao.getEmbeddingCount(bookId)

    override suspend fun embeddingModelStatus(): String = withContext(Dispatchers.IO) {
        embeddingModel.statusText()
    }

    override suspend fun embedTexts(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val model = embeddingModel
        if (!model.isAvailable()) return@withContext emptyList()
        embedMutex.withLock { model.embed(texts) }
    }

    override suspend fun getChunkCount(bookId: Long): Int =
        bookChunkDao.getChunkCount(bookId)

    override suspend fun getUnsummarizedChunks(bookId: Long, limit: Int): List<BookChunk> =
        bookChunkDao.getChunksWithoutSummary(bookId, limit).map { it.toDomain() }

    override suspend fun updateChunkEventSummary(chunkId: Long, summary: String) {
        bookChunkDao.updateEventSummary(chunkId, summary)
    }

    override suspend fun getDistinctChapters(bookId: Long): List<String> =
        bookChunkDao.getDistinctChapters(bookId)

    override suspend fun getChunksByChapters(bookId: Long, chapterTitles: List<String>): List<BookChunk> =
        bookChunkDao.getChunksByChapters(bookId, chapterTitles).map { it.toDomain() }

    override suspend fun searchChunks(
        bookIds: Set<Long>,
        queries: List<String>,
        topK: Int,
        preferEarlierChunks: Boolean,
    ): List<BookChunk> = withContext(Dispatchers.IO) {
        if (bookIds.isEmpty() || queries.isEmpty()) return@withContext emptyList()

        // Load all chunks from the specified books
        val allChunks = mutableListOf<com.ebookreader.data.local.entity.BookChunkEntity>()
        val bookMeta = mutableMapOf<Long, Pair<String, String>>() // bookId -> (title, author)
        val maxChunkIndexPerBook = mutableMapOf<Long, Int>() // for time decay normalization
        for (bid in bookIds) {
            val chunks = bookChunkDao.getChunksForBook(bid)
            if (chunks.isNotEmpty()) {
                allChunks.addAll(chunks)
                maxChunkIndexPerBook[bid] = chunks.maxOf { it.chunkIndex }
                val book = resolveBook(bid)
                if (book != null) {
                    bookMeta[bid] = book.title to book.author
                }
            }
        }
        if (allChunks.isEmpty()) return@withContext emptyList()

        // Score chunks: dense vector retrieval when embeddings are ready, BM25 otherwise.
        val scored = if (embeddingsReady(bookIds)) {
            vectorScore(queries, allChunks, bookIds)
        } else {
            bm25Score(queries, allChunks, topK)
        }

        // Flashback penalty: reduce score for chunks containing flashback markers
        for ((chunkId, score) in scored.toList()) {
            val entity = allChunks.find { it.id == chunkId } ?: continue
            if (hasFlashbackMarker(entity.content, entity.eventSummary)) {
                scored[chunkId] = score * 0.5
            }
        }

        // Time decay: if preferEarlierChunks, give bonus to chronologically earlier chunks
        if (preferEarlierChunks) {
            for (entry in scored.entries) {
                val entity = allChunks.find { it.id == entry.key } ?: continue
                val maxIdx = maxChunkIndexPerBook[entity.bookId] ?: 1
                val positionRatio = entity.chunkIndex.toDouble() / maxIdx.coerceAtLeast(1)
                // Sigmoid decay: earlier chunks (index near 0) get up to +40% boost
                val timeBonus = 1.0 + 0.4 * (1.0 - positionRatio)
                scored[entry.key] = entry.value * timeBonus
            }
        }

        // Deduplicate: remove near-duplicate chunks (adjacent indices from same book)
        val deduped = mutableListOf<Pair<Long, Double>>()
        val used = mutableSetOf<Pair<Long, Int>>() // (bookId, window)
        val sorted = scored.entries.sortedByDescending { it.value }
        for (entry in sorted) {
            val entity = allChunks.find { it.id == entry.key } ?: continue
            val window = entity.chunkIndex / 3 // group every 3 adjacent chunks
            val key = entity.bookId to window
            if (key !in used) {
                used.add(key)
                deduped.add(entry.key to entry.value)
            }
            if (deduped.size >= topK) break
        }

        deduped.mapNotNull { (chunkId, _) ->
            val entity = allChunks.find { it.id == chunkId } ?: return@mapNotNull null
            val (title, author) = bookMeta[entity.bookId] ?: ("" to "")
            entity.toDomain(bookTitle = title, bookAuthor = author)
        }.let { topChunks ->
            expandWithContext(topChunks, allChunks, bookMeta, topK)
        }
    }

    /** True when the dense model is loaded and (almost) every queried chunk has a vector. */
    private suspend fun embeddingsReady(bookIds: Set<Long>): Boolean {
        var totalChunks = 0
        var totalEmbedded = 0
        for (bid in bookIds) {
            totalChunks += bookChunkDao.getChunkCount(bid)
            totalEmbedded += chunkEmbeddingDao.getEmbeddingCount(bid)
        }
        if (totalChunks == 0 || totalEmbedded < totalChunks * 0.9) return false
        return embeddingModel.isAvailable()
    }

    /** BM25 scoring over content + eventSummary, summary weighted 3:1 vs content. */
    private fun bm25Score(
        queries: List<String>,
        allChunks: List<com.ebookreader.data.local.entity.BookChunkEntity>,
        topK: Int,
    ): MutableMap<Long, Double> {
        val bm25 = Bm25Index(allChunks)
        val scored = mutableMapOf<Long, Double>()
        for (query in queries) {
            val summaryResults = bm25.searchInField(query, topK * 3, useSummary = true)
            val contentResults = bm25.search(query, topK * 3)
            for ((chunk, score) in summaryResults) {
                scored[chunk.id] = maxOf(scored[chunk.id] ?: 0.0, score * 3.0)
            }
            for ((chunk, score) in contentResults) {
                scored[chunk.id] = maxOf(scored[chunk.id] ?: 0.0, score)
            }
        }
        return scored
    }

    /** Dense cosine scoring: embed each query and dot-product against every chunk vector. */
    private suspend fun vectorScore(
        queries: List<String>,
        allChunks: List<com.ebookreader.data.local.entity.BookChunkEntity>,
        bookIds: Set<Long>,
    ): MutableMap<Long, Double> {
        val scored = mutableMapOf<Long, Double>()
        val embeddings = chunkEmbeddingDao.getEmbeddingsForBooks(bookIds)
            .associate { it.chunkId to bytesToFloatArray(it.vector) }
        if (embeddings.isEmpty()) return scored

        for (query in queries) {
            val qv = embedMutex.withLock { embeddingModel.embed(listOf(query)) }.firstOrNull() ?: continue
            for (chunk in allChunks) {
                val cv = embeddings[chunk.id] ?: continue
                val sim = dot(qv, cv).toDouble()
                if (sim > 0.0) scored[chunk.id] = maxOf(scored[chunk.id] ?: 0.0, sim)
            }
        }
        return scored
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun floatArrayToBytes(f: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in f) buf.putFloat(x)
        return buf.array()
    }

    private fun bytesToFloatArray(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val f = FloatArray(b.size / 4)
        for (i in f.indices) f[i] = buf.getFloat()
        return f
    }

    /**
     * For each selected chunk, include its immediate neighbors (±1) to ensure
     * the AI sees surrounding context, not just the exact keyword match.
     * This prevents the "chapter title only" problem where BM25 matches a
     * chapter heading chunk but the body text is in adjacent chunks.
     */
    private fun expandWithContext(
        selected: List<BookChunk>,
        allChunks: List<com.ebookreader.data.local.entity.BookChunkEntity>,
        bookMeta: Map<Long, Pair<String, String>>,
        maxResults: Int,
    ): List<BookChunk> {
        if (selected.isEmpty()) return selected

        // Build lookup: (bookId, chunkIndex) -> entity
        val indexMap = mutableMapOf<Pair<Long, Int>, com.ebookreader.data.local.entity.BookChunkEntity>()
        for (chunk in allChunks) {
            indexMap[chunk.bookId to chunk.chunkIndex] = chunk
        }

        val seen = mutableSetOf<Long>() // chunk id
        val result = mutableListOf<BookChunk>()
        fun addEntity(e: com.ebookreader.data.local.entity.BookChunkEntity?) {
            if (e != null && seen.add(e.id)) {
                val (title, author) = bookMeta[e.bookId] ?: ("" to "")
                result.add(e.toDomain(bookTitle = title, bookAuthor = author))
            }
        }

        // Pass 1: 保留全部被选中的块（按相关性顺序），确保 topK 个去重窗口都进入候选，
        // 避免相邻块「挤掉」其他窗口，导致热点章节垄断、跨章召回不足。
        for (chunk in selected) {
            addEntity(indexMap[chunk.bookId to chunk.chunkIndex])
        }
        // Pass 2: 仅当候选不足 maxResults（小书 / 命中少）时，用 ±1 相邻块补齐上下文。
        for (chunk in selected) {
            if (result.size >= maxResults) break
            addEntity(indexMap[chunk.bookId to (chunk.chunkIndex - 1)])
            addEntity(indexMap[chunk.bookId to (chunk.chunkIndex + 1)])
        }
        return result.take(maxResults)
    }

    /** Flashback keyword patterns — content containing these is likely a flashback/recall, not an event. */
    private val flashbackPatterns = listOf(
        "回忆", "想起", "记得", "那时候", "当时", "那年", "曾经",
        "回想", "往事", "从前", "以前", "脑海里浮现", "记忆",
        "回想起", "回忆起", "想到以前", "回想起当初",
    )

    private fun hasFlashbackMarker(content: String, eventSummary: String): Boolean {
        val searchText = eventSummary.ifBlank { content.take(200) }
        return flashbackPatterns.any { it in searchText }
    }

    // ── Content extraction internals ───────────────────────────────────────

    private suspend fun resolveBook(bookId: Long): com.ebookreader.domain.model.Book? {
        return try {
            com.ebookreader.di.Injector.bookRepository().getBookById(bookId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve book $bookId")
            null
        }
    }

    private suspend fun extractContent(filePath: String, storedFormat: String): String {
        val file = File(filePath)
        if (!file.exists()) return ""
        val actualExt = file.extension.lowercase()
        val format = when {
            actualExt == "epub" -> "EPUB"
            actualExt == "pdf" -> "PDF"
            actualExt == "txt" -> "TXT"
            else -> storedFormat.uppercase()
        }
        val text = when (format) {
            "TXT" -> extractTxt(file)
            "EPUB" -> extractEpub(file)
            "PDF" -> extractPdf(file)
            else -> tryExtractByExtension(file)
        }
        // 自定义抽取（OPF/spine → 全量 HTML 兜底）仍取空时，改用 Readium（与阅读器同源）
        // 按 readingOrder 抽取全文：这类 EPUB 阅读器能正常渲染、只是结构特殊，自定义解析读不到正文。
        if (text.isBlank() && format == "EPUB") {
            return extractEpubByReadium(file)
        }
        return text
    }

    private suspend fun tryExtractByExtension(file: File): String {
        return when (file.extension.lowercase()) {
            "epub" -> extractEpub(file)
            "pdf" -> extractPdf(file)
            "txt" -> extractTxt(file)
            else -> ""
        }
    }

    // ── Chapter-aware splitting ────────────────────────────────────────────

    /**
     * Attempt to split text by chapter boundaries.
     * For EPUB: relies on XHTML-level separation already done in extraction.
     * For TXT: detects common chapter patterns.
     * Returns list of (chapterTitle, text).
     */
    private fun splitIntoChapterAware(
        text: String,
        file: File,
        format: String,
    ): List<Pair<String, String>> {
        // 与阅读器目录保持一致：复用 BookImporter 的章节检测（同一套正则 + 100 字过滤 + 去重），
        // 这样拆书使用的章节列表与书籍当前目录相同。
        val detected = try {
            BookImporter.detectChapters(text)
        } catch (_: Exception) {
            emptyList()
        }
        if (detected.isNotEmpty()) {
            val result = mutableListOf<Pair<String, String>>()
            val preface = text.substring(0, detected[0].startIndex).trim()
            if (preface.length > 50) {
                result.add("序言/前言" to preface)
            } else if (preface.isNotBlank()) {
                result.add("" to preface)
            }
            for (i in detected.indices) {
                val start = detected[i].startIndex
                val end = if (i + 1 < detected.size) detected[i + 1].startIndex else text.length
                val body = text.substring(start, end).trim()
                result.add(detected[i].title to body)
            }
            if (result.size > 1) return result
        }
        // 回退：单一未命名章节
        return listOf("" to text)
    }

    /** Detect chapter patterns: "第X章", "Chapter X", etc. */
    private fun splitByChapterPatterns(text: String): List<Pair<String, String>> {
        val patterns = listOf(
            Regex("""(第[一二三四五六七八九十百千0-9]+[章节回卷集篇部])"""),
            Regex("""(Chapter\s+\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(PART\s+[IVX]+)""", RegexOption.IGNORE_CASE),
            Regex("""^\s*([一二三四五六七八九十百千]+、)"""),
        )

        for (pattern in patterns) {
            val splits = pattern.split(text)
            if (splits.size > 1) {
                val matches = pattern.findAll(text).map { it.value }.toList()
                val result = mutableListOf<Pair<String, String>>()
                // First segment before any chapter marker
                val firstText = splits[0].trim()
                if (firstText.length > 50) {
                    result.add("序言/前言" to firstText)
                } else if (firstText.isNotBlank()) {
                    result.add("" to firstText)
                }
                for (i in matches.indices) {
                    val title = matches[i]
                    val body = splits[i + 1].trim()
                    result.add(title to body)
                }
                if (result.size > 1) return result
            }
        }
        return listOf("" to text)
    }

    /** Split text into roughly equal segments at paragraph boundaries. */
    private fun smartSplit(text: String, targetSize: Int): List<String> {
        if (text.length <= targetSize) return listOf(text)

        val result = mutableListOf<String>()
        var current = StringBuilder()
        val paragraphs = text.split(Regex("""\n{2,}|\r\n{2,}"""))

        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue

            if (current.length + trimmed.length > targetSize && current.isNotEmpty()) {
                result.add(current.toString().trim())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(trimmed)
        }
        if (current.isNotBlank()) {
            result.add(current.toString().trim())
        }
        return result.ifEmpty { listOf(text) }
    }

    // ── TXT extraction ─────────────────────────────────────────────────────

    private val candidateEncodings = listOf("GBK", "GB18030", "GB2312", "UTF-8", "Big5", "windows-1252")

    private fun extractTxt(file: File): String {
        val raw = file.readBytes()
        val charset = detectCharset(raw)
        val text = String(raw, charset)
        val sampleLen = minOf(text.length, 2000).coerceAtLeast(1)
        val badRatio = text.take(2000).count { it == ' ' || it == '�' }.toFloat() / sampleLen
        if (badRatio > 0.1f) {
            for (fallback in listOf("UTF-8", "GBK", "GB18030")) {
                try {
                    val fb = Charset.forName(fallback)
                    val fbText = String(raw, fb)
                    val fbBad = fbText.take(2000).count { it == ' ' || it == '�' }.toFloat() /
                        minOf(fbText.length, 2000).coerceAtLeast(1)
                    if (fbBad < 0.05f) return fbText
                } catch (_: Exception) {}
            }
            Timber.w("TXT encoding quality poor: ratio=$badRatio, charset=$charset")
        }
        return text
    }

    private fun detectCharset(raw: ByteArray): Charset {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte())
            return Charsets.UTF_8
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
            return Charset.forName("UTF-16BE")
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte())
            return Charset.forName("UTF-16LE")

        val sample = raw.copyOfRange(0, minOf(raw.size, 131072))
        val clean = candidateEncodings.mapNotNull { encName ->
            val charset = try { Charset.forName(encName) } catch (_: Exception) { return@mapNotNull null }
            val text = String(sample, charset)
            if (text.none { it == '�' }) encName to text else null
        }
        if (clean.isEmpty()) {
            return candidateEncodings.mapNotNull { encName ->
                val charset = try { Charset.forName(encName) } catch (_: Exception) { return@mapNotNull null }
                val text = String(sample, charset)
                encName to text.count { it == '�' }
            }.minByOrNull { it.second }?.let { Charset.forName(it.first) } ?: Charsets.UTF_8
        }
        if (clean.size == 1) return Charset.forName(clean.first().first)
        val scored = clean.map { (name, text) ->
            name to text.count { ch -> ch in '一'..'鿿' || ch in '㐀'..'䶿' }
        }
        val best = scored.maxByOrNull { it.second }
        return if (best != null && best.second > 0) Charset.forName(best.first) else Charsets.UTF_8
    }

    // ── EPUB extraction ────────────────────────────────────────────────────

    private fun extractEpub(file: File): String {
        try {
            var spineText = ""
            val opfContent = readOpfFromEpub(file)
            if (opfContent != null) {
                val opfDir = extractOpfDir(opfContent.first)
                val xml = opfContent.second
                val items = parseManifest(xml)
                val spineIds = parseSpine(xml)
                if (spineIds.isNotEmpty()) {
                    val spineHrefs = spineIds.mapNotNull { items[it] }
                    spineText = readSpineTexts(file, opfDir, spineHrefs)
                }
            }
            // 兜底：遍历所有 XHTML/HTML 文件拼接正文。若 spine 路径匹配只命中部分章节（例如只
            // 取到封面/版权页等前言），正文会比全量兜底明显偏短，此时改用全量兜底，保证拿到完整
            // 正文（否则关键词会只剩「出版社」等前言通用词）。
            val fallback = extractEpubAllHtml(file)
            if (fallback.length > spineText.length) return fallback
            if (spineText.isNotBlank()) return spineText
            if (fallback.isNotBlank()) return fallback
            Timber.w("EPUB extraction empty: ${file.name}")
            return ""
        } catch (e: Exception) {
            Timber.e(e, "EPUB extraction failed")
            return ""
        }
    }

    private fun readOpfFromEpub(file: File): Pair<String, String>? {
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("META-INF/container.xml", ignoreCase = true)) {
                    val xml = String(zip.readBytes(), Charsets.UTF_8)
                    val m = Regex("""full-path\s*=\s*"([^"]+)"""").find(xml)
                        ?: Regex("""full-path\s*=\s*'([^']+)'""").find(xml)
                    val opfPath = m?.groupValues?.get(1) ?: return null
                    entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == opfPath ||
                            entry.name.endsWith("/$opfPath") ||
                            entry.name.replace('\\', '/') == opfPath) {
                            return opfPath to String(zip.readBytes(), Charsets.UTF_8)
                        }
                        entry = zip.nextEntry
                    }
                    return null
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun extractOpfDir(opfPath: String): String {
        val normalized = opfPath.replace('\\', '/')
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash >= 0) normalized.substring(0, lastSlash) else ""
    }

    private fun parseManifest(opfXml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val itemRe = Regex("""<item[^>]+>""")
        for (m in itemRe.findAll(opfXml)) {
            val tag = m.value
            val id = Regex("""id\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1)
                ?: Regex("""id\s*=\s*'([^']+)'""").find(tag)?.groupValues?.get(1)
            val href = Regex("""href\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1)
                ?: Regex("""href\s*=\s*'([^']+)'""").find(tag)?.groupValues?.get(1)
            if (id != null && href != null) result[id] = href
        }
        return result
    }

    private fun parseSpine(opfXml: String): List<String> {
        val result = mutableListOf<String>()
        val itemrefRe = Regex("""<itemref[^>]+>""")
        for (m in itemrefRe.findAll(opfXml)) {
            val tag = m.value
            val idref = Regex("""idref\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1)
                ?: Regex("""idref\s*=\s*'([^']+)'""").find(tag)?.groupValues?.get(1)
            if (idref != null) result.add(idref)
        }
        return result
    }

    private fun readSpineTexts(file: File, opfDir: String, spineHrefs: List<String>): String {
        val sb = StringBuilder()
        val targetSet = spineHrefs.toSet()
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                val matched = targetSet.any { href ->
                    val resolved = if (opfDir.isEmpty()) href else "$opfDir/$href"
                    name == href || name == resolved || name.endsWith("/$href")
                }
                if (matched) {
                    val html = String(zip.readBytes(), Charsets.UTF_8)
                    val text = Jsoup.parse(html).text()
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(text)
                    }
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString()
    }

    /** 兜底抽取：不依赖 OPF/spine，遍历 zip 内所有 XHTML/HTML 文件，按文件名排序后拼接纯文本。 */
    private fun extractEpubAllHtml(file: File): String {
        val htmlFiles = mutableListOf<Pair<String, String>>() // (name, html)
        try {
            ZipInputStream(FileInputStream(file)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val lower = name.lowercase()
                        if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                            try {
                                htmlFiles.add(name to String(zip.readBytes(), Charsets.UTF_8))
                            } catch (_: Exception) {}
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {
            return ""
        }
        val sb = StringBuilder()
        for ((_, html) in htmlFiles.sortedBy { it.first }) {
            val text = Jsoup.parse(html).text()
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(text)
            }
        }
        return sb.toString()
    }

    /** 兜底全文抽取：用 Readium 按 readingOrder 读全文，覆盖无导航/结构特殊的 EPUB。 */
    private suspend fun extractEpubByReadium(file: File): String {
        return try {
            val app = context.applicationContext as? Application ?: return ""
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
            val pdfFactory = PdfiumDocumentFactory(app)
            val parser = DefaultPublicationParser(app, httpClient, assetRetriever, pdfFactory)
            val opener = PublicationOpener(parser)
            val url = file.toUrl(isDirectory = false)
            val asset = assetRetriever.retrieve(url).getOrElse { return "" }
            val publication = try {
                opener.open(asset, allowUserInteraction = false).getOrElse { asset.close(); return "" }
            } catch (_: Exception) { asset.close(); return "" }

            val sb = StringBuilder()
            try {
                for (link in publication.readingOrder) {
                    val bytes = publication.get(link)?.read()?.getOrElse { continue } ?: continue
                    val text = Jsoup.parse(String(bytes, Charsets.UTF_8)).text().trim()
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(text)
                    }
                }
            } finally {
                try { publication.close() } catch (_: Exception) {}
                try { asset.close() } catch (_: Exception) {}
            }
            sb.toString()
        } catch (_: Exception) { "" }
    }

    // ── EPUB 导航（与阅读器目录一致）──────────────────────────────────────

    /**
     * 章节切分入口：EPUB 直接读取导航目录（tableOfContents），与阅读器目录完全一致，
     * 避免把「书中书」或正文里的章节标记误判成真正的章节；不再用正则从正文反推章节。
     * 注意：按「文件实际扩展名」判断格式（TXT 导入后已转成 EPUB，book.format 仍可能是 TXT）。
     */
    private suspend fun extractChapters(file: File, format: String, fullText: String): List<Pair<String, String>> {
        val actualFormat = when (file.extension.lowercase()) {
            "epub" -> "EPUB"
            "pdf" -> "PDF"
            "txt" -> "TXT"
            else -> format.uppercase()
        }
        if (actualFormat == "EPUB") {
            // 优先用 Readium 读 EPUB 导航（与阅读器目录完全一致）
            val byReadium = extractEpubChaptersByReadium(file)
            // 手动解析导航兜底（同样读目录，不用正则反推）
            val byToc = if (byReadium.isEmpty()) extractEpubChaptersByToc(file) else emptyList()
            val tocChapters = byReadium.ifEmpty { byToc }
            // 目录可能只指向固定版式图片页/版权页，正文在目录未引用的「孤儿」文件里
            // （混排 EPUB 常见：正文 xhtml 不在 nav/toc 中）。此时按目录切分几乎抽不到正文，
            // 关键词会只剩出版社信息。若目录抽取总字数不足全文一半，回退用全文。
            if (tocChapters.isNotEmpty()) {
                val tocLength = tocChapters.sumOf { it.second.length }
                if (fullText.isNotBlank() && tocLength < fullText.length / 2) {
                    return listOf("" to fullText)
                }
                return tocChapters
            }
            // 没有导航：整本作为一章
            return listOf("" to fullText)
        }
        return splitIntoChapterAware(fullText, file, actualFormat)
    }

    /** 用 Readium 打开 EPUB，按 tableOfContents 切分章节（与阅读器目录一致）。 */
    private suspend fun extractEpubChaptersByReadium(file: File): List<Pair<String, String>> {
        return try {
            val app = context.applicationContext as? Application ?: return emptyList()
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
            val pdfFactory = PdfiumDocumentFactory(app)
            val parser = DefaultPublicationParser(app, httpClient, assetRetriever, pdfFactory)
            val opener = PublicationOpener(parser)
            val url = file.toUrl(isDirectory = false)
            val asset = assetRetriever.retrieve(url).getOrElse { return emptyList() }
            val publication = try {
                opener.open(asset, allowUserInteraction = false).getOrElse { asset.close(); return emptyList() }
            } catch (_: Exception) { asset.close(); return emptyList() }

            val result = mutableListOf<Pair<String, String>>()
            try {
                val flat = flattenLinks(publication.tableOfContents)
                var lastHref = ""
                for (link in flat) {
                    val href = link.url().toString().substringBefore('#')
                    if (href.isBlank() || href == lastHref) continue  // 跳过同一文件的子目录项
                    lastHref = href
                    val title = link.title?.trim().orEmpty()
                    val bytes = publication.get(link)?.read()?.getOrElse { continue } ?: continue
                    val text = Jsoup.parse(String(bytes, Charsets.UTF_8)).text().trim()
                    if (title.isNotBlank() && text.isNotBlank()) result.add(title to text)
                }
            } finally {
                try { publication.close() } catch (_: Exception) {}
                try { asset.close() } catch (_: Exception) {}
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    private fun flattenLinks(links: List<org.readium.r2.shared.publication.Link>): List<org.readium.r2.shared.publication.Link> {
        val result = mutableListOf<org.readium.r2.shared.publication.Link>()
        for (link in links) {
            result.add(link)
            result.addAll(flattenLinks(link.children))
        }
        return result
    }

    private fun extractEpubChaptersByToc(file: File): List<Pair<String, String>> {
        return try {
            val opf = readOpfFromEpub(file) ?: return emptyList()
            val opfDir = extractOpfDir(opf.first)
            val xml = opf.second
            val items = parseManifest(xml)
            val toc = parseEpubToc(file, opfDir, xml, items)
            if (toc.isEmpty()) return emptyList()
            readChaptersByToc(file, opfDir, toc)
        } catch (_: Exception) { emptyList() }
    }

    /** 解析 EPUB 目录（EPUB3 nav.xhtml 或 EPUB2 NCX），返回 (标题, href) 有序列表。 */
    private fun parseEpubToc(file: File, opfDir: String, opfXml: String, items: Map<String, String>): List<Pair<String, String>> {
        // EPUB3：manifest 里 properties="nav" 的文档
        val navId = Regex("""<item[^>]*properties\s*=\s*"[^"]*\bnav\b[^"]*"[^>]*>""").find(opfXml)
            ?.let { tag -> Regex("""id\s*=\s*"([^"]+)"""").find(tag.value)?.groupValues?.get(1) }
        navId?.let { items[it] }?.let { navHref ->
            val toc = parseNavXhtml(file, opfDir, navHref)
            if (toc.isNotEmpty()) return toc
        }
        // EPUB2：spine 的 toc 属性指向 NCX
        val ncxId = Regex("""<spine[^>]*toc\s*=\s*"([^"]+)"""").find(opfXml)?.groupValues?.get(1)
        ncxId?.let { items[it] }?.let { ncxHref ->
            val toc = parseNcx(file, opfDir, ncxHref)
            if (toc.isNotEmpty()) return toc
        }
        return emptyList()
    }

    private fun parseNavXhtml(file: File, opfDir: String, navHref: String): List<Pair<String, String>> {
        val html = readZipEntryText(file, opfDir, navHref) ?: return emptyList()
        val doc = Jsoup.parse(html)
        val nav = doc.selectFirst("nav[epub\\:type=toc]") ?: doc.selectFirst("nav") ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        for (a in nav.select("a[href]")) {
            val title = a.text().trim()
            val href = a.attr("href")
            if (title.isNotBlank() && href.isNotBlank()) result.add(title to href)
        }
        return result
    }

    private fun parseNcx(file: File, opfDir: String, ncxHref: String): List<Pair<String, String>> {
        val xml = readZipEntryText(file, opfDir, ncxHref) ?: return emptyList()
        val doc = Jsoup.parse(xml)
        val result = mutableListOf<Pair<String, String>>()
        for (np in doc.select("navPoint")) {
            val title = np.selectFirst("navLabel > text")?.text()?.trim() ?: ""
            val src = np.selectFirst("content")?.attr("src") ?: ""
            if (title.isNotBlank() && src.isNotBlank()) result.add(title to src)
        }
        return result
    }

    private fun readChaptersByToc(file: File, opfDir: String, toc: List<Pair<String, String>>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var lastKey = ""
        for ((title, href) in toc) {
            val key = normalizeHref(href)
            if (key.isBlank() || key == lastKey) continue  // 跳过指向同一文件的子目录项
            lastKey = key
            val html = readZipEntryText(file, opfDir, href)
            val text = html?.let { Jsoup.parse(it).text() }?.trim().orEmpty()
            if (text.isNotBlank()) result.add(title to text)
        }
        return result
    }

    /** 读取单个 ZIP 条目的文本（按 href 匹配，忽略 fragment/query）。 */
    private fun readZipEntryText(file: File, opfDir: String, href: String): String? {
        val clean = normalizeHref(href)
        if (clean.isBlank()) return null
        val target = clean.replace('\\', '/')
        val resolved = if (opfDir.isEmpty()) target else "$opfDir/$target"
        return try {
            ZipInputStream(FileInputStream(file)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (name == target || name == resolved || name.endsWith("/$target") || name.endsWith("/$resolved")) {
                        return String(zip.readBytes(), Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
                return null
            }
        } catch (_: Exception) { null }
    }

    private fun normalizeHref(href: String): String {
        var s = href.substringBefore('#').substringBefore('?').trim()
        try { s = java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) {}
        return s
    }

    // ── PDF extraction ─────────────────────────────────────────────────────

    private suspend fun extractPdf(file: File): String {
        try {
            val app = context.applicationContext as? Application ?: return ""
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
            val pdfFactory = PdfiumDocumentFactory(app)
            val parser = DefaultPublicationParser(app, httpClient, assetRetriever, pdfFactory)
            val opener = PublicationOpener(parser)
            val url = file.toUrl(isDirectory = false)
            val asset = assetRetriever.retrieve(url).getOrElse { return "" }
            val publication = try {
                opener.open(asset, allowUserInteraction = false).getOrElse { asset.close(); return "" }
            } catch (_: Exception) { asset.close(); return "" }

            val sb = StringBuilder()
            try {
                for (link in publication.readingOrder.take(500)) {
                    val bytes = publication.get(link)?.read()?.getOrElse { continue } ?: continue
                    val text = Jsoup.parse(String(bytes, Charsets.UTF_8)).text()
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(text)
                    }
                }
            } finally {
                try { publication.close() } catch (_: Exception) {}
                try { asset.close() } catch (_: Exception) {}
            }
            return sb.toString()
        } catch (e: Exception) {
            Timber.e(e, "PDF extraction failed")
            return ""
        }
    }
}

// ── BM25 Implementation ────────────────────────────────────────────────────

/**
 * BM25 scoring for Chinese text search.
 *
 * BM25 is a bag-of-words ranking function that scores documents against a query.
 * It's computationally lightweight and works well for keyword matching in CJK text,
 * which uses character bigrams as tokens.
 */
class Bm25Index(private val chunks: List<com.ebookreader.data.local.entity.BookChunkEntity>) {

    /** Default BM25 parameters — well-tuned for CJK text. */
    private val k1 = 1.2
    private val b = 0.75

    private val docCount = chunks.size
    private val docLengths = IntArray(docCount)
    private val summaryDocLengths = IntArray(docCount)
    private val avgDocLen: Double
    private val avgSummaryLen: Double
    private val invertedIndex = mutableMapOf<String, MutableMap<Int, Int>>() // term -> {docIdx -> tf}
    private val summaryInvertedIndex = mutableMapOf<String, MutableMap<Int, Int>>()

    init {
        var totalLen = 0
        var totalSummaryLen = 0
        for ((i, chunk) in chunks.withIndex()) {
            // Content tokens
            val tokens = tokenize(chunk.content)
            docLengths[i] = tokens.size
            totalLen += tokens.size
            for (token in tokens) {
                val docMap = invertedIndex.getOrPut(token) { mutableMapOf() }
                docMap[i] = (docMap[i] ?: 0) + 1
            }
            // Event summary tokens (if generated)
            if (chunk.eventSummary.isNotBlank()) {
                val summaryTokens = tokenize(chunk.eventSummary)
                summaryDocLengths[i] = summaryTokens.size
                totalSummaryLen += summaryTokens.size
                for (token in summaryTokens) {
                    val docMap = summaryInvertedIndex.getOrPut(token) { mutableMapOf() }
                    docMap[i] = (docMap[i] ?: 0) + 1
                }
            }
        }
        avgDocLen = if (docCount > 0) totalLen.toDouble() / docCount else 1.0
        avgSummaryLen = if (docCount > 0) totalSummaryLen.toDouble() / docCount else 1.0
    }

    /**
     * Score all documents against query using content field, return top results with scores.
     */
    fun search(
        query: String,
        topK: Int,
    ): List<Pair<com.ebookreader.data.local.entity.BookChunkEntity, Double>> {
        return searchInternal(query, topK, invertedIndex, docLengths, avgDocLen)
    }

    /**
     * Score all documents against query using eventSummary field.
     * Event summaries are much shorter but more precise — better signal-to-noise ratio.
     */
    fun searchInField(
        query: String,
        topK: Int,
        useSummary: Boolean,
    ): List<Pair<com.ebookreader.data.local.entity.BookChunkEntity, Double>> {
        if (useSummary && summaryInvertedIndex.isEmpty()) return emptyList()
        return searchInternal(query, topK,
            if (useSummary) summaryInvertedIndex else invertedIndex,
            if (useSummary) summaryDocLengths else docLengths,
            if (useSummary) avgSummaryLen else avgDocLen)
    }

    private fun searchInternal(
        query: String,
        topK: Int,
        index: Map<String, MutableMap<Int, Int>>,
        lengths: IntArray,
        avgLen: Double,
    ): List<Pair<com.ebookreader.data.local.entity.BookChunkEntity, Double>> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val scores = DoubleArray(docCount)
        val queryTf = mutableMapOf<String, Int>()
        for (t in queryTokens) queryTf[t] = (queryTf[t] ?: 0) + 1

        for ((term, qtf) in queryTf) {
            val postings = index[term] ?: continue
            val df = postings.size
            val idf = ln((docCount - df + 0.5) / (df + 0.5) + 1.0)

            for ((docIdx, tf) in postings) {
                val docLen = lengths[docIdx].coerceAtLeast(1)
                val numerator = tf * (k1 + 1.0)
                val denominator = tf + k1 * (1.0 - b + b * docLen / avgLen)
                scores[docIdx] += idf * numerator / denominator * qtf
            }
        }

        return scores.withIndex()
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .take(topK)
            .map { chunks[it.index] to it.value }
    }

    /**
     * Tokenize Chinese text into character bigrams for CJK matching.
     * Words and numbers are kept as-is.
     */
    private fun tokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val len = text.length

        while (i < len) {
            val ch = text[i]
            when {
                // CJK character — use bigrams
                ch in '一'..'鿿' || ch in '㐀'..'䶿' ||
                    ch in '豈'..'﫿' || ch in '　'..'〿' -> {
                    // Current + next char bigram
                    if (i + 1 < len) {
                        val next = text[i + 1]
                        if (next in '一'..'鿿' || next in '㐀'..'䶿' ||
                            next in '豈'..'﫿') {
                            result.add("${ch}${next}")
                        }
                    }
                    // Also add unigram
                    result.add(ch.toString())
                    i++
                }
                // Latin letters / digits — keep as word token
                ch.isLetterOrDigit() -> {
                    val start = i
                    while (i < len && text[i].isLetterOrDigit()) i++
                    result.add(text.substring(start, i).lowercase())
                }
                else -> { i++ }
            }
        }
        return result
    }
}
