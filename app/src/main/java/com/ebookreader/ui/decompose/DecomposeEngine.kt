package com.ebookreader.ui.decompose

import com.ebookreader.data.local.entity.BookDecompositionEntity
import com.ebookreader.data.network.DeepSeekClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.BookDecomposition
import com.ebookreader.domain.model.DecomposedChapter
import com.ebookreader.domain.model.OutlineNode
import com.ebookreader.domain.repository.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 拆书 UI 状态。 */
sealed class DecomposeState {
    object Idle : DecomposeState()
    data class Generating(val done: Int, val total: Int, val currentChapter: String) : DecomposeState()
    /** 某章自动重试 3 次仍失败，等待用户确认重试/标记失败。 */
    data class NeedsRetry(val done: Int, val total: Int, val chapterTitle: String) : DecomposeState()
    data class Done(val decomposition: BookDecomposition) : DecomposeState()
    data class Error(val message: String) : DecomposeState()
}

/**
 * 拆书引擎（进程级单例）。生成逻辑运行在独立于页面的 scope 中，切屏/退到后台仍继续，
 * 配合 DecomposeService 在通知栏展示进度。
 */
object DecomposeEngine {

    private val chatRepository: ChatRepository = Injector.chatRepository()
    private val deepSeekClient: DeepSeekClient = Injector.deepSeekClient()
    private val apiKeyManager = Injector.apiKeyManager()
    private val bookRepository = Injector.bookRepository()
    private val dao = Injector.appDatabase().bookDecompositionDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<DecomposeState>(DecomposeState.Idle)
    val state: StateFlow<DecomposeState> = _state.asStateFlow()

    private var generationJob: Job? = null
    private var retryDeferred: CompletableDeferred<Boolean>? = null
    private var activeBookId: Long = -1L

    private val bookTypeLabels = mapOf(
        "webnovel" to "长篇网文",
        "fanfic" to "长篇同人",
        "classic" to "经典名著",
        "shortstory" to "短篇小说",
        "collection" to "短篇小说集",
        "collection_fanfic" to "短篇同人小说集",
        "history_novel" to "历史题材小说",
        "history_academic" to "历史学术研究",
        "novel" to "小说",
        "history" to "史书/历史",
        "stem" to "理工科",
        "humanities" to "人文社科",
        "general" to "通用",
    )

    fun bookTypeLabel(type: String): String = bookTypeLabels[type] ?: type

    /** 载入已生成的拆书结果（仅用于已完成的；未完成由 start() 断点续传）。 */
    fun load(bookId: Long) {
        scope.launch(Dispatchers.IO) {
            val entity = dao.getByBookId(bookId)
            if (entity != null && entity.status == "done") {
                _state.value = DecomposeState.Done(entity.toDomain())
            } else {
                _state.value = DecomposeState.Idle
            }
        }
    }

