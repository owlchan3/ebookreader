package com.ebookreader.ui.recommend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.data.network.BookRecommendClient
import com.ebookreader.data.network.GenreTranslations
import com.ebookreader.data.network.OnlineBook
import com.ebookreader.data.network.PixivClient
import com.ebookreader.data.network.PreferredGenre
import com.ebookreader.data.network.WebNovelClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.repository.BookRepository
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 一条推荐（本地书或联网书，统一卡片展示）。 */
data class RecommendationItem(
    val isLocal: Boolean,
    val bookId: Long = 0,
    val title: String,
    val author: String,
    val cover: String?,        // 本地封面路径 或 联网封面 URL
    val description: String,
    val reason: String,
    val link: String?,         // 联网详情页链接
    val dismissKey: String,
    val score: Double = 0.0,   // 联网推荐的命中得分
)

class RecommendViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository: BookRepository = Injector.bookRepository()
    private val apiKeyManager: ApiKeyManager = Injector.apiKeyManager()

    sealed class UiState {
        object Loading : UiState()
        data class Loaded(val items: List<RecommendationItem>) : UiState()
        object Error : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val hadContent = _uiState.value is UiState.Loaded
            if (!hadContent) _uiState.value = UiState.Loading
            try {
                val profile = buildProfile()
                val dismissed = apiKeyManager.getDismissedRecommendations()
                val recentlyShown = apiKeyManager.getRecentlyShownRecommendations()

                val bookClient = BookRecommendClient(apiKeyManager.getGoogleBooksApiKey())
                val webNovelClient = WebNovelClient(apiKeyManager.getCustomSearchUrls())
                val pixivClient = PixivClient(
                    apiKeyManager.getPixivRefreshToken(),
                    apiKeyManager.getPixivClientId(),
                    apiKeyManager.getPixivClientSecret(),
                )

                val localCandidates = recommendLocal(profile)
                val randomCandidates = recommendRandom()
                val allLocal = localCandidates + randomCandidates
                val onlineCandidates = recommendOnline(profile, allLocal, bookClient, webNovelClient, pixivClient)

                // 本地：2 本题材推荐 + 1 本随机推荐（只排除「不感兴趣」）
                val localKeyword = localCandidates.filter { it.dismissKey !in dismissed }.shuffled().take(2)
                val usedKeys = localKeyword.map { it.dismissKey }.toSet()
                val localRandom = randomCandidates
                    .filter { it.dismissKey !in dismissed && it.dismissKey !in usedKeys }
                    .take(1)
                val local = localKeyword + localRandom
                // 联网：按分数排序（recommendOnline 已排序），优先新鲜，不够用重复补齐到 4。
                // 用「书名|作者」去重/去近期展示，避免同一本书换个来源又重复出现。
                val onlinePool = onlineCandidates.filter { it.dismissKey !in dismissed }
                val fresh = onlinePool.filter { "${it.title}|${it.author}" !in recentlyShown }
                val repeated = onlinePool.filter { "${it.title}|${it.author}" in recentlyShown }
                val online = (fresh + repeated).take(4)
                val items = local + online

                if (online.isNotEmpty()) {
                    apiKeyManager.addRecentlyShownRecommendations(online.map { "${it.title}|${it.author}" }.toSet())
                }
                _uiState.value = UiState.Loaded(items)
            } catch (_: Exception) {
                if (!hadContent) _uiState.value = UiState.Error
            }
            _isRefreshing.value = false
        }
    }

    fun dismiss(key: String) {
        apiKeyManager.addDismissedRecommendation(key)
        val current = (_uiState.value as? UiState.Loaded)?.items ?: return
        _uiState.value = UiState.Loaded(current.filter { it.dismissKey != key })
    }

    // ── 偏好画像：自动题材（书名/简介关键词）+ 作者；偏好题材单独存，仅供联网 ──

    private data class Profile(
        val preferredGenres: List<PreferredGenre>,   // 用户手动输入的偏好题材（带权重，供联网）
        val topKeywords: List<Pair<String, Double>>, // 自动提取的题材 -> 权重，降序
        val topAuthors: List<String>,
    )

    private suspend fun buildProfile(): Profile {
        val books = bookRepository.getAllBooks().first()
        val preferredGenres = apiKeyManager.getPreferredGenres()
        val preferredNames = preferredGenres.map { it.name }.toSet()
        val keywordWeights = mutableMapOf<String, Double>()
        val authorWeights = mutableMapOf<String, Double>()
        for (book in books) {
            if (book.totalReadingTime <= 0) continue
            val w = book.totalReadingTime.toDouble().coerceAtLeast(1.0)
            for (kw in extractKeywords(book.title, book.description)) {
                if (kw in preferredNames) continue  // 偏好题材已单独计分，避免重复/冲突（如负权重）
                keywordWeights[kw] = (keywordWeights[kw] ?: 0.0) + w
            }
            val author = book.author.trim()
            if (author.isNotBlank() && author != "未知作者") {
                authorWeights[author] = (authorWeights[author] ?: 0.0) + w
            }
        }
        return Profile(
            preferredGenres = preferredGenres,
            topKeywords = keywordWeights.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
            topAuthors = authorWeights.entries.sortedByDescending { it.value }.take(6).map { it.key },
        )
    }

    /** 从书名和简介里提取题材关键词（书名 + 简介两个来源）。 */
    private fun extractKeywords(title: String, description: String): List<String> =
        GENRE_KEYWORDS.filter { title.contains(it) || description.contains(it) }

    // ── 本地推荐：只用自动题材关键词 + 作者（不用偏好题材）──────────

    private suspend fun recommendLocal(profile: Profile): List<RecommendationItem> {
        val books = bookRepository.getAllBooks().first()
        if (books.size <= 1) return emptyList()

        val totalWeight = profile.topKeywords.sumOf { it.second }

        data class Scored(val book: Book, val score: Double, val reason: String)
        val scored = mutableListOf<Scored>()
        for (book in books) {
            if (book.totalReadingTime > 0) continue
            val text = book.title + book.description
            val matchedAuto = profile.topKeywords.filter { text.contains(it.first) }
            val autoScore = if (totalWeight > 0) matchedAuto.sumOf { it.second } / totalWeight else 0.0
            if (autoScore <= 0) continue

            val author = book.author.trim()
            var score = autoScore
            var reason = ""
            if (matchedAuto.isNotEmpty()) {
                val names = matchedAuto.sortedByDescending { it.second }.take(2).map { it.first }
                reason = "题材 ${names.joinToString("、") { "「$it」" }}"
            }
            if (author.isNotBlank() && author != "未知作者" && author in profile.topAuthors) {
                score += 0.6
                if (reason.isEmpty()) reason = "同作者：$author" else reason += " · 同作者"
            }
            scored.add(Scored(book, score, reason))
        }
        scored.sortByDescending { it.score }
        return scored.take(10).map { s ->
            RecommendationItem(
                isLocal = true,
                bookId = s.book.id,
                title = s.book.title,
                author = s.book.author,
                cover = s.book.coverPath,
                description = s.book.description,
                reason = s.reason,
                link = null,
                dismissKey = "book:${s.book.id}",
            )
        }
    }

    // ── 本地随机推荐：从未读的书里随机挑 ──────────

    private suspend fun recommendRandom(): List<RecommendationItem> {
        val books = bookRepository.getAllBooks().first()
        return books.filter { it.totalReadingTime <= 0 }.shuffled().take(10).map { book ->
            RecommendationItem(
                isLocal = true,
                bookId = book.id,
                title = book.title,
                author = book.author,
                cover = book.coverPath,
                description = book.description,
                reason = "随机推荐",
                link = null,
                dismissKey = "book:${book.id}",
            )
        }
    }

    // ── 联网推荐：加权随机选题材搜索，按命中题材加减分，取分最高 ──────────

    private suspend fun recommendOnline(
        profile: Profile,
        local: List<RecommendationItem>,
        bookClient: BookRecommendClient,
        webNovelClient: WebNovelClient,
        pixivClient: PixivClient,
    ): List<RecommendationItem> {
        // 正权重的题材池（偏好题材 + 自动题材），用于加权随机选搜索词
        val genrePool = mutableListOf<Pair<String, Double>>()
        genrePool += profile.preferredGenres.filter { it.weight > 0 }.map { it.name to it.weight }
        // 自动题材权重归一化到 0~0.5，与偏好题材(默认 1)可比，避免「阅读秒数」这种大数值盖过偏好题材
        val maxAuto = profile.topKeywords.maxOfOrNull { it.second } ?: 1.0
        genrePool += profile.topKeywords.map { it.first to (it.second / maxAuto) * 0.5 }

        val selected = weightedRandomSelect(genrePool, 4)
        val searchQueries = selected.ifEmpty { listOf("小说") }

        val raw = mutableListOf<RecommendationItem>()
        val seen = mutableSetOf<String>()
        fun addAll(list: List<OnlineBook>, fallbackReason: String, requireMatch: Boolean = true) {
            for (b in list) {
                val dedupeKey = "${b.title}|${b.author}"
                if (!seen.add(dedupeKey)) continue
                val (score, topGenre) = scoreCandidate(b.title, b.description.orEmpty(), b.tags, b.popularity, profile)
                // 题材搜索要求必须命中题材，过滤掉「搜了词但内容不相关」的结果（如搜无期迷途返回杂志）
                if (requireMatch && topGenre == null) continue
                val reason = if (topGenre != null) "「$topGenre」题材推荐" else fallbackReason
                raw.add(
                    RecommendationItem(
                        isLocal = false,
                        title = b.title,
                        author = b.author,
                        cover = b.coverUrl,
                        description = listOfNotNull(b.rating?.let { "评分 $it" }, b.description).joinToString(" · "),
                        reason = reason,
                        link = b.link,
                        dismissKey = b.key,
                        score = score,
                    )
                )
            }
        }

        // 并行发起所有搜索（各源内部已捕获异常返回空列表），显著缩短首次加载时间：
        // 之前是串行等待每个源，首次加载 = 所有源耗时之和；现在 = 最慢单个源耗时。
        data class Pending(val deferred: Deferred<List<OnlineBook>>, val reason: String, val requireMatch: Boolean)
        val pending = mutableListOf<Pending>()
        coroutineScope {
            fun fire(block: suspend () -> List<OnlineBook>, reason: String, requireMatch: Boolean) {
                pending.add(Pending(async { block() }, reason, requireMatch))
            }
            for (q in searchQueries) {
                fire({ bookClient.searchGoogleBooks(q, 12) }, "题材「$q」", true)
                fire({ bookClient.searchOpenLibrary(q, 12) }, "题材「$q」", true)
                fire({ bookClient.searchSaltyLeo(q, 10) }, "题材「$q」", true)
                fire({ webNovelClient.search(q, 12) }, "题材「$q」", true)
                fire({ pixivClient.searchNovels(q, 8) }, "Pixiv 题材「$q」", true)
            }
            // 作者搜索（作者命中本身就相关，不强制题材命中）
            val author = profile.topAuthors.firstOrNull()
            if (author != null) {
                fire({ bookClient.searchGoogleBooks(author, 12) }, "作者：$author", false)
                fire({ bookClient.searchOpenLibrary(author, 12) }, "作者：$author", false)
                fire({ bookClient.searchSaltyLeo(author, 10) }, "作者：$author", false)
                fire({ webNovelClient.search(author, 12) }, "作者：$author", false)
            }
            // Pixiv 官方推荐（自身已相关，不强制题材命中）
            fire({ pixivClient.recommendedNovels(10) }, "Pixiv 推荐", false)
        }
        // coroutineScope 返回时所有搜索已完成；按发起顺序统一评分/去重。
        for (p in pending) {
            addAll(p.deferred.await(), p.reason, p.requireMatch)
        }

        val localKeys = local.map { "${it.title}|${it.author}" }.toSet()
        val filtered = raw.filter { "${it.title}|${it.author}" !in localKeys }

        // Pixiv 最多 2 本
        val pixivItems = filtered.filter { it.dismissKey.startsWith("pixiv:") }
            .sortedByDescending { it.score }.take(2)
        val otherItems = filtered.filter { !it.dismissKey.startsWith("pixiv:") }
        return (otherItems + pixivItems).sortedByDescending { it.score }.take(30)
    }

    /** 按权重加权随机选取 count 个题材（只选正权重）。 */
    private fun weightedRandomSelect(items: List<Pair<String, Double>>, count: Int): List<String> {
        val remaining = items.filter { it.second > 0 }.toMutableList()
        val result = mutableListOf<String>()
        val random = Random
        while (result.size < count && remaining.isNotEmpty()) {
            val total = remaining.sumOf { it.second }
            var r = random.nextDouble() * total
            var idx = remaining.lastIndex
            for ((i, item) in remaining.withIndex()) {
                r -= item.second
                if (r <= 0) { idx = i; break }
            }
            val picked = remaining.removeAt(idx)
            if (picked.first !in result) result.add(picked.first)
        }
        return result
    }

    /** 命中题材得分 + 返回命中的最高分题材名（用于推荐理由），并加热度分与语言优先。 */
    private fun scoreCandidate(
        title: String,
        description: String,
        tags: List<String>,
        popularity: Double,
        profile: Profile,
    ): Pair<Double, String?> {
        val text = title + description + tags.joinToString(" ")
        // 语言优先：简体 > 繁体 > 其他语言
        var score = languageBoost(title + description)
        var topGenre: String? = null
        var topContribution = 0.0
        for (g in profile.preferredGenres) {
            if (genreMatches(text, g.name, g.englishTags)) {
                score += g.weight
                if (g.weight > topContribution) { topContribution = g.weight; topGenre = g.name }
            }
        }
        val maxAuto = profile.topKeywords.maxOfOrNull { it.second } ?: 1.0
        for ((kw, w) in profile.topKeywords) {
            if (genreMatches(text, kw)) {
                val c = (w / maxAuto) * 0.5
                score += c
                if (c > topContribution) { topContribution = c; topGenre = kw }
            }
        }
        // 热度加分（优先热门/经典）
        score += popularity * 0.15
        return score to topGenre
    }

    /** 判断题材是否命中：中文名或英文标签任一命中即可。多个英文标签用 | 或逗号分隔，标签内部可含空格。 */
    private fun genreMatches(text: String, name: String, extraEnglish: String = ""): Boolean {
        if (text.contains(name)) return true
        val builtin = GenreTranslations.CHINESE_TO_ENGLISH[name] ?: ""
        val english = (builtin + "|" + extraEnglish)
            .split(Regex("[|,，、;；]"))
            .map { it.trim() }
            .filter { it.length >= 3 }
        return english.any { text.contains(it, ignoreCase = true) }
    }

    /** 语言优先：简体 +1.0、繁体 +0.5、其他语言 -0.4。 */
    private fun languageBoost(text: String): Double {
        if (text.isEmpty()) return 0.0
        val hasCjk = text.any { it.code in 0x4E00..0x9FFF }
        if (!hasCjk) return -0.4
        val traditionalChars = "這們麼語書說見開關東車馬門時間會來個那為應該覺得讓對還樣種點現過進從長問聞間風飛飯飲魚鳥龍龜體齊齒齋舊國與學愛爾邊頭實際續"
        val hasTraditional = text.any { it in traditionalChars }
        return if (hasTraditional) 0.5 else 1.0
    }

    companion object {
        /** 中文网文/出版书常见题材关键词，用于从书名里提取兴趣标签（替代人工标签）。 */
        private val GENRE_KEYWORDS = listOf(
            "异世界", "穿越", "重生", "系统", "修仙", "玄幻", "武侠", "仙侠", "科幻", "都市",
            "恋爱", "后宫", "校园", "末世", "无限", "西幻", "种田", "宫斗", "悬疑", "推理",
            "恐怖", "灵异", "游戏", "竞技", "历史", "军事", "网游", "同人", "百合", "耽美",
            "言情", "纯爱", "女频", "男频", "魔法", "精灵", "兽人", "克苏鲁", "转生", "召唤",
            "进化", "末日", "丧尸", "修真", "洪荒", "神话", "星际", "机甲", "幻想", "奇幻",
            "冒险", "春梦", "风俗", "沦陷", "榨取", "繁殖", "品鉴",
        )
    }
}
