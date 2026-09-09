package com.ebookreader.ui.recommend

import com.ebookreader.data.network.GenreTranslations
import com.ebookreader.data.network.OnlineBook
import com.ebookreader.data.network.PreferredGenre
import com.ebookreader.domain.model.Keyword

/**
 * 推荐打分纯逻辑（主推荐页与单本书推荐共用）。
 * 全部为无状态纯函数，避免两处 ViewModel 各自维护一份易漂移的评分实现。
 */
object RecommendationScorer {

    /** 推荐画像（供联网题材/关键词/作者命中打分）。 */
    data class Profile(
        val preferredGenres: List<PreferredGenre>,   // 用户手动输入的偏好题材（带权重）
        val topKeywords: List<Pair<String, Double>>, // 自动提取的题材 -> 权重，降序
        val topAuthors: List<String>,
    )

    /** 命中题材得分 + 返回命中的最高分题材名（用于推荐理由），并加热度分与语言优先。 */
    fun scoreCandidate(
        title: String,
        description: String,
        tags: List<String>,
        popularity: Double,
        profile: Profile,
        searchTag: String?,
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
        // 搜索 tag 命中：直接视为命中（题材搜索据此过滤），并作为推荐理由
        if (searchTag != null && genreMatches(text, searchTag)) {
            score += 1.0
            topGenre = searchTag
        }
        // 热度加分（优先热门/经典）
        score += popularity * 0.15
        return score to topGenre
    }

    // ── 来源信誉 + 评分可信度（软排序）+ 低质硬门控 ──

    /** 来源信誉基线（无评分的源靠它兜底）。 */
    fun sourceBaseline(source: String): Double = when (source) {
        "Google Books" -> 0.60
        "豆瓣" -> 0.55
        "Open Library" -> 0.50
        "Pixiv" -> 0.55
        "起点" -> 0.55
        "晋江" -> 0.55
        "菠萝包" -> 0.55
        else -> 0.25
    }

    /** 书籍质量分（0~1）：来源基线 + 评分可信度 + 元数据加成，封顶 1.0。 */
    fun qualityScore(b: OnlineBook): Double {
        var q = sourceBaseline(b.source)
        val rating = b.rating?.toDoubleOrNull()
        when (b.source) {
            // Google 评分 0~5，人数从 popularity(log10(人数+1)) 反解，对数加权
            "Google Books" -> {
                if (rating != null && rating > 0) {
                    val count = Math.pow(10.0, b.popularity) - 1.0
                    val conf = (Math.log10(count + 1.0) / Math.log10(50.0)).coerceIn(0.0, 1.0)
                    q += 0.35 * (rating / 5.0) * conf
                }
            }
            // 豆瓣 10 分制
            "豆瓣" -> {
                if (rating != null && rating > 0) q += 0.35 * (rating / 10.0)
            }
        }
        if (!b.description.isNullOrBlank()) q += 0.05
        return q.coerceIn(0.0, 1.0)
    }

    /** 质量硬门控：仅 Pixiv 保留「收藏 ≥100」门槛，其余源不再排除低质量书。 */
    fun qualityGatePass(b: OnlineBook): Boolean = when (b.source) {
        "Pixiv" -> b.popularity >= Math.log10(101.0)
        else -> true
    }

    /** Open Library 搜索用：中文题材词转首个英文标签（绕过 q≥3 字符下限 + 命中英文 subject）。
     *  无映射时原样返回（英文词直接可用）。 */
    fun englishSearchTag(zh: String): String =
        GenreTranslations.CHINESE_TO_ENGLISH[zh]
            ?.split(Regex("[|,，、;；]"))
            ?.firstOrNull { it.trim().length >= 3 }
            ?.trim()
            ?: zh

    /** 判断题材是否命中：中文名或英文标签任一命中即可。多个英文标签用 | 或逗号分隔，标签内部可含空格。 */
    fun genreMatches(text: String, name: String, extraEnglish: String = ""): Boolean {
        if (text.contains(name)) return true
        val builtin = GenreTranslations.CHINESE_TO_ENGLISH[name] ?: ""
        val english = (builtin + "|" + extraEnglish)
            .split(Regex("[|,，、;；]"))
            .map { it.trim() }
            .filter { it.length >= 3 }
        return english.any { text.contains(it, ignoreCase = true) }
    }