    /** 开始拆书。tier 缺省时读取用户在设置里选的档位（默认 standard）。 */
    fun start(bookId: Long, bookType: String, tier: String = "deep") {
        // 已在为同一本书生成中：不打断，直接返回（支持切屏后回来不重复启动）
        if (activeBookId == bookId && generationJob?.isActive == true) return
        generationJob?.cancel()
        activeBookId = bookId
        // 立即设为「生成中」，避免前台服务刚启动就看到 Idle 而立刻停止、导致通知不显示
        _state.value = DecomposeState.Generating(0, 0, "准备中…")
        generationJob = scope.launch(Dispatchers.IO) {
            try {
                // 已拆完的书：直接展示既有结果，避免重复消耗 token。
                val existing = dao.getByBookId(bookId)
                if (existing != null && existing.status == "done") {
                    _state.value = DecomposeState.Done(existing.toDomain())
                    return@launch
                }

                // 断点续传：上次拆到一半（status=generating）→ 从已完成的章节之后继续。
                val results = mutableListOf<DecomposedChapter>()
                // 只保留深度拆书，固定 deep 档
                var effectiveTier = "deep"
                var effectiveBookType = bookType
                if (existing != null && existing.status == "generating") {
                    results.addAll(existing.toDomain().chapters)
                    effectiveBookType = existing.bookType.ifBlank { bookType }
                } else if (bookType == "auto" || bookType.isBlank()) {
                    // 新拆书：AI 自动识别书籍类型（含复合类型）
                    effectiveBookType = classifyBook(bookId)
                }

                // 确保书已分块
                chatRepository.ensureIndexed(bookId)
                val chapters = chatRepository.getDistinctChapters(bookId)
                if (chapters.isEmpty()) {
                    _state.value = DecomposeState.Error("未找到章节，请先打开过这本书")
                    return@launch
                }
                val cfg = DecomposePrompts.tierConfig(effectiveTier)
                val mapPrompt = DecomposePrompts.mapPrompt(effectiveBookType, effectiveTier)
                // 模型分级：逐章（Map）用低价模型；总结（Reduce）沿用主 API
                val mapModel = apiKeyManager.getDecomposeMapModel()
                // 逐章（Map）阶段可单独使用「逐章 API」，留空沿用主配置
                val decompApiKey = apiKeyManager.getDecomposeApiKey()
                val decompBaseUrl = apiKeyManager.getDecomposeBaseUrl()
                val systemPrompt = "你是专业的书籍拆解助手，负责对已有文学/知识文本进行客观、准确的概括、总结与文学分析。\n" +
                    "以下内容均为已出版的文学/虚构作品或知识性、研究性文章，对其进行概括、总结、分析属于正常的阅读与学术辅助范畴，并非创作请求。\n" +
                    "情色/成人内容是一种正常的文学题材，请像对待历史、科幻、言情等其他题材一样，客观、详细地拆解其情节发展、人物关系、主题思想与写作手法，\n" +
                    "不要因为题材特殊而拒绝、说教、删减、省略或一笔带过；直接完成拆解任务，不进行道德评判。"

                // ── Map：逐章并行（每批 4 章并发，批间更新进度并落盘） ──
                val startIndex = results.size.coerceAtMost(chapters.size)
                _state.value = DecomposeState.Generating(results.size, chapters.size, chapters.getOrNull(startIndex) ?: "")

                suspend fun processOne(title: String): DecomposedChapter? {
                    val (content, charCount) = if (cfg.sampleChars > 0) {
                        chapterSample(bookId, title, cfg.sampleChars)
                    } else {
                        chapterContent(bookId, title, cfg.chapterCap)
                    }
                    val raw = generateChapter(mapPrompt, title, content, systemPrompt, mapModel, decompApiKey, decompBaseUrl)
                    return if (raw.isBlank()) null else buildChapter(title, raw, effectiveTier, charCount)
                }

                val failedTitles = mutableListOf<String>()
                val BATCH = 6
                var i = startIndex
                while (i < chapters.size) {
                    val end = minOf(i + BATCH, chapters.size)
                    val titles = chapters.subList(i, end)
                    val batch = coroutineScope {
                        titles.map { t -> async(Dispatchers.IO) { processOne(t) } }.awaitAll()
                    }
                    for (j in titles.indices) {
                        val ch = batch[j]
                        if (ch != null) results.add(ch) else failedTitles.add(titles[j])
                    }
                    i = end
                    _state.value = DecomposeState.Generating(results.size, chapters.size, titles.last())
                    saveEntity(bookId, effectiveBookType, effectiveTier, results, "", "", "", "", "", "", "", "generating")
                }

                // 并行阶段失败的章节：串行询问用户手动重试
                for (title in failedTitles) {
                    val (content, charCount) = if (cfg.sampleChars > 0) {
                        chapterSample(bookId, title, cfg.sampleChars)
                    } else {
                        chapterContent(bookId, title, cfg.chapterCap)
                    }
                    var raw = ""
                    while (raw.isBlank()) {
                        retryDeferred = CompletableDeferred()
                        _state.value = DecomposeState.NeedsRetry(results.size, chapters.size, title)
                        val retry = retryDeferred!!.await()
                        if (!retry) break
                        // 点击「重试」后立即切回进度页，避免弹窗停留在屏幕上显得无响应
                        _state.value = DecomposeState.Generating(results.size, chapters.size, title)
                        raw = generateChapter(mapPrompt, title, content, systemPrompt, mapModel, decompApiKey, decompBaseUrl)
                    }
                    results.add(buildChapter(title, raw, effectiveTier, charCount))
                    _state.value = DecomposeState.Generating(results.size, chapters.size, title)
                    saveEntity(bookId, effectiveBookType, effectiveTier, results, "", "", "", "", "", "", "", "generating")
                }

                _state.value = DecomposeState.Generating(chapters.size, chapters.size, "生成全书总结…")
                val chaptersText = results.joinToString("\n") { "【${it.title}】${it.summary}" }

                // ── Reduce：全书总结（沿用主 API / 主模型）──
                val bookSummary = deepSeekClient.complete(
                    prompt = DecomposePrompts.bookSummaryPrompt(effectiveBookType, effectiveTier) + "\n\n$chaptersText",
                    systemPrompt = systemPrompt,
                    model = null,
                    apiKeyOverride = "",
                    baseUrlOverride = "",
                    maxTokens = 4096,
                ).getOrNull()?.trim().orEmpty()

                // ── deep 档额外模块：逐模块生成 + 非空校验重试 ──
                var characters = ""
                var timeline = ""
                var quotes = ""
                var characterBios = ""
                var worldSetting = ""
                var chapterOverview = ""
                var extendedReading = ""
                if (effectiveTier == "standard" || effectiveTier == "deep") {
                    val moduleResults = generateDeepModules(
                        modules = DecomposePrompts.deepModules(effectiveBookType),
                        chaptersText = chaptersText,
                        systemPrompt = systemPrompt,
                    )
                    characters = moduleResults["characters"].orEmpty()
                    timeline = moduleResults["timeline"].orEmpty()
                    quotes = moduleResults["quotes"].orEmpty()
                    characterBios = moduleResults["characterBios"].orEmpty()
                    worldSetting = moduleResults["worldSetting"].orEmpty()
                    chapterOverview = moduleResults["chapterOverview"].orEmpty()
                    extendedReading = moduleResults["extendedReading"].orEmpty()
                }

                saveEntity(bookId, effectiveBookType, effectiveTier, results, "", bookSummary, characters, timeline, quotes, characterBios, worldSetting, "done", chapterOverview, extendedReading)
                _state.value = DecomposeState.Done(
                    BookDecomposition(bookId, effectiveBookType, effectiveTier, null, results, bookSummary, characters, timeline, quotes, characterBios, worldSetting, chapterOverview, extendedReading, "done")
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = DecomposeState.Error(e.message ?: "拆书失败")
            } finally {
                if (activeBookId == bookId) activeBookId = -1L
            }
        }
    }

    fun cancel() {
        generationJob?.cancel()
        retryDeferred?.complete(false)
        retryDeferred = null
        activeBookId = -1L
        _state.value = DecomposeState.Idle
    }

    /** 用户在「章节失败」弹窗中的选择：true=重试，false=标记失败并继续。 */
    fun answerRetry(retry: Boolean) {
        retryDeferred?.complete(retry)
        retryDeferred = null
    }

    /** 删除拆书结果（完成后回调在主线程，用于返回上一页）。 */
    fun delete(bookId: Long, onDeleted: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { dao.deleteByBookId(bookId) }
            withContext(Dispatchers.Main) {
                _state.value = DecomposeState.Idle
                onDeleted()
            }
        }
    }

