package com.ebookreader.ui.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.provider.Settings
import android.text.Html
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.local.entity.DailyBookReadingEntity
import com.ebookreader.data.local.entity.DailyReadingSessionEntity
import com.ebookreader.data.importer.BookImporter
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Annotation
import com.ebookreader.domain.model.AnnotationStyle
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Bookmark
import com.ebookreader.domain.repository.AnnotationRepository
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.BookmarkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.navigator.web.common.WebDecorationTemplates
import org.readium.navigator.web.fixedlayout.FixedWebConfiguration
import org.readium.navigator.web.fixedlayout.FixedWebGoLocation
import org.readium.navigator.web.fixedlayout.FixedWebRenditionFactory
import org.readium.navigator.web.fixedlayout.FixedWebRenditionState
import org.readium.navigator.web.reflowable.ReflowableWebConfiguration
import org.readium.navigator.web.reflowable.ReflowableWebDecorationLocation
import org.readium.navigator.web.reflowable.ReflowableWebGoLocation
import org.readium.navigator.web.reflowable.ReflowableWebLocation
import org.readium.navigator.web.reflowable.ReflowableWebRenditionController
import org.readium.navigator.web.reflowable.ReflowableWebRenditionFactory
import org.readium.navigator.web.reflowable.ReflowableWebRenditionState
import org.readium.navigator.web.reflowable.preferences.ReflowableWebPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.navigator.common.Decoration
import org.readium.navigator.common.Progression
import org.readium.navigator.common.TextAnchor
import org.readium.navigator.common.TextQuote
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ReaderUiState {
    data object Loading : ReaderUiState()
    data class Ready(
        val publication: Publication,
        val state: ReflowableWebRenditionState,
        val controller: ReflowableWebRenditionController? = null,
    ) : ReaderUiState()
    data class FixedReady(
        val publication: Publication,
        val fixedState: FixedWebRenditionState,
    ) : ReaderUiState()
    data class PdfReady(
        val publication: Publication,
        val filePath: String,
    ) : ReaderUiState()
    data class Error(val message: String) : ReaderUiState()
}

data class TocItem(val title: String, val href: String, val level: Int = 0, val readingOrderIndex: Int = -1)

data class SearchResultItem(val title: String, val locatorJson: String, val locator: Locator)

/** 待处理的文字选择：用户点「划线/批注」后暂存，等待选择样式/颜色或输入笔记。 */
data class PendingSelection(val text: String, val locator: Locator, val pageIndex: Int)


sealed class TtsPlaybackState {
    data object Idle : TtsPlaybackState()
    data object Initializing : TtsPlaybackState()
    data class Playing(override val sentenceIndex: Int, override val totalSentences: Int, override val sentence: String) : TtsPlaybackState(), ActiveTts
    data class Paused(override val sentenceIndex: Int, override val totalSentences: Int, override val sentence: String) : TtsPlaybackState(), ActiveTts
    data class Error(val message: String) : TtsPlaybackState()
}

/** Shared interface for [TtsPlaybackState.Playing] and [TtsPlaybackState.Paused]. */
interface ActiveTts {
    val sentenceIndex: Int
    val totalSentences: Int
    val sentence: String
}

