package com.ebookreader.ui.detail

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.importer.BookImporter
import com.ebookreader.data.ml.KeywordExtractor
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.data.network.BookRecommendClient
import com.ebookreader.data.network.DeepSeekClient
import com.ebookreader.data.network.OnlineBook
import com.ebookreader.data.network.PixivClient
import com.ebookreader.data.network.Xiu2BookSourceClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Keyword
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.ChatRepository
import com.ebookreader.domain.repository.KeywordRepository
import com.ebookreader.domain.repository.TagRepository
import com.ebookreader.ui.recommend.RecommendationItem
import com.ebookreader.ui.recommend.RecommendationScorer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BookDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository: BookRepository = Injector.bookRepository()
    private val tagRepository: TagRepository = Injector.tagRepository()
    private val apiKeyManager: ApiKeyManager = Injector.apiKeyManager()
    private val chatRepository: ChatRepository = Injector.chatRepository()
    private val deepSeekClient: DeepSeekClient = Injector.deepSeekClient()
    private val keywordRepository: KeywordRepository = Injector.keywordRepository()
    private val keywordExtractor: KeywordExtractor = Injector.keywordExtractor()
    private val decomposeDao = Injector.appDatabase().bookDecompositionDao()
    private val bookImporter = BookImporter(application)

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _allTags = MutableStateFlow<List<Tag>>(emptyList())
    val allTags: StateFlow<List<Tag>> = _allTags.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _relatedBooks = MutableStateFlow<List<Book>>(emptyList())
    val relatedBooks: StateFlow<List<Book>> = _relatedBooks.asStateFlow()

    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    private val _showAddRelatedDialog = MutableStateFlow(false)
    val showAddRelatedDialog: StateFlow<Boolean> = _showAddRelatedDialog.asStateFlow()

    private val _isGeneratingDesc = MutableStateFlow(false)
    val isGeneratingDesc: StateFlow<Boolean> = _isGeneratingDesc.asStateFlow()

    private val _coverMessage = MutableStateFlow<String?>(null)
    val coverMessage: StateFlow<String?> = _coverMessage.asStateFlow()

    /** 更新书籍（替换文件）进行中状态。 */
    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    /** 更新书籍结果提示（snackbar）。 */
    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    fun clearUpdateMessage() { _updateMessage.value = null }

    /** 关键词提取结果提示（snackbar，失败时显示原因）。 */
    private val _keywordMessage = MutableStateFlow<String?>(null)
    val keywordMessage: StateFlow<String?> = _keywordMessage.asStateFlow()

    fun clearKeywordMessage() { _keywordMessage.value = null }

    /** 本书是否已有拆书（完成或进行中，用于「AI 拆书」按钮直接打开）。 */
    private val _hasDecomposition = MutableStateFlow(false)
    val hasDecomposition: StateFlow<Boolean> = _hasDecomposition.asStateFlow()

    /** 拆书状态：none / generating / done。 */
    private val _decomposeStatus = MutableStateFlow("none")
    val decomposeStatus: StateFlow<String> = _decomposeStatus.asStateFlow()

    /** 已拆书的 bookType（用于直接打开结果页时带参数）。 */
    private val _existingDecomposeBookType = MutableStateFlow("general")
    val existingDecomposeBookType: StateFlow<String> = _existingDecomposeBookType.asStateFlow()

    private var currentBookId: Long = 0

    /** 本书已提取的关键词（词云展示）。 */
    private val _keywords = MutableStateFlow<List<Keyword>>(emptyList())
    val keywords: StateFlow<List<Keyword>> = _keywords.asStateFlow()

    /** 手动生成关键词的进行中状态（按钮/进度条）。 */
    private val _isGeneratingKeywords = MutableStateFlow(false)
    val isGeneratingKeywords: StateFlow<Boolean> = _isGeneratingKeywords.asStateFlow()

    /** 单本书推荐（本地 + 联网），惰性生成后缓存。 */
    private val _recommendations = MutableStateFlow<List<RecommendationItem>>(emptyList())
    val recommendations: StateFlow<List<RecommendationItem>> = _recommendations.asStateFlow()

    private val _isGeneratingRecommendations = MutableStateFlow(false)
    val isGeneratingRecommendations: StateFlow<Boolean> = _isGeneratingRecommendations.asStateFlow()

    private val _recommendationsLoaded = MutableStateFlow(false)

    fun isAiEnabled(): Boolean = apiKeyManager.isEnabled()

    /** 智能推荐插件是否开启：关闭时书籍详情页隐藏「推荐书籍」，只保留「相关书籍」。 */
    fun isRecommendEnabled(): Boolean = apiKeyManager.isRecommendEnabled()

    /** Reload the current book from DB (called on screen resume to reflect reader progress). */
    fun reloadBook() {
        if (currentBookId == 0L) return
        viewModelScope.launch {
            _book.value = bookRepository.getBookById(currentBookId)
        }
        refreshDecomposeStatus(currentBookId)
    }

    private fun refreshDecomposeStatus(bookId: Long) {
        viewModelScope.launch {
            val entity = decomposeDao.getByBookId(bookId)
            _hasDecomposition.value = entity != null && (entity.status == "done" || entity.status == "generating")
            _decomposeStatus.value = entity?.status ?: "none"
            if (entity != null && entity.bookType.isNotBlank()) {
                _existingDecomposeBookType.value = entity.bookType
            }
        }
    }

    fun loadBook(bookId: Long) {
        currentBookId = bookId
        reloadBook()
        viewModelScope.launch {
            tagRepository.getTagsForBook(bookId).collect { _tags.value = it }
        }
        viewModelScope.launch {
            tagRepository.getAllTags().collect { _allTags.value = it }
        }
        viewModelScope.launch {
            bookRepository.getRelatedBooks(bookId).collect { _relatedBooks.value = it }
        }
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { _allBooks.value = it }
        }
        viewModelScope.launch {
            keywordRepository.getKeywordsForBook(bookId).collect { _keywords.value = it }
        }
    }

    /** 手动触发关键词提取（生成/重新生成）；完全重生成：删分块→重建→重提取→覆盖，与重新导入同等级彻底。 */
    fun generateKeywords() {
        val book = _book.value ?: return
        if (_isGeneratingKeywords.value) return
        _isGeneratingKeywords.value = true
        viewModelScope.launch {
            try {
                val keywords = keywordExtractor.extract(book.id, forceReindex = true)
                keywordRepository.replaceKeywords(book.id, keywords)
            } catch (e: Exception) {
                _keywordMessage.value = "关键词提取失败：${e.message ?: "未知错误"}"
            } finally {
                _isGeneratingKeywords.value = false
            }
        }
    }

    /**
     * 更新书籍：替换文件与阅读进度相关字段，保留书名/作者/简介/标签/相关书籍/阅读时长/时间戳。
     * 封面规则：新文件有封面则用新封面，否则保留旧封面。
     */
    fun updateBook(uri: Uri) {
        val old = _book.value ?: return
        if (_isUpdating.value) return
        _isUpdating.value = true
        viewModelScope.launch {
            try {
                val imported = bookImporter.importFromUri(uri).getOrElse {
                    _updateMessage.value = "更新失败：${it.message ?: "无法读取文件"}"
                    return@launch
                }
                val nb = imported.book
                val newCover = imported.coverPath
                val oldFile = old.filePath
                val oldCover = old.coverPath

                // 封面：新书有封面用新封面，否则保留旧封面
                val finalCover = newCover ?: oldCover
                val updated = old.copy(
                    filePath = nb.filePath,
                    format = nb.format,
                    fileSize = nb.fileSize,
                    totalPages = nb.totalPages,
                    currentPage = old.currentPage.coerceIn(0, (nb.totalPages - 1).coerceAtLeast(0)),
                    currentLocator = null,
                    coverPath = finalCover,
                    originalFilePath = nb.originalFilePath ?: old.originalFilePath,
                    totalCharacters = nb.totalCharacters,
                )
                bookRepository.updateBook(updated)
                _book.value = updated

                // 删除旧文件；若封面被替换，删除旧封面文件
                runCatching { File(oldFile).takeIf { it.exists() && it.absolutePath != nb.filePath }?.delete() }
                if (newCover != null && oldCover != null && oldCover != newCover) {
                    runCatching { File(oldCover).takeIf { it.exists() }?.delete() }
                }

                // 重建分块 + 重生成关键词（forceReindex 强制按新文件重新抽取正文）
                try {
                    val keywords = keywordExtractor.extract(updated.id, forceReindex = true)
                    keywordRepository.replaceKeywords(updated.id, keywords)
                } catch (_: Exception) {
                    // 关键词提取失败不影响文件替换
                }

                // 标题/作者/正文变化后刷新自动相关
                runCatching { bookRepository.syncAutoRelationsForBook(updated.id) }

                _updateMessage.value = "更新完成"
            } catch (e: Exception) {
                _updateMessage.value = "更新失败：${e.message ?: "未知错误"}"
            } finally {
                _isUpdating.value = false
            }
        }
    }

    // ── 单本书推荐（本地相似 + 联网，统一相关性竞争，惰性生成） ──

    /** 首次切换到「推荐书籍」时触发；已生成或生成中则直接返回。 */
    fun ensureRecommendations() {
        if (_recommendationsLoaded.value || _isGeneratingRecommendations.value) return
        val seed = _book.value ?: return
        _isGeneratingRecommendations.value = true
        viewModelScope.launch {
            try {
                _recommendations.value = buildSingleBookRecommendations(seed)
                _recommendationsLoaded.value = true
            } catch (_: Exception) {
                // 生成失败保持现状，用户可再次切换重试
            } finally {
                _isGeneratingRecommendations.value = false
            }
        }
    }

    private suspend fun buildSingleBookRecommendations(seed: Book): List<RecommendationItem> {
        val bookClient = BookRecommendClient(apiKeyManager.getGoogleBooksApiKey())
        val pixivClient = PixivClient(
            apiKeyManager.getPixivRefreshToken(),
            apiKeyManager.getPixivClientId(),
            apiKeyManager.getPixivClientSecret(),
        )
        val xiu2Client = Xiu2BookSourceClient(getApplication(), "official_sources.json")

        // 种子信号：标签 / 作者 / 书名题材 / 简介关键词 / 正文关键词
        val seedTags = tagRepository.getTagsForBookSync(seed.id).map { it.name.trim() }
            .filter { it.isNotBlank() }.toSet()
        val seedAuthor = seed.author.trim()
        val seedTitleKw = keywordExtractor.extractTitleKeywords(seed.title).toSet()
        val seedDescKw = keywordExtractor.extractTitleKeywords(seed.description).toSet()
        // 正文关键词是本书最强的信号；首次推荐若尚未生成，先提取一次（之后缓存复用）
        var seedContentKw = keywordRepository.getKeywordsForBookOnce(seed.id)
            .associate { it.keyword to it.weight.toDouble() }
        if (seedContentKw.isEmpty()) {
            seedContentKw = try {
                val extracted = keywordExtractor.extract(seed.id)
                keywordRepository.replaceKeywords(seed.id, extracted)
                extracted.associate { it.keyword to it.weight.toDouble() }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        // 本地候选：与种子做五路相关性，排除种子自身
        val allBooks = bookRepository.getAllBooks().first()
        val allKeywords = keywordRepository.getAllKeywordsByBook()
        // 跨书库文档频率：词 → 含该词的书数；对正文关键词降权主角名/地名、上浮主题词
        val df = HashMap<String, Int>()
        for ((_, kws) in allKeywords) for (kw in kws) {
            df[kw.keyword] = (df[kw.keyword] ?: 0) + 1
        }
        seedContentKw = seedContentKw.mapValues { (kw, w) -> w * RecommendationScorer.collectionWeight(df[kw] ?: 1) }
        // 共现语义迁移：对权重极高的关键词，提升与其在同一批书中共现的近邻词权重（词嵌入的轻量离线替代）
        val cooccurrenceIndex = RecommendationScorer.buildCooccurrenceIndex(allKeywords)
        seedContentKw = RecommendationScorer.expandByCooccurrence(seedContentKw, cooccurrenceIndex)
        val localItems = mutableListOf<RecommendationItem>()
        for (cand in allBooks) {
            if (cand.id == seed.id) continue
            val candTags = tagRepository.getTagsForBookSync(cand.id).map { it.name.trim() }
                .filter { it.isNotBlank() }.toSet()
            val candContentKw = (allKeywords[cand.id]?.associate { it.keyword to it.weight.toDouble() } ?: emptyMap())
                .mapValues { (kw, w) -> w * RecommendationScorer.collectionWeight(df[kw] ?: 1) }
            val candTitleKw = keywordExtractor.extractTitleKeywords(cand.title).toSet()
            val candDescKw = keywordExtractor.extractTitleKeywords(cand.description).toSet()
            val sim0 = RecommendationScorer.relevance(
                seedTags, seedAuthor, seedTitleKw, seedDescKw, seedContentKw,
                candTags, cand.author.trim(), candTitleKw, candDescKw, candContentKw,
            )
            val contains = RecommendationScorer.titleContainment(seed.title, cand.title)
            val sim = if (contains) maxOf(sim0, 0.40) else sim0
            if (sim <= 0.0) continue
            localItems.add(
                RecommendationItem(
                    isLocal = true,
                    bookId = cand.id,
                    title = cand.title,
                    author = cand.author,
                    cover = cand.coverPath,
                    description = cand.description,
                    reason = localReason(seedTags, seedAuthor, seedTitleKw, candTags, cand.author, candTitleKw)
                        .let { r -> if (contains) "$r · 相关作品" else r },
                    link = null,
                    dismissKey = "book:${cand.id}",
                    score = sim,
                )
            )
        }

        val onlineItems = fetchOnlineForBook(seed, seedTags, seedAuthor, seedTitleKw, seedDescKw, seedContentKw, bookClient, pixivClient, xiu2Client)

        // 本地/联网统一分数竞争：总数 ≤10；联网保底 3 本（若有）、软上限 6 本（≤60%）
        val maxOnline = 6
        val onlineCount = if (onlineItems.isEmpty()) 0 else onlineItems.size.coerceIn(3, maxOnline)
        val onlineTop = onlineItems.sortedByDescending { it.score }.take(onlineCount)
        val localTop = localItems.sortedByDescending { it.score }.take((10 - onlineCount).coerceAtLeast(0))
        return (localTop + onlineTop).sortedByDescending { it.score }.take(10)
    }

    private suspend fun fetchOnlineForBook(
        seed: Book,
        seedTags: Set<String>,
        seedAuthor: String,
        seedTitleKw: Set<String>,
        seedDescKw: Set<String>,
        seedContentKw: Map<String, Double>,
        bookClient: BookRecommendClient,
        pixivClient: PixivClient,
        xiu2Client: Xiu2BookSourceClient,
    ): List<RecommendationItem> {
        // 搜索命中的书本身即相关：作者搜索给更高保底分（音译名无法精确匹配也能兜住），题材搜索次之，Pixiv 推荐最低
        data class Pending(val d: Deferred<List<OnlineBook>>, val bonus: Double, val verifyAuthor: Boolean = false)
        val pending = mutableListOf<Pending>()
        coroutineScope {
            fun fire(block: suspend () -> List<OnlineBook>, bonus: Double, verifyAuthor: Boolean = false) {
                pending.add(Pending(async { block() }, bonus, verifyAuthor))
            }
            // 搜索词优先级：正文关键词(最有代表性) > 书名题材 > 简介词；去重取前 6
            val searchKeywords = (seedContentKw.keys.toList() + seedTitleKw.toList() + seedDescKw.toList())
                .distinct().take(6)
            for (q in searchKeywords) {
                val olQuery = RecommendationScorer.englishSearchTag(q)
                fire({ bookClient.searchGoogleBooks(q, 12) }, 0.15)
                fire({ bookClient.searchOpenLibrary(olQuery, 12) }, 0.15)
                fire({ bookClient.searchDouban(q, 20) }, 0.15)
                fire({ pixivClient.searchNovels(q, 8) }, 0.15)
                fire({ xiu2Client.search(q, 8) }, 0.15)
            }
            // 书名搜索：在各个源搜索与本书相似/相同的书名（先用书名找到书，再借源的推荐）
            val titleQ = seed.title.trim()
            if (titleQ.isNotBlank()) {
                fire({ bookClient.searchGoogleBooks(titleQ, 10) }, 0.22)
                fire({ bookClient.searchOpenLibrary(RecommendationScorer.englishSearchTag(titleQ), 10) }, 0.22)
                fire({ bookClient.searchDouban(titleQ, 15) }, 0.22)
                fire({ pixivClient.searchNovelsByTitle(titleQ, 6) }, 0.12)
                fire({ xiu2Client.search(titleQ, 8) }, 0.22)
            }
            if (seedAuthor.isNotBlank() && RecommendationScorer.isSafeSearchAuthor(seedAuthor)) {
                fire({ bookClient.searchGoogleBooks(seedAuthor, 12) }, 0.25, verifyAuthor = true)
                fire({ bookClient.searchOpenLibrary(seedAuthor, 12) }, 0.25, verifyAuthor = true)
                fire({ bookClient.searchDouban(seedAuthor, 15) }, 0.25, verifyAuthor = true)
                fire({ xiu2Client.search(seedAuthor, 8) }, 0.25, verifyAuthor = true)
            }
            fire({ pixivClient.recommendedNovels(6) }, 0.05)
        }

        // 去重 + 质量门控，保留每本书的搜索保底分；排除与本书同名同作者
        val seen = mutableSetOf<String>()
        val seedKey = "${seed.title}|${seed.author}"
        val books = mutableListOf<Pair<OnlineBook, Double>>()
        for (p in pending) {
            for (b in p.d.await()) {
                if ("${b.title}|${b.author}" == seedKey) continue
                // 作者搜索只保留「真同作者」，过滤「搜作者名却回来书名命中」的噪声（此前会带 0.25 保底分挤进结果）
                if (p.verifyAuthor && !RecommendationScorer.sameAuthor(seedAuthor, b.author)) continue
                if (!seen.add("${b.title}|${b.author}")) continue
                if (!RecommendationScorer.qualityGatePass(b)) continue
                books.add(b to p.bonus)
            }
        }

        // 确认本：书名与本书互相包含（本书在该平台确实存在），用它做定向关联，比「随机前 3 本各取 top2 标签」更准。
        val confirmed = books.mapNotNull { (b, _) -> b.takeIf { RecommendationScorer.titleContainment(seed.title, b.title) } }
        val relatedSeeds = coroutineScope {
            (if (confirmed.isNotEmpty()) confirmed else books.take(3).map { it.first }).map { b ->
                async {
                    // 确认的 Google 书补全完整分类（搜索返回常缺）；失败/无分类则原样复用。
                    if (b.key.startsWith("gb:")) {
                        val d = bookClient.fetchGoogleBookDetail(b.key.removePrefix("gb:"))
                        if (d != null && d.tags.isNotEmpty()) b.copy(tags = (b.tags + d.tags).distinct()) else b
                    } else b
                }
            }.map { it.await() }
        }
        // 书名命中后，利用各源本身的「相关/相似」二次检索：按确认本的题材/标签再搜一轮。
        val related = coroutineScope {
            val relatedPending = mutableListOf<Pair<Deferred<List<OnlineBook>>, Double>>()
            for (b in relatedSeeds) {
                val tags = b.tags.filter { it.isNotBlank() }.take(4)
                for (tag in tags) {
                    when {
                        b.key.startsWith("gb:") -> relatedPending.add(async { bookClient.searchGoogleBooks("subject:$tag", 6) } to 0.12)
                        b.key.startsWith("ol:") -> relatedPending.add(async { bookClient.searchOpenLibrary(tag, 6) } to 0.12)
                        b.key.startsWith("pixiv:") -> relatedPending.add(async { pixivClient.searchNovels(tag, 4) } to 0.10)
                    }
                }
            }
            relatedPending.flatMap { (d, bonus) -> d.await().map { it to bonus } }
        }
        for ((b, bonus) in related) {
            if ("${b.title}|${b.author}" == seedKey) continue
            if (!seen.add("${b.title}|${b.author}")) continue
            if (!RecommendationScorer.qualityGatePass(b)) continue
            books.add(b to bonus)
        }

        val items = books.map { (b, bonus) ->
            val candTags = b.tags.toSet()
            val candTitle = b.title
            val candDesc = b.description.orEmpty()
            val sim0 = RecommendationScorer.onlineRelevance(
                seedTags, seedAuthor, seedTitleKw, seedDescKw, seedContentKw,
                candTags, b.author.trim(), candTitle, candDesc,
            )
            val contains = RecommendationScorer.titleContainment(seed.title, candTitle)
            val sim = if (contains) maxOf(sim0, 0.40) else sim0
            val authorMatch = RecommendationScorer.sameAuthor(seedAuthor, b.author)
            val sharedTag = seedTags.firstOrNull { it in candTags }
            val sharedTitle = seedTitleKw.firstOrNull { candTitle.contains(it) || candDesc.contains(it) }
            val sharedContent = seedContentKw.keys.firstOrNull { kw ->
                candTags.any { it.contains(kw) || kw.contains(it) } || candTitle.contains(kw) || candDesc.contains(kw)
            }
            val reason = when {
                contains -> "相关作品"
                authorMatch -> "同作者"
                sharedTag != null -> "标签「$sharedTag」"
                sharedContent != null -> "关键词「$sharedContent」"
                sharedTitle != null -> "题材「$sharedTitle」"
                else -> "相关推荐"
            }
            RecommendationItem(
                isLocal = false,
                title = b.title,
                author = b.author,
                cover = b.coverUrl,
                description = listOfNotNull(b.rating?.let { "评分 $it" }, b.description).joinToString(" · "),
                reason = reason,
                source = b.source,
                link = b.link,
                dismissKey = b.key,
                score = sim + bonus + 0.1 * RecommendationScorer.qualityScore(b),
            )
        }

        // 每源限额，防单源霸榜（轻量）
        fun cap(prefix: String, n: Int) =
            items.filter { it.dismissKey.startsWith(prefix) }.sortedByDescending { it.score }.take(n)
        val known = listOf("db:", "gb:", "ol:", "pixiv:", "qd:", "jj:", "sf:")
        return cap("db:", 5) + cap("gb:", 5) + cap("ol:", 5) + cap("pixiv:", 2) +
            cap("qd:", 3) + cap("jj:", 3) + cap("sf:", 3) +
            items.filter { item -> known.none { item.dismissKey.startsWith(it) } }
    }

    private fun localReason(
        seedTags: Set<String>,
        seedAuthor: String,
        seedTitleKw: Set<String>,
        candTags: Set<String>,
        candAuthor: String,
        candTitleKw: Set<String>,
    ): String {
        val reasons = mutableListOf<String>()
        if (RecommendationScorer.sameAuthor(seedAuthor, candAuthor)) reasons.add("同作者")
        seedTags.firstOrNull { it in candTags }?.let { reasons.add("标签「$it」") }
        seedTitleKw.firstOrNull { it in candTitleKw }?.let { reasons.add("题材「$it」") }
        return if (reasons.isEmpty()) "内容相似" else reasons.joinToString(" · ")
    }

    fun addTag(tagId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { tagRepository.addTagToBook(book.id, tagId) }
    }

    fun removeTag(tagId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { tagRepository.removeTagFromBook(book.id, tagId) }
    }

    fun createAndAddTag(name: String) {
        val book = _book.value ?: return
        viewModelScope.launch {
            val tagId = tagRepository.insertTag(Tag(name = name))
            tagRepository.addTagToBook(book.id, tagId)
        }
    }

    fun deleteBook() {
        val book = _book.value ?: return
        viewModelScope.launch {
            bookRepository.deleteBooks(listOf(book.id))
            _isDeleted.value = true
        }
    }

    fun updateBookInfo(title: String, author: String, description: String) {
        val book = _book.value ?: return
        val oldAuthor = book.author
        val updated = book.copy(title = title, author = author, description = description)
        viewModelScope.launch {
            bookRepository.updateBook(updated)
            _book.value = updated
            // Re-sync auto relations if author changed
            if (author.trim().lowercase() != oldAuthor.trim().lowercase()) {
                bookRepository.syncAutoRelationsForBook(book.id)
            }
        }
    }

    fun generateDescription(onResult: (String) -> Unit) {
        val book = _book.value ?: return
        if (!apiKeyManager.isEnabled()) return
        _isGeneratingDesc.value = true
        viewModelScope.launch {
            try {
                val sample = chatRepository.getBookTextSample(book.id, 1000)
                if (sample.isBlank()) {
                    _isGeneratingDesc.value = false
                    return@launch
                }
                val prompt = "以下是一本书前1000字的内容：\n\n$sample"
                val systemPrompt = "你是一个专业的图书编辑。根据提供的书籍内容片段，提取或推断出一段简洁的书籍简介（100字以内，中文）。只返回简介本身，不要包含任何前缀或解释。"
                val result = deepSeekClient.complete(prompt, systemPrompt)
                result.onSuccess { desc ->
                    onResult(desc)
                }
            } catch (_: Exception) {
                // Silently fail — user can manually edit
            }
            _isGeneratingDesc.value = false
        }
    }

    fun addRelatedBook(relatedBookId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { bookRepository.addManualRelation(book.id, relatedBookId) }
    }

    fun removeRelatedBook(relatedBookId: Long) {
        val book = _book.value ?: return
        viewModelScope.launch { bookRepository.removeBookRelation(book.id, relatedBookId) }
    }

    fun showAddRelatedDialog() { _showAddRelatedDialog.value = true }
    fun dismissAddRelatedDialog() { _showAddRelatedDialog.value = false }

    fun clearCoverMessage() { _coverMessage.value = null }

    /** 从相册选择新封面，缩放到 400px 后保存到 covers 目录并更新书籍。 */
    fun updateCover(uri: Uri) {
        val book = _book.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val coverDir = File(getApplication<Application>().filesDir, "covers")
                    coverDir.mkdirs()
                    val coverFile = File(coverDir, "book_${book.id}_${System.currentTimeMillis()}.jpg")
                    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw Exception("无法读取图片")
                    val maxDim = 400
                    val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                        Bitmap.createScaledBitmap(
                            bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                            (bitmap.height * ratio).toInt().coerceAtLeast(1), true,
                        )
                    } else bitmap
                    FileOutputStream(coverFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    val updated = book.copy(coverPath = coverFile.absolutePath)
                    bookRepository.updateBook(updated)
                    _book.value = updated
                }
                _coverMessage.value = "封面已更新"
            } catch (e: Exception) {
                _coverMessage.value = "封面更新失败"
            }
        }
    }

    /** 将当前封面保存到系统相册。 */
    fun saveCoverToGallery() {
        val book = _book.value ?: return
        val coverPath = book.coverPath ?: run { _coverMessage.value = "暂无封面"; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val src = File(coverPath)
                    if (!src.exists()) throw Exception("封面文件不存在")
                    val app = getApplication<Application>()
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "cover_${book.title}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("无法创建相册条目")
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw Exception("无法写入相册")
                }
                _coverMessage.value = "已保存到相册"
            } catch (e: Exception) {
                _coverMessage.value = "保存失败"
            }
        }
    }
}