    /** 删除当前拆书进度，并重新开始。 */
    fun deleteProgressAndRestart(bookId: Long, bookType: String) {
        generationJob?.cancel()
        retryDeferred?.complete(false)
        retryDeferred = null
        activeBookId = -1L
        scope.launch {
            withContext(Dispatchers.IO) { dao.deleteByBookId(bookId) }
            _state.value = DecomposeState.Idle
            start(bookId, bookType)
        }
    }

    // ── 内部：书籍类型自动识别 ──────────────────────────────────────────────

    /** 用 AI 自动识别书籍类型（含复合类型），返回复合类型 key；失败回退 general。 */
    private suspend fun classifyBook(bookId: Long): String {
        return try {
            val book = bookRepository.getBookById(bookId) ?: return "general"
            val chapters = chatRepository.getDistinctChapters(bookId)
            if (chapters.isEmpty()) return "general"

            // 多段采样：开头 + 中间 + 结尾，避免只靠开头误判（如序言/作者按语干扰）
            val sampleTitles = buildList {
                add(chapters.first())
                if (chapters.size > 2) add(chapters[chapters.size / 2])
                if (chapters.size > 1) add(chapters.last())
            }.distinct()
            val samples = chatRepository.getChunksByChapters(bookId, sampleTitles)
                .groupBy { it.chapterTitle }
                .entries
                .joinToString("\n\n") { (title, chunks) ->
                    "【$title】" + chunks.joinToString("") { it.content }.take(1000)
                }

            val toc = chapters.take(40).joinToString("、")
            val prompt = "书名：《${book.title}》\n作者：${book.author.ifBlank { "未知" }}\n章节数：${chapters.size}\n" +
                "目录（前40个）：$toc\n\n内容采样（开头/中间/结尾）：\n$samples\n\n" +
                "请判断这本书的类型，从下面选一个最合适的（只能输出其中一个选项的文字，不要解释）：\n" +
                "长篇网文、长篇同人、经典名著、短篇小说、短篇小说集、短篇同人小说集、历史题材小说、历史学术研究、史书/历史、理工科、人文社科、通用"
            val raw = deepSeekClient.complete(
                prompt = prompt,
                systemPrompt = "你是书籍分类助手。根据书名、目录和内容采样，准确判断书籍类型。只输出分类结果，不要解释。",
                model = apiKeyManager.getDecomposeMapModel(),
                apiKeyOverride = apiKeyManager.getDecomposeApiKey(),
                baseUrlOverride = apiKeyManager.getDecomposeBaseUrl(),
            ).getOrNull()?.trim().orEmpty()
            mapTypeLabel(raw)
        } catch (_: Exception) { "general" }
    }

