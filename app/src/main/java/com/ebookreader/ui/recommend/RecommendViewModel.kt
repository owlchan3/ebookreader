package com.ebookreader.ui.recommend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.data.network.ApiKeyManager
import com.ebookreader.data.network.BookRecommendClient
import com.ebookreader.data.network.OnlineBook
import com.ebookreader.data.network.PixivClient
import com.ebookreader.data.network.Xiu2BookSourceClient
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.KeywordRepository
import com.ebookreader.domain.repository.TagRepository
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
    val source: String = "",    // 联网推荐来源（豆瓣/起点/…）；本地为空
    val link: String?,         // 联网详情页链接
    val dismissKey: String,
    val score: Double = 0.0,   // 联网推荐的命中得分
)

class RecommendViewModel(application: Application) : AndroidViewModel(application) {

    private val bookRepository: BookRepository = Injector.bookRepository()
    private val keywordRepository: KeywordRepository = Injector.keywordRepository()
    private val keywordExtractor = Injector.keywordExtractor()
    private val apiKeyManager: ApiKeyManager = Injector.apiKeyManager()
    private val tagRepository = Injector.tagRepository()

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
                val pixivClient = PixivClient(
                    apiKeyManager.getPixivRefreshToken(),
                    apiKeyManager.getPixivClientId(),
                    apiKeyManager.getPixivClientSecret(),
                )
                val xiu2Client = Xiu2BookSourceClient(getApplication(), "official_sources.json")

                val localCandidates = recommendLocal(profile)
                val randomCandidates = recommendRandom()
                val allLocal = localCandidates + randomCandidates
                val onlineCandidates = recommendOnline(profile, allLocal, bookClient, pixivClient, xiu2Client)

                // 本地：4 本关键词推荐（含已读）+ 1 本随机（未读）；关键词不足 4 本时用随机补齐到 5（只排除「不感兴趣」）
                val localScored = localCandidates.filter { it.dismissKey !in dismissed }.take(4)
                val usedKeys = localScored.map { it.dismissKey }.toSet()
                val localRandom = randomCandidates
                    .filter { it.dismissKey !in dismissed && it.dismissKey !in usedKeys }
                    .take(5 - localScored.size)
                val local = localScored + localRandom
                // 联网：按分数排序（recommendOnline 已排序），优先新鲜，不够用重复补齐到 8。
                // 用「书名|作者」去重/去近期展示，避免同一本书换个来源又重复出现。
                val onlinePool = onlineCandidates.filter { it.dismissKey !in dismissed }
                val fresh = onlinePool.filter { "${it.title}|${it.author}" !in recentlyShown }
                val repeated = onlinePool.filter { "${it.title}|${it.author}" in recentlyShown }
                val online = (fresh + repeated).take(8)
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

    private suspend fun buildProfile(): RecommendationScorer.Profile {
        val books = bookRepository.getAllBooks().first()
        val preferredGenres = apiKeyManager.getPreferredGenres()
        val preferredNames = preferredGenres.map { it.name }.toSet()
        val allKeywords = keywordRepository.getAllKeywordsByBook()
        val keywordWeights = mutableMapOf<String, Double>()
        val authorWeights = mutableMapOf<String, Double>()
        for (book in books) {
            if (book.totalReadingTime <= 0) continue
            // 只统计已生成正文关键词的书；未生成关键词的书不参与画像
            val keywords = allKeywords[book.id] ?: continue
            val w = book.totalReadingTime.toDouble().coerceAtLeast(1.0)
            for (kw in keywords) {
                if (kw.keyword in preferredNames) continue  // 偏好题材已单独计分，避免重复/冲突（如负权重）
                keywordWeights[kw.keyword] = (keywordWeights[kw.keyword] ?: 0.0) + w * kw.weight
            }
            val author = book.author.trim()
            if (author.isNotBlank() && author != "未知作者") {
                authorWeights[author] = (authorWeights[author] ?: 0.0) + w
            }
        }
        // 共现语义迁移：对高权重题材词，注入与其在同一批书中共现的近邻词，提升跨书关联。
        val cooccurrenceIndex = RecommendationScorer.buildCooccurrenceIndex(allKeywords)
        val baseTop = keywordWeights.entries.sortedByDescending { it.value }.take(15).associate { it.key to it.value }
        val expandedTop = RecommendationScorer.expandByCooccurrence(baseTop, cooccurrenceIndex)
        return RecommendationScorer.Profile(
            preferredGenres = preferredGenres,
            topKeywords = expandedTop.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
            topAuthors = authorWeights.entries.sortedByDescending { it.value }.take(6).map { it.key },
        )
    }

    // ── 本地推荐：只用正文关键词 + 作者（不用偏好题材）──────────

