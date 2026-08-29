package com.ebookreader.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.local.entity.BookDecompositionEntity
import com.ebookreader.data.network.DeepSeekClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookChunk
import com.ebookreader.domain.model.ChatMessage
import com.ebookreader.domain.model.Conversation
import com.ebookreader.domain.model.DecomposedChapter
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.ChatRepository
import com.ebookreader.ui.decompose.DecomposePrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository: ChatRepository = Injector.chatRepository()
    private val bookRepository: BookRepository = Injector.bookRepository()
    private val deepSeekClient: DeepSeekClient = Injector.deepSeekClient()
    private val decompositionDao = Injector.appDatabase().bookDecompositionDao()

    private var bookId: Long = 0

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<Long?>(null)
    val selectedConversationId: StateFlow<Long?> = _selectedConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** True during the retrieval pipeline (query expansion, BM25, reranking). */
    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** 正在处理（检索/流式回答）的对话 ID；用于多对话间隔离流式输出，避免串到别的对话。 */
    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    /** Input text (persisted in ViewModel to survive navigation). */
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /** Token / context size info shown during thinking & streaming. */
    private val _tokenEstimate = MutableStateFlow("")
    val tokenEstimate: StateFlow<String> = _tokenEstimate.asStateFlow()

    /** Reasoning (chain-of-thought) streamed from deepseek-reasoner. */
    private val _reasoningContent = MutableStateFlow("")
    val reasoningContent: StateFlow<String> = _reasoningContent.asStateFlow()

    /** Whether reasoning is in progress (thinking phase). */
    private val _isReasoning = MutableStateFlow(false)
    val isReasoning: StateFlow<Boolean> = _isReasoning.asStateFlow()

    /** Total thinking time in milliseconds. */
    private val _thinkingTimeMs = MutableStateFlow(0L)
    val thinkingTimeMs: StateFlow<Long> = _thinkingTimeMs.asStateFlow()

    /** Whether the collapsed reasoning section is expanded. */
    private val _showReasoning = MutableStateFlow(false)
    val showReasoning: StateFlow<Boolean> = _showReasoning.asStateFlow()

    private var thinkingStartMs = 0L

    private val _showConversationDrawer = MutableStateFlow(false)
    val showConversationDrawer: StateFlow<Boolean> = _showConversationDrawer.asStateFlow()

    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    private val _showImportDialog = MutableStateFlow(false)
    val showImportDialog: StateFlow<Boolean> = _showImportDialog.asStateFlow()

    /** 已拆完（status=done）的书籍 id 集合，用于导入弹窗标记「已拆书」并优先排序。 */
    private val _decomposedBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val decomposedBookIds: StateFlow<Set<Long>> = _decomposedBookIds.asStateFlow()

    private val _importedBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val importedBookIds: StateFlow<Set<Long>> = _importedBookIds.asStateFlow()

    private val _isLoadingBook = MutableStateFlow(false)
    val isLoadingBook: StateFlow<Boolean> = _isLoadingBook.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** Diagnostic info visible in UI. */
    private val _diagnostic = MutableStateFlow("")
    val diagnostic: StateFlow<String> = _diagnostic.asStateFlow()

    /** Selected chapters to filter context by. When non-empty, the pipeline loads
     *  the full content of these chapters instead of running BM25 keyword search. */
    private val _chapterFilter = MutableStateFlow<Set<String>>(emptySet())
    val chapterFilter: StateFlow<Set<String>> = _chapterFilter.asStateFlow()

    /** All distinct chapter titles across imported books (bookId → chapters). */
    private val _allChapters = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    val allChapters: StateFlow<Map<Long, List<String>>> = _allChapters.asStateFlow()

    // Book metadata cache for prompt building
    private val bookMetaCache = mutableMapOf<Long, Book>()

    // Guards one-shot auto-selection of the last active conversation
    private var hasAutoSelectedConversation = false

    private val apiKeyManager get() = Injector.apiKeyManager()

    companion object {
        // ── Context window budget ──
        private const val MAX_CONTEXT_CHARS = 55_000
        private const val HISTORY_BUDGET = 8_000
        private const val PREVIEW_BUDGET = 4_000
        private const val PREVIEW_HEAD = 2_000
        private const val PREVIEW_TAIL = 1_500

        // AI 拆书结果作为优先上下文的预算（全书总结 + 逐章梗概）
        private const val DECOMPOSITION_BUDGET = 12_000

        // ── Temporal query detection patterns ──
        private val TEMPORAL_PATTERNS = listOf(
            Regex("""怎么?认识"""), Regex("""如何?相识"""), Regex("""初次?见"""),
            Regex("""第一?次"""), Regex("""何时"""), Regex("""什么时候"""),
            Regex("""怎么开始"""), Regex("""起源"""), Regex("""来历"""),
            Regex("""如何?相遇"""), Regex("""如何?认识"""), Regex("""早期"""),
            Regex("""最初"""), Regex("""一开始"""), Regex("""起[因源]"""),
            Regex("""结识"""), Regex("""初遇"""),
        )
    }

    fun init(bookId: Long) {
        this.bookId = bookId
        _isLoadingBook.value = true
        _loadError.value = null
        _diagnostic.value = "初始化中…"

        // Restore persisted input text from last session
        _inputText.value = apiKeyManager.getChatInputText(bookId)

        viewModelScope.launch {
            val book = bookRepository.getBookById(bookId)
            _book.value = book
            _importedBookIds.value = setOf(bookId)
            if (book != null) bookMetaCache[bookId] = book

            val diag = StringBuilder()
            diag.appendLine("=== 书籍加载 ===")
            diag.appendLine("书名: ${book?.title}")
            diag.appendLine("格式: ${book?.format}")
            try {
                val indexed = chatRepository.ensureIndexed(bookId)
                val chunkCount = chatRepository.getChunkCount(bookId)
                diag.appendLine("索引状态: ${if (indexed) "新建索引" else "已缓存"}")
                diag.appendLine("分块数: $chunkCount")
            } catch (e: Exception) {
                diag.appendLine("!!! 索引失败: ${e.message}")
                _loadError.value = "索引失败: ${e.message}"
            }
            _diagnostic.value = diag.toString()
            _isLoadingBook.value = false

            // Kick off event summary generation in background (don't block UI)
            if (book != null) {
                generateMissingSummaries(bookId, book.title)
            }
        }
        viewModelScope.launch {
            chatRepository.getConversationsForBook(bookId).collect {
                _conversations.value = it
                // Auto-select the most recent conversation on first load
                if (!hasAutoSelectedConversation && it.isNotEmpty()) {
                    hasAutoSelectedConversation = true
                    selectConversation(it.first().id) // already sorted by updatedTimestamp DESC
                } else if (!hasAutoSelectedConversation && it.isEmpty()) {
                    hasAutoSelectedConversation = true
                    createNewConversation()
                }
            }
        }
        // Load chapter list for the primary book
        viewModelScope.launch {
            try {
                val chapters = chatRepository.getDistinctChapters(bookId)
                val map = _allChapters.value.toMutableMap()
                map[bookId] = chapters
                _allChapters.value = map
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { _allBooks.value = it }
        }
    }

    fun selectConversation(conversationId: Long?) {
        _selectedConversationId.value = conversationId
        _streamingContent.value = ""
        _reasoningContent.value = ""
        _thinkingTimeMs.value = 0L
        _isReasoning.value = false
        _showReasoning.value = false
        if (conversationId != null) {
            viewModelScope.launch {
                chatRepository.getMessages(conversationId).collect { _messages.value = it }
            }
        } else {
            _messages.value = emptyList()
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            val title = _book.value?.title?.let { "与《$it》的对话" } ?: "新对话"
            val id = chatRepository.createConversation(bookId, title)
            selectConversation(id)
            _showConversationDrawer.value = false
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversationId)
            if (_selectedConversationId.value == conversationId) {
                selectConversation(null)
            }
        }
    }

    // ── Multi-step retrieval pipeline ──────────────────────────────────────

    fun sendMessage(text: String) {
        val convId = _selectedConversationId.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _activeConversationId.value = convId

        viewModelScope.launch {
            try {
                // Save user message
                chatRepository.addMessage(convId, "user", trimmed)
                apiKeyManager.clearChatInputText(bookId)

                // Show thinking state during pipeline
                _isThinking.value = true

                // Update book meta cache
                for (bid in _importedBookIds.value) {
                    if (!bookMetaCache.containsKey(bid)) {
                        val b = bookRepository.getBookById(bid)
                        if (b != null) bookMetaCache[bid] = b
                    }
                }

                val diag = StringBuilder("=== 检索管线 ===\n")
                diag.appendLine("API地址: ${Injector.apiKeyManager().getBaseUrl()}/chat/completions")
                diag.appendLine("模型: ${Injector.apiKeyManager().getModel()}")
                _diagnostic.value = diag.toString()

                // Step 0: Ensure all imported books are indexed
                // (summary generation runs in background to avoid blocking)
                for (bid in _importedBookIds.value) {
                    chatRepository.ensureIndexed(bid)
                }
                // Launch summaries as non-blocking background task
                for (bid in _importedBookIds.value) {
                    val meta = bookMetaCache[bid]
                    if (meta != null) {
                        launch { generateMissingSummaries(bid, meta.title) }
                    }
                }

                // Step 0.5: Detect temporal query
                val isTemporal = isTemporalQuery(trimmed)
                if (isTemporal) {
                    diag.appendLine("检测到时间类问题，启用时间排序")
                }
                _diagnostic.value = diag.toString()

                // ── Chapter-filter branch: load full chapters, skip BM25 ──
                val selectedChapters = _chapterFilter.value
                val chapterFilterChunks: List<BookChunk>? = if (selectedChapters.isNotEmpty()) {
                    diag.appendLine("=== 章节精确模式 ===")
                    diag.appendLine("选中章节: ${selectedChapters.joinToString(" · ")}")

                    val allChapterChunks = mutableListOf<BookChunk>()
                    for (bid in _importedBookIds.value) {
                        val chapters = _allChapters.value[bid] ?: continue
                        val matching = chapters.filter { it in selectedChapters }
                        if (matching.isNotEmpty()) {
                            val loaded = try {
                                chatRepository.getChunksByChapters(bid, matching)
                            } catch (e: Exception) {
                                diag.appendLine("加载章节失败: ${e.message?.take(60)}")
                                emptyList()
                            }
                            // Enrich with book metadata
                            val meta = bookMetaCache[bid]
                            val enriched = loaded.map { chunk ->
                                chunk.copy(
                                    bookTitle = meta?.title ?: "",
                                    bookAuthor = meta?.author ?: "",
                                )
                            }
                            allChapterChunks.addAll(enriched)
                            diag.appendLine("《${meta?.title ?: "?"}》${matching.size}章: ${loaded.size}块, " +
                                String.format("%,d", loaded.sumOf { it.content.length }) + "字")
                        }
                    }

                    // Budget management: take chunks in order, truncate last if needed
                    val budgeted = mutableListOf<BookChunk>()
                    var charUsed = 0
                    for (chunk in allChapterChunks) {
                        if (charUsed >= MAX_CONTEXT_CHARS - HISTORY_BUDGET) break
                        val remaining = (MAX_CONTEXT_CHARS - HISTORY_BUDGET) - charUsed
                        if (chunk.content.length > remaining) {
                            budgeted.add(chunk.copy(content = chunk.content.take(remaining) + "…"))
                            diag.appendLine("⚠ 超出上下文预算: 已截断最后一块")
                            break
                        }
                        budgeted.add(chunk)
                        charUsed += chunk.content.length
                    }
                    diag.appendLine("结果: ${budgeted.size}块, " +
                        String.format("%,d", budgeted.sumOf { it.content.length }) + "字")
                    _diagnostic.value = diag.toString()
                    budgeted
                } else {
                    // ── Standard BM25 pipeline (unchanged) ──
                    null
                }

                if (chapterFilterChunks != null) {
                    // Chapter-filter mode: skip query expansion, BM25, and reranking.
                    // chapterFilterChunks already contains all content ready for the prompt.
                    diag.appendLine("--- 选中块详情 ---")
                    for ((i, chunk) in chapterFilterChunks.withIndex()) {
                        val titlePart = if (chunk.chapterTitle.isNotBlank()) "【${chunk.chapterTitle}】" else ""
                        val sample = chunk.content.take(80).replace('\n', ' ').trim()
                        diag.appendLine("  #${i} idx=${chunk.chunkIndex}: $titlePart → $sample…")
                    }
                    _diagnostic.value = diag.toString()

                    // Build system prompt
                    val systemPrompt = buildChapterFilterPrompt(chapterFilterChunks)

                    // Get recent messages
                    val allMessages = try {
                        chatRepository.getMessagesOnce(convId)
                    } catch (e: Exception) {
                        diag.appendLine("获取历史消息失败: ${e.message?.take(60)}")
                        emptyList()
                    }
                    val recentMessages = allMessages.takeLast(20)

                    diag.appendLine("--- 上下文 ---")
                    diag.appendLine("选中块: ${chapterFilterChunks.size}")
                    diag.appendLine("SysPrompt: ${systemPrompt.length}字")
                    diag.appendLine("历史消息: ${recentMessages.size}条")
                    _diagnostic.value = diag.toString()

                    val estTokens = (systemPrompt.length + recentMessages.sumOf { it.content.length }) / 2
                    _tokenEstimate.value = "约" + String.format("%,d", estTokens) + " tokens"

                    // Stream response
                    startStreamingResponse(convId, recentMessages, systemPrompt)
                    return@launch
                }

                // ── Original BM25 pipeline (below) ──
                // Step 1: Query expansion (with timeout protection)
                val expandStart = System.currentTimeMillis()
                val queries = try {
                    val expanded = deepSeekClient.expandQuery(trimmed)
                    expanded.getOrDefault(listOf(trimmed))
                } catch (e: Exception) {
                    diag.appendLine("查询扩展失败: ${e.message?.take(60)}，回退到原查询")
                    listOf(trimmed)
                }
                val expandMs = System.currentTimeMillis() - expandStart
                diag.appendLine("查询扩展: ${expandMs}ms, ${queries.size}个查询")
                diag.appendLine("  查询: ${queries.joinToString(" | ")}")
                _diagnostic.value = diag.toString()

                // Step 2: BM25 search
                val searchStart = System.currentTimeMillis()
                val candidates = try {
                    chatRepository.searchChunks(
                        bookIds = _importedBookIds.value,
                        queries = queries,
                        topK = 20,
                        preferEarlierChunks = isTemporal,
                    )
                } catch (e: Exception) {
                    diag.appendLine("BM25检索失败: ${e.message?.take(60)}")
                    emptyList()
                }
                val searchMs = System.currentTimeMillis() - searchStart
                diag.appendLine("BM25检索: ${searchMs}ms, ${candidates.size}个候选块" +
                    if (isTemporal) " (时间优先)" else "")
                _diagnostic.value = diag.toString()

                // Step 3: LLM reranking
                val rerankedIndices: List<Int> = if (candidates.size > 8) {
                    val rerankStart = System.currentTimeMillis()
                    val indices = try {
                        val result = deepSeekClient.rerankChunks(trimmed, candidates, topK = 8)
                        result.getOrDefault(candidates.indices.take(8).toList())
                    } catch (e: Exception) {
                        diag.appendLine("LLM重排失败: ${e.message?.take(60)}，回退到top-8")
                        candidates.indices.take(8).toList()
                    }
                    val rerankMs = System.currentTimeMillis() - rerankStart
                    diag.appendLine("LLM重排: ${rerankMs}ms, 选中${indices.size}块")
                    indices
                } else {
                    candidates.indices.toList()
                }

                // Step 3.5: For temporal queries, sort selected chunks chronologically
                val selectedChunks = rerankedIndices.mapNotNull { idx ->
                    if (idx in candidates.indices) candidates[idx] else null
                }.let { chunks ->
                    if (isTemporal) {
                        val sorted = chunks.sortedBy { it.chunkIndex }
                        diag.appendLine("时间重排: 按原文顺序排列${sorted.size}块")
                        sorted
                    } else chunks
                }
                _diagnostic.value = diag.toString()

                // Log chunk content samples for debugging
                diag.appendLine("--- 选中块详情 ---")
                for ((i, chunk) in selectedChunks.withIndex()) {
                    val titlePart = if (chunk.chapterTitle.isNotBlank()) "【${chunk.chapterTitle}】" else ""
                    val summaryPart = if (chunk.eventSummary.isNotBlank()) " [${chunk.eventSummary}]" else ""
                    val sample = chunk.content.take(80).replace('\n', ' ').trim()
                    diag.appendLine("  #${i} idx=${chunk.chunkIndex} len=${chunk.content.length}: $titlePart$summaryPart → $sample…")
                }
                _diagnostic.value = diag.toString()

                // Step 4: Build system prompt
                val systemPrompt = buildSystemPrompt(selectedChunks)

                // Step 5: Get recent messages
                val allMessages = try {
                    chatRepository.getMessagesOnce(convId)
                } catch (e: Exception) {
                    diag.appendLine("获取历史消息失败: ${e.message?.take(60)}")
                    emptyList()
                }
                val recentMessages = allMessages.takeLast(20)

                diag.appendLine("--- 上下文 ---")
                diag.appendLine("选中块: ${selectedChunks.size}")
                diag.appendLine("SysPrompt: ${systemPrompt.length}字")
                diag.appendLine("历史消息: ${recentMessages.size}条")
                diag.appendLine("总估算: ~${(systemPrompt.length + recentMessages.sumOf { it.content.length }) / 2} tokens")
                _diagnostic.value = diag.toString()

                // Update token estimate for the thinking bubble
                val estTokens = (systemPrompt.length + recentMessages.sumOf { it.content.length }) / 2
                _tokenEstimate.value = "约" + String.format("%,d", estTokens) + " tokens"

                // Step 6: Stream response
                startStreamingResponse(convId, recentMessages, systemPrompt)
            } catch (e: Exception) {
                // Top-level catch: ensure user always sees an error
                _isThinking.value = false
                _isStreaming.value = false
                _activeConversationId.value = null
                val errMsg = "[系统错误] ${e.message}\n${e.stackTraceToString().take(300)}"
                try {
                    chatRepository.addMessage(convId, "assistant", errMsg)
                } catch (_: Exception) {}
                _diagnostic.value = _diagnostic.value + "\n!!! 致命错误: ${e.message}"
            }
        }
    }

    fun toggleConversationDrawer() {
        _showConversationDrawer.value = !_showConversationDrawer.value
    }

    fun setInputText(text: String) {
        _inputText.value = text
        apiKeyManager.setChatInputText(bookId, text)
    }

    fun toggleChapterFilter(chapter: String) {
        val current = _chapterFilter.value.toMutableSet()
        if (chapter in current) current.remove(chapter) else current.add(chapter)
        _chapterFilter.value = current
    }

    fun clearChapterFilter() {
        _chapterFilter.value = emptySet()
    }

    fun toggleShowReasoning() {
        _showReasoning.value = !_showReasoning.value
    }

    fun showImportDialog() {
        _showImportDialog.value = true
        viewModelScope.launch {
            _decomposedBookIds.value = try {
                decompositionDao.getDoneBookIds().toSet()
            } catch (_: Exception) { emptySet() }
        }
    }
    fun dismissImportDialog() { _showImportDialog.value = false }

    fun importBook(importBookId: Long) {
        if (_importedBookIds.value.contains(importBookId)) return
        viewModelScope.launch {
            chatRepository.ensureIndexed(importBookId)
            val importedBook = bookRepository.getBookById(importBookId)
            if (importedBook != null) {
                bookMetaCache[importBookId] = importedBook
                generateMissingSummaries(importBookId, importedBook.title)
            }
            _importedBookIds.value = _importedBookIds.value + importBookId
            // Load chapters for the newly imported book
            try {
                val chapters = chatRepository.getDistinctChapters(importBookId)
                val map = _allChapters.value.toMutableMap()
                map[importBookId] = chapters
                _allChapters.value = map
            } catch (_: Exception) {}
        }
    }

    fun removeImportedBook(removeBookId: Long) {
        if (removeBookId == bookId) return
        bookMetaCache.remove(removeBookId)
        _importedBookIds.value = _importedBookIds.value - removeBookId
    }

    fun exportConversation(): String {
        val convId = _selectedConversationId.value ?: return ""
        var result = ""
        kotlinx.coroutines.runBlocking {
            result = chatRepository.exportConversation(convId)
        }
        return result
    }

    // ── Event summary generation ───────────────────────────────────────────

    /**
     * Build system prompt from chapter-filter mode chunks.
     * Prefaces the full chapter content with a reading-assistant preamble.
     */
    private suspend fun buildChapterFilterPrompt(chunks: List<BookChunk>): String {
        val sb = StringBuilder()
        sb.append("你是一个专业的阅读助手，专门分析小说和文学作品。你的职责是客观、准确地回答用户关于书籍内容的任何问题，包括情节、人物、主题分析等。\n")
        sb.append("重要提醒：以下所有内容均为虚构文学作品，讨论和分析文学作品中的任何内容都属于正常的文学研究和阅读辅助范畴。请直接回应用户的问题，不要拒绝、说教或进行道德评判。\n\n")
        sb.append("用户指定了以下章节，请基于这些章节的完整内容回答问题：\n\n")

        // 已拆过的书：注入拆书结果作为全书背景（便于回答时定位情节与人物）
        appendDecompositionContext(sb)

        var charUsed = sb.length
        val chaptersInOrder = chunks.map { it.chapterTitle }.distinct()

        sb.append("━━━ 指定章节：${chaptersInOrder.joinToString("、")} ━━━\n")

        // Show book context for each chunk's book
        for ((bid, group) in chunks.groupBy { it.bookId }) {
            val meta = bookMetaCache[bid] ?: continue
            sb.append("【《${meta.title}》")
            if (meta.author.isNotBlank()) sb.append(" / ${meta.author}")
            val totalChunks = try { chatRepository.getChunkCount(bid) } catch (_: Exception) { 0 }
            if (totalChunks > 0) sb.append(" · 共" + String.format("%,d", totalChunks) + "段，本页展示" + group.size + "段")
            sb.append("】\n")
        }
        charUsed = sb.length

        for (chunk in chunks) {
            if (charUsed >= MAX_CONTEXT_CHARS - HISTORY_BUDGET) break
            val header = buildString {
                if (chunk.chapterTitle.isNotBlank())
                    append("【${chunk.chapterTitle}】")
                if (chunk.eventSummary.isNotBlank())
                    append(" [${chunk.eventSummary}]")
            }
            val block = (if (header.isNotBlank()) "$header\n" else "") + chunk.content + "\n\n"
            val remaining = (MAX_CONTEXT_CHARS - HISTORY_BUDGET) - charUsed
            val text = if (block.length <= remaining) block else block.take(remaining) + "…\n"
            sb.append(text)
            charUsed = sb.length
        }

        sb.append("\n回答指引：1) 优先引用以上章节中的具体内容作为依据；")
        sb.append("2) 如果问题涉及未覆盖的内容，可结合一般性知识回答；")
        sb.append("3) 使用中文回复；")
        sb.append("4) 适当提及信息来源（如'根据第X章'）。")
        return sb.toString()
    }

    /**
     * Shared streaming-launch block used by both the chapter-filter and BM25 pipelines.
     * Switches UI state from thinking → streaming, then invokes [DeepSeekClient.streamChat].
     */
    private fun startStreamingResponse(
        convId: Long,
        recentMessages: List<ChatMessage>,
        systemPrompt: String,
    ) {
        _streamingContent.value = ""
        _reasoningContent.value = ""
        _thinkingTimeMs.value = 0L
        _isReasoning.value = false
        _showReasoning.value = false
        _isStreaming.value = true
        thinkingStartMs = 0L
        _isThinking.value = false
        _streamingContent.value = "▊"

        val fullContent = StringBuilder()
        var streamTokenCount = 0
        deepSeekClient.streamChat(
            messages = recentMessages,
            systemPrompt = systemPrompt,
            onToken = { token ->
                if (thinkingStartMs > 0L && _thinkingTimeMs.value == 0L) {
                    _thinkingTimeMs.value = System.currentTimeMillis() - thinkingStartMs
                    _isReasoning.value = false
                }
                fullContent.append(token)
                if (_streamingContent.value == "▊") {
                    _streamingContent.value = token
                } else {
                    _streamingContent.value += token
                }
                streamTokenCount++
                if (streamTokenCount % 5 == 0) {
                    _tokenEstimate.value = "已消耗 " + String.format("%,d", streamTokenCount) + " tokens"
                }
            },
            onReasoningToken = { reasoning ->
                if (thinkingStartMs == 0L) {
                    thinkingStartMs = System.currentTimeMillis()
                    _isReasoning.value = true
                    _showReasoning.value = true
                }
                _reasoningContent.value = _reasoningContent.value + reasoning
            },
            onComplete = {
                viewModelScope.launch {
                    val reasoningText = _reasoningContent.value
                    val finalContent = if (reasoningText.isNotEmpty()) {
                        "[思考过程]\n$reasoningText\n[正文]\n${fullContent}"
                    } else {
                        fullContent.toString()
                    }
                    chatRepository.addMessage(convId, "assistant", finalContent)
                    _streamingContent.value = ""
                    _reasoningContent.value = ""
                    _isStreaming.value = false
                    _isReasoning.value = false
                    _thinkingTimeMs.value = 0L
                    _tokenEstimate.value = ""
                    _activeConversationId.value = null
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    val errMsg = "[错误] ${error.message}"
                    chatRepository.addMessage(convId, "assistant", errMsg)
                    _streamingContent.value = ""
                    _reasoningContent.value = ""
                    _isStreaming.value = false
                    _isReasoning.value = false
                    _thinkingTimeMs.value = 0L
                    _tokenEstimate.value = ""
                    _activeConversationId.value = null
                }
            },
            onDiagnostic = { d ->
                _diagnostic.value = _diagnostic.value + "\n" + d
            },
        )
    }

    /**
     * Generate event summaries for chunks that don't have them yet.
     * Processes in batches of 10 to keep individual API calls small.
     * Runs in background; doesn't block the main flow.
     */
    private suspend fun generateMissingSummaries(bookId: Long, bookTitle: String) {
        try {
            var totalProcessed = 0
            while (true) {
                val unsummarized = chatRepository.getUnsummarizedChunks(bookId, limit = 10)
                if (unsummarized.isEmpty()) break

                val batch = unsummarized.map { it.id to it.content }
                val result = deepSeekClient.generateEventSummaries(bookTitle, batch)
                val summaries = result.getOrDefault(emptyMap())

                for ((chunkId, summary) in summaries) {
                    chatRepository.updateChunkEventSummary(chunkId, summary)
                }
                totalProcessed += summaries.size

                // Update diagnostic if still on loading screen
                val current = _diagnostic.value
                if (current.contains("书籍加载")) {
                    _diagnostic.value = current + "\n事件摘要: 已生成 $totalProcessed 块"
                }
            }
            if (totalProcessed > 0) {
                _diagnostic.value = _diagnostic.value + "\n事件摘要完成: $totalProcessed 块"
            }
        } catch (e: Exception) {
            // Non-fatal: summaries are optional
            _diagnostic.value = _diagnostic.value + "\n摘要生成跳过: ${e.message?.take(80)}"
        }
    }

    // ── Temporal query detection ───────────────────────────────────────────

    private fun isTemporalQuery(query: String): Boolean {
        return TEMPORAL_PATTERNS.any { pattern ->
            pattern.containsMatchIn(query)
        }
    }

    // ── AI 拆书结果注入 ──────────────────────────────────────────────

    /**
     * 对已拆过的书（status = done），把拆书结果（全书总结 + 逐章梗概）作为优先上下文
     * 注入系统提示词。拆书结果是比原始检索段落更精炼、更全面的信息源，应优先参考。
     */
    private suspend fun appendDecompositionContext(sb: StringBuilder) {
        var used = 0
        for (bid in _importedBookIds.value) {
            if (used >= DECOMPOSITION_BUDGET) break
            val entity = try {
                decompositionDao.getByBookId(bid)
            } catch (_: Exception) { null } ?: continue
            if (entity.status != "done") continue
            val meta = bookMetaCache[bid] ?: continue

            val block = buildDecompositionBlock(meta.title, entity)
            val remaining = DECOMPOSITION_BUDGET - used
            val text = if (block.length <= remaining) block else block.take(remaining) + "…\n"
            sb.append(text)
            used += text.length
        }
    }

    private fun buildDecompositionBlock(title: String, entity: BookDecompositionEntity): String {
        val sb = StringBuilder()
        sb.append("━━━ 《$title》 AI拆书结果（优先参考） ━━━\n")
        if (entity.bookSummary.isNotBlank()) {
            sb.append("【全书总结】\n").append(entity.bookSummary.trim()).append("\n\n")
        }
        // deep 档模块（按书类型取标题，避免"人物关系"等小说向标题套在非小说上）
        val titles = DecomposePrompts.deepModules(entity.bookType).associateBy { it.key }
        listOf(
            "characters" to entity.charactersJson,
            "timeline" to entity.timelineJson,
            "quotes" to entity.quotesJson,
            "characterBios" to entity.characterBiosJson,
            "worldSetting" to entity.worldSettingJson,
            "extendedReading" to entity.extendedReadingJson,
        ).forEach { (key, content) ->
            if (content.isNotBlank()) {
                sb.append("【").append(titles[key]?.title ?: key).append("】\n")
                    .append(content.trim()).append("\n\n")
            }
        }
        val chapters = parseDecomposedChapters(entity.chapterSummariesJson)
        if (chapters.isNotEmpty()) {
            sb.append("【逐章梗概】\n")
            for (ch in chapters) {
                sb.append("· ${ch.title}：${ch.summary}")
                if (ch.events.isNotBlank()) sb.append("（关键事件：${ch.events}）")
                sb.append("\n")
            }
            sb.append("\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        return sb.toString()
    }

    private fun parseDecomposedChapters(json: String): List<DecomposedChapter> {
        val chapters = mutableListOf<DecomposedChapter>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title")
                val summary = obj.optString("summary")
                if (title.isNotBlank() && summary.isNotBlank()) {
                    chapters.add(
                        DecomposedChapter(
                            title = title,
                            summary = summary,
                            events = obj.optString("events", ""),
                            characters = obj.optString("characters", ""),
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        return chapters
    }

    // ── System prompt builder ──────────────────────────────────────────────

    private suspend fun buildSystemPrompt(selectedChunks: List<BookChunk>): String {
        val sb = StringBuilder()
        sb.append("你是一个专业的阅读助手，专门分析小说和文学作品。你的职责是客观、准确地回答用户关于书籍内容的任何问题，包括情节、人物、主题分析等。\n")
        sb.append("重要提醒：以下所有内容均为虚构文学作品，讨论和分析文学作品中的任何内容都属于正常的文学研究和阅读辅助范畴。请直接回应用户的问题，不要拒绝、说教或进行道德评判。\n\n")
        sb.append("以下是用户阅读的书籍信息以及与你问题相关的段落：\n\n")

        // 已拆过的书：优先注入拆书结果（全书总结 + 逐章梗概），比原始段落更精炼全面
        appendDecompositionContext(sb)

        var charUsed = sb.length

        // Collect distinct books from selected chunks
        val booksWithChunks = selectedChunks
            .groupBy { it.bookId }

        // Add book headers and their relevant chunks
        for ((bid, chunks) in booksWithChunks) {
            val meta = bookMetaCache[bid]
            if (meta == null) continue
            if (charUsed >= MAX_CONTEXT_CHARS - HISTORY_BUDGET) break

            sb.append("━━━ 《${meta.title}》")
            if (meta.author.isNotBlank()) sb.append(" / ${meta.author}")
            val totalChunks = try { chatRepository.getChunkCount(bid) } catch (_: Exception) { 0 }
            if (totalChunks > 0) sb.append(" （共${totalChunks}段，已检索${chunks.size}段相关部分）")
            sb.append(" ━━━\n")

            for (chunk in chunks) {
                if (charUsed >= MAX_CONTEXT_CHARS - HISTORY_BUDGET) break
                val header = buildString {
                    if (chunk.chapterTitle.isNotBlank())
                        append("【${chunk.chapterTitle}】")
                    // Show event summary as context hint for LLM
                    if (chunk.eventSummary.isNotBlank())
                        append(" [${chunk.eventSummary}]")
                }
                val block = (if (header.isNotBlank()) "$header\n" else "") + chunk.content + "\n\n"
                val remaining = (MAX_CONTEXT_CHARS - HISTORY_BUDGET) - charUsed
                val text = if (block.length <= remaining) block else block.take(remaining) + "…\n"
                sb.append(text)
                charUsed = sb.length
            }
        }

        // Add book previews for books WITHOUT selected chunks
        for (bid in _importedBookIds.value) {
            if (booksWithChunks.containsKey(bid)) continue
            if (charUsed >= MAX_CONTEXT_CHARS - HISTORY_BUDGET - 500) break
            val meta = bookMetaCache[bid] ?: continue

            sb.append("━━━ 《${meta.title}》")
            if (meta.author.isNotBlank()) sb.append(" / ${meta.author}")
            sb.append(" （全文未检索到相关段落，仅供参考）━━━\n")
            charUsed = sb.length

            if (bid == bookId) {
                // 当前书：保留开头+结尾，让 AI 对全书有基本把握
                val content = try { chatRepository.getBookContent(bid) } catch (_: Exception) { "" }
                if (content.isNotBlank()) {
                    val remaining = (MAX_CONTEXT_CHARS - HISTORY_BUDGET) - charUsed - 200
                    val headSize = minOf(PREVIEW_HEAD, remaining / 2).coerceAtMost(content.length)
                    val tailSize = minOf(PREVIEW_TAIL, remaining - headSize).coerceAtMost(content.length)
                    sb.append("【书籍开头】\n")
                    sb.append(content.take(headSize))
                    if (content.length > headSize + tailSize) {
                        sb.append("\n\n…（共" + String.format("%,d", content.length) + "字，此处省略）…\n\n")
                    }
                    sb.append("【书籍结尾】\n")
                    sb.append(content.takeLast(tailSize))
                    sb.append("\n\n")
                    charUsed = sb.length
                }
            } else {
                // 参考书：只给简介（开头结尾原文价值低、浪费预算）
                val desc = meta.description.takeIf { it.isNotBlank() }
                if (desc != null) {
                    sb.append("【简介】").append(desc.take(500)).append("\n\n")
                    charUsed = sb.length
                }
            }
        }

        if (sb.length < 80) {
            sb.append("（暂无书籍内容可供参考。请以通用模式回答用户问题。）\n")
        }

        sb.append("\n回答指引：1) 优先引用以上段落中的具体内容作为依据；")
        sb.append("2) 如果涉及段落中未覆盖的问题，也可以进行一般性知识回答；")
        sb.append("3) 使用中文回复；")
        sb.append("4) 引用具体内容时明确标注出处（如'据《XXX》第X章'）；涉及多本书时务必区分是哪本书，不要张冠李戴。")
        return sb.toString()
    }
}