@OptIn(ExperimentalReadiumApi::class)
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository: BookRepository = Injector.bookRepository()
    private val bookmarkRepository: BookmarkRepository = Injector.bookmarkRepository()
    private val annotationRepository: AnnotationRepository = Injector.annotationRepository()

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    private val _pendingHighlight = MutableStateFlow<PendingSelection?>(null)
    val pendingHighlight: StateFlow<PendingSelection?> = _pendingHighlight.asStateFlow()

    private val _pendingAnnotation = MutableStateFlow<PendingSelection?>(null)
    val pendingAnnotation: StateFlow<PendingSelection?> = _pendingAnnotation.asStateFlow()

    /** 当前 TTS 高亮装饰；与批注装饰合并渲染，避免互相覆盖。 */
    private var ttsDecor: Decoration<ReflowableWebDecorationLocation>? = null

    private val _currentPageLabel = MutableStateFlow("")
    val currentPageLabel: StateFlow<String> = _currentPageLabel.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    // PDF-specific state
    private val _pdfPage = MutableStateFlow(0)
    val pdfPage: StateFlow<Int> = _pdfPage.asStateFlow()
    private var pdfPageCount = 0

    // Positions per chapter（内容长度代理，字号无关；用缓存可跳过昂贵的 positionsByReadingOrder()）
    private var positionCountsPerChapter: IntArray = IntArray(0)
    private var totalPositions: Int = 0

    /** Exact rendered page count per chapter (from the navigator's viewport), filled in as the user reads. */
    private val actualPagesPerChapter = mutableMapOf<Int, Int>()

    /** SharedPreferences key for persisting [actualPagesPerChapter] of the current book+font+screen. */
    private var pageCountCacheKey: String? = null

    /** readingOrder 章节数（跳过 positions 计算时用于逐章页数估算）。 */
    private var readingOrderSize: Int = 0

    private val _currentChapterTitle = MutableStateFlow("")
    val currentChapterTitle: StateFlow<String> = _currentChapterTitle.asStateFlow()

    /** 当前章节在 readingOrder 中的索引，用于目录高亮。 */
    private val _currentChapterIndex = MutableStateFlow(-1)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _currentChapterHref = MutableStateFlow("")
    val currentChapterHref: StateFlow<String> = _currentChapterHref.asStateFlow()

    private val _tocItems = MutableStateFlow<List<TocItem>>(emptyList())
    val tocItems: StateFlow<List<TocItem>> = _tocItems.asStateFlow()

    private val _fontSize = MutableStateFlow(1.0)
    val fontSize: StateFlow<Double> = _fontSize.asStateFlow()

    private val _preferences = MutableStateFlow(ReflowableWebPreferences())
    val preferences: StateFlow<ReflowableWebPreferences> = _preferences.asStateFlow()

    private val _themeKey = MutableStateFlow("default")
    val themeKey: StateFlow<String> = _themeKey.asStateFlow()

    /** 翻页模式：false=左右翻页（默认），true=上下滚动。 */
    private val _scrollMode = MutableStateFlow(false)
    val scrollMode: StateFlow<Boolean> = _scrollMode.asStateFlow()

    /** 屏幕亮度（0.2~1.0，默认 1.0），通过半透明黑色遮罩实现。 */
    private val _brightness = MutableStateFlow(1.0f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    /** Guards against reloading the same book when composable is recomposed. */
    private var loadedBookId: Long? = null

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private var searchIterator: SearchIterator? = null

    // ── Chapter refresh / delete state ──────────────────────────────────

    private val _isRefreshingToc = MutableStateFlow(false)
    val isRefreshingToc: StateFlow<Boolean> = _isRefreshingToc.asStateFlow()

    private val _tocRefreshMessage = MutableStateFlow<String?>(null)
    val tocRefreshMessage: StateFlow<String?> = _tocRefreshMessage.asStateFlow()

    fun clearRefreshMessage() { _tocRefreshMessage.value = null }

    // ── TTS (Text-to-Speech) ───────────────────────────────────────────

    /** Android system TTS engine. */
    private var tts: TextToSpeech? = null
    /** MediaPlayer for the single-WAV chapter audio. */
    private var ttsPlayer: MediaPlayer? = null
    /** Chapter WAV file — cleaned up on stop/clear. */
    private var ttsChapterFile: File? = null
    /** Synthesis + polling coroutine. */
    private var ttsJob: Job? = null
    /** 云端流式：尾部播放器（头段为 [ttsPlayer]）。 */
    private var ttsTailPlayer: MediaPlayer? = null
    /** 云端流式：尾部 WAV 文件。 */
    private var ttsTailFile: File? = null
    /** 云端流式：尾部是否已接管播放。 */
    private var ttsTailActive: Boolean = false
    /** 云端流式：头段句数（用于字符边界）。 */
    private var ttsHeadCount: Int = 0
    /** 云端流式：尾段每句累计起始时间（毫秒，相对尾段起点），供时间→句子二分。 */
    private var ttsTailTimesMs: LongArray? = null
    /** 云端流式：各段（首句/中段/尾段）元数据，按播放顺序。 */
    private var ttsStreamSegs: MutableList<StreamSeg> = mutableListOf()

    private var ttsSentences: List<String> = emptyList()
    private var ttsChapterIndex: Int = -1
    /** Maps each sentence index to its visual page (within the chapter). */
    private var ttsSentencePage: IntArray = intArrayOf()
    /** Maps each sentence index to its start progression (0..1) within the chapter, for scroll mode. */
    private var ttsSentenceProgression: DoubleArray = DoubleArray(0)
    /** Total visual pages in all chapters before the current TTS chapter. */
    private var ttsPrevPageCount: Int = 0
    /** Last global page we flipped to (anti-pump). */
    private var ttsLastFlippedPage: Int = -1
    /** Current sentence index during playback, for resume after pause. */
    private var ttsCurrentIdx: Int = 0
    /** 本次播放从第几句开始（全局索引）；cumChars 是相对它的子列表索引。 */
    private var ttsStartIdx: Int = 0
    /** True when the current engine does NOT support synthesizeToFile, so we use speak(). */
    private var ttsUseFallback: Boolean = false
    /** Cumulative character counts for time→sentence mapping. Entry [k] = chars before sentence k. */
    private var ttsCumChars: LongArray = LongArray(0)
    /** 本地整章 WAV 每句的累计起始时间（毫秒），由各批真实时长折算；供单文件播放/seek 精确定位。 */
    private var ttsSingleTimesMs: LongArray? = null

    private val _ttsState = MutableStateFlow<TtsPlaybackState>(TtsPlaybackState.Idle)
    val ttsState: StateFlow<TtsPlaybackState> = _ttsState.asStateFlow()

    /** Synthesis progress text (e.g. "正在准备朗读... 12/45句"). */
    private val _ttsSynthesisProgress = MutableStateFlow("")
    val ttsSynthesisProgress: StateFlow<String> = _ttsSynthesisProgress.asStateFlow()

    private fun isTtsPluginEnabled(): Boolean = Injector.apiKeyManager().isTtsEnabled()

    private var currentLocator: Locator? = null

    private val httpClient = DefaultHttpClient()
    private var activePublication: Publication? = null
    private var readingStartTime: Long = 0

    fun loadBook(bookId: Long) {
        if (loadedBookId == bookId) return // Already loaded, skip re-initialization
        loadedBookId = bookId
        stopTts() // Kill any TTS from a previous book to avoid stale-state interference
        viewModelScope.launch {
            val b = bookRepository.getBookById(bookId)
            _book.value = b
            readingStartTime = System.currentTimeMillis()
            if (b != null) {
                openPublication(b)
            }
        }
        viewModelScope.launch {
            bookmarkRepository.getBookmarksForBook(bookId).collect { _bookmarks.value = it }
        }
        viewModelScope.launch {
            annotationRepository.getAnnotationsForBook(bookId).collect {
                _annotations.value = it
                refreshDecorations()
            }
        }
    }

    private suspend fun openPublication(book: Book) {
        _uiState.value = ReaderUiState.Loading
        val app = getApplication<Application>()

        // Safety net: if loading takes >60s, show error so user isn't stuck on black screen
        val timeoutJob = viewModelScope.launch {
            delay(60_000)
            if (_uiState.value is ReaderUiState.Loading) {
                _uiState.value = ReaderUiState.Error("加载超时，请返回重试")
            }
        }

        // ── Phase 1: Heavy I/O on background thread ──
        // Publication parsing, positions computation, TOC building,
        // and legacy TXT→EPUB auto-conversion all run off the main thread
        // so the loading spinner animates smoothly and we get a real 30s timeout.
        val result: OpenResult = withContext(Dispatchers.IO) {
            try {
                // Auto-convert legacy .txt files to EPUB (encoding detected once, cached as EPUB)
                var actualPath = book.filePath
                if (book.filePath.endsWith(".txt", ignoreCase = true)) {
                    val txtFile = File(book.filePath)
                    if (txtFile.exists()) {
                        val epubFile = BookImporter(app).ensureEpub(txtFile)
                        actualPath = epubFile.absolutePath
                        // Persist the updated path so subsequent opens go straight to EPUB
                        if (actualPath != book.filePath) {
                            bookRepository.updateFilePath(book.id, actualPath)
                        }
                    }
                } else if (book.filePath.endsWith(".epub", ignoreCase = true)) {
                    // TXT 转换来的 EPUB：若同目录存在同名 .txt 源文件，则按生成版本号自动重新转换，
                    // 以修复旧版本生成器留下的非法控制字符、章节识别等问题。
                    val epubFile = File(book.filePath)
                    val siblingTxt = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}.txt")
                    if (siblingTxt.exists()) {
                        actualPath = BookImporter(app).ensureEpub(siblingTxt).absolutePath
                    }
                }

                val file = File(actualPath)
                if (!file.exists()) return@withContext OpenResult.Error("文件不存在")

                // EPUB：打开前确保 `</html>` 之后的残留内容已被清除（旧版导入的书籍首次打开自动处理一次）。
                if (actualPath.endsWith(".epub", ignoreCase = true)) {
                    BookImporter(app).ensureEpubTrailingStripped(file)
                }

                val url = file.toUrl(isDirectory = false)
                val assetRetriever = AssetRetriever(app.contentResolver, httpClient)
                val pdfFactory = PdfiumDocumentFactory(app)
                val parser = DefaultPublicationParser(app, httpClient, assetRetriever, pdfFactory)
                val opener = PublicationOpener(parser)
                val asset = assetRetriever.retrieve(url).getOrElse {
                    return@withContext OpenResult.Error("无法读取文件")
                }
                val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
                    asset.close(); return@withContext OpenResult.Error("不支持的格式")
                }

                val readerPrefs = app.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                val savedFontSize = readerPrefs.getFloat("reader_font_size", 1.0f).toDouble().coerceIn(0.8, 1.5)
                val savedTheme = readerPrefs.getString("reader_theme", "default") ?: "default"
                val savedBrightness = readerPrefs.getFloat("reader_brightness", 1.0f).coerceIn(0.2f, 1.0f)
                val savedScroll = readerPrefs.getBoolean("reader_scroll", false)

                val isPdf = publication.readingOrder.size == 1 &&
                    publication.readingOrder.firstOrNull()?.mediaType?.matches(MediaType.PDF) == true

                // 逐章 position 计数（字号无关的内容长度代理）：优先读缓存。
                // 缓存缺失时不在打开阶段同步计算（positionsByReadingOrder 对长书较慢），
                // 放到阅读器就绪后后台补算，让内容先显示出来（大文件打开慢的主要瓶颈）。
                val positionCountsKey = "poscount_${book.id}"
                val positionCounts: IntArray
                if (isPdf) {
                    positionCounts = IntArray(0)
                } else {
                    val cached = Injector.apiKeyManager().getCachedPositionCounts(positionCountsKey)
                    positionCounts = if (cached.isNotEmpty() && cached.size == publication.readingOrder.size) {
                        cached
                    } else {
                        IntArray(0)
                    }
                }
                val totalPositions = positionCounts.sum()

                // 缓存精确页数（按 bookId + 字号 + 屏幕宽度）
                val cachedPageCounts = if (!isPdf) {
                    Injector.apiKeyManager().getCachedPageCounts(pageCountCacheKeyOf(book.id, savedFontSize))
                } else emptyMap()

                // 初始总页数：PDF=页数；有当前字号的缓存精确页数时沿用存储总页数（Phase 2 再校准）；
                // 否则用 position 总数估算（Readium 512 字节/position 约等于一页）。
                val totalPages = if (isPdf) publication.readingOrder.size
                    else if (cachedPageCounts.isNotEmpty() && book.totalPages > 0) book.totalPages
                    else maxOf(totalPositions, publication.readingOrder.size)
                val initialPage = book.currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                val tocItems = buildTocList(publication.tableOfContents, publication.readingOrder)

                OpenResult.Success(
                    publication = publication,
                    filePath = actualPath,
                    isPdf = isPdf,
                    savedFontSize = savedFontSize,
                    savedTheme = savedTheme,
                    savedBrightness = savedBrightness,
                    savedScroll = savedScroll,
                    positionCounts = positionCounts,
                    totalPositions = totalPositions,
                    totalPages = totalPages,
                    initialPage = initialPage,
                    tocItems = tocItems,
                    currentLocatorJson = book.currentLocator ?: "{}",
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to open publication")
                OpenResult.Error(e.message ?: "打开失败")
            }
        }

        // ── Phase 2: Apply result on Main thread ──
        when (result) {
            is OpenResult.Error -> {
                timeoutJob.cancel()
                _uiState.value = ReaderUiState.Error(result.message)
                return
            }
            is OpenResult.Success -> {
                val publication = result.publication
                activePublication = publication
                _themeKey.value = result.savedTheme
                _fontSize.value = result.savedFontSize
                _brightness.value = result.savedBrightness
                _scrollMode.value = result.savedScroll
                positionCountsPerChapter = result.positionCounts
                totalPositions = result.totalPositions
                readingOrderSize = publication.readingOrder.size
                _totalPages.value = result.totalPages
                _currentPageIndex.value = result.initialPage
                _tocItems.value = result.tocItems

                // 加载已缓存的精确页数（按 bookId + 字号 + 屏幕宽度），并按「已测章节精确页数 +
                // 未测章节按 position 比例估算」立即校准总页数（不再有 uniform 摊派或字符数跳变）。
                loadCachedPageCounts(book.id, result.savedFontSize)
                if (!result.isPdf) {
                    _totalPages.value = pageCounts().sum()
                }

                // 首次打开（position 缓存缺失）时，阅读器就绪后再后台补算 position 计数并持久化，
                // 让内容先显示、页码随后校准，避免大文件打开时长时间转圈。
                if (!result.isPdf && result.positionCounts.isEmpty()) {
                    val positionCountsKey = "poscount_${book.id}"
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val list = publication.positionsByReadingOrder()
                            val counts = list.map { it.size }.toIntArray()
                            if (counts.isNotEmpty()) {
                                Injector.apiKeyManager().setCachedPositionCounts(positionCountsKey, counts)
                                withContext(Dispatchers.Main) {
                                    positionCountsPerChapter = counts
                                    totalPositions = counts.sum()
                                    _totalPages.value = pageCounts().sum()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                try {
                    bookRepository.updateReadingProgress(book.id, result.initialPage, result.totalPages, book.currentLocator ?: "{}", book.lastReadTimestamp)
                } catch (_: Exception) {}

                if (result.isPdf) {
                    timeoutJob.cancel()
                    pdfPageCount = result.totalPages
                    _pdfPage.value = result.initialPage
                    _uiState.value = ReaderUiState.PdfReady(publication, result.filePath)
                    return
                }

                val initialLocation = if (!book.currentLocator.isNullOrEmpty()) {
                    try {
                        val jsonObj = JSONObject(book.currentLocator)
                        val loc = Locator.fromJSON(jsonObj)
                        currentLocator = loc
                        if (loc != null) {
                            _currentChapterHref.value = loc.href.toString()
                            ReflowableWebGoLocation(
                                href = loc.href,
                                progression = loc.locations.progression?.let { Progression(it) }
                            )
                        } else null
                    } catch (_: Exception) { null }
                } else null

                val factory = ReflowableWebRenditionFactory(app, publication, reflowableConfig)
                if (factory != null) {
                    val prefs = when (result.savedTheme) {
                        "sepia" -> ReflowableWebPreferences.SepiaTheme
                        "dark" -> darkBeigeTheme
                        else -> ReflowableWebPreferences()
                    } + ReflowableWebPreferences(fontSize = result.savedFontSize, scroll = result.savedScroll)
                    _preferences.value = prefs
                    val state = factory.createRenditionState(
                        initialPreferences = prefs,
                        initialLocation = initialLocation,
                    ).getOrElse {
                        timeoutJob.cancel(); _uiState.value = ReaderUiState.Error("初始化失败"); return
                    }
                    timeoutJob.cancel()
                    _uiState.value = ReaderUiState.Ready(publication, state, state.controller)
                    viewModelScope.launch {
                        snapshotFlow { state.controller }.collect { ctrl ->
                            if (ctrl != null) {
                                _uiState.value = ReaderUiState.Ready(publication, state, ctrl)
                                refreshDecorations()
                                snapshotFlow { ctrl.location }.collect { loc ->
                                    try {
                                        currentLocator = loc.toLocator()
                                        _currentChapterHref.value = loc.href.toString()
                                    } catch (_: Exception) {}
                                    val idx = publication.readingOrder.indexOfFirst { it.url() == loc.href }
                                    if (idx >= 0) {
                                        _currentChapterIndex.value = idx
                                        // 标题优先用目录项（可读），否则用 readingOrder 的 title
                                        _currentChapterTitle.value = _tocItems.value.firstOrNull { it.readingOrderIndex == idx }?.title
                                            ?: publication.readingOrder[idx].title.orEmpty()
                                        updatePagePosition(ctrl, loc, idx, publication)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val fixedInitialLocation = if (!book.currentLocator.isNullOrEmpty()) {
                        try {
                            val jsonObj = JSONObject(book.currentLocator)
                            val loc = Locator.fromJSON(jsonObj)
                            currentLocator = loc
                            if (loc != null) FixedWebGoLocation(href = loc.href) else null
                        } catch (_: Exception) { null }
                    } else null

                    val fixedFactory = FixedWebRenditionFactory(app, publication, fixedConfig)
                    if (fixedFactory != null) {
                        val fixedState = fixedFactory.createRenditionState(
                            initialPreferences = org.readium.navigator.web.fixedlayout.preferences.FixedWebPreferences(),
                            initialLocation = fixedInitialLocation,
                        ).getOrElse { timeoutJob.cancel(); _uiState.value = ReaderUiState.Error("初始化失败"); return }
                        timeoutJob.cancel()
                        _totalPages.value = publication.readingOrder.size
                        _uiState.value = ReaderUiState.FixedReady(publication, fixedState)
                    } else {
                        timeoutJob.cancel()
                        publication.close(); activePublication = null
                        _uiState.value = ReaderUiState.Error("不支持的出版物类型")
                    }
                }
            }
        }
    }

    /** Intermediate result from Phase-1 background I/O. */
    private sealed class OpenResult {
        data class Success(
            val publication: Publication,
            val filePath: String,
            val isPdf: Boolean,
            val savedFontSize: Double,
            val savedTheme: String,
            val savedBrightness: Float,
            val savedScroll: Boolean,
            val positionCounts: IntArray,
            val totalPositions: Int,
            val totalPages: Int,
            val initialPage: Int,
            val tocItems: List<TocItem>,
            val currentLocatorJson: String,
        ) : OpenResult()
        data class Error(val message: String) : OpenResult()
    }

    private fun buildTocList(
        links: List<org.readium.r2.shared.publication.Link>,
        readingOrder: List<org.readium.r2.shared.publication.Link>,
        level: Int = 0,
    ): List<TocItem> {
        // 预建 hrefKey -> readingOrder 下标 的映射，避免每个目录项都 O(n) 扫描（原实现 O(n²)，
        // 长目录书（如数百上千章）会显著拖慢打开）。
        val indexByHrefKey = HashMap<String, Int>()
        for ((idx, link) in readingOrder.withIndex()) {
            indexByHrefKey.putIfAbsent(tocHrefKey(link.url().toString()), idx)
        }
        return buildTocListInner(links, level, indexByHrefKey)
    }

    private fun buildTocListInner(
        links: List<org.readium.r2.shared.publication.Link>,
        level: Int,
        indexByHrefKey: Map<String, Int>,
    ): List<TocItem> {
        return links.flatMap { link ->
            val href = link.url().toString()
            val item = TocItem(
                title = link.title ?: "—",
                href = href,
                level = level,
                readingOrderIndex = indexByHrefKey[tocHrefKey(href)] ?: -1,
            )
            listOf(item) + buildTocListInner(link.children, level + 1, indexByHrefKey)
        }
    }

    /** 归一化 href：去 fragment/query、URL 解码、取文件名（不含扩展名），用于目录与阅读器的匹配。 */
    private fun tocHrefKey(href: String): String {
        val stripped = href.substringBefore('#').substringBefore('?')
        val decoded = try { java.net.URLDecoder.decode(stripped, "UTF-8") } catch (_: Exception) { stripped }
        val filename = decoded.substringAfterLast('/')
        return filename.substringBeforeLast('.', filename)
    }

    private fun getCurrentLocatorJson(): String {
        return currentLocator?.toJSON()?.toString() ?: "{}"
    }

    // PDF page navigation
    fun onPdfPageChanged(page: Int, count: Int) {
        _pdfPage.value = page
        pdfPageCount = count
        _totalPages.value = count
        _currentPageLabel.value = "${page + 1} / $count"
        _currentPageIndex.value = page
        try {
            val loc = JSONObject()
            loc.put("href", "/pdf")
            val locations = JSONObject()
            locations.put("position", page + 1)
            loc.put("locations", locations)
            loc.put("type", "application/pdf")
            currentLocator = Locator.fromJSON(loc)
        } catch (_: Exception) {}
    }

    fun goToPdfPage(page: Int) {
        _pdfPage.value = page.coerceIn(0, (pdfPageCount - 1).coerceAtLeast(0))
    }

    /**
     * 用渲染器 viewport 的可见区间推算当前章节的**实际渲染页数**，并据此计算全局页码。
     *
     * `viewport.progressions[href]` 是当前可见内容（翻页模式下为一列）的进度区间
     * [start, end]，其中 `end - start` 恰为「一页占本章的进度比例」，因此：
     *   - 本章实际页数 = 1 / (end - start)
     *   - 本章内当前页 = start / (end - start)
     * 对未访问过的章节，用固定 1:1（position≈页）估算，保证页码稳定不漂移。
     */
    private fun updatePagePosition(
        ctrl: ReflowableWebRenditionController,
        loc: ReflowableWebLocation,
        idx: Int,
        publication: Publication,
    ) {
        val range = ctrl.viewport.progressions[loc.href]
        if (range != null && range.endInclusive.value - range.start.value > 0.0001) {
            val pageWidth = range.endInclusive.value - range.start.value
            val pageCountInChapter = Math.round(1.0 / pageWidth).toInt().coerceAtLeast(1)
            val pageInChapter = Math.round(range.start.value / pageWidth).toInt()
                .coerceIn(0, pageCountInChapter - 1)
            if (actualPagesPerChapter[idx] != pageCountInChapter) {
                actualPagesPerChapter[idx] = pageCountInChapter
                persistPageCounts()
            }

            val counts = pageCounts()
            var globalIndex = 0
            for (i in 0 until idx) globalIndex += counts.getOrElse(i) { 1 }
            globalIndex += pageInChapter
            val total = counts.sum()

            _currentPageIndex.value = globalIndex
            _totalPages.value = total
            _currentPageLabel.value = "${globalIndex + 1} / $total"
        } else if (positionCountsPerChapter.isNotEmpty()) {
            // 回退：position 数（无法拿到渲染信息时）
            val chapterSize = positionCountsPerChapter.getOrElse(idx) { 0 }.coerceAtLeast(1)
            val posInChapter = ((loc.progression.value) * chapterSize).toInt()
                .coerceIn(0, chapterSize - 1)
            val prevPositions = positionCountsPerChapter.take(idx).sum()
            _currentPageIndex.value = prevPositions + posInChapter
            _currentPageLabel.value = "${_currentPageIndex.value + 1} / $totalPositions"
        } else {
            _currentPageIndex.value = idx
            _currentPageLabel.value = "${idx + 1} / ${publication.readingOrder.size}"
        }
    }

    /** 计算当前 bookId+字号+屏幕宽度的缓存键。 */
    private fun pageCountCacheKeyOf(bookId: Long, fontSize: Double): String {
        val screenW = getApplication<Application>().resources.displayMetrics.widthPixels
        return "pagecount_${bookId}_${fontSize.toFloat()}_${screenW}"
    }

    /** 从磁盘加载已缓存的精确页数。 */
    private fun loadCachedPageCounts(bookId: Long, fontSize: Double) {
        actualPagesPerChapter.clear()
        val key = pageCountCacheKeyOf(bookId, fontSize)
        pageCountCacheKey = key
        actualPagesPerChapter.putAll(Injector.apiKeyManager().getCachedPageCounts(key))
    }

    /**
     * 每章页数：已测章节用精确值，未测章节用「position 数」固定估算（512 字节/position ≈ 一页）。
     *
     * 刻意不用全局校准比例（已测页数/已测 position 数）：它会随阅读推进而漂移，
     * 让未测章节的估算页数不断变化，进而导致书签页码与实际页码不符、总页数偶发跳动。
     * 固定 1:1 比例保证页码稳定，且已测章节逐步用精确值收敛。
     */
    private fun pageCounts(): IntArray {
        val n = readingOrderSize
        if (n == 0) return IntArray(0)
        return IntArray(n) { i ->
            actualPagesPerChapter[i]
                ?: positionCountsPerChapter.getOrElse(i) { 0 }.coerceAtLeast(1)
        }
    }

    /** 持久化已测得的精确页数。 */
    private fun persistPageCounts() {
        val key = pageCountCacheKey ?: return
        Injector.apiKeyManager().setCachedPageCounts(key, actualPagesPerChapter)
    }

    fun moveForward() {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.PdfReady -> goToPdfPage(_pdfPage.value + 1)
            is ReaderUiState.Ready -> {
                state.controller?.let { ctrl ->
                    viewModelScope.launch { ctrl.moveForward() }
                }
            }
            else -> {}
        }
    }

    fun moveBackward() {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.PdfReady -> goToPdfPage(_pdfPage.value - 1)
            is ReaderUiState.Ready -> {
                state.controller?.let { ctrl ->
                    viewModelScope.launch { ctrl.moveBackward() }
                }
            }
            else -> {}
        }
    }

    /** 跳到上一章（重排式）；PDF 回退到上一页。 */
    fun moveToPrevChapter() {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.PdfReady -> goToPdfPage(_pdfPage.value - 1)
            is ReaderUiState.Ready -> {
                val pub = state.publication
                val ctrl = state.controller ?: return
                val href = currentLocator?.href?.toString() ?: ""
                val idx = pub.readingOrder.indexOfFirst { it.url().toString() == href }
                val prevIdx = if (idx <= 0) 0 else idx - 1
                if (prevIdx != idx) {
                    viewModelScope.launch { ctrl.goTo(pub.readingOrder[prevIdx].url()) }
                }
            }
            else -> {}
        }
    }

    /** 跳到下一章（重排式）；PDF 回退到下一页。 */
    fun moveToNextChapter() {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.PdfReady -> goToPdfPage(_pdfPage.value + 1)
            is ReaderUiState.Ready -> {
                val pub = state.publication
                val ctrl = state.controller ?: return
                val href = currentLocator?.href?.toString() ?: ""
                val idx = pub.readingOrder.indexOfFirst { it.url().toString() == href }
                val nextIdx = (idx + 1).coerceAtMost(pub.readingOrder.size - 1)
                if (nextIdx != idx) {
                    viewModelScope.launch { ctrl.goTo(pub.readingOrder[nextIdx].url()) }
                }
            }
            else -> {}
        }
    }

    fun goToPage(pageIndex: Int) {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.PdfReady -> goToPdfPage(pageIndex)
            is ReaderUiState.Ready -> {
                val ctrl = state.controller ?: return
                val pub = state.publication
                val counts = pageCounts()
                if (counts.isEmpty() || pub.readingOrder.isEmpty()) return

                val total = counts.sum()
                val safePage = pageIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
                var remaining = safePage
                var chapterIdx = 0
                for (i in counts.indices) {
                    if (remaining < counts[i] || i == counts.size - 1) {
                        chapterIdx = i
                        if (remaining >= counts[i]) {
                            remaining = (counts[i] - 1).coerceAtLeast(0)
                        }
                        break
                    }
                    remaining -= counts[i]
                }
                val chapterSize = counts[chapterIdx].coerceAtLeast(1)
                val progression = Progression(remaining.toDouble() / chapterSize)
                val item = pub.readingOrder.getOrNull(chapterIdx) ?: return
                viewModelScope.launch {
                    ctrl.goTo(ReflowableWebGoLocation(href = item.url(), progression = progression))
                }
            }
            else -> {}
        }
    }

    fun goToTocItem(href: String) {
        val state = _uiState.value
        if (state is ReaderUiState.Ready && state.controller != null) {
            viewModelScope.launch {
                val url = Url(href)
                if (url != null) state.controller.goTo(url)
            }
        }
    }

    fun goToLocator(locatorJson: String) {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.PdfReady -> {
                try {
                    val jsonObj = JSONObject(locatorJson)
                    val pageIdx = jsonObj.optJSONObject("locations")?.optInt("position", 0)?.minus(1) ?: 0
                    goToPdfPage(pageIdx)
                } catch (_: Exception) {}
            }
            is ReaderUiState.Ready -> {
                val ctrl = state.controller ?: return
                try {
                    val jsonObj = JSONObject(locatorJson)
                    val loc = Locator.fromJSON(jsonObj)
                    if (loc != null) {
                        viewModelScope.launch {
                            ctrl.goTo(ReflowableWebGoLocation(
                                href = loc.href,
                                progression = loc.locations.progression?.let { Progression(it) }
                            ))
                        }
                    }
                } catch (_: Exception) {}
            }
            else -> {}
        }
    }

    // Search
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun performSearch(query: String) {
        _searchQuery.value = query
        searchIterator?.close()
        _searchResults.value = emptyList()
        if (query.isBlank()) return
        _isSearching.value = true
        viewModelScope.launch {
            try {
                val pub = activePublication ?: return@launch
                val iter = pub.search(query) ?: return@launch
                searchIterator = iter
                val results = mutableListOf<SearchResultItem>()
                while (true) {
                    val page = iter.next().getOrElse { break }
                    if (page == null) break
                    page.locators.forEach { loc ->
                        // 展示上下文：前文 + 「匹配词」 + 后文，而不是只显示匹配词
                        val before = loc.text.before.orEmpty().replace('\n', ' ').trim()
                        val highlight = loc.text.highlight.orEmpty().replace('\n', ' ').trim()
                        val after = loc.text.after.orEmpty().replace('\n', ' ').trim()
                        val snippet = buildString {
                            if (before.isNotEmpty()) append(before.takeLast(25))
                            if (highlight.isNotEmpty()) append("「$highlight」")
                            if (after.isNotEmpty()) append(after.take(25))
                        }.trim().ifBlank { query }
                        val title = buildString {
                            if (!loc.title.isNullOrBlank()) append("【${loc.title}】")
                            append(snippet)
                        }
                        results.add(SearchResultItem(title, loc.toJSON().toString(), loc))
                    }
                }
                _searchResults.value = results
            } catch (_: Exception) {}
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        searchIterator?.close()
        searchIterator = null
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    // Bookmarks (page notes)
    fun addBookmark(title: String, note: String = "") {
        val book = _book.value ?: return
        val locatorJson = getCurrentLocatorJson()
        viewModelScope.launch {
            bookmarkRepository.insertBookmark(
                Bookmark(bookId = book.id, title = title, note = note, locatorJson = locatorJson)
            )
        }
    }

    fun updateBookmarkNote(bookmarkId: Long, note: String) {
        viewModelScope.launch {
            bookmarkRepository.updateNote(bookmarkId, note)
        }
    }

    fun removeBookmark(bookmarkId: Long) {
        viewModelScope.launch { bookmarkRepository.deleteBookmark(bookmarkId) }
    }

    /**
     * 根据书签的 locator 实时计算当前页码标签（如 "189 / 1987"）。
     * 页码随阅读进度 / 字号重排自动校准；无法解析时返回 null，由调用方回退到书签标题。
     */
    fun bookmarkPageLabel(bookmark: Bookmark): String? {
        val pub = activePublication ?: return null
        val json = bookmark.locatorJson
        if (json.isNullOrEmpty() || json == "{}") return null
        return try {
            val loc = Locator.fromJSON(org.json.JSONObject(json)) ?: return null
            val href = loc.href.toString()
            val idx = pub.readingOrder.indexOfFirst { it.url().toString() == href }
            if (idx < 0) return null
            val counts = pageCounts()
            if (counts.isEmpty() || idx >= counts.size) return null
            val total = counts.sum()
            if (total <= 0) return null
            val chCount = counts[idx].coerceAtLeast(1)
            val prog = loc.locations.progression ?: 0.0
            val pageInChapter = (prog * chCount).toInt().coerceIn(0, chCount - 1)
            val global = counts.take(idx).sum() + pageInChapter
            "${global + 1} / $total"
        } catch (_: Exception) { null }
    }

    // Annotations (highlight / underline / wavy / note)
    fun onHighlightRequested(text: String, locator: Locator) {
        _pendingHighlight.value = PendingSelection(text, locator, _currentPageIndex.value)
    }

    fun onAnnotateRequested(text: String, locator: Locator) {
        _pendingAnnotation.value = PendingSelection(text, locator, _currentPageIndex.value)
    }

    fun dismissPendingHighlight() { _pendingHighlight.value = null }
    fun dismissPendingAnnotation() { _pendingAnnotation.value = null }

    fun applyHighlight(style: AnnotationStyle, color: Int) {
        val pending = _pendingHighlight.value ?: return
        val book = _book.value ?: return
        viewModelScope.launch {
            annotationRepository.insertAnnotation(
                Annotation(
                    bookId = book.id,
                    locatorJson = locatorToJson(pending.locator),
                    selectedText = pending.text,
                    pageIndex = pending.pageIndex,
                    style = style,
                    color = color,
                    note = "",
                )
            )
        }
        _pendingHighlight.value = null
    }

    fun saveAnnotation(note: String, style: AnnotationStyle, color: Int) {
        val pending = _pendingAnnotation.value ?: return
        val book = _book.value ?: return
        viewModelScope.launch {
            annotationRepository.insertAnnotation(
                Annotation(
                    bookId = book.id,
                    locatorJson = locatorToJson(pending.locator),
                    selectedText = pending.text,
                    pageIndex = pending.pageIndex,
                    style = style,
                    color = color,
                    note = note,
                )
            )
        }
        _pendingAnnotation.value = null
    }

    fun editAnnotation(annotationId: Long, note: String, style: AnnotationStyle, color: Int) {
        viewModelScope.launch {
            annotationRepository.updateAnnotation(annotationId, note, style, color)
        }
    }

    fun removeAnnotation(annotationId: Long) {
        viewModelScope.launch { annotationRepository.deleteAnnotation(annotationId) }
    }

    /** 批注所在页码标签（如 "189 / 1987"）。页码在创建时固化，跳转用 pageIndex 即可。 */
    fun annotationPageLabel(annotation: Annotation): String {
        val total = _totalPages.value
        return if (total > 0) {
            "${annotation.pageIndex.coerceIn(0, total - 1) + 1} / $total"
        } else {
            "${annotation.pageIndex + 1}"
        }
    }

    private fun locatorToJson(locator: Locator): String =
        try { locator.toJSON().toString() } catch (_: Exception) { "{}" }

    private fun annotationToDecoration(ann: Annotation): Decoration<ReflowableWebDecorationLocation>? {
        val loc = try {
            Locator.fromJSON(JSONObject(ann.locatorJson))
        } catch (_: Exception) { null } ?: return null
        val textQuote = TextQuote(
            text = loc.text.highlight ?: ann.selectedText,
            prefix = loc.text.before.orEmpty(),
            suffix = loc.text.after.orEmpty(),
        )
        val decLoc = ReflowableWebDecorationLocation(href = loc.href, textQuote = textQuote, cssSelector = null)
        val style: Decoration.Style = when (ann.style) {
            AnnotationStyle.HIGHLIGHT -> Decoration.Style.Highlight(tint = ann.color)
            AnnotationStyle.UNDERLINE -> Decoration.Style.Underline(tint = ann.color)
            AnnotationStyle.WAVY -> WavyUnderlineStyle(tint = ann.color)
        }
        return Decoration(id = Decoration.Id("ann-${ann.id}"), location = decLoc, style = style)
    }

    /** 合并「批注」与「TTS」两组装饰后整体下发，避免互相覆盖。 */
    private fun refreshDecorations() {
        val s = _uiState.value
        if (s !is ReaderUiState.Ready || s.controller == null) return

        val annDecorations = _annotations.value.mapNotNull { annotationToDecoration(it) }
        val groups = buildMap {
            if (annDecorations.isNotEmpty()) put("annotations", annDecorations.toPersistentList())
            ttsDecor?.let { put("tts", persistentListOf(it)) }
        }

        s.controller.decorations = groups.toPersistentMap()
    }

    // Preferences
    fun applyFontSize(size: Double) {
        // 改字号前记录当前阅读位置（href + progression，二者按内容 position 定义、与字号无关），
        // 改完重排后恢复，确保不跳到章首或错页。
        val ctrl = (_uiState.value as? ReaderUiState.Ready)?.controller
        val prevLoc = ctrl?.location

        _fontSize.value = size
        val base = when (_themeKey.value) {
            "sepia" -> ReflowableWebPreferences.SepiaTheme
            "dark" -> darkBeigeTheme
            else -> ReflowableWebPreferences()
        }
        val newPrefs = base + ReflowableWebPreferences(fontSize = size, scroll = _scrollMode.value)
        _preferences.value = newPrefs
        val s = _uiState.value
        if (s is ReaderUiState.Ready && s.controller != null) s.controller.preferences = newPrefs

        // 切换字号：切到新字号对应的页数缓存键并重载，避免新旧字号的每章页数混用、
        // 以及把新字号页数写进旧字号缓存。
        _book.value?.let { book ->
            loadCachedPageCounts(book.id, size)
            if (_uiState.value is ReaderUiState.Ready) {
                _totalPages.value = pageCounts().sum()
            }
        }

        // 恢复字号调整前的阅读位置：重排（可能重建 WebView）是异步的，稍等片刻再 goTo。
        if (ctrl != null && prevLoc != null) {
            viewModelScope.launch {
                delay(300)
                ctrl.goTo(ReflowableWebGoLocation(href = prevLoc.href, progression = prevLoc.progression))
            }
        }
    }

    fun applyScrollMode(scroll: Boolean) {
        _scrollMode.value = scroll
        val merged = _preferences.value + ReflowableWebPreferences(scroll = scroll)
        _preferences.value = merged
        val s = _uiState.value
        if (s is ReaderUiState.Ready && s.controller != null) s.controller.preferences = merged
        getApplication<Application>()
            .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("reader_scroll", scroll).apply()
    }

    fun applyTheme(key: String) {
        _themeKey.value = key
        val base = when (key) {
            "sepia" -> ReflowableWebPreferences.SepiaTheme
            "dark" -> darkBeigeTheme
            else -> ReflowableWebPreferences()
        }
        val merged = base + ReflowableWebPreferences(fontSize = _fontSize.value, scroll = _scrollMode.value)
        _preferences.value = merged
        val s = _uiState.value
        if (s is ReaderUiState.Ready && s.controller != null) s.controller.preferences = merged
        getApplication<Application>()
            .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
            .edit().putString("reader_theme", key).apply()
    }

    fun applyBrightness(brightness: Float) {
        _brightness.value = brightness.coerceIn(0.2f, 1.0f)
        getApplication<Application>()
            .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("reader_brightness", _brightness.value).apply()
    }

    /** 阅读页回到前台时调用：重置计时起点，避免聊天等停留时间被算进阅读时长。 */
    fun onScreenResume() {
        readingStartTime = System.currentTimeMillis()
    }

    fun saveProgress() {
        val book = _book.value ?: return
        val now = System.currentTimeMillis()
        val elapsed = (now - readingStartTime) / 1000
        val locatorJson = getCurrentLocatorJson()
        val page = when (_uiState.value) {
            is ReaderUiState.PdfReady -> _pdfPage.value
            else -> _currentPageIndex.value
        }
        runBlocking(Dispatchers.IO) {
            try {
                bookRepository.updateReadingProgress(book.id, page, _totalPages.value, locatorJson, now)
                if (elapsed > 0) {
                    bookRepository.addReadingTime(book.id, elapsed)
                    // Record daily reading session
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
                    val sessionDao = Injector.appDatabase().dailyReadingSessionDao()
                    val existing = sessionDao.getSessionByDate(today)
                    if (existing != null) {
                        sessionDao.upsertSession(existing.copy(seconds = existing.seconds + elapsed))
                    } else {
                        sessionDao.upsertSession(DailyReadingSessionEntity(date = today, seconds = elapsed))
                    }
                    // 记录「每本书每日」的阅读时长，用于统计当日阅读最久的书
                    val bookReadingDao = Injector.appDatabase().dailyBookReadingDao()
                    val existingBook = bookReadingDao.getByDateAndBook(today, book.id)
                    if (existingBook != null) {
                        bookReadingDao.upsert(existingBook.copy(seconds = existingBook.seconds + elapsed))
                    } else {
                        bookReadingDao.upsert(DailyBookReadingEntity(date = today, bookId = book.id, seconds = elapsed))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save reading progress")
            }
        }
        getApplication<Application>()
            .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("reader_font_size", _fontSize.value.toFloat())
            .putString("reader_theme", _themeKey.value)
            .putFloat("reader_brightness", _brightness.value)
            .putBoolean("reader_scroll", _scrollMode.value)
            .apply()
        readingStartTime = now
    }

    // ── Chapter refresh / delete ──────────────────────────────────────

    fun refreshToc() {
        val book = _book.value ?: return
        if (_isRefreshingToc.value) return
        _isRefreshingToc.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val apiKeyManager = Injector.apiKeyManager()
                val importer = BookImporter(app)
                val customPatterns = apiKeyManager.getEnabledChapterPatterns()

                val epubFile = File(book.filePath)
                val siblingTxt = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}.txt")
                val isTextDerived = book.format == "TXT" || book.format == "DOCX"

                // 原生 EPUB 等格式不支持重新识别章节
                if (!siblingTxt.exists() && !isTextDerived) {
                    _tocRefreshMessage.value = "仅支持从 TXT/DOCX 导入的书籍刷新章节"
                    return@launch
                }

                // Clear previously hidden titles — refresh means re-detect everything
                apiKeyManager.clearHiddenChapterTitles(book.id)

                // Close current publication before overwriting EPUB
                activePublication?.close()
                activePublication = null

                if (!siblingTxt.exists()) {
                    // 旧版导入（源 .txt 已丢失）：先从生成的 EPUB 反推源文本，再重新识别
                    importer.reconstructSourceTxt(epubFile)
                }

                // Regenerate EPUB with fresh chapter detection (no hidden filter)
                importer.reconvertTxt(book.filePath, customPatterns, emptySet())

                // Reload the publication
                loadedBookId = null
                loadBook(book.id)

                _tocRefreshMessage.value = "章节刷新成功"
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh TOC")
                _tocRefreshMessage.value = "刷新失败: ${e.message?.take(80)}"
                // Try to reload anyway so reader isn't stuck
                loadedBookId = null
                loadBook(book.id)
            } finally {
                _isRefreshingToc.value = false
            }
        }
    }

    fun deleteChapter(title: String) {
        val book = _book.value ?: return
        if (_isRefreshingToc.value) return
        _isRefreshingToc.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val apiKeyManager = Injector.apiKeyManager()
                val importer = BookImporter(app)

                val epubFile = File(book.filePath)
                val siblingTxt = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}.txt")
                val isTextDerived = book.format == "TXT" || book.format == "DOCX"

                // 原生 EPUB 等格式不支持删除章节，直接给出提示
                if (!siblingTxt.exists() && !isTextDerived) {
                    _tocRefreshMessage.value = "仅支持从 TXT/DOCX 导入的书籍删除章节"
                    return@launch
                }

                // Add this title to hidden set, then regenerate
                apiKeyManager.addHiddenChapterTitle(book.id, title)
                val hiddenTitles = apiKeyManager.getHiddenChapterTitles(book.id)

                // Close current publication before overwriting EPUB
                activePublication?.close()
                activePublication = null

                if (!siblingTxt.exists()) {
                    // 旧版导入（源 .txt 已丢失）：先从生成的 EPUB 反推源文本，再重新识别
                    importer.reconstructSourceTxt(epubFile)
                }

                // 依据源文本重新识别章节并合并被隐藏章节。源 .txt 始终保留完整全文，
                // 因此「刷新目录」清空隐藏章节后即可恢复原来的章节划分。
                val result = importer.reconvertTxt(book.filePath, apiKeyManager.getEnabledChapterPatterns(), hiddenTitles)

                // Reload the publication
                loadedBookId = null
                loadBook(book.id)

                // 合并成功与否以 reconvertTxt 实际匹配到的隐藏章节为准；未匹配（如首章无上一章、
                // 标题差异或章节被 detectChapters 过滤）时回滚隐藏标记，避免“成功”但无变化的假象。
                _tocRefreshMessage.value = if (title in result.mergedHiddenTitles) {
                    "已合并章节到上一章: $title"
                } else {
                    apiKeyManager.removeHiddenChapterTitle(book.id, title)
                    "合并失败：未匹配到章节「$title」"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete chapter")
                _tocRefreshMessage.value = "删除失败: ${e.message?.take(80)}"
                // Try to reload anyway
                loadedBookId = null
                loadBook(book.id)
            } finally {
                _isRefreshingToc.value = false
            }
        }
    }

    // ── TTS playback ──────────────────────────────────────────────────

    // ── 听书前台服务桥接 ───────────────────────────────────────────────
    init {
        // 通知栏刷新 + 终止时停服务：观察 TTS 状态
        viewModelScope.launch {
            _ttsState.collect { s ->
                when (s) {
                    is TtsPlaybackState.Playing -> TtsServiceBridge.update(true, s.sentence)
                    is TtsPlaybackState.Paused -> TtsServiceBridge.update(false, s.sentence)
                    is TtsPlaybackState.Idle, is TtsPlaybackState.Error -> stopTtsService()
                    is TtsPlaybackState.Initializing -> {}
                }
            }
        }
        // 通知按钮 → 控制指令
        viewModelScope.launch {
            TtsServiceBridge.commands.collect { cmd ->
                when (cmd) {
                    TtsServiceBridge.Command.Stop -> stopTts()
                    TtsServiceBridge.Command.Toggle -> toggleTtsPlayPause()
                }
            }
        }
    }

    /** Toggle between play and pause. If idle, start playing from current chapter. */
    fun toggleTtsPlayPause() {
        if (!isTtsPluginEnabled()) return
        when (val s = _ttsState.value) {
            is TtsPlaybackState.Idle -> startTts()
            is TtsPlaybackState.Playing -> pauseTts()
            is TtsPlaybackState.Paused -> resumeTts()
            is TtsPlaybackState.Error -> startTts()
            is TtsPlaybackState.Initializing -> {}
        }
    }

    private fun startTts() {
        val pub = activePublication ?: run {
            _ttsState.value = TtsPlaybackState.Error("书籍未加载")
            return
        }

        val currentHref = currentLocator?.href?.toString() ?: ""
        val chapterIdx = pub.readingOrder.indexOfFirst { it.url().toString() == currentHref }
            .let { if (it < 0) 0 else it }

        val sentences = extractSentencesForChapter(pub, chapterIdx)
        if (sentences.isEmpty()) {
            _ttsState.value = TtsPlaybackState.Error("无法提取本章文本")
            return
        }

        ttsSentences = sentences
        ttsChapterIndex = chapterIdx
        val counts = pageCounts()
        ttsPrevPageCount = counts.take(chapterIdx).sum()

        // 起始句定位按模式分开：
        // - 翻页模式：二分实测「第一个渲染进度 ≥ 当前页顶」的句子（=本页第一句）。
        // - 滚动模式：二分实测「第一个渲染进度 ≥ 可见区顶」的句子（从当下可见文本开始）。
        // 二者共用二分实测（log2(N) 次 JS），长章节也毫秒级、且无插值漂移；测量失败回退字符比例。
        buildSentencePageMapping(chapterIdx, sentences.size)
        viewModelScope.launch {
            val currentGlobalPage = _currentPageIndex.value
            val state = _uiState.value as? ReaderUiState.Ready
            val ctrl = state?.controller
            val chapterUrl = pub.readingOrder.getOrNull(chapterIdx)?.url()
            // 目标：当前视口顶部的渲染进度（翻页模式=当前页顶，滚动模式=可见区顶）。
            val target = ctrl?.viewport?.progressions?.get(chapterUrl)?.start?.value
            val fallback = if (_scrollMode.value) {
                val topProg = target ?: currentLocator?.locations?.progression ?: 0.0
                ttsSentenceProgression.indexOfFirst { it >= topProg }
                    .let { if (it < 0) sentences.size - 1 else it }
            } else {
                val pageInChapter = (currentGlobalPage - ttsPrevPageCount).coerceAtLeast(0)
                ttsSentencePage.indexOfFirst { it >= pageInChapter }
                    .let { if (it < 0) sentences.size - 1 else it }
            }
            val startIdx = if (ctrl != null && target != null) {
                findFirstSentenceAtOrAfter(ctrl, target, sentences.size, fallback)
            } else {
                fallback
            }
            ttsLastFlippedPage = currentGlobalPage
            ttsCurrentIdx = startIdx

            // 翻页模式：跳回当前页保证视图一致；滚动模式：不移动屏幕，保持当前滚动位置，
            // 从当下可见内容开始朗读（startIdx 已由可见区顶部渲染进度二分定位）。
            if (!_scrollMode.value) {
                goToPage(currentGlobalPage)
            }
            beginTtsSynthesis(startIdx)
        }
    }

    /**
     * 按用户选择的 TTS 方案分发：系统引擎走本地合成，OpenAI 兼容走云端合成。
     */
    private fun beginTtsSynthesis(startIdx: Int) {
        startTtsService()
        if (Injector.apiKeyManager().getTtsProvider() == "openai") {
            startCloudSynthesis(startIdx)
        } else {
            initTtsAndSpeak(startIdx)
        }
    }

    /** 启动听书前台服务，确保熄屏/切后台朗读不中断。 */
    private fun startTtsService() {
        TtsServiceBridge.clear()
        try {
            val ctx = getApplication<Application>()
            ContextCompat.startForegroundService(ctx, Intent(ctx, TtsPlaybackService::class.java))
        } catch (_: Exception) {
            Timber.w("无法启动听书前台服务")
        }
    }

    /** 停止听书前台服务并清空通知状态。 */
    private fun stopTtsService() {
        TtsServiceBridge.clear()
        try {
            getApplication<Application>()
                .stopService(Intent(getApplication<Application>(), TtsPlaybackService::class.java))
        } catch (_: Exception) { }
    }

    // ── TTS: Batch-synthesis + PCM-merge pipeline ──────────────────────
    // Split sentences into small batches → synthesizeToFile() per batch →
    // merge WAVs at the PCM level → one clean WAV → MediaPlayer.
    // Small batches keep CloneTTS in its quality sweet spot;
    // PCM merge eliminates inter-sentence silences and gaps entirely.
    // Binder IPC calls: N sentences / TTS_BATCH_SIZE (vs N for one-by-one).
    // If synthesizeToFile is unsupported, fall back to speak().

    private fun initTtsAndSpeak(startIdx: Int) {
        if (tts != null) {
            startBatchSynthesis(startIdx)
            return
        }

        _ttsState.value = TtsPlaybackState.Initializing

        val savedEngine = Injector.apiKeyManager().getTtsEngine()
        val engineToUse = savedEngine.ifEmpty {
            try {
                Settings.Secure.getString(
                    getApplication<Application>().contentResolver,
                    "tts_default_synth"
                )
            } catch (_: Exception) { "" }
        }

        Timber.d("TTS: using engine = '$engineToUse'")

        val listener = { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    Timber.d("TTS: init OK, engine = ${t.defaultEngine}")
                    t.setSpeechRate(Injector.apiKeyManager().getTtsSpeed())
                    startBatchSynthesis(startIdx)
                } else {
                    _ttsState.value = TtsPlaybackState.Error("TTS 引擎初始化异常")
                }
            } else {
                Timber.e("TTS: init failed with status $status")
                _ttsState.value = TtsPlaybackState.Error("TTS 引擎初始化失败 (status=$status)")
            }
        }

        tts = if (engineToUse.isNotEmpty()) {
            TextToSpeech(getApplication(), listener, engineToUse)
        } else {
            TextToSpeech(getApplication(), listener)
        }
    }

    /**
     * 云端 TTS 管线：按批次（[TTS_BATCH_SIZE] 句/组）多次请求云端 API，
     * 合并为一个 WAV 文件，复用 [playChapterAudio] 播放 + 字符比例追踪。
     */
    private fun startCloudSynthesis(startIdx: Int) {
        ttsJob?.cancel()
        ttsJob = null
        releaseTtsPlayers()
        tts?.stop()
        ttsUseFallback = false
        _ttsState.value = TtsPlaybackState.Initializing

        ttsJob = viewModelScope.launch {
            try {
                val cacheDir = getApplication<Application>().cacheDir
                val total = ttsSentences.size - startIdx
                if (total <= 0) {
                    _ttsState.value = TtsPlaybackState.Error("无可朗读的句子")
                    return@launch
                }

                // 累积字符映射（与拼接后文本对齐，空格分隔；相对 startIdx）
                val cumChars = LongArray(total + 1)
                var running = 0L
                for (i in 0 until total) {
                    cumChars[i] = running
                    val text = ttsSentences[startIdx + i].trim()
                    running += text.length.toLong()
                    if (text.isNotEmpty()) running += 1L
                }
                cumChars[total] = running
                ttsCumChars = cumChars

                // 只有一句：直接单文件播放
                if (total == 1) {
                    val r = withContext(Dispatchers.IO) { synthesizeCloudRange(cacheDir, startIdx, 1) }
                    if (r == null) return@launch
                    _ttsSynthesisProgress.value = ""
                    playChapterAudio(r.file, startIdx, r.cumTimesMs)
                    return@launch
                }

                // 后续段先行后台合成（与首句并行），首句播完即可无缝接续，避免「读满头句后卡住」的空档。
                // 段切分：第 2 句单独（首句约 1~2 秒播完时已就绪）；头部剩余句合并成一批，让出 permit 给
                // 第一批次并行合成。若头部 5 句逐个单句合成，会占满 3 个 permit 分两波（2×网络延迟），
                // 第一批次要等两波做完才开跑，网络稍慢时句 3~5 处必然断档卡顿。
                val segSemaphore = Semaphore(CLOUD_TTS_CONCURRENCY)
                val later = mutableListOf<Pair<Int, Deferred<SynthRange?>>>()
                val headEnd = startIdx + CLOUD_TTS_HEAD_SENTENCES
                var segStart = startIdx + 1
                while (segStart < ttsSentences.size) {
                    val segCount = when {
                        // 第 2 句单独：首句约 1~2 秒播完时已就绪，可直接接上。
                        segStart == startIdx + 1 -> 1
                        // 头部剩余句（句 2..headEnd-1）合成一批：把原来的 N 个单句请求压缩成 1 次，
                        // 让第 3 个 permit 立即给第一批次，消除「两波合成 + 批次等 permit」的 2×延迟。
                        segStart < headEnd ->
                            minOf(headEnd - segStart, ttsSentences.size - segStart)
                        else -> minOf(TTS_BATCH_SIZE, ttsSentences.size - segStart)
                    }
                    val from = segStart
                    later.add(from to async(Dispatchers.IO) {
                        segSemaphore.withPermit {
                            synthesizeCloudRange(cacheDir, from, segCount, batchSize = TTS_BATCH_SIZE)
                        }
                    })
                    segStart += segCount
                }

                // 首句单独合成（把等待压到接近单句生成时间），到手即播。
                _ttsSynthesisProgress.value = "正在准备朗读…"
                val first = withContext(Dispatchers.IO) {
                    synthesizeCloudRange(cacheDir, startIdx, 1)
                }
                if (first == null) {
                    later.forEach { it.second.cancel() }
                    return@launch
                }

                playStreaming(first, later, startIdx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Cloud TTS pipeline failed")
                _ttsState.value = TtsPlaybackState.Error("云端合成失败: ${e.message?.take(60)}")
            }
        }
    }

    /** 云端合成区间：合并后的 WAV 文件 + 该区间内每句的累计起始时间（毫秒）。 */
    private data class SynthRange(val file: File, val cumTimesMs: LongArray)

    /** 云端流式中的一段：句偏移、每句累计时间、WAV 文件与播放器。 */
    private data class StreamSeg(
        val offset: Int,
        val times: LongArray,
        val file: File,
        val player: MediaPlayer,
    )

    /** 解析标准 44 字节 PCM WAV 头，返回音频时长（毫秒）；解析失败返回 0。 */
    private fun wavDurationMs(file: File): Long {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44) return 0L
                val h = ByteArray(44)
                raf.readFully(h)
                val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                if (b.getInt() != 0x46464952) return 0L // "RIFF"
                b.getInt()                              // 文件大小
                if (b.getInt() != 0x45564157) return 0L // "WAVE"
                b.position(22)
                b.getShort()                            // 声道数（忽略）
                b.getInt()                              // 采样率（忽略）
                val byteRate = b.getInt().toLong()      // 字节率
                b.position(40)
                val dataSize = b.getInt().toLong()      // 数据块大小
                if (byteRate <= 0) return 0L
                dataSize * 1000L / byteRate
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 检测 WAV 内所有静音段 [startMs, endMs]（按时间升序，相对文件起点）。
     * 静音段 = 连续低幅值采样 >= 200ms。仅处理 8/16-bit PCM，解析失败返回 null。
     * 句边界由 buildCumTimesMs 按字符比例估计位置就近匹配，这里只做纯检测。
     */
    private fun detectSilenceRuns(file: File): List<LongArray>? {
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (bytes.size < 44) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt() != 0x46464952) return null // "RIFF"
        buf.getInt()
        if (buf.getInt() != 0x45564157) return null // "WAVE"

        var channels = 1
        var sampleRate = 16000
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (buf.position() + 8 <= bytes.size) {
            val id = ByteArray(4)
            buf.get(id)
            val size = buf.getInt()
            when (String(id)) {
                "fmt " -> {
                    val s = buf.position()
                    buf.getShort() // audioFormat
                    channels = buf.getShort().toInt()
                    sampleRate = buf.getInt()
                    buf.getInt() // byteRate
                    buf.getShort() // blockAlign
                    bits = buf.getShort().toInt()
                    buf.position(s + size)
                }
                "data" -> {
                    dataOffset = buf.position()
                    dataSize = size
                    break
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        if (dataOffset < 0 || dataSize <= 0 || (bits != 8 && bits != 16)) return null
        val bps = bits / 8
        val frame = bps * channels
        if (frame <= 0) return null
        val total = dataSize / frame
        if (total <= 0) return null
        val threshold = if (bits == 8) 4 else 200

        fun amp(idx: Int): Int {
            val base = dataOffset + idx * frame
            var mx = 0
            for (ch in 0 until channels) {
                val off = base + ch * bps
                val v = if (bits == 8) {
                    (bytes[off].toInt() and 0xFF) - 128
                } else {
                    (bytes[off].toInt() and 0xFF) or (bytes[off + 1].toInt() shl 8)
                }
                val a = if (v < 0) -v else v
                if (a > mx) mx = a
            }
            return mx
        }

        val minSilence = (sampleRate * 200L / 1000L).toInt().coerceAtLeast(1)
        val runs = ArrayList<LongArray>() // [startMs, endMs]
        var i = 0
        while (i < total) {
            if (amp(i) <= threshold) {
                val start = i
                while (i < total && amp(i) <= threshold) i++
                if (i - start >= minSilence) {
                    runs.add(longArrayOf(
                        start.toLong() * 1000L / sampleRate,
                        i.toLong() * 1000L / sampleRate,
                    ))
                }
            } else {
                i++
            }
        }
        // 合并间隙 <= 100ms 的相邻静音段：句边界「。，」的句号停顿 + 逗号停顿常被极短间隙拆成两段，
        // 不合并会取到前一段的结束（偏早半个停顿），导致边界提前。
        val merged = ArrayList<LongArray>()
        for (r in runs) {
            if (merged.isEmpty()) {
                merged.add(r)
                continue
            }
            val prev = merged[merged.size - 1]
            if (r[0] - prev[1] <= 100L) {
                prev[1] = r[1]
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    /**
     * 由各批次 WAV 的真实时长计算每句累计起始时间（相对 [fromIdx]）。
     * 「字符比例锚定 + 静音精修」：先按批内字符比例估计每句边界的粗略位置，再在估计位置附近
     * 找最近的静音段结束（= 下一句首音开始）作为精确边界。某句附近无静音段就退回该句的字符
     * 比例估计值（不整批回退）。返回 size = count+1 的数组，末元素为整段总时长。
     */
    private fun buildCumTimesMs(
        files: List<File>,
        batches: List<IntRange>,
        fromIdx: Int,
        count: Int,
    ): LongArray {
        val cum = LongArray(count + 1)
        var batchStart = 0L
        for (bi in batches.indices) {
            val range = batches[bi]
            val dur = wavDurationMs(files[bi])
            val n = range.last - range.first + 1

            // 批内每句字符数（句末标点权重 +1），用于估计句边界位置。
            val chars = LongArray(n)
            var totalChars = 0L
            for (k in 0 until n) {
                val t = ttsSentences[range.first + k].trim()
                val c = t.length.toLong() + (if (t.isNotEmpty()) 1L else 0L)
                chars[k] = c
                totalChars += c
            }
            // 相对批文件起点的每句估计起始毫秒。
            val est = LongArray(n + 1)
            var acc = 0L
            for (k in 0 until n) {
                est[k] = if (totalChars > 0) dur * acc / totalChars else 0L
                acc += chars[k]
            }
            est[n] = dur

            // 静音精修：每个估计边界附近选「最长」静音段结束（句末「。，」明显长于句内逗号），
            // 并保持单调递增，避免相邻边界抢同一个静音段。某句附近无静音段则退回字符比例估计。
            val runs = detectSilenceRuns(files[bi])
            // boundary[k] = 句 k 与 k+1 之间边界的毫秒；-1 表示精修失败，退回该句的字符比例估计。
            val boundary = LongArray(n - 1) { -1L }
            if (runs != null && runs.isNotEmpty()) {
                var prev = -1L
                for (k in 0 until n - 1) {
                    val target = est[k + 1]
                    // 窗口 = 相邻句估计间隔的一半（下限 600ms），足够容纳「。，」停顿本身。
                    val half = ((est[k + 1] - est[k]) / 2).coerceAtLeast(600L)
                    var best = -1L
                    for (r in runs) {
                        val e = r[1]
                        if (e <= prev) continue
                        if (e < target - half || e > target + half) continue
                        // 选窗口内「最靠后」的静音段结束（= 下一句首音）。句边界是「句号停顿+逗号停顿」，
                        // 逗号停顿结束才是真正的下一句起点；选最长会在两者被拆开时误选句号停顿（偏早→提前翻页）。
                        if (e > best) best = e
                    }
                    if (best >= 0) {
                        boundary[k] = best
                        prev = best
                    }
                }
            }

            // 逐句回退：精修成功的句用静音边界，失败的句只回退该句的字符比例估计（不整批回退，
            // 否则字符比例误差会在批内逐句累积，到批尾放大到半页，造成翻页提前/延后）。
            var start = 0L
            for (k in 0 until n) {
                cum[range.first + k - fromIdx] = batchStart + start
                start = when {
                    k >= n - 1 -> dur
                    boundary[k] >= 0 -> boundary[k]
                    else -> est[k + 1]
                }
            }
            batchStart += dur
        }
        cum[count] = batchStart
        return cum
    }

    /** 在递增的累计时间数组里二分，返回 pos 所在的句子相对索引（0..size-2）。 */
    private fun indexForTime(times: LongArray, pos: Long): Int {
        if (times.size <= 1) return 0
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, times.size - 2)
    }

    /**
     * 云端合成句子区间 [fromIdx, fromIdx+count) 并合并为单个 WAV。失败返回 null。
     * 运行于 [Dispatchers.IO]；每批合成后裁剪静音；并发 [CLOUD_TTS_CONCURRENCY]。
     */
    private suspend fun synthesizeCloudRange(
        cacheDir: File,
        fromIdx: Int,
        count: Int,
        batchSize: Int = TTS_BATCH_SIZE,
    ): SynthRange? {
        val end = minOf(fromIdx + count, ttsSentences.size)
        if (end <= fromIdx) return null

        val batches = mutableListOf<IntRange>()
        var b = fromIdx
        while (b < end) {
            val e = minOf(b + batchSize, end)
            batches.add(b until e)
            b = e
        }

        val tempFiles = mutableListOf<File>()
        try {
            val completed = AtomicInteger(0)
            val semaphore = Semaphore(CLOUD_TTS_CONCURRENCY)
            val files = coroutineScope {
                batches.map { range ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val sb = StringBuilder()
                            for (si in range) {
                                val text = ttsSentences[si].trim()
                                sb.append(text)
                                if (text.isNotEmpty()) sb.append('，')
                            }
                            val bytes = trimWavSilence(
                                Injector.cloudTtsClient().synthesize(sb.toString())
                            )
                            val f = File(cacheDir, "tts_cloud_${System.nanoTime()}.wav")
                            f.writeBytes(bytes)
                            _ttsSynthesisProgress.value =
                                "云端合成中… ${completed.incrementAndGet()}/${batches.size} 组"
                            f
                        }
                    }
                }.awaitAll()
            }
            tempFiles.addAll(files)

            val merged = mergeWavFiles(tempFiles, cacheDir) ?: return null
            val cumTimes = buildCumTimesMs(files, batches, fromIdx, count)
            Timber.d("TTS cloud OK: ${batches.size} 组 → ${merged.length()} bytes")
            return SynthRange(merged, cumTimes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Cloud TTS synthesis failed")
            _ttsState.value = TtsPlaybackState.Error("云端合成失败: ${e.message?.take(60)}")
            return null
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    /** Probe → batch synthesis + merge or fall back to speak(). */
    private fun startBatchSynthesis(startIdx: Int) {
        ttsJob?.cancel()
        ttsJob = null
        releaseTtsPlayers()
        tts?.stop()
        ttsUseFallback = false
        _ttsState.value = TtsPlaybackState.Initializing

        ttsJob = viewModelScope.launch {
            try {
                val t = tts
                if (t == null) {
                    _ttsState.value = TtsPlaybackState.Error("TTS 引擎未初始化")
                    return@launch
                }

                val cacheDir = getApplication<Application>().cacheDir

                // Probe one sentence to check synthesizeToFile support
                val probeIdx = (startIdx until ttsSentences.size).firstOrNull {
                    ttsSentences[it].trim().isNotEmpty()
                }
                if (probeIdx == null) {
                    _ttsState.value = TtsPlaybackState.Error("无可朗读的句子")
                    return@launch
                }
                val probeFile = File(cacheDir, "tts_probe_${System.nanoTime()}.wav")
                val probeResult = withContext(Dispatchers.IO) {
                    t.synthesizeToFile(ttsSentences[probeIdx].trim(), android.os.Bundle(), probeFile, probeIdx.toString())
                }

                if (probeResult == TextToSpeech.SUCCESS && probeFile.length() > 44) {
                    probeFile.delete()
                    Timber.d("TTS: probe OK — batch synthesis pipeline")
                    val merged = withContext(Dispatchers.IO) {
                        synthesizeAndMerge(t, cacheDir, startIdx)
                    }
                    if (merged == null) {
                        _ttsState.value = TtsPlaybackState.Error("语音合成失败")
                        return@launch
                    }
                    playChapterAudio(merged.first, startIdx, merged.second)
                } else {
                    probeFile.delete()
                    Timber.d("TTS: probe FAILED — fallback to speak()")
                    ttsUseFallback = true
                    fallbackQueueSpeak(startIdx)
                }
            } catch (e: Exception) {
                Timber.e(e, "TTS batch synthesis pipeline failed")
                _ttsState.value = TtsPlaybackState.Error("语音合成失败: ${e.message?.take(60)}")
            }
        }
    }

    /**
     * Synthesize sentences [startIdx..] in batches, merge into one WAV.
     * 返回 (合并文件, 每句累计起始时间)：累计时间用各批真实时长折算，替代整章字符比例的累积漂移。
     * Called on [Dispatchers.IO].
     */
    private fun synthesizeAndMerge(tts: TextToSpeech, cacheDir: File, startIdx: Int): Pair<File, LongArray>? {
        val sentences = ttsSentences.subList(startIdx, ttsSentences.size)
        val total = sentences.size
        if (total == 0) return null

        // Partition into batches
        val batches = mutableListOf<IntRange>()
        var b = startIdx
        while (b < ttsSentences.size) {
            val e = minOf(b + TTS_BATCH_SIZE, ttsSentences.size)
            batches.add(b until e)
            b = e
        }
        val batchCount = batches.size

        val batchFiles = mutableListOf<File>()

        for ((bi, range) in batches.withIndex()) {
            _ttsSynthesisProgress.value = "正在准备朗读… ${bi + 1}/$batchCount 组"

            val sb = StringBuilder()
            for (si in range) {
                val text = ttsSentences[si].trim()
                sb.append(text)
                if (text.isNotEmpty()) sb.append('，')
            }

            val file = File(cacheDir, "tts_batch_${bi}_${System.nanoTime()}.wav")
            val result = tts.synthesizeToFile(sb.toString(), android.os.Bundle(), file, "batch$bi")
            if (result == TextToSpeech.SUCCESS && file.length() > 44) {
                batchFiles.add(file)
            } else {
                file.delete()
                Timber.w("TTS batch synth FAIL [batch $bi]: result=$result size=${file.length()}")
                batchFiles.forEach { it.delete() }
                return null
            }
        }

        _ttsSynthesisProgress.value = "正在合并音频…"

        // Build cumChars: cumChars[k] = chars before sentence k in the merged text
        val cumChars = LongArray(total + 1)
        var running = 0L
        for (i in 0 until total) {
            cumChars[i] = running
            running += sentences[i].trim().length.toLong()
            if (sentences[i].trim().isNotEmpty()) running += 1L // space separator
        }
        cumChars[total] = running
        ttsCumChars = cumChars

        // 用各批真实时长（而非整章字符比例）折算每句累计时间，翻页/高光定位不再随时间累积漂移。
        val cumTimesMs = buildCumTimesMs(batchFiles, batches, startIdx, total)

        // Merge batch WAVs into one file
        val merged = mergeWavFiles(batchFiles, cacheDir)

        // Clean up batch temp files (safe: mergeWavFiles copies single files, reuses only on failure)
        batchFiles.forEach { it.delete() }

        _ttsSynthesisProgress.value = ""
        Timber.d("TTS merge OK: $batchCount batches → ${merged?.length() ?: 0} bytes")
        return if (merged == null) null else merged to cumTimesMs
    }

    /**
     * Merge multiple PCM WAV files into one by concatenating raw audio data.
     * All files must share the same format (sample rate, channels, bit depth).
     */
    private fun mergeWavFiles(files: List<File>, cacheDir: File): File? {
        if (files.isEmpty()) return null
        // Single batch: copy so caller can safely delete the original
        if (files.size == 1) {
            val out = File(cacheDir, "tts_merged_${System.nanoTime()}.wav")
            files[0].copyTo(out, overwrite = true)
            return out
        }

        val first = files[0]
        // Parse the first file's WAV structure to find the "data" chunk
        val raf = RandomAccessFile(first, "r")
        val header = ByteArray(44)
        raf.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // Validate RIFF header
        val riff = ByteArray(4); buf.get(riff)
        if (String(riff) != "RIFF") { raf.close(); return null }
        buf.getInt() // file size (will recalculate)
        val wave = ByteArray(4); buf.get(wave)
        if (String(wave) != "WAVE") { raf.close(); return null }

        // Read fmt chunk
        val fmtId = ByteArray(4); buf.get(fmtId)
        if (String(fmtId) != "fmt ") { raf.close(); return null }
        val fmtSize = buf.getInt()
        val fmtData = ByteArray(fmtSize)
        buf.get(fmtData)

        // Find data chunk
        var dataOffset = 12 + 8 + fmtSize
        while (true) {
            raf.seek(dataOffset.toLong())
            val chunkId = ByteArray(4)
            if (raf.read(chunkId) < 4) break
            val chunkSize = ByteBuffer.wrap(ByteArray(4)).order(ByteOrder.LITTLE_ENDIAN).apply {
                raf.readFully(array()); position(0)
            }.getInt()
            if (String(chunkId) == "data") {
                dataOffset += 8 // skip "data" + size
                break
            }
            dataOffset += 8 + chunkSize
        }
        val firstDataLen = raf.length() - dataOffset
        raf.close()

        // Calculate total PCM size
        var totalPcm = firstDataLen
        for (i in 1 until files.size) {
            totalPcm += findDataChunkOffset(files[i])?.let { files[i].length() - it } ?: 0L
        }

        // Write merged file
        val outFile = File(cacheDir, "tts_merged_${System.nanoTime()}.wav")
        val out = RandomAccessFile(outFile, "rw")
        out.setLength(0)

        // Rebuild header
        val totalFileSize = 4 + (8 + fmtSize) + (8 + totalPcm) // "WAVE" + fmt chunk + data chunk
        val headerBuf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put("RIFF".toByteArray())
        headerBuf.putInt(totalFileSize.toInt())
        headerBuf.put("WAVE".toByteArray())
        headerBuf.put("fmt ".toByteArray())
        headerBuf.putInt(fmtSize)
        headerBuf.put(fmtData)
        headerBuf.put("data".toByteArray())
        headerBuf.putInt(totalPcm.toInt())
        out.write(headerBuf.array())

        // Append PCM from each file
        val pcmBuf = ByteArray(65536)
        for (file in files) {
            val offset = findDataChunkOffset(file) ?: continue
            val inp = RandomAccessFile(file, "r")
            inp.seek(offset)
            var remaining = file.length() - offset
            while (remaining > 0) {
                val n = inp.read(pcmBuf, 0, minOf(remaining, pcmBuf.size.toLong()).toInt())
                if (n < 0) break
                out.write(pcmBuf, 0, n)
                remaining -= n
            }
            inp.close()
        }
        out.close()
        return outFile
    }

    /** Return byte offset of the "data" chunk in a WAV file, or null. */
    private fun findDataChunkOffset(file: File): Long? {
        val raf = RandomAccessFile(file, "r")
        raf.seek(12) // skip RIFF header
        val id = ByteArray(4)
        val sz = ByteArray(4)
        while (raf.filePointer < raf.length()) {
            if (raf.read(id) < 4) break
            if (raf.read(sz) < 4) break
            val size = ByteBuffer.wrap(sz).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (String(id) == "data") {
                val off = raf.filePointer
                raf.close()
                return off
            }
            raf.seek(raf.filePointer + size)
        }
        raf.close()
        return null
    }

    /**
     * 去掉 WAV 首尾的静音段（数字静音/低幅度），返回裁剪后的 WAV 字节。
     * 云端 TTS（如 MiMo）每段开头常带一段静音，若不裁掉，按时间比例推算的
     * 高光/翻页会比实际朗读提前。裁剪后「字符 0 = 语音起点」，比例映射才准确。
     * 仅处理 8/16-bit PCM；其它格式原样返回。
     */
    private fun trimWavSilence(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt() != 0x46464952) return bytes // "RIFF"
        buf.getInt() // 文件大小
        if (buf.getInt() != 0x45564157) return bytes // "WAVE"

        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0
        while (buf.position() + 8 <= bytes.size) {
            val chunkId = ByteArray(4)
            buf.get(chunkId)
            val chunkSize = buf.getInt()
            val id = String(chunkId)
            when {
                id == "fmt " -> {
                    val fmtStart = buf.position()
                    buf.getShort() // audioFormat
                    channels = buf.getShort().toInt()
                    sampleRate = buf.getInt()
                    buf.getInt() // byteRate
                    buf.getShort() // blockAlign
                    bitsPerSample = buf.getShort().toInt()
                    buf.position(fmtStart + chunkSize)
                }
                id == "data" -> {
                    dataOffset = buf.position()
                    dataSize = chunkSize
                    break
                }
                else -> buf.position(buf.position() + chunkSize + (chunkSize and 1))
            }
        }
        if (dataOffset < 0 || dataSize <= 0 || (bitsPerSample != 8 && bitsPerSample != 16)) {
            return bytes
        }

        val bytesPerSample = bitsPerSample / 8
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return bytes
        val sampleCount = dataSize / frameBytes
        if (sampleCount == 0) return bytes

        fun amplitudeAt(idx: Int): Int {
            val base = dataOffset + idx * frameBytes
            var max = 0
            for (ch in 0 until channels) {
                val off = base + ch * bytesPerSample
                val v = if (bitsPerSample == 8) {
                    (bytes[off].toInt() and 0xFF) - 128
                } else {
                    (bytes[off].toInt() and 0xFF) or (bytes[off + 1].toInt() shl 8)
                }
                val av = if (v < 0) -v else v
                if (av > max) max = av
            }
            return max
        }

        val threshold = if (bitsPerSample == 8) 4 else 200
        var first = -1
        for (i in 0 until sampleCount) {
            if (amplitudeAt(i) > threshold) { first = i; break }
        }
        if (first < 0) return bytes // 整段静音，不裁剪
        var last = first
        for (i in sampleCount - 1 downTo first + 1) {
            if (amplitudeAt(i) > threshold) { last = i; break }
        }

        val newDataSize = (last - first + 1) * frameBytes
        val newData = ByteArray(newDataSize)
        System.arraycopy(bytes, dataOffset + first * frameBytes, newData, 0, newDataSize)

        // 重建为标准 44 字节 PCM 头
        val out = ByteArray(44 + newDataSize)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        ob.put("RIFF".toByteArray())
        ob.putInt(36 + newDataSize)
        ob.put("WAVE".toByteArray())
        ob.put("fmt ".toByteArray())
        ob.putInt(16)
        ob.putShort(1) // PCM
        ob.putShort(channels.toShort())
        ob.putInt(sampleRate)
        ob.putInt(sampleRate * frameBytes)
        ob.putShort(frameBytes.toShort())
        ob.putShort(bitsPerSample.toShort())
        ob.put("data".toByteArray())
        ob.putInt(newDataSize)
        ob.put(newData)
        return out
    }

    /** 释放所有 TTS 播放器与临时文件（含云端流式的各段）。 */
    private fun releaseTtsPlayers() {
        val hadSegs = ttsStreamSegs.isNotEmpty()
        ttsStreamSegs.forEach { seg ->
            try { seg.player.release() } catch (_: Exception) {}
            seg.file.delete()
        }
        ttsStreamSegs = mutableListOf()
        if (!hadSegs) {
            try { ttsPlayer?.release() } catch (_: Exception) {}
        }
        ttsPlayer = null
        try { ttsTailPlayer?.release() } catch (_: Exception) {}
        ttsTailPlayer = null
        ttsChapterFile?.delete()
        ttsChapterFile = null
        ttsTailFile?.delete()
        ttsTailFile = null
        ttsTailActive = false
        ttsHeadCount = 0
        ttsTailTimesMs = null
    }

    /** 对播放器应用当前语速（保真变速）。 */
    private fun applyTtsSpeedTo(player: MediaPlayer) {
        val speed = Injector.apiKeyManager().getTtsSpeed()
        if (speed != 1.0f) {
            try { player.playbackParams = player.playbackParams.setSpeed(speed) } catch (_: Exception) {}
        }
    }

    /** 章节结束：有下一章则续读，否则回到 Idle。 */
    private fun finishTtsChapterOrIdle() {
        ttsCurrentIdx = ttsSentences.size - 1
        val pub = activePublication
        if (pub != null && ttsChapterIndex + 1 < pub.readingOrder.size) {
            nextChapter(pub)
        } else {
            _ttsState.value = TtsPlaybackState.Idle
        }
    }

    /**
     * 云端流式播放：首句已就绪，后续段（中段 + 尾段）后台合成后按序用
     * [MediaPlayer.setNextMediaPlayer] 无缝衔接。定位按各段真实累计时间二分
     * （时间→句子）。某段失败则在其前一段结束后按章节结束处理。
     */
    private suspend fun playStreaming(
        first: SynthRange,
        later: List<Pair<Int, Deferred<SynthRange?>>>,
        startIdx: Int,
    ) {
        val totalSent = ttsSentences.size
        val segCount = 1 + later.size

        val firstPlayer = MediaPlayer().apply {
            setDataSource(first.file.absolutePath)
            setOnErrorListener { _, what, extra ->
                Timber.e("MediaPlayer error: what=$what extra=$extra")
                _ttsState.value = TtsPlaybackState.Error("播放错误")
                true
            }
            prepare()
        }
        ttsPlayer = firstPlayer
        first.file.deleteOnExit()
        ttsTailActive = false
        ttsHeadCount = 0
        ttsTailTimesMs = null
        applyTtsSpeedTo(firstPlayer)

        val segs = ttsStreamSegs
        segs.clear()
        ttsSingleTimesMs = null
        segs.add(StreamSeg(startIdx, first.cumTimesMs, first.file, firstPlayer))

        ttsCurrentIdx = startIdx
        ttsStartIdx = startIdx
        _ttsSynthesisProgress.value = ""
        val firstSent = ttsSentences.getOrNull(startIdx)?.trim() ?: ""
        _ttsState.value = TtsPlaybackState.Playing(startIdx, totalSent, firstSent)
        applyTtsHighlight(startIdx)

        coroutineScope {
            // 后台：按顺序等待后续段，就绪后创建播放器并按「完成监听」接续。
            // 不用 setNextMediaPlayer：上一段可能只是「尚未开播」（刚被链上、正在等它前面那段播完），
            // 此时 isPlaying==false 会被误判成「已播完」而提前 start，导致多段叠音。
            var ended = false
            val prep = launch {
                var prev: MediaPlayer = firstPlayer
                for (li in later.indices) {
                    val (offset, deferred) = later[li]

                    // 在 await 之前挂「prev 是否已播完」标记，捕捉合成期间 prev 提前播完的情况。
                    var finished = false
                    prev.setOnCompletionListener { finished = true }

                    val range = try {
                        deferred.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Cloud segment synthesis failed")
                        null
                    }
                    if (range == null) {
                        if (finished) finishTtsChapterOrIdle()
                        break
                    }

                    val p = MediaPlayer().apply {
                        setDataSource(range.file.absolutePath)
                        setOnErrorListener { _, what, extra ->
                            Timber.e("MediaPlayer error: what=$what extra=$extra")
                            _ttsState.value = TtsPlaybackState.Error("播放错误")
                            true
                        }
                        prepare()
                    }
                    applyTtsSpeedTo(p)
                    range.file.deleteOnExit()
                    segs.add(StreamSeg(offset, range.cumTimesMs, range.file, p))

                    if (finished) {
                        // 上一段已播完（本段合成较慢、链已断）：直接启动本段。
                        p.start()
                    } else {
                        // 上一段还没播完：等它真正播完再自动接续本段，避免叠音。
                        prev.setOnCompletionListener { p.start() }
                    }
                    prev = p
                    _ttsSynthesisProgress.value = ""
                }
                // 最后一段播完即本章结束。
                prev.setOnCompletionListener { finishTtsChapterOrIdle() }
                ended = true
            }

            firstPlayer.start()

            // 定位轮询：按活动段的时间数组二分
            try {
                var activeIdx = 0
                while (true) {
                    delay(100)

                    val next = activeIdx + 1
                    if (next < segs.size && segs[next].player.isPlaying) {
                        activeIdx = next
                        ttsPlayer = segs[next].player
                    }

                    val seg = segs.getOrNull(activeIdx) ?: break
                    val player = seg.player
                    if (!player.isPlaying) {
                        if (activeIdx >= segCount - 1) break
                        if (ended && segs.getOrNull(activeIdx + 1) == null) break
                        continue
                    }

                    try {
                        val pos = player.currentPosition
                        if (pos < 0) continue
                        val si = (indexForTime(seg.times, pos.toLong()) + seg.offset).coerceIn(0, totalSent - 1)

                        if (si != ttsCurrentIdx) {
                            ttsCurrentIdx = si
                            val sent = ttsSentences[si].trim()
                            _ttsState.value = TtsPlaybackState.Playing(si, totalSent, sent)

                            keepSentenceInView(si)
                            withContext(Dispatchers.Main) { applyTtsHighlight(si) }
                        }
                    } catch (_: Exception) { break }
                }
            } finally {
                prep.cancel()
                later.forEach { it.second.cancel() }
            }
        }
    }

    /**
     * Play the chapter WAV via MediaPlayer with time-based sentence tracking.
     * Polls position every 100ms; maps time→char→sentence via binary search.
     */
    private fun playChapterAudio(file: File, startIdx: Int, cumTimesMs: LongArray? = null) {
        releaseTtsPlayers()
        ttsSingleTimesMs = cumTimesMs
        ttsChapterFile = file
        file.deleteOnExit()

        val player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                ttsCurrentIdx = ttsSentences.size - 1
                val pub = activePublication
                if (pub != null && ttsChapterIndex + 1 < pub.readingOrder.size) {
                    nextChapter(pub)
                } else {
                    _ttsState.value = TtsPlaybackState.Idle
                }
            }
            setOnErrorListener { _, what, extra ->
                Timber.e("MediaPlayer error: what=$what extra=$extra")
                _ttsState.value = TtsPlaybackState.Error("播放错误")
                true
            }
            prepare()
            start()
        }
        ttsPlayer = player

        // Apply speed
        val speed = Injector.apiKeyManager().getTtsSpeed()
        if (speed != 1.0f) {
            try { player.playbackParams = player.playbackParams.setSpeed(speed) } catch (_: Exception) {}
        }

        val cumChars = ttsCumChars
        val totalSent = ttsSentences.size
        ttsCurrentIdx = startIdx
        ttsStartIdx = startIdx

        // Show first sentence
        val firstSent = ttsSentences.getOrNull(startIdx)?.trim() ?: ""
        _ttsState.value = TtsPlaybackState.Playing(startIdx, totalSent, firstSent)
        applyTtsHighlight(startIdx)

        // Polling loop: map time→char proportion→sentence index
        ttsJob = viewModelScope.launch(Dispatchers.Default) {
            while (player.isPlaying) {
                delay(100)
                try {
                    val pos = player.currentPosition

                    val si: Int
                    if (cumTimesMs != null) {
                        si = (indexForTime(cumTimesMs, pos.toLong()) + ttsStartIdx).coerceIn(0, totalSent - 1)
                    } else {
                        val dur = player.duration
                        if (dur <= 0) continue
                        val totalChars = cumChars.lastOrNull() ?: 0L
                        if (totalChars <= 0) continue

                        val targetChar = (pos.toDouble() / dur * totalChars).toLong()

                        // Binary search in cumChars
                        var lo = 0
                        var hi = cumChars.size - 1
                        while (lo < hi) {
                            val mid = (lo + hi + 1) ushr 1
                            if (cumChars[mid] <= targetChar) lo = mid else hi = mid - 1
                        }
                        si = (lo + ttsStartIdx).coerceIn(0, totalSent - 1)
                    }

                    if (si != ttsCurrentIdx) {
                        ttsCurrentIdx = si
                        val sent = ttsSentences[si].trim()
                        _ttsState.value = TtsPlaybackState.Playing(si, totalSent, sent)

                        keepSentenceInView(si)
                        withContext(Dispatchers.Main) { applyTtsHighlight(si) }
                    }
                } catch (_: Exception) { break }
            }
        }
    }

    /** Seek 到 [targetIdx]：流式按活动段内时间二分；单文件按字符比例。 */
    private fun seekToSentence(targetIdx: Int) {
        val segs = ttsStreamSegs
        if (segs.isNotEmpty()) {
            // 流式：定位到包含当前句的活动段，并在其内 seek（clamp 到该段范围）
            var k = 0
            for (i in segs.indices) {
                if (segs[i].offset <= ttsCurrentIdx) k = i else break
            }
            val seg = segs.getOrNull(k) ?: return
            val rel = (targetIdx - seg.offset).coerceIn(0, seg.times.size - 1)
            seg.player.seekTo(seg.times[rel].toInt())
            return
        }

        // 单文件：优先用真实逐句时间（本地整章 WAV 按批折算），否则退回字符比例。
        val times = ttsSingleTimesMs
        val player = ttsPlayer ?: return
        if (times != null && times.size > 1) {
            val subIdx = (targetIdx - ttsStartIdx).coerceIn(0, times.size - 2)
            player.seekTo(times[subIdx].toInt())
            return
        }
        val cumChars = ttsCumChars
        if (cumChars.size < 2) return
        val totalChars = cumChars.lastOrNull() ?: return
        if (totalChars <= 0) return
        val subIdx = (targetIdx - ttsStartIdx).coerceIn(0, cumChars.size - 2)
        val targetChar = cumChars[subIdx]
        val dur = player.duration
        if (dur <= 0) return
        player.seekTo((targetChar.toDouble() / totalChars * dur).toInt())
    }

    /** Shared sentence-start UI update (used by fallback path). */
    private fun onSentenceStart(idx: Int, total: Int) {
        ttsCurrentIdx = idx
        _ttsState.value = TtsPlaybackState.Playing(idx, total, ttsSentences[idx].trim())
        keepSentenceInView(idx)
        applyTtsHighlight(idx)
    }

    // ── Fallback: traditional speak() queue ─────────────────────────────

    /** Queue sentences via [TextToSpeech.speak] — fallback path. */
    private fun fallbackQueueSpeak(startIdx: Int) {
        val t = this.tts ?: return
        t.stop()
        t.setOnUtteranceProgressListener(fallbackTtsListener)
        ttsCurrentIdx = startIdx
        for (i in startIdx until ttsSentences.size) {
            val text = ttsSentences[i].trim()
            if (text.isEmpty()) continue
            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, i.toString())
            val queueMode = if (i == startIdx) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            t.speak(text, queueMode, params, i.toString())
        }
    }

    private val fallbackTtsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val idx = utteranceId?.toIntOrNull() ?: return
            onSentenceStart(idx, ttsSentences.size)
        }

        override fun onDone(utteranceId: String?) {
            val idx = utteranceId?.toIntOrNull() ?: return
            ttsCurrentIdx = idx
            if (idx >= ttsSentences.size - 1) {
                val pub = activePublication
                if (pub != null && ttsChapterIndex + 1 < pub.readingOrder.size) {
                    nextChapter(pub)
                } else {
                    _ttsState.value = TtsPlaybackState.Idle
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Timber.w("TTS utterance error: $utteranceId")
        }
    }

    // ── TTS playback controls ──────────────────────────────────────────

    private fun pauseTts() {
        if (ttsUseFallback) {
            tts?.stop()
        } else {
            ttsPlayer?.pause()
        }
        val s = _ttsState.value
        if (s is TtsPlaybackState.Playing) {
            _ttsState.value = TtsPlaybackState.Paused(s.sentenceIndex, s.totalSentences, s.sentence)
        }
    }

    private fun resumeTts() {
        val s = _ttsState.value
        if (s !is TtsPlaybackState.Paused) return
        _ttsState.value = TtsPlaybackState.Playing(s.sentenceIndex, s.totalSentences, s.sentence)
        applyTtsHighlight(s.sentenceIndex)
        if (ttsUseFallback) {
            fallbackQueueSpeak(s.sentenceIndex)
        } else {
            ttsPlayer?.start()
        }
    }

    fun stopTts() { stopTtsInternal() }

    private fun stopTtsInternal() {
        ttsJob?.cancel()
        ttsJob = null
        if (ttsUseFallback) {
            tts?.stop()
        } else {
            releaseTtsPlayers()
        }
        ttsUseFallback = false
        _ttsState.value = TtsPlaybackState.Idle
        ttsSentences = emptyList()
        ttsChapterIndex = -1
        ttsSentencePage = intArrayOf()
        ttsSentenceProgression = DoubleArray(0)
        ttsPrevPageCount = 0
        ttsLastFlippedPage = -1
        ttsCurrentIdx = 0
        ttsCumChars = LongArray(0)
        ttsSingleTimesMs = null
        clearTtsHighlight()
    }

    fun ttsNextSentence() {
        val nextIdx = (ttsCurrentIdx + 1).coerceAtMost(ttsSentences.size - 1)
        if (nextIdx == ttsCurrentIdx) return
        if (ttsUseFallback) {
            tts?.stop(); fallbackQueueSpeak(nextIdx)
        } else {
            seekToSentence(nextIdx)
        }
    }

    fun ttsPrevSentence() {
        val prevIdx = (ttsCurrentIdx - 1).coerceAtLeast(0)
        if (prevIdx == ttsCurrentIdx) return
        if (ttsUseFallback) {
            tts?.stop(); fallbackQueueSpeak(prevIdx)
        } else {
            seekToSentence(prevIdx)
        }
    }

    fun setTtsSpeed(speed: Float) {
        Injector.apiKeyManager().setTtsSpeed(speed)
        tts?.setSpeechRate(speed)
        if (!ttsUseFallback) {
            ttsStreamSegs.forEach { applyTtsSpeedTo(it.player) }
            ttsPlayer?.let { applyTtsSpeedTo(it) }
        }
    }

    // ── TTS Helpers ────────────────────────────────────────────────────

    /** 句→页/进度的字符比例兜底映射（纯本地 O(N)，无 JS）。仅在懒测量失败时回退使用。 */
    private fun buildSentencePageMapping(chapterIdx: Int, sentenceCount: Int) {
        val chPageCount = pageCounts().getOrNull(chapterIdx)?.coerceAtLeast(1) ?: 1
        val totalChars = ttsSentences.sumOf { it.length + 1 }.coerceAtLeast(1)
        val fullPageChars = totalChars / (chPageCount - 1.0).coerceAtLeast(1.0)
        ttsSentencePage = IntArray(sentenceCount)
        ttsSentenceProgression = DoubleArray(sentenceCount)
        var running = 0
        for (i in 0 until sentenceCount) {
            val cp = (running.toDouble() / totalChars).coerceIn(0.0, 1.0)
            ttsSentenceProgression[i] = cp
            ttsSentencePage[i] = (running.toDouble() / fullPageChars).toInt().coerceIn(0, chPageCount - 1)
            running += ttsSentences[i].length + 1
        }
    }

    /** 实测句 [idx] 起始位置的渲染进度（0..1，与 viewport.progressions 同尺度）；失败返回 null。 */
    private suspend fun measureSentenceProgression(
        ctrl: ReflowableWebRenditionController,
        idx: Int,
    ): Double? {
        val anchor = makeTextAnchor(idx) ?: return null
        return try {
            ctrl.getProgressionForTextAnchor(anchor)
        } catch (e: Exception) {
            Timber.w(e, "measureSentenceProgression failed at sentence $idx")
            null
        }
    }

    /**
     * 二分找「第一个渲染进度 >= [target] 的句子」下标。渲染进度随句序单调递增（翻页模式按列量化、
     * 滚动模式按高度连续），一次二分仅 log2(N) 次 JS 调用即精确定位到页/视口边界，无插值误差，
     * 长章节（数百页）也在毫秒级完成。任一次测量失败返回 [fallback]（字符比例估计）。
     */
    private suspend fun findFirstSentenceAtOrAfter(
        ctrl: ReflowableWebRenditionController,
        target: Double,
        sentenceCount: Int,
        fallback: Int,
    ): Int {
        var lo = 0
        var hi = sentenceCount - 1
        var ans = sentenceCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = measureSentenceProgression(ctrl, mid) ?: return fallback
            if (r >= target) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    /** 构造句 [idx] 起始位置的 TextAnchor（前句尾 60 字 + 本句本身）。 */
    private fun makeTextAnchor(idx: Int): TextAnchor? {
        val sentence = ttsSentences.getOrNull(idx)?.trim() ?: return null
        if (sentence.isEmpty()) return null
        val prefix = ttsSentences.getOrNull(idx - 1)?.trim()?.takeLast(60) ?: ""
        // textAfter 只放本句本身：JS 桥把「textAfter 开头若干字」当定位引文，落在句内不跨句边界，
        // 避免分页模式下「前句句号 + 本句首字」被拆到两个 page 元素导致 textContent 不相邻而定位错位。
        return TextAnchor(textBefore = prefix, textAfter = sentence)
    }

    private fun nextChapter(pub: Publication) {
        val nextIdx = ttsChapterIndex + 1
        if (nextIdx >= pub.readingOrder.size) { _ttsState.value = TtsPlaybackState.Idle; return }
        val nextSentences = extractSentencesForChapter(pub, nextIdx)
        if (nextSentences.isEmpty()) { stopTtsInternal(); return }
        val oldPageCount = pageCounts().getOrNull(ttsChapterIndex)?.coerceAtLeast(1) ?: 1
        ttsChapterIndex = nextIdx
        ttsSentences = nextSentences
        ttsPrevPageCount += oldPageCount
        ttsLastFlippedPage = _currentPageIndex.value
        val nextHref = pub.readingOrder[nextIdx].url()
        // 字符比例兜底映射（纯本地，无 JS）；真正的翻页定位由 keepSentenceInView 懒测渲染进度完成。
        buildSentencePageMapping(nextIdx, nextSentences.size)
        // 先导航到新章并等待其 DOM 加载完成再开始合成（新章从第 0 句起，无需二分定位）。
        viewModelScope.launch {
            val s = _uiState.value
            if (s is ReaderUiState.Ready && s.controller != null) s.controller.goTo(nextHref)
            beginTtsSynthesis(0)
        }
    }

    /** 朗读推进到第 [si] 句时保持语句可见：滚动模式滚到该句；翻页模式跨页翻页。 */
    private fun keepSentenceInView(si: Int) {
        val state = _uiState.value
        if (state !is ReaderUiState.Ready) return
        val ctrl = state.controller ?: return
        val href = state.publication.readingOrder.getOrNull(ttsChapterIndex)?.url() ?: return

        if (_scrollMode.value) {
            // 懒测当前句渲染进度（1 次 JS），滚出「舒适带」才平滑滚动；测量失败退回字符比例。
            viewModelScope.launch(Dispatchers.Main) {
                val prog = measureSentenceProgression(ctrl, si)
                    ?: ttsSentenceProgression.getOrElse(si) { 0.0 }
                // 视口感知：仅当语句滚出可视区「舒适带」（上 30% ~ 下 70%）时才平滑滚动，把它带回
                // 上缘 30% 处。避免每句一跳到顶部，也避免高频重导航卡住朗读。
                val range = ctrl.viewport.progressions[href]
                val target = if (range != null) {
                    val start = range.start.value
                    val end = range.endInclusive.value
                    val len = (end - start).coerceAtLeast(0.0001)
                    if (prog < start + len * 0.3 || prog >= start + len * 0.7) {
                        (prog - len * 0.3).coerceIn(0.0, 1.0)
                    } else {
                        null
                    }
                } else {
                    prog
                }
                if (target != null) {
                    ctrl.smoothScrollTo(target)
                }
            }
            return
        }
        if (si <= 0) return
        // 翻页判定：懒测当前句起始的渲染进度，按「页索引」比较——句所在页 > 当前页才翻，并翻到
        // 「句所在页左缘」（精确页边界）。不用「进度 >= 视口右缘」：用户手动翻页可能停在半页处，
        // 此时视口右缘不是页边界，会误判提前半页。测量失败退回字符比例句→页映射。
        val range = ctrl.viewport.progressions[href] ?: return
        val pageWidth = range.endInclusive.value - range.start.value
        if (pageWidth <= 0.0001) return
        val currentPage = Math.floor(range.start.value / pageWidth).toInt().coerceAtLeast(0)
        viewModelScope.launch(Dispatchers.Main) {
            val r = measureSentenceProgression(ctrl, si)
            if (r != null) {
                val pageOfSi = Math.floor(r / pageWidth).toInt().coerceAtLeast(0)
                if (pageOfSi > currentPage) {
                    ttsLastFlippedPage = _currentPageIndex.value
                    ctrl.goTo(ReflowableWebGoLocation(
                        href = href,
                        progression = Progression((pageOfSi * pageWidth).coerceIn(0.0, 1.0))
                    ))
                }
            } else {
                val prevPage = ttsSentencePage.getOrElse(si - 1) { currentPage }
                val curPage = ttsSentencePage.getOrElse(si) { currentPage }
                if (prevPage <= currentPage && curPage > currentPage) {
                    ttsLastFlippedPage = _currentPageIndex.value
                    ctrl.goTo(ReflowableWebGoLocation(
                        href = href,
                        progression = Progression((curPage * pageWidth).coerceIn(0.0, 1.0))
                    ))
                }
            }
        }
    }

    private fun applyTtsHighlight(sentenceIdx: Int) {
        val s = _uiState.value
        if (s !is ReaderUiState.Ready || s.controller == null) return
        val href = s.publication.readingOrder.getOrNull(ttsChapterIndex)?.url() ?: return
        val sentence = ttsSentences.getOrNull(sentenceIdx)?.trim() ?: return
        if (sentence.isEmpty()) return
        val prefix = ttsSentences.getOrNull(sentenceIdx - 1)?.trim()?.takeLast(60) ?: ""
        val suffix = ttsSentences.getOrNull(sentenceIdx + 1)?.trim()?.take(60) ?: ""
        val textQuote = TextQuote(text = sentence, prefix = prefix, suffix = suffix)
        val location = ReflowableWebDecorationLocation(href = href, textQuote = textQuote, cssSelector = null)
        ttsDecor = Decoration(
            id = Decoration.Id("tts-current"), location = location,
            style = Decoration.Style.Highlight(tint = android.graphics.Color.argb(80, 255, 193, 7), isActive = true),
        )
        refreshDecorations()
    }

    private fun clearTtsHighlight() {
        ttsDecor = null
        refreshDecorations()
    }

    /**
     * Extracts plain text from a chapter inside the EPUB (or any supported format).
     *
     * For EPUB files, resolves the chapter's actual ZIP entry path from the reading
     * order href. For our generated EPUBs this is "OEBPS/c{idx}.xhtml", but native
     * EPUBs use arbitrary filenames like "OEBPS/chapter1.xhtml" etc.
     *
     *
     * Falls back to reading the raw HTML from the publication link if ZIP lookup fails.
     */
    private fun extractSentencesForChapter(pub: Publication, chapterIndex: Int): List<String> {
        val book = _book.value ?: return emptyList()
        if (chapterIndex < 0 || chapterIndex >= pub.readingOrder.size) return emptyList()

        val linkHref = pub.readingOrder.getOrNull(chapterIndex)?.url()?.toString() ?: ""
        if (linkHref.isEmpty()) return emptyList()

        val html = try {
            val epubFile = File(book.filePath)
            // Try to read directly from the EPUB zip, matching the actual href
            readHtmlFromEpubZip(epubFile, linkHref)
        } catch (e: Exception) {
            Timber.w(e, "ZIP read failed for $linkHref, trying link media type")
            // Last resort: extract from the publication link (may have raw content)
            extractTextFromLink(pub.readingOrder[chapterIndex])
        }

        if (html.isBlank()) return emptyList()

        // Strip <head>, <style>, <script> blocks first, then HTML tags, then decode ALL entities
        // via Html.fromHtml (manual 7-entity whitelist left &mdash;/&hellip;/&ldquo;/&#8217; etc.
        // as literal text that TTS read aloud as garbage).
        val plainText = html
            .replace(Regex("""<head[^>]*>[\s\S]*?</head>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<script[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<[^>]+>"""), " ")
            .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (plainText.isBlank()) return emptyList()

        return splitSentences(plainText)
    }

    /**
     * Resolves a Readium href into a ZIP entry, trying multiple path variants.
     * EPUB hrefs can be relative to OPF dir (chapter1.xhtml), absolute
     * (/OEBPS/chapter1.xhtml), or depth-relative (../Text/ch01.xhtml).
     */
    private fun readHtmlFromEpubZip(epubFile: File, href: String): String {
        val zipFile = java.util.zip.ZipFile(epubFile)
        try {
            // Normalize: strip leading "/" and fragment/query
            val raw = href.trimStart('/').substringBefore('#').substringBefore('?')

            // Resolve "../" relative to OEBPS (the most common OPF location)
            val baseDir = "OEBPS/"
            val resolved = resolveRelativePath(baseDir, raw)

            // Build candidate paths to try
            val candidates = mutableListOf<String>()
            candidates.add(raw)                         // e.g. OEBPS/ch01.xhtml
            candidates.add(resolved)                    // e.g. OEBPS/Text/ch01.xhtml
            candidates.add("OEBPS/$raw")                // e.g. OEBPS/ch01.xhtml (if raw is bare filename)
            candidates.add("OEBPS/${raw.substringAfterLast('/')}") // OEBPS/basename only
            candidates.add("Text/${raw.substringAfterLast('/')}")
            if (!resolved.startsWith("OEBPS/")) {
                candidates.add("OEBPS/$resolved")
            }

            // Try each candidate
            for (path in candidates.distinct()) {
                val entry = zipFile.getEntry(path)
                if (entry != null) {
                    return decodeHtmlEntry(zipFile, entry)
                }
            }

            // Slow fallback: scan all entries for matching filename
            val baseName = raw.substringAfterLast('/')
            val scanned = zipFile.entries().asSequence()
                .firstOrNull { it.name.endsWith("/$baseName") || it.name == baseName }
                ?: throw Exception("ZIP entry not found for: $href")
            return decodeHtmlEntry(zipFile, scanned)
        } finally {
            zipFile.close()
        }
    }

    /** 按文件自身声明的编码（BOM / XML encoding / meta charset）解码 ZIP 条目，默认 UTF-8。 */
    private fun decodeHtmlEntry(zipFile: java.util.zip.ZipFile, entry: java.util.zip.ZipEntry): String {
        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
        return String(bytes, detectHtmlCharset(bytes))
    }

    /** 探测 HTML 文件的字符编码：GBK/GB2312 等非 UTF-8 中文 EPUB 若不按其声明解码会读成乱码。 */
    private fun detectHtmlCharset(bytes: ByteArray): java.nio.charset.Charset {
        // Byte Order Mark（最高优先级）
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE

        // 头部用 ASCII 兼容方式解码（编码声明本身都是 ASCII 字符）
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)

        fun resolve(name: String): java.nio.charset.Charset? =
            try { java.nio.charset.Charset.forName(name.trim()) } catch (_: Exception) { null }

        // GB18030 是 GBK/GB2312 的超集，作为「非 UTF-8 中文」的统一回退。
        fun gbk(): java.nio.charset.Charset =
            try { java.nio.charset.Charset.forName("GB18030") } catch (_: Exception) { Charsets.UTF_8 }

        // <?xml version="1.0" encoding="GBK"?>
        val declared = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(head)?.let { resolve(it.groupValues[1]) }
            // <meta charset="GBK"> 或 <meta ... content="text/html; charset=GBK">
            ?: Regex("""<meta[^>]+charset\s*=\s*["']?([^"'>\s/]+)""", RegexOption.IGNORE_CASE)
                .find(head)?.let { resolve(it.groupValues[1]) }

        if (declared != null) {
            // 声明为 UTF-8 但字节并非合法 UTF-8：文件实际是 GBK 却被误标，按真实字节回退 GB18030。
            val name = declared.name().lowercase()
            if ((name == "utf-8" || name == "utf8") && !isValidUtf8(bytes)) return gbk()
            return declared
        }

        // 无任何声明：优先 UTF-8；字节非法（旧式中文 EPUB 常见）则回退 GB18030。
        return if (isValidUtf8(bytes)) Charsets.UTF_8 else gbk()
    }

    /** 严格校验 [bytes] 是否为合法 UTF-8 字节序列（非法/不完整序列会抛 CharacterCodingException）。 */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    /** Resolve a relative path against a base directory using segment-by-segment resolution. */
    private fun resolveRelativePath(baseDir: String, relative: String): String {
        val segments = (baseDir.trimEnd('/') + "/" + relative.trimStart('/'))
            .split('/')
            .filter { it.isNotEmpty() }
            .toMutableList()
        val result = mutableListOf<String>()
        for (seg in segments) {
            when (seg) {
                "." -> {} // Skip
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                else -> result.add(seg)
            }
        }
        return result.joinToString("/")
    }

    /** Fallback: extract whatever text we can from a Readium Link metadata. */
    private fun extractTextFromLink(link: org.readium.r2.shared.publication.Link): String {
        // Some links carry text content inline — rare, but worth trying
        return buildString {
            link.title?.let { append(it).append(" ") }
        }
    }

    /** Split text into sentences, keeping punctuation attached. */
    private fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // 在句末标点后切分（固定长度 lookbehind，兼容各 Android 版本的正则引擎）。
        val closingPunct = setOf('"', '\'', '”', '’', '」', '』', '）', ')', '】', '》', '〉')
        val raw = text.split(Regex("""(?<=[。！？.!?])\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (raw.isEmpty()) return listOf(text)

        val merged = mutableListOf<String>()
        for (seg in raw) {
            if (merged.isEmpty()) {
                merged.add(seg)
                continue
            }
            // 本段开头紧跟的右引号/右括号等「收尾符号」挪到上一句末尾：如 “你好。” 后面的 ” 应属于
            // 上一句，否则会被误归为下一句的开头。
            val lead = seg.takeWhile { it in closingPunct }
            val rest = seg.substring(lead.length)
            if (lead.isNotEmpty()) {
                merged[merged.lastIndex] = merged.last() + lead
            }
            // 剩余部分若没有任何字母/数字（纯标点，如连续的 。 或空格后残留的右引号），并入上一句，
            // 避免被语音引擎单独读成一句。
            if (rest.none { it.isLetterOrDigit() }) {
                merged[merged.lastIndex] = merged.last() + rest
            } else {
                merged.add(rest)
            }
        }
        return merged
    }

    override fun onCleared() {
        super.onCleared()
        saveProgress()
        ttsJob?.cancel()
        ttsJob = null
        releaseTtsPlayers()
        stopTtsService()
        tts?.shutdown()
        tts = null
        searchIterator?.close()
        activePublication?.close()
    }

    companion object {
        /** 云端/本地合成批句数。批越大请求越少、缓冲越厚（避免卡顿）；批内逐句时间由静音检测得到，不再受批大小影响。 */
        const val TTS_BATCH_SIZE = 6
        /** 云端 TTS 并发合成批次数（降低开始朗读前的等待）。 */
        const val CLOUD_TTS_CONCURRENCY = 3
        /** 云端流式头段句数：先合成并播放这 N 句，其余后台合成后无缝衔接。 */
        const val CLOUD_TTS_HEAD_SENTENCES = 6
        private val reflowableConfig = ReflowableWebConfiguration(
            decorationTemplates = WebDecorationTemplates(
                defaultTemplates = WebDecorationTemplates.defaultTemplates(alpha = 0.5)
            ) {
                set(WavyUnderlineStyle::class, wavyUnderlineTemplate())
                set(Decoration.Style.Underline::class, underlineTemplate())
            }
        )
        private val fixedConfig = FixedWebConfiguration()
        private val darkBeigeTheme = ReflowableWebPreferences(
            textColor = org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor("#FFEFD5")),
            backgroundColor = org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor("#000000")),
            linkColor = org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor("#63caff")),
            visitedColor = org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor("#0099E5")),
            overridePublisherColors = true,
        )
    }
}