    private suspend fun recommendLocal(profile: RecommendationScorer.Profile): List<RecommendationItem> {
        val books = bookRepository.getAllBooks().first()
        if (books.size <= 1) return emptyList()
        val allKeywords = keywordRepository.getAllKeywordsByBook()
        // 跨书库文档频率：词 → 含该词的书数；对「只在一本书出现的专名」降权、通用主题词上浮
        val df = HashMap<String, Int>()
        for ((_, kws) in allKeywords) for (kw in kws) {
            df[kw.keyword] = (df[kw.keyword] ?: 0) + 1
        }

        // 每一本（含已读）的「关键词 → 权重」映射（只保留已生成关键词的书）
        data class Cand(val book: Book, val kwWeights: Map<String, Double>)
        val all = books.mapNotNull { b ->
            allKeywords[b.id]?.let { kws -> Cand(b, kws.associate { it.keyword to it.weight.toDouble() }) }
        }
        if (all.isEmpty()) return emptyList()

        // 1. 加权随机抽 5 本作「种子书」提供关键词；近 3 天当过种子的书降权，每次刷新重新抽
        val recentSeeds = apiKeyManager.getRecentSeeds()
        val threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        val seedCount = minOf(all.size, 5)
        val seedPool = all.map { c ->
            val isRecent = (recentSeeds[c.book.id] ?: 0L) >= threeDaysAgo
            c to if (isRecent) 0.5 else 1.0
        }
        val seeds = weightedRandomPick(seedPool, seedCount)
        apiKeyManager.recordSeeds(seeds.map { it.book.id })
        val seedIds = seeds.map { it.book.id }.toSet()

        // 2. 每本种子的所有关键词 → 待匹配关键词池；权重 s 经 s/2+0.5 映射到 0.5~1.0（种子书权重累加）
        val poolW = mutableMapOf<String, Double>()
        for (s in seeds) {
            for ((kw, w) in s.kwWeights.entries) {
                poolW[kw] = (poolW[kw] ?: 0.0) + (w / 2.0 + 0.5)
            }
        }
        if (poolW.isEmpty()) return emptyList()
        // 共现语义迁移：种子关键词的近邻（同一批书中共现）也参与匹配，放大题材相近的信号
        val cooccurrenceIndex = RecommendationScorer.buildCooccurrenceIndex(allKeywords)
        val expandedPoolW = RecommendationScorer.expandByCooccurrence(poolW, cooccurrenceIndex)

        // 3. 对每一本书打分：score = Σ(待匹配关键词权重 × 该书同关键词权重)
        data class Scored(val book: Book, val score: Double, val reason: String)
        val scored = mutableListOf<Scored>()
        for (c in all) {
            val matched = c.kwWeights.entries.mapNotNull { (kw, w) ->
                expandedPoolW[kw]?.let { pw -> kw to (pw * w * RecommendationScorer.collectionWeight(df[kw] ?: 1)) }
            }.filter { it.second > 0.0 }
            if (matched.isEmpty()) continue
            var score = matched.sumOf { it.second }
            val names = matched.sortedByDescending { it.second }.take(2).map { it.first }
            var reason = "关键词 ${names.joinToString("、") { "「$it」" }}"
            val author = c.book.author.trim()
            if (author.isNotBlank() && author != "未知作者" && author in profile.topAuthors) {
                score += 0.6
                reason += " · 同作者"
            }
            // 种子书打折（它们提供了关键词、天然和自己最像），让「与种子相似但不同」的书浮上来
            if (c.book.id in seedIds) score *= 0.2
            scored.add(Scored(c.book, score, reason))
        }
        scored.sortByDescending { it.score }
        return scored.take(4).map { s ->
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
        // 未读完 = 未标记「已阅」且进度未到 100%（当前页 < 总页）；读完的书不进随机推荐
        val readIds = runCatching {
            val readTagId = tagRepository.ensureReadTagExists()
            tagRepository.getAllBookTags().filterValues { tags -> tags.any { it.id == readTagId } }.keys
        }.getOrDefault(emptySet())
        return books.filter { b ->
            b.id !in readIds && !(b.totalPages > 0 && b.currentPage >= b.totalPages)
        }.shuffled().take(10).map { book ->
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
        profile: RecommendationScorer.Profile,
        local: List<RecommendationItem>,
        bookClient: BookRecommendClient,
        pixivClient: PixivClient,
        xiu2Client: Xiu2BookSourceClient,
    ): List<RecommendationItem> {
        // 1. 随机抽取 ≤40 本书的标题，提取题材词；df = 该词出现在多少个标题里，作权重
        val books = bookRepository.getAllBooks().first()
        val titleKeywordDf = mutableMapOf<String, Int>()
        val sample = if (books.size <= 40) books else books.shuffled().take(40)
        for (book in sample) {
            for (kw in keywordExtractor.extractTitleKeywords(book.title)) {
                titleKeywordDf[kw] = (titleKeywordDf[kw] ?: 0) + 1
            }
        }

        // 2. tag 池 = 标题题材词(df 加权) + 偏好主题词(正权重)，加权随机选出搜索 tag
        val tagPool = mutableListOf<Pair<String, Double>>()
        tagPool += titleKeywordDf.map { it.key to it.value.toDouble() }
        tagPool += profile.preferredGenres.filter { it.weight > 0 }.map { it.name to it.weight }
        val searchQueries = weightedRandomSelect(tagPool, 4).ifEmpty { listOf("小说") }

        val raw = mutableListOf<RecommendationItem>()
        val seen = mutableSetOf<String>()
        fun addAll(list: List<OnlineBook>, tag: String?, fallbackReason: String, requireMatch: Boolean = true) {
            for (b in list) {
                val dedupeKey = "${b.title}|${b.author}"
                if (!seen.add(dedupeKey)) continue
                if (!RecommendationScorer.qualityGatePass(b)) continue  // 低质书硬门控
                val (score, topGenre) = RecommendationScorer.scoreCandidate(b.title, b.description.orEmpty(), b.tags, b.popularity, profile, tag)
                // tag 搜索要求必须命中该 tag（或画像题材），过滤「搜了词但内容不相关」的结果
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
                        source = b.source,
                        link = b.link,
                        dismissKey = b.key,
                        score = score + 1.0 * RecommendationScorer.qualityScore(b),
                    )
                )
            }
        }

        // 3. 并行发起所有搜索（各源内部已捕获异常返回空列表），显著缩短首次加载时间
        data class Pending(val deferred: Deferred<List<OnlineBook>>, val tag: String?, val reason: String, val requireMatch: Boolean)
        val pending = mutableListOf<Pending>()
        coroutineScope {
            fun fire(block: suspend () -> List<OnlineBook>, tag: String?, reason: String, requireMatch: Boolean) {
                pending.add(Pending(async { block() }, tag, reason, requireMatch))
            }
            for (q in searchQueries) {
                // Open Library 只收英文 subject，且 q 至少 3 字符；中文题材词先转英文标签再搜
                val olQuery = RecommendationScorer.englishSearchTag(q)
                fire({ bookClient.searchGoogleBooks(q, 12) }, q, "题材「$q」", true)
                fire({ bookClient.searchOpenLibrary(olQuery, 12) }, q, "题材「$q」", true)
                fire({ bookClient.searchDouban(q, 20) }, q, "题材「$q」", true)
                fire({ pixivClient.searchNovels(q, 8) }, q, "Pixiv 题材「$q」", true)
                fire({ xiu2Client.search(q, 8) }, q, "题材「$q」", true)
            }
            // 作者搜索（作者命中本身就相关，不强制题材命中）；仅用纯汉字作者名，排除外文名避免多义误匹配
            val author = profile.topAuthors.firstOrNull { RecommendationScorer.isSafeSearchAuthor(it) }
            if (author != null) {
                fire({ bookClient.searchGoogleBooks(author, 12) }, null, "作者：$author", false)
                fire({ bookClient.searchOpenLibrary(author, 12) }, null, "作者：$author", false)
                fire({ bookClient.searchDouban(author, 15) }, null, "作者：$author", false)
                fire({ xiu2Client.search(author, 8) }, null, "作者：$author", false)
            }
            // Pixiv 官方推荐（自身已相关，不强制题材命中）
            fire({ pixivClient.recommendedNovels(10) }, null, "Pixiv 推荐", false)
        }
        // coroutineScope 返回时所有搜索已完成；按发起顺序统一评分/去重。
        for (p in pending) {
            addAll(p.deferred.await(), p.tag, p.reason, p.requireMatch)
        }

        val localKeys = local.map { "${it.title}|${it.author}" }.toSet()
        val filtered = raw.filter { "${it.title}|${it.author}" !in localKeys }

        // 每源固定名额：保证 Google / 豆瓣 / Open Library / Pixiv 都露脸，不被高分源霸榜
        fun cap(prefix: String, n: Int): List<RecommendationItem> =
            filtered.filter { it.dismissKey.startsWith(prefix) }
                .sortedByDescending { it.score }.take(n)
        val knownPrefixes = listOf("db:", "gb:", "ol:", "pixiv:", "qd:", "jj:", "sf:")
        val capped = cap("db:", 8) + cap("gb:", 8) + cap("ol:", 8) + cap("pixiv:", 2) +
            cap("qd:", 3) + cap("jj:", 3) + cap("sf:", 3) +
            filtered.filter { item -> knownPrefixes.none { item.dismissKey.startsWith(it) } }
        return capped.sortedByDescending { it.score }.take(30)
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

    /** 按权重加权随机选取 count 个元素（泛型版，供种子书抽样等使用）。 */
    private fun <T> weightedRandomPick(items: List<Pair<T, Double>>, count: Int): List<T> {
        val remaining = items.filter { it.second > 0 }.toMutableList()
        val result = mutableListOf<T>()
        val random = Random
        while (result.size < count && remaining.isNotEmpty()) {
            val total = remaining.sumOf { it.second }
            var r = random.nextDouble() * total
            var idx = remaining.lastIndex
            for ((i, item) in remaining.withIndex()) {
                r -= item.second
                if (r <= 0) { idx = i; break }
            }
            result.add(remaining.removeAt(idx).first)
        }
        return result
    }

}