    private fun mapTypeLabel(label: String): String = when {
        label.contains("短篇同人") -> "collection_fanfic"
        label.contains("长篇同人") -> "fanfic"
        label.contains("同人") -> "fanfic"
        label.contains("历史题材") -> "history_novel"
        label.contains("历史学术") -> "history_academic"
        label.contains("史书") -> "history"
        label.contains("历史") -> "history"
        label.contains("长篇网文") -> "webnovel"
        label.contains("网文") -> "webnovel"
        label.contains("经典名著") -> "classic"
        label.contains("名著") -> "classic"
        label.contains("短篇小说集") -> "collection"
        label.contains("小说集") -> "collection"
        label.contains("短篇") -> "shortstory"
        label.contains("理工") -> "stem"
        label.contains("人文社科") -> "humanities"
        label.contains("人文") -> "humanities"
        else -> "general"
    }

    // ── 内部：内容读取 ──────────────────────────────────────────────

    private suspend fun generateChapter(
        mapPrompt: String, title: String, content: String,
        systemPrompt: String, mapModel: String,
        decompApiKey: String, decompBaseUrl: String,
    ): String {
        for (attempt in 1..3) {
            val raw = deepSeekClient.complete(
                prompt = mapPrompt + "\n\n章节标题：$title\n章节内容：\n$content",
                systemPrompt = systemPrompt,
                model = mapModel,
                apiKeyOverride = decompApiKey,
                baseUrlOverride = decompBaseUrl,
                // deep 档每章输出「梗概+关键事件+人物」三段，默认 2048 可能截断最后一段
                maxTokens = 4096,
            ).getOrNull()?.trim().orEmpty()
            if (raw.isNotBlank()) return raw
        }
        return ""
    }

    // ── 内部：deep 档逐模块生成 ──────────────────────────────────────

    /** 逐模块并行生成 deep 档附加内容，每模块独立非空校验重试，避免漏模块。 */
    private suspend fun generateDeepModules(
        modules: List<DecomposePrompts.DeepModule>,
        chaptersText: String,
        systemPrompt: String,
    ): Map<String, String> {
        val results = mutableMapOf<String, String>()
        coroutineScope {
            modules.map { module ->
                async(Dispatchers.IO) {
                    module.key to generateModuleContent(module, chaptersText, systemPrompt)
                }
            }.awaitAll().forEach { (key, content) -> results[key] = content }
        }
        return results
    }

    private suspend fun generateModuleContent(
        module: DecomposePrompts.DeepModule,
        chaptersText: String,
        systemPrompt: String,
    ): String {
        val prompt = "以下是这本书各章的梗概。请生成「${module.title}」这部分内容：${module.instruction}\n" +
            "直接输出内容本身，不要输出「${module.title}」这个标题，也不要任何解释。\n\n$chaptersText"
        for (attempt in 1..3) {
            val raw = deepSeekClient.complete(
                prompt = prompt,
                systemPrompt = systemPrompt,
                model = null,
                apiKeyOverride = "",
                baseUrlOverride = "",
                // 单模块输出可能较长（如人物小传/世界观），给足预算避免截断
                maxTokens = 12000,
            ).getOrNull()?.trim().orEmpty()
            if (raw.isNotBlank()) return raw
        }
        return ""
    }

    private suspend fun chapterContent(bookId: Long, title: String, cap: Int): Pair<String, Int> {
        val chunks = chatRepository.getChunksByChapters(bookId, listOf(title))
        val text = chunks.joinToString("\n") { it.content }
        return text.take(cap) to text.length
    }