    /** 是否可安全用于联网作者搜索：整名须为纯汉字（0x4E00..0x9FFF）。
     *  排除英文/日文假名/韩文及含「·」的音译名，避免外文作者名多义导致误匹配不相干外文书。 */
    fun isSafeSearchAuthor(name: String): Boolean =
        name.isNotBlank() && name.all { it.code in 0x4E00..0x9FFF }

    /** 语言优先：简体 +1.0、繁体 +0.5、其他语言 -0.4。 */
    fun languageBoost(text: String): Double {
        if (text.isEmpty()) return 0.0
        val hasCjk = text.any { it.code in 0x4E00..0x9FFF }
        if (!hasCjk) return -0.4
        val traditionalChars = "這們麼語書說見開關東車馬門時間會來個那為應該覺得讓對還樣種點現過進從長問聞間風飛飯飲魚鳥龍龜體齊齒齋舊國與學愛爾邊頭實際續"
        val hasTraditional = text.any { it in traditionalChars }
        return if (hasTraditional) 0.5 else 1.0
    }

    // ── 单本书推荐：本地/联网统一相关性 ──

    /** 跨书库文档频率权重：df 越大（越多书含此词）越是通用主题词，权重越高；
     *  df=1 的专名（主角名/地名）压低，避免污染相似度。 */
    fun collectionWeight(df: Int): Double = Math.log(1.0 + df)

    /** 书名互相包含（归一化、长度≥2）：同人书名通常含原作书名，是强相关信号。 */
    fun titleContainment(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        return x.length >= 2 && y.length >= 2 && (x.contains(y) || y.contains(x))
    }

    // ── 书库共现嵌入（语义迁移 / 查询扩展，路线 B） ──

    /** 词 → 含该词的书 id 集合（共现向量基础：两词在同一批书里共现，题材往往相近）。 */
    fun buildCooccurrenceIndex(allKeywords: Map<Long, List<Keyword>>): Map<String, Set<Long>> {
        val index = HashMap<String, MutableSet<Long>>()
        for ((bookId, kws) in allKeywords) {
            for (kw in kws) index.getOrPut(kw.keyword) { HashSet() }.add(bookId)
        }
        return index
    }

    /** 对高权重关键词做共现扩展（查询扩展）：
     *  权重 ≥ maxWeight×minWeightFraction 的词 K，找「含 K 的书集」Jaccard 相似 ≥ jaccardMin 的近邻，
     *  按 K 权重 × 相似度 × decay 注入近邻权重（取 topM）。近邻不覆盖原词、不替换已有词。
     *  只对极高权重词迁移，避免「主角/男主」这类万能词与谁都共现而污染。 */
    fun expandByCooccurrence(
        seed: Map<String, Double>,
        index: Map<String, Set<Long>>,
        minWeightFraction: Double = 0.5,
        jaccardMin: Double = 0.25,
        topM: Int = 5,
        decay: Double = 0.4,
    ): Map<String, Double> {
        if (seed.isEmpty() || index.isEmpty()) return seed
        val maxW = seed.values.maxOrNull() ?: return seed
        if (maxW <= 0.0) return seed
        val threshold = maxW * minWeightFraction
        val out = seed.toMutableMap()
        for ((kw, w) in seed) {
            if (w < threshold) continue
            val books = index[kw] ?: continue
            if (books.size <= 1) continue
            val neighbors = ArrayList<Pair<String, Double>>()
            for ((other, otherBooks) in index) {
                if (other == kw || other in out) continue
                val inter = books.intersect(otherBooks).size
                if (inter == 0) continue
                val union = books.size + otherBooks.size - inter
                val j = inter.toDouble() / union.toDouble()
                if (j >= jaccardMin) neighbors.add(other to j)
            }
            neighbors.sortByDescending { it.second }
            for ((nb, j) in neighbors.take(topM)) {
                out[nb] = maxOf(out[nb] ?: 0.0, w * j * decay)
            }
        }
        return out
    }

