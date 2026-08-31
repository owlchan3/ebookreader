package com.ebookreader.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.backup.BackupManager
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.data.network.ChapterPattern
import com.ebookreader.data.network.DeepSeekClient
import com.ebookreader.data.network.PixivClient
import com.ebookreader.data.network.PreferredGenre
import com.ebookreader.data.network.WebNovelClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.repository.TagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val tagRepository: TagRepository = Injector.tagRepository()
    private val apiKeyManager: ApiKeyManager = Injector.apiKeyManager()
    private val deepSeekClient: DeepSeekClient = Injector.deepSeekClient()

    /** 内置通用音色目录：覆盖 OpenAI / SiliconFlow CosyVoice2 等主流免费方案。 */
    private val ttsVoiceCatalog = listOf(
        "alloy", "echo", "fable", "onyx", "nova", "shimmer",
        "alex", "benjamin", "charles", "david", "anna", "bella", "claire", "diana",
    )

    /** 小米 MiMo 预置音色（mimo-v2.5-tts）。 */
    private val mimoVoiceCatalog = listOf(
        "mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean",
    )

    /** 按 API 地址选择内置音色目录：MiMo 用其预置音色，其余用通用目录。 */
    private fun voiceCatalogFor(url: String): List<String> =
        if (url.contains("xiaomimimo", ignoreCase = true)) mimoVoiceCatalog else ttsVoiceCatalog

    val tags: StateFlow<List<Tag>> = tagRepository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _aiEnabled = MutableStateFlow(apiKeyManager.isEnabled())
    val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()

    private val _apiKey = MutableStateFlow(apiKeyManager.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(apiKeyManager.getModel())
    val model: StateFlow<String> = _model.asStateFlow()

    private val _baseUrl = MutableStateFlow(apiKeyManager.getBaseUrl())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    /** Models fetched from the API, empty until fetchModels() is called. */
    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()

    /** True while fetching model list from API. */
    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    val modelPresets = ApiKeyManager.MODEL_PRESETS

    fun setAiEnabled(enabled: Boolean) {
        apiKeyManager.setEnabled(enabled)
        _aiEnabled.value = enabled
    }

    fun setApiKey(key: String) {
        apiKeyManager.setApiKey(key)
        _apiKey.value = key
    }

    fun setModel(model: String) {
        apiKeyManager.setModel(model)
        _model.value = model
    }

    fun setBaseUrl(url: String) {
        apiKeyManager.setBaseUrl(url)
        _baseUrl.value = url
    }

    private val _decomposeTier = MutableStateFlow(apiKeyManager.getDecomposeTier())
    val decomposeTier: StateFlow<String> = _decomposeTier.asStateFlow()

    private val _decomposeMapModel = MutableStateFlow(apiKeyManager.getDecomposeMapModel())
    val decomposeMapModel: StateFlow<String> = _decomposeMapModel.asStateFlow()

    private val _decomposeReduceModel = MutableStateFlow(apiKeyManager.getDecomposeReduceModel())
    val decomposeReduceModel: StateFlow<String> = _decomposeReduceModel.asStateFlow()

    private val _decomposeApiKey = MutableStateFlow(apiKeyManager.getDecomposeApiKey())
    val decomposeApiKey: StateFlow<String> = _decomposeApiKey.asStateFlow()

    private val _decomposeBaseUrl = MutableStateFlow(apiKeyManager.getDecomposeBaseUrl())
    val decomposeBaseUrl: StateFlow<String> = _decomposeBaseUrl.asStateFlow()

    fun setDecomposeTier(tier: String) {
        apiKeyManager.setDecomposeTier(tier)
        _decomposeTier.value = tier
    }

    fun setDecomposeMapModel(model: String) {
        apiKeyManager.setDecomposeMapModel(model)
        _decomposeMapModel.value = model.trim()
    }

    fun setDecomposeReduceModel(model: String) {
        apiKeyManager.setDecomposeReduceModel(model)
        _decomposeReduceModel.value = model.trim()
    }

    fun setDecomposeApiKey(key: String) {
        apiKeyManager.setDecomposeApiKey(key)
        _decomposeApiKey.value = key.trim()
    }

    fun setDecomposeBaseUrl(url: String) {
        apiKeyManager.setDecomposeBaseUrl(url)
        _decomposeBaseUrl.value = url.trim()
    }

    /** Fetch available models from the configured API endpoint. */
    fun fetchModels() {
        viewModelScope.launch {
            _isFetchingModels.value = true
            try {
                val result = deepSeekClient.fetchModels()
                result.onSuccess { models ->
                    _fetchedModels.value = models
                }.onFailure { e ->
                    _fetchedModels.value = listOf("获取失败: ${e.message?.take(60)}")
                }
            } catch (e: Exception) {
                _fetchedModels.value = listOf("获取失败: ${e.message?.take(60)}")
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    fun createTag(name: String) {
        viewModelScope.launch { tagRepository.insertTag(Tag(name = name)) }
    }

    fun updateTag(tag: Tag) {
        viewModelScope.launch { tagRepository.updateTag(tag) }
    }

    fun deleteTag(tag: Tag) {
        // 「已阅」特殊标签不可删除
        if (tag.isReadTag) return
        viewModelScope.launch { tagRepository.deleteTag(tag) }
    }

    // ── Custom chapter regex patterns ──────────────────────────────────

    private val _chapterPatterns = MutableStateFlow(apiKeyManager.getChapterPatterns())
    val chapterPatterns: StateFlow<List<ChapterPattern>> = _chapterPatterns.asStateFlow()

    fun addChapterPattern(pattern: String) {
        apiKeyManager.addCustomChapterPattern(pattern)
        _chapterPatterns.value = apiKeyManager.getChapterPatterns()
    }

    fun removeChapterPattern(pattern: String) {
        apiKeyManager.removeCustomChapterPattern(pattern)
        _chapterPatterns.value = apiKeyManager.getChapterPatterns()
    }

    fun setChapterPatternEnabled(pattern: String, enabled: Boolean) {
        apiKeyManager.setChapterPatternEnabled(pattern, enabled)
        _chapterPatterns.value = apiKeyManager.getChapterPatterns()
    }

    /** Validate that a string compiles as a Regex. */
    fun isValidPattern(pattern: String): Boolean {
        return try {
            Regex(pattern); true
        } catch (_: Exception) { false }
    }

    // ── TTS (Text-to-Speech) plugin ────────────────────────────────────

    private val _ttsEnabled = MutableStateFlow(apiKeyManager.isTtsEnabled())
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _ttsSpeed = MutableStateFlow(apiKeyManager.getTtsSpeed())
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    private val _ttsEngines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val ttsEngines: StateFlow<List<TtsEngineInfo>> = _ttsEngines.asStateFlow()

    private val _selectedTtsEngine = MutableStateFlow(apiKeyManager.getTtsEngine())
    val selectedTtsEngine: StateFlow<String> = _selectedTtsEngine.asStateFlow()

    private val _ttsProvider = MutableStateFlow(apiKeyManager.getTtsProvider())
    val ttsProvider: StateFlow<String> = _ttsProvider.asStateFlow()

    private val _ttsOpenAiUrl = MutableStateFlow(apiKeyManager.getTtsOpenAiUrl())
    val ttsOpenAiUrl: StateFlow<String> = _ttsOpenAiUrl.asStateFlow()

    private val _ttsOpenAiKey = MutableStateFlow(apiKeyManager.getTtsOpenAiKey())
    val ttsOpenAiKey: StateFlow<String> = _ttsOpenAiKey.asStateFlow()

    private val _ttsOpenAiModel = MutableStateFlow(apiKeyManager.getTtsOpenAiModel())
    val ttsOpenAiModel: StateFlow<String> = _ttsOpenAiModel.asStateFlow()

    private val _ttsOpenAiVoice = MutableStateFlow(apiKeyManager.getTtsOpenAiVoice())
    val ttsOpenAiVoice: StateFlow<String> = _ttsOpenAiVoice.asStateFlow()

    private val _ttsModelOptions = MutableStateFlow<List<String>>(emptyList())
    val ttsModelOptions: StateFlow<List<String>> = _ttsModelOptions.asStateFlow()

    private val _ttsVoiceOptions = MutableStateFlow<List<String>>(emptyList())
    val ttsVoiceOptions: StateFlow<List<String>> = _ttsVoiceOptions.asStateFlow()

    private val _ttsListStatus = MutableStateFlow("")
    val ttsListStatus: StateFlow<String> = _ttsListStatus.asStateFlow()

    private val _isFetchingTtsLists = MutableStateFlow(false)
    val isFetchingTtsLists: StateFlow<Boolean> = _isFetchingTtsLists.asStateFlow()

    /** Simple engine label for display. */
    val ttsEngineLabel: String
        get() {
            if (_selectedTtsEngine.value.isEmpty()) return "系统默认"
            val label = _ttsEngines.value.find { it.packageName == _selectedTtsEngine.value }?.label
            return label ?: _selectedTtsEngine.value
        }

    fun setTtsEnabled(enabled: Boolean) {
        apiKeyManager.setTtsEnabled(enabled)
        _ttsEnabled.value = enabled
    }

    fun setTtsSpeed(speed: Float) {
        apiKeyManager.setTtsSpeed(speed)
        _ttsSpeed.value = speed
    }

    fun refreshTtsEngines() {
        val engines: List<TtsEngineInfo> = try {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager

            // Query services that declare the TTS_SERVICE intent filter.
            // MagicOS may filter TextToSpeech.getEngines(), so we use
            // PackageManager directly to find all TTS engines on the device.
            val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
            val resolveInfos = pm.queryIntentServices(intent, android.content.pm.PackageManager.MATCH_ALL)
                ?: emptyList()

            // Also try via TextToSpeech API for labels (has better display names)
            val standardEngines = try {
                val tmpTts = android.speech.tts.TextToSpeech(ctx, null)
                val list = tmpTts.engines.map { e -> TtsEngineInfo(e.name, e.label) }
                tmpTts.shutdown()
                list
            } catch (_: Exception) { emptyList() }

            // Merge: PM gives us all engines, standard API gives us labels
            val merged = mutableMapOf<String, String>() // packageName -> label
            resolveInfos.forEach { ri ->
                val pkg = ri.serviceInfo.packageName
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                merged[pkg] = label
            }
            // Standard API labels override (better quality)
            standardEngines.forEach { merged[it.packageName] = it.label }

            merged.map { (pkg, label) -> TtsEngineInfo(pkg, label) }
                .sortedBy { it.label }
        } catch (_: Exception) {
            emptyList()
        }
        _ttsEngines.value = engines
    }

    fun setTtsEngine(engine: String) {
        apiKeyManager.setTtsEngine(engine)
        _selectedTtsEngine.value = engine
    }

    fun setTtsProvider(provider: String) {
        apiKeyManager.setTtsProvider(provider)
        _ttsProvider.value = provider
    }

    fun setTtsOpenAiUrl(url: String) {
        apiKeyManager.setTtsOpenAiUrl(url)
        _ttsOpenAiUrl.value = url
    }

    fun setTtsOpenAiKey(key: String) {
        apiKeyManager.setTtsOpenAiKey(key)
        _ttsOpenAiKey.value = key
    }

    fun setTtsOpenAiModel(model: String) {
        apiKeyManager.setTtsOpenAiModel(model)
        _ttsOpenAiModel.value = model
    }

    fun setTtsOpenAiVoice(voice: String) {
        apiKeyManager.setTtsOpenAiVoice(voice)
        _ttsOpenAiVoice.value = voice
    }

    /**
     * 拉取 OpenAI 兼容服务的模型列表（`GET /models`）与音色列表
     * （`GET /audio/voice/list`，叠加内置音色目录），供选择框使用。
     */
    fun fetchTtsLists() {
        if (_ttsOpenAiUrl.value.isBlank() || _ttsOpenAiKey.value.isBlank()) {
            _ttsListStatus.value = "请先填写 API 地址与 Key"
            return
        }
        if (_isFetchingTtsLists.value) return
        _isFetchingTtsLists.value = true
        _ttsListStatus.value = "获取中…"
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) {
                runCatching { Injector.cloudTtsClient().listModels() }.getOrDefault(emptyList())
            }
            val apiVoices = withContext(Dispatchers.IO) {
                runCatching { Injector.cloudTtsClient().listVoices() }.getOrDefault(emptyList())
            }
            _ttsModelOptions.value = models
            _ttsVoiceOptions.value = (voiceCatalogFor(_ttsOpenAiUrl.value) + apiVoices).distinct()
            _isFetchingTtsLists.value = false
            _ttsListStatus.value = if (models.isEmpty()) {
                "未获取到模型，可手动输入"
            } else {
                "已获取 ${models.size} 个模型、${_ttsVoiceOptions.value.size} 个音色"
            }
        }
    }

    // ── 智能推荐 (Recommendation) 插件 ─────────────────────────────

    private val _recommendEnabled = MutableStateFlow(apiKeyManager.isRecommendEnabled())
    val recommendEnabled: StateFlow<Boolean> = _recommendEnabled.asStateFlow()

    private val _googleBooksApiKey = MutableStateFlow(apiKeyManager.getGoogleBooksApiKey())
    val googleBooksApiKey: StateFlow<String> = _googleBooksApiKey.asStateFlow()

    private val _customSearchUrls = MutableStateFlow(apiKeyManager.getCustomSearchUrls())
    val customSearchUrls: StateFlow<List<String>> = _customSearchUrls.asStateFlow()

    private val _pixivRefreshToken = MutableStateFlow(apiKeyManager.getPixivRefreshToken())
    val pixivRefreshToken: StateFlow<String> = _pixivRefreshToken.asStateFlow()

    private val _pixivClientId = MutableStateFlow(apiKeyManager.getPixivClientId())
    val pixivClientId: StateFlow<String> = _pixivClientId.asStateFlow()

    private val _pixivClientSecret = MutableStateFlow(apiKeyManager.getPixivClientSecret())
    val pixivClientSecret: StateFlow<String> = _pixivClientSecret.asStateFlow()

    fun setRecommendEnabled(enabled: Boolean) {
        apiKeyManager.setRecommendEnabled(enabled)
        _recommendEnabled.value = enabled
    }

    fun setGoogleBooksApiKey(key: String) {
        apiKeyManager.setGoogleBooksApiKey(key)
        _googleBooksApiKey.value = key
    }

    fun addCustomSearchUrl(url: String) {
        apiKeyManager.addCustomSearchUrl(url)
        _customSearchUrls.value = apiKeyManager.getCustomSearchUrls()
    }

    fun removeCustomSearchUrl(url: String) {
        apiKeyManager.removeCustomSearchUrl(url)
        _customSearchUrls.value = apiKeyManager.getCustomSearchUrls()
    }

    private val _preferredGenres = MutableStateFlow(apiKeyManager.getPreferredGenres())
    val preferredGenres: StateFlow<List<PreferredGenre>> = _preferredGenres.asStateFlow()

    fun addPreferredGenre(name: String) {
        apiKeyManager.addPreferredGenre(name)
        _preferredGenres.value = apiKeyManager.getPreferredGenres()
    }

    fun removePreferredGenre(name: String) {
        apiKeyManager.removePreferredGenre(name)
        _preferredGenres.value = apiKeyManager.getPreferredGenres()
    }

    fun setPreferredGenreWeight(name: String, weight: Double) {
        apiKeyManager.setPreferredGenreWeight(name, weight)
        _preferredGenres.value = apiKeyManager.getPreferredGenres()
    }

    fun setPreferredGenreEnglishTags(name: String, englishTags: String) {
        apiKeyManager.setPreferredGenreEnglishTags(name, englishTags)
        _preferredGenres.value = apiKeyManager.getPreferredGenres()
    }

    fun setPixivRefreshToken(token: String) {
        apiKeyManager.setPixivRefreshToken(token)
        _pixivRefreshToken.value = token
    }

    fun setPixivClientId(id: String) {
        apiKeyManager.setPixivClientId(id)
        _pixivClientId.value = id
    }

    fun setPixivClientSecret(secret: String) {
        apiKeyManager.setPixivClientSecret(secret)
        _pixivClientSecret.value = secret
    }

    private val _pixivLoginStatus = MutableStateFlow<String?>(null)
    val pixivLoginStatus: StateFlow<String?> = _pixivLoginStatus.asStateFlow()

    fun testPixivLogin() {
        viewModelScope.launch {
            _pixivLoginStatus.value = "测试中…"
            val client = PixivClient(
                apiKeyManager.getPixivRefreshToken(),
                apiKeyManager.getPixivClientId(),
                apiKeyManager.getPixivClientSecret(),
            )
            val ok = client.testLogin()
            _pixivLoginStatus.value = if (ok) "连接成功" else "连接失败（检查 refresh_token）"
        }
    }

    private val _customUrlStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val customUrlStatus: StateFlow<Map<String, Boolean>> = _customUrlStatus.asStateFlow()

    fun testCustomSearchUrls() {
        viewModelScope.launch {
            val urls = apiKeyManager.getCustomSearchUrls()
            val client = WebNovelClient(urls)
            val result = mutableMapOf<String, Boolean>()
            for (url in urls) {
                result[url] = client.testUrl(url)
            }
            _customUrlStatus.value = result
        }
    }

    fun clearDismissedRecommendations() {
        apiKeyManager.clearDismissedRecommendations()
    }

    // ── 存储清理 ──────────────────────────────────────────────

    private val _cleanupMessage = MutableStateFlow<String?>(null)
    val cleanupMessage: StateFlow<String?> = _cleanupMessage.asStateFlow()

    fun clearCleanupMessage() { _cleanupMessage.value = null }

    /** 每本书的索引块数（bookId -> 块数），用于「删除某本书的索引」。 */
    private val _bookIndexCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val bookIndexCounts: StateFlow<Map<Long, Int>> = _bookIndexCounts.asStateFlow()

    /** 各书籍的索引列表（书名 + 块数），用于「删除某本书的索引」弹窗。 */
    private val _bookIndexList = MutableStateFlow<List<BookIndexInfo>>(emptyList())
    val bookIndexList: StateFlow<List<BookIndexInfo>> = _bookIndexList.asStateFlow()

    fun refreshBookIndexCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val counts = Injector.appDatabase().bookChunkDao().getChunkCountsPerBook()
                _bookIndexCounts.value = counts.associate { it.bookId to it.count }
                val books = Injector.bookRepository().getAllBooks().first()
                _bookIndexList.value = counts.map { c ->
                    val title = books.firstOrNull { it.id == c.bookId }?.title ?: "未知书籍(${c.bookId})"
                    BookIndexInfo(c.bookId, title, c.count)
                }.sortedByDescending { it.count }
            } catch (_: Exception) { }
        }
    }

    /** 清理 books 目录里未被任何书籍引用的孤儿文件（历史 TXT→EPUB / EPUB→修复版 留下的副本）。 */
    fun cleanupStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val books = Injector.bookRepository().getAllBooks().first()
                val referenced = books.map { File(it.filePath).absolutePath }.toSet()
                val booksDir = File(getApplication<Application>().filesDir, "books")
                var deletedCount = 0
                var freedBytes = 0L
                booksDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.absolutePath !in referenced && !file.name.endsWith(".genv")) {
                        freedBytes += file.length()
                        if (file.delete()) deletedCount++
                    }
                }
                _cleanupMessage.value = "已删除 $deletedCount 个重复文件，释放 ${formatBytes(freedBytes)}"
            } catch (e: Exception) {
                _cleanupMessage.value = "清理失败：${e.message}"
            }
        }
    }

    /** 删除某本书的索引，并压缩数据库回收空间。 */
    fun deleteBookIndex(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Injector.appDatabase().bookChunkDao().deleteChunksForBook(bookId)
                withContext(Dispatchers.IO) { Injector.appDatabase().vacuum() }
                refreshBookIndexCounts()
                _cleanupMessage.value = "已删除该书索引并回收空间"
            } catch (e: Exception) {
                _cleanupMessage.value = "删除索引失败：${e.message}"
            }
        }
    }

    /** 清空全部书籍索引，并压缩数据库回收空间。 */
    fun clearAllIndexes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Injector.appDatabase().bookChunkDao().deleteAllChunks()
                withContext(Dispatchers.IO) { Injector.appDatabase().vacuum() }
                refreshBookIndexCounts()
                _cleanupMessage.value = "已清空全部索引并回收空间"
            } catch (e: Exception) {
                _cleanupMessage.value = "清空索引失败：${e.message}"
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ── 存储详情 ──────────────────────────────────────────────

    private val _storageDetail = MutableStateFlow<StorageDetail?>(null)
    val storageDetail: StateFlow<StorageDetail?> = _storageDetail.asStateFlow()

    fun refreshStorageDetail() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val db = Injector.appDatabase()
                val filesDir = app.filesDir
                val booksDir = File(filesDir, "books")
                val bookFiles = booksDir.listFiles()?.filter { it.isFile } ?: emptyList()
                val breakdown = bookFiles.groupBy { it.extension.lowercase() }
                    .map { (ext, files) ->
                        val size = files.sumOf { it.length() }
                        "$ext: ${files.size}个 ${formatBytes(size)}"
                    }
                    .sortedByDescending { it }
                    .joinToString("\n")
                _storageDetail.value = StorageDetail(
                    booksBytes = dirSize(booksDir),
                    coversBytes = dirSize(File(filesDir, "covers")),
                    indexBytes = db.bookChunkDao().getTotalIndexBytes(),
                    decomposeBytes = db.bookDecompositionDao().getTotalDecompositionBytes(),
                    chatBytes = db.chatDao().getTotalMessageBytes(),
                    dbFileBytes = dbFileSize(app.getDatabasePath("ebook_reader.db")),
                    bookFileCount = bookFiles.size,
                    bookCount = Injector.bookRepository().getAllBooks().first().size,
                    bookBreakdown = breakdown.ifEmpty { "（无）" },
                )
            } catch (_: Exception) { }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirSize(f) else f.length()
        }
        return total
    }

    private fun dbFileSize(dbFile: File): Long {
        var total = 0L
        for (suffix in listOf("", "-wal", "-shm")) {
            val f = File(dbFile.absolutePath + suffix)
            if (f.exists()) total += f.length()
        }
        return total
    }

    // ── 备份 / 恢复 ──────────────────────────────────────────────

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() { _backupMessage.value = null }

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _isBackingUp.value = true
            _backupMessage.value = BackupManager.exportBackup(getApplication<Application>(), uri).message
            _isBackingUp.value = false
        }
    }

    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            _isBackingUp.value = true
            val result = BackupManager.importBackup(getApplication<Application>(), uri)
            _backupMessage.value = result.message
            _isBackingUp.value = false
            if (result.success) {
                // 恢复的文件在内存单例/ViewModel 里仍是旧引用，延迟片刻后重启进程，让数据立即生效。
                delay(1800)
                BackupManager.restartApp(getApplication())
            }
        }
    }

}

data class StorageDetail(
    val booksBytes: Long,
    val coversBytes: Long,
    val indexBytes: Long,
    val decomposeBytes: Long,
    val chatBytes: Long,
    val dbFileBytes: Long,
    val bookFileCount: Int,
    val bookCount: Int,
    val bookBreakdown: String,
)

data class TtsEngineInfo(
    val packageName: String,
    val label: String,
)

data class BookIndexInfo(
    val bookId: Long,
    val title: String,
    val count: Int,
)