    private suspend fun chapterSample(bookId: Long, title: String, sampleChars: Int): Pair<String, Int> {
        val chunks = chatRepository.getChunksByChapters(bookId, listOf(title))
        val text = chunks.joinToString("\n") { it.content }
        return text.take(sampleChars) to text.length
    }

    // ── 内部：解析 ──────────────────────────────────────────────

    private fun buildChapter(title: String, raw: String, tier: String, charCount: Int = 0): DecomposedChapter {
        if (tier == "deep") {
            val secs = parseSections(raw, listOf("梗概", "关键事件", "人物"))
            return DecomposedChapter(
                title = title,
                summary = secs["梗概"]?.takeIf { it.isNotBlank() } ?: raw.take(300).ifBlank { "（生成失败）" },
                events = secs["关键事件"].orEmpty(),
                characters = secs["人物"].orEmpty(),
                charCount = charCount,
            )
        }
        return DecomposedChapter(title, raw.ifBlank { "（生成失败）" }, charCount = charCount)
    }

    private fun parseSections(raw: String, labels: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var current: String? = null
        val buffer = StringBuilder()
        fun flush() {
            if (current != null) result[current!!] = buffer.toString().trim()
            buffer.setLength(0)
        }
        for (line in raw.lines()) {
            val m = Regex("""^\s*【(.{1,12}?)】\s*(.*)$""").find(line)
            if (m != null && m.groupValues[1] in labels) {
                flush()
                current = m.groupValues[1]
                if (m.groupValues[2].isNotBlank()) buffer.append(m.groupValues[2])
            } else if (current != null) {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line.trim())
            }
        }
        flush()
        return result
    }

    private fun parseOutline(raw: String, chapters: List<DecomposedChapter>): OutlineNode {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try {
            val obj = JSONObject(cleaned)
            val node = parseOutlineNode(obj)
            if (node.title.isNotBlank()) return node
        } catch (_: Exception) { }
        return OutlineNode("全书大纲", "", chapters.map { OutlineNode(it.title, it.summary) })
    }

    private fun parseOutlineNode(obj: JSONObject): OutlineNode {
        val title = obj.optString("title", "未命名")
        val summary = obj.optString("summary", "")
        val children = mutableListOf<OutlineNode>()
        obj.optJSONArray("children")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { children.add(parseOutlineNode(it)) }
            }
        }
        return OutlineNode(title, summary, children)
    }

    // ── 内部：持久化 ──────────────────────────────────────────────

    private suspend fun saveEntity(
        bookId: Long, bookType: String, tier: String,
        chapters: List<DecomposedChapter>, outlineJson: String, bookSummary: String,
        characters: String, timeline: String, quotes: String,
        characterBios: String, worldSetting: String, status: String,
        chapterOverview: String = "", extendedReading: String = "",
    ) {
        val arr = JSONArray()
        chapters.forEach { c ->
            arr.put(JSONObject().apply {
                put("title", c.title)
                put("summary", c.summary)
                put("events", c.events)
                put("characters", c.characters)
                put("charCount", c.charCount)
            })
        }
        dao.upsert(
            BookDecompositionEntity(
                bookId = bookId,
                bookType = bookType,
                tier = tier,
                outlineJson = outlineJson,
                chapterSummariesJson = arr.toString(),
                bookSummary = bookSummary,
                charactersJson = characters,
                timelineJson = timeline,
                quotesJson = quotes,
                characterBiosJson = characterBios,
                worldSettingJson = worldSetting,
                chapterOverviewJson = chapterOverview,
                extendedReadingJson = extendedReading,
                status = status,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun BookDecompositionEntity.toDomain(): BookDecomposition {
        val chapters = mutableListOf<DecomposedChapter>()
        try {
            val arr = JSONArray(chapterSummariesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                chapters.add(
                    DecomposedChapter(
                        title = obj.optString("title"),
                        summary = obj.optString("summary"),
                        events = obj.optString("events", ""),
                        characters = obj.optString("characters", ""),
                        charCount = obj.optInt("charCount", 0),
                    )
                )
            }
        } catch (_: Exception) { }
        return BookDecomposition(
            bookId = bookId,
            bookType = bookType,
            tier = tier,
            outline = parseOutline(outlineJson, chapters),
            chapters = chapters,
            bookSummary = bookSummary,
            characters = charactersJson,
            timeline = timelineJson,
            quotes = quotesJson,
            characterBios = characterBiosJson,
            worldSetting = worldSettingJson,
            chapterOverview = chapterOverviewJson,
            extendedReading = extendedReadingJson,
            status = status,
        )
    }
}