    /** 五路信号相关性（0~1）：标签 0.35 / 作者 0.20 / 书名题材 0.10 / 简介关键词 0.20 / 正文关键词 0.15。 */
    fun relevance(
        seedTags: Set<String>,
        seedAuthor: String,
        seedTitleKw: Set<String>,
        seedDescKw: Set<String>,
        seedContentKw: Map<String, Double>,
        candTags: Set<String>,
        candAuthor: String,
        candTitleKw: Set<String>,
        candDescKw: Set<String>,
        candContentKw: Map<String, Double>,
    ): Double {
        val tagSim = jaccard(seedTags, candTags)
        val authorSim = if (sameAuthor(seedAuthor, candAuthor)) 1.0 else 0.0
        val titleSim = jaccard(seedTitleKw, candTitleKw)
        val descSim = jaccard(seedDescKw, candDescKw)
        val contentSim = weightedOverlap(seedContentKw, candContentKw)
        return 0.35 * tagSim + 0.20 * authorSim + 0.10 * titleSim + 0.20 * descSim + 0.15 * contentSim
    }

    /** Jaccard 相似度；两集合皆空视为 0（无信号不产生相关性）。 */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    /** 是否同作者（忽略大小写，排除空与「未知作者」）。 */
    fun sameAuthor(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        return x.isNotBlank() && x != "未知作者" && x.equals(y, ignoreCase = true)
    }

    /** 正文关键词加权重叠：Σ(种子词权重 × 候选词权重) / 种子总权重，范围 0~1。 */
    fun weightedOverlap(seed: Map<String, Double>, cand: Map<String, Double>): Double {
        if (seed.isEmpty()) return 0.0
        val totalSeed = seed.values.sum().coerceAtLeast(1e-9)
        var acc = 0.0
        for ((kw, w) in seed) {
            val cw = cand[kw] ?: continue
            acc += w * cw
        }
        return (acc / totalSeed).coerceIn(0.0, 1.0)
    }

    /** 单本书联网相关性：同一组权重映射到联网书可用的信号。
     *  作者严格只比作者；其余四路都尽量利用联网书抓回的 tags(关键词) + description(简介)：
     *  tags 被标签/书名题材/简介/正文四路命中，description 被简介/正文两路命中。 */
    fun onlineRelevance(
        seedTags: Set<String>,
        seedAuthor: String,
        seedTitleKw: Set<String>,
        seedDescKw: Set<String>,
        seedContentKw: Map<String, Double>,
        candTags: Set<String>,
        candAuthor: String,
        candTitle: String,
        candDescription: String,
    ): Double {
        val candTagsText = candTags.joinToString(" ")
        val tagSim = jaccard(seedTags, candTags)                                // 标签 vs 关键词
        val authorSim = if (sameAuthor(seedAuthor, candAuthor)) 1.0 else 0.0    // 作者 vs 作者（严格）
        val titleSim = keywordHit(seedTitleKw, "$candTitle $candTagsText")      // 书名题材 → 书名 + 关键词
        val descSim = keywordHit(seedDescKw, "$candDescription $candTagsText")  // 简介 → 简介 + 关键词
        val contentSim = weightedKeywordHit(seedContentKw, candTags, candDescription) // 正文 → 关键词 + 简介
        return 0.35 * tagSim + 0.20 * authorSim + 0.10 * titleSim + 0.20 * descSim + 0.15 * contentSim
    }

    /** 种子词命中比例：命中文本的词数 / 总词数，范围 0~1（中文子串或题材英译命中）。 */
    fun keywordHit(words: Set<String>, text: String): Double {
        if (words.isEmpty() || text.isBlank()) return 0.0
        val hit = words.count { w -> genreMatches(text, w) }
        return hit.toDouble() / words.size
    }

    /** 正文关键词加权命中：Σ命中词权重 / 种子总权重，范围 0~1。
     *  命中 = 出现在联网标签（含子串/反向包含/英译）或标题/简介文本。 */
    fun weightedKeywordHit(seedKw: Map<String, Double>, candTags: Set<String>, candText: String): Double {
        if (seedKw.isEmpty()) return 0.0
        val total = seedKw.values.sum().coerceAtLeast(1e-9)
        var acc = 0.0
        for ((kw, w) in seedKw) {
            val hitTag = candTags.any { t -> genreMatches(t, kw) || kw.contains(t) }
            val hitText = genreMatches(candText, kw)
            if (hitTag || hitText) acc += w
        }
        return (acc / total).coerceIn(0.0, 1.0)
    }
}
