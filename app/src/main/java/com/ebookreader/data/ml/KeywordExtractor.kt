package com.ebookreader.data.ml

import android.content.Context
import com.ebookreader.data.local.dao.BookChunkDao
import com.ebookreader.domain.model.Keyword
import com.ebookreader.domain.repository.ChatRepository
import com.github.houbb.opencc4j.util.ZhConverterUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

/**
 * 本地离线关键词提取引擎（jieba 风格：词典分词 + TF-IDF + 未登录词新词发现）。
 *
 * 管线：
 *  1. 词典分词：用 jieba 官方 dict.txt（词/词频/词性，约 34.9 万词）对全书正文做
 *     DAG + 动态规划最大概率路径切分，候选只来自「真实词」，从源头杜绝 n-gram 句子碎片。
 *  2. 候选：a) 词典切出的多字词，按词性保留（n/nr/ns/nt/nz/vn/v/a/an），过滤功能词/停用词/成语/单字；
 *           b) 未登录词新词发现：词典外的 2~4 字 n-gram，同时满足凝固度（PMI）与
 *              左右信息熵（自由度）阈值 → 识别音译人名等专名（艾洛丝/芙蕾朵/杰拉尔德）。
 *  3. 打分：jieba TF-IDF = 词频 × IDF；IDF 来自 THUOCL 的 DF（文档频率），未登录词用中位 IDF。
 *  4. MMR 去近义 → 归一化 TopN。
 *
 * 资产表（assets/ml 下）：jieba_dict.txt（分词词典，词/词频/词性）、thuocl.txt（词/DF，做 IDF）、
 * zh_func.txt（功能词）、zh_idiom.txt（成语/习用语）。缺失时优雅降级，不报错。
 */
class KeywordExtractor(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val bookChunkDao: BookChunkDao,
) {

    companion object {
        private const val TOP_N = 50
        /** 词典分词时尝试的最长词长（覆盖「中国特色社会主义」等 7 字核心词）。 */
        private const val MAX_DICT_WORD_LEN = 8
        /** 新词发现的 n-gram 长度范围（音译人名多为 2~4 字）。 */
        private const val NGRAM_MIN = 2
        private const val NGRAM_MAX = 6
        private const val MIN_FREQ = 3
        /** 姓氏名最低频次：放宽到 2，覆盖极小配角（出现 2 次也要识别，好删掉字辈前缀碎片 姬无/蔺无）。 */
        private const val MIN_NAME_FREQ = 2
        /** 凝固度（自然对数 PMI）阈值。 */
        private const val MIN_PMI = 3.0
        /** 「X之Y」型名称的凝固度阈值：比通用新词放宽（名称内部常含功能字），仅用于拦截「名称+尾字」碎片。 */
        private const val MIN_PMI_ZHI = 2.0
        /** 左右信息熵（自然对数）最小值，语境单一（左右邻字固定）的碎片被剔除。 */
        private const val MIN_ENTROPY = 1.5
        private const val N_REF = 1_000_000.0
        private const val MMR_LAMBDA = 0.6
        /** 布隆过滤器期望元素上限（两枚 × 100M bits ≈ 25MB）。 */
        private const val BLOOM_MAX_ITEMS = 10_000_000L
        /** 流式扫描每页块数（约 200 块 × ~1k 字，控制单页内存峰值）。 */
        private const val PAGE_SIZE = 200
        /** 繁体检测采样字数（约 3000 字足够稳定判断，且 opencc4j 转换开销小）。 */
        private const val SAMPLE_CHARS = 3000
        /** 繁体检测每次取块数。 */
        private const val SAMPLE_PAGE = 20
        /** n-gram 精确计数表硬上限：超限后丢弃新的低频串，防止病态书籍撑爆内存。 */
        private const val NGRAM_FREQ_CAP = 1_500_000
        /** 保留词性：名词/人名/地名/机构/专名/动名词/动词/形容词/名形词。 */
        private val ALLOW_POS = setOf("n", "nr", "ns", "nt", "nz", "vn", "v", "a", "an")
    }

    // ── 资产表（懒加载，缺失则空） ─────────────────────────────────────────

    private data class Dict(
        val freq: HashMap<String, Int>,
        val pos: HashMap<String, String>,
        val total: Long,
        val prefixes: HashSet<String>,
    )

    /** jieba 官方词典：词 -> 词频、词 -> 词性，及 ≤MAX_DICT_WORD_LEN 词的前缀集（DAG 剪枝用）。 */
    private val jiebaDict: Dict by lazy { loadJiebaDict() }

    /** THUOCL 词表：词 -> DF（文档频率），作 IDF 兜底。 */
    private val thuoclDf: Map<String, Int> by lazy { loadTabIntAsset("ml/thuocl.txt") }

    /** jieba 官方 IDF 词典：词 -> 现成 IDF 值（主 IDF 来源）。 */
    private val jiebaIdf: Map<String, Double> by lazy { loadSpaceDoubleAsset("ml/jieba_idf.txt") }

    /** 功能词表（数词/量词/副词/代词/助词/连词/介词/叹词/语气/方位/状态/拟声/区别等）。 */
    private val functionWords: Set<String> by lazy { loadWordSetAsset("ml/zh_func.txt") }

    /** 成语/习用语表，几乎不作主题关键词。 */
    private val idiomWords: Set<String> by lazy { loadWordSetAsset("ml/zh_idiom.txt") }

    /** 中文姓氏表（单姓 1 字 + 复姓 2 字，按长度区分），姓名识别锚点。 */
    private val surnames: Set<String> by lazy { loadWordSetAsset("ml/zh_surname.txt") }

    private val stopwords: Set<String> by lazy { STOPWORDS }

    /** 未登录词的中位 IDF（jieba 对不在 IDF 词典里的词的做法，取 idf.txt 值的中位）。 */
    private val medianIdf: Double by lazy {
        val vals = jiebaIdf.values.sorted()
        if (vals.isEmpty()) 6.0 else vals[vals.size / 2]
    }

    suspend fun extract(bookId: Long, forceReindex: Boolean = false): List<Keyword> = withContext(Dispatchers.Default) {
        // 手动「重新生成」/更新书籍时强制重建分块：旧分块可能是旧版正文抽取逻辑的产物（正文残缺），
        // 若不删，ensureIndexed 命中 hasCurrentIndex 会直接复用，导致「重新生成无效、重新导入正常」。
        if (forceReindex) bookChunkDao.deleteChunksForBook(bookId)
        chatRepository.ensureIndexed(bookId)
        val totalChars = bookChunkDao.getBookTotalChars(bookId)
        if (totalChars <= 0L) return@withContext emptyList()
        jiebaDict // 触发词典加载

        // 1. 分页流式扫描：词典分词词频 + n-gram 计数（逐页聚合，整本正文不常驻内存，避免超长书 OOM）
        val dictFreq = HashMap<String, Int>()
        val ngramFreq = HashMap<String, Int>()
        val bloomSize = (totalChars * (NGRAM_MAX - 1)).coerceAtMost(BLOOM_MAX_ITEMS).toInt()
        val bloom = BloomFilter(bloomSize)
        val bloom2 = BloomFilter(bloomSize)
        val bloomName = BloomFilter(bloomSize)
        surnames // 触发姓氏表加载（流式计数 isPotentialSurnameName 用）
        forEachChunkPage(bookId) { text ->
            for (w in segment(text)) {
                if (w.length >= 2) dictFreq[w] = (dictFreq[w] ?: 0) + 1
            }
            countNgrams(text, ngramFreq, bloom, bloom2, bloomName)
        }

        // 2. 候选：词典词（词性过滤）+ 未登录新词（凝固度+左右熵）
        val candidates = HashMap<String, Int>()
        val pos = jiebaDict.pos
        for ((w, f) in dictFreq) {
            if (f < MIN_FREQ) continue
            if (pos[w] !in ALLOW_POS) continue
            if (w in stopwords || w in functionWords || w in idiomWords) continue
            candidates[w] = f
        }
        for (w in discoverNewWords(bookId, ngramFreq)) {
            val f = ngramFreq[w] ?: continue
            candidates[w] = f
        }
        // 补入「THUOCL 已知、且正文高频出现」的词典外词（如 罗伯斯庇尔）：分词词典没有它，
        // 切词会把它拆成「碎片 + 单字」（罗伯斯庇 + 尔），碎片被误当作关键词；补入完整词后，
        // 下面的 removeSubstrings 因「罗伯斯庇 ⊂ 罗伯斯庇尔」把碎片删掉。
        for ((t, f) in ngramFreq) {
            if (t.length < 2 || f < MIN_FREQ) continue
            if (t in jiebaDict.freq) continue
            if (t in stopwords || t in functionWords || t in idiomWords) continue
            if (containsFuncChar(t) || isFragment(t) || isDateBoundaryFragment(t)) continue
            if (t in thuoclDf) candidates[t] = f
        }
        // 顺序很关键：先删「名字+动词」3 字碎片（薛牧来/薛牧看 ⊂ 薛牧），再删前缀碎片（姬无 ⊂ 姬无忧）——
        // 否则「名字+动词」会撑大主角 2 字名的前缀和，导致 removeSubstrings 误删真名。
        removeNameVerbFragments(candidates)
        // 跨「词典词 + 新词 + THUOCL 词」的完整子串去重（如 罗伯斯庇 ⊂ 罗伯斯庇尔；前缀碎片 姬无 ⊂ 姬无忧）
        removeSubstrings(candidates)
        // 姓名「名」整体后缀碎片删除（无涯 ⊂ 蔺无涯、青青 ⊂ 卓青青）
        removeNameFragments(candidates)
        if (candidates.isEmpty()) return@withContext emptyList()

        // 3. TF-IDF 打分（词频 × IDF × 词性权重）
        val scored = candidates.map { (t, f) -> t to (f.toDouble() * idf(t) * posWeight(pos[t])) }
            .sortedByDescending { it.second }

        // 4. MMR 去近义 + 饱和归一 + TopN
        val deduped = mmr(scored)
        val normalized = normalize(deduped)
        normalized.mapIndexed { i, (t, w) -> Keyword(t, w.toFloat(), i) }
    }

    /** 从书名/简介等短文本提取题材关键词（联网推荐搜索词用）：词典切分 + 词性/功能词过滤，只保留 ≥2 字的多字词。 */
    suspend fun extractTitleKeywords(text: String): List<String> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext emptyList()
        jiebaDict // 触发词典加载
        functionWords; idiomWords; stopwords // 触发过滤表加载
        val pos = jiebaDict.pos
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (w in segment(text)) {
            if (w.length < 2) continue
            if (pos[w] !in ALLOW_POS) continue
            if (w in stopwords || w in functionWords || w in idiomWords) continue
            if (seen.add(w)) out.add(w)
        }
        out
    }

    /** 繁→简转换：分词/IDF/停用词等资产均为简体，繁体正文须先转简体才能命中；失败则原样返回。 */
    private fun toSimplified(text: String): String = try {
        ZhConverterUtil.toSimple(text)
    } catch (_: Throwable) {
        text
    }

    /** 判断书籍正文是否含繁体字：采样前若干正文块，繁→简后与原样不同即为繁体。 */
    suspend fun isTraditionalBook(bookId: Long): Boolean = withContext(Dispatchers.Default) {
        val sample = StringBuilder(SAMPLE_CHARS)
        var offset = 0
        while (sample.length < SAMPLE_CHARS) {
            val page = bookChunkDao.getChunksForBookPaged(bookId, SAMPLE_PAGE, offset)
            if (page.isEmpty()) break
            for (chunk in page) {
                sample.append(chunk.content)
                if (sample.length >= SAMPLE_CHARS) break
            }
            offset += page.size
            if (page.size < SAMPLE_PAGE) break
        }
        val s = sample.toString()
        if (s.isBlank()) return@withContext false
        try {
            ZhConverterUtil.toSimple(s) != s
        } catch (_: Throwable) {
            false
        }
    }

    /** 分页遍历书籍全部正文块，逐页回调（不一次性载入，控制内存峰值）。 */
    private suspend fun forEachChunkPage(bookId: Long, action: (String) -> Unit) {
        var offset = 0
        while (true) {
            val page = bookChunkDao.getChunksForBookPaged(bookId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (chunk in page) action(toSimplified(chunk.content))
            offset += page.size
            if (page.size < PAGE_SIZE) break
        }
    }

    // ── 词典分词（DAG + 动态规划最大概率路径，jieba calc 等价） ────────────

    private fun segment(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (isCjk(ch)) {
                val start = i
                while (i < n && isCjk(text[i])) i++
                segmentCjk(text, start, i, out)
            } else if (isAsciiAlnum(ch)) {
                val start = i
                while (i < n && isAsciiAlnum(text[i])) i++
                out.add(text.substring(start, i).lowercase())
            } else {
                i++
            }
        }
        return out
    }

    /** 对一段连续 CJK 串做 DAG + DP 切分：route[idx] 存从 idx 到段末的最大 log 概率路径。 */
    private fun segmentCjk(text: String, start: Int, end: Int, out: MutableList<String>) {
        val n = end - start
        if (n <= 0) return
        val freq = jiebaDict.freq
        val prefixes = jiebaDict.prefixes
        val logTotal = ln(jiebaDict.total.toDouble())
        val route = DoubleArray(n + 1)
        val next = IntArray(n + 1)
        route[n] = 0.0
        for (idx in n - 1 downTo 0) {
            val maxLen = minOf(MAX_DICT_WORD_LEN, n - idx)
            var best = Double.NEGATIVE_INFINITY
            var bestNext = idx + 1
            for (l in 1..maxLen) {
                val sub = text.substring(start + idx, start + idx + l)
                val f = freq[sub]
                if (f != null) {
                    val score = ln(f.toDouble()) - logTotal + route[idx + l]
                    if (score > best) {
                        best = score
                        bestNext = idx + l
                    }
                }
                // 前缀剪枝：sub 既非词也非任何更长词的前缀 → 停止尝试更长
                if (l < maxLen && f == null && sub !in prefixes) break
            }
            if (best == Double.NEGATIVE_INFINITY) {
                best = -logTotal + route[idx + 1]
                bestNext = idx + 1
            }
            route[idx] = best
            next[idx] = bestNext
        }
        var p = 0
        while (p < n) {
            val nxt = next[p]
            out.add(text.substring(start + p, start + nxt))
            p = nxt
        }
    }

    // ── n-gram 计数（1..4 字，供新词发现） ─────────────────────────────────

    private fun countNgrams(text: String, freq: MutableMap<String, Int>, bloom: BloomFilter, bloom2: BloomFilter, bloomName: BloomFilter) {
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (isCjk(ch)) {
                val start = i
                while (i < n && isCjk(text[i])) i++
                val s = text.substring(start, i)
                for (len in 1..NGRAM_MAX) {
                    if (len > s.length) break
                    val limit = s.length - len
                    for (k in 0..limit) {
                        val g = s.substring(k, k + len)
                        if (len == 1) {
                            freq[g] = (freq[g] ?: 0) + 1
                        } else {
                            countStreaming(g, freq, bloom, bloom2, bloomName)
                        }
                    }
                }
            } else {
                i++
            }
        }
    }

    /** 流式计数：可能姓名（首字单姓/前两字复姓）2 次即物化，其余 n-gram 3 次才物化。
     *  只有达标次数的 n-gram 才占用精确表，压掉海量 1~2 次句子碎片，显著降低内存。 */
    private fun countStreaming(g: String, freq: MutableMap<String, Int>, bloom1: BloomFilter, bloom2: BloomFilter, bloomName: BloomFilter) {
        val existing = freq[g]
        if (existing != null) {
            freq[g] = existing + 1
            return
        }
        val h1 = g.hashCode()
        val h2raw = fnv1a(g)
        val h2 = if (h2raw == 0) 1 else h2raw
        if (isPotentialSurnameName(g)) {
            // 可能姓名：2 次即物化（极小配角如 姬无忧 出现 2 次也要识别，好删掉字辈前缀 姬无）
            if (bloomName.mightContain(h1, h2)) {
                if (freq.size < NGRAM_FREQ_CAP) freq[g] = 2
            } else {
                bloomName.add(h1, h2)
            }
        } else {
            // 普通 n-gram：3 次才物化
            if (bloom2.mightContain(h1, h2)) {
                if (freq.size < NGRAM_FREQ_CAP) freq[g] = 3
            } else if (bloom1.mightContain(h1, h2)) {
                bloom2.add(h1, h2)
            } else {
                bloom1.add(h1, h2)
            }
        }
    }

    private fun fnv1a(s: String): Int {
        var hash = -2128831035
        for (c in s) {
            hash = (hash xor c.code) * 16777619
        }
        return hash
    }

    // ── 未登录词新词发现（凝固度 PMI + 左右信息熵） ────────────────────────

    /**
     * 识别词典外的高质量新词（音译人名等）：2~4 字 CJK n-gram，频次达标、凝固度达标、
     * 且非词典词/功能词/停用词/成语；再二次扫描统计左右邻字分布，左右熵取小者 ≥ MIN_ENTROPY。
     */
    private suspend fun discoverNewWords(bookId: Long, freq: Map<String, Int>): Set<String> {
        val candSet = discoverNewWordCandidates(freq)
        if (candSet.isEmpty()) return emptySet()

        // 第二遍分页扫描左右邻字（避免整本正文二次载入）
        val left = HashMap<String, HashMap<Char, Int>>()
        val right = HashMap<String, HashMap<Char, Int>>()
        for (c in candSet) {
            left[c] = HashMap()
            right[c] = HashMap()
        }
        forEachChunkPage(bookId) { text -> scanNeighbors(text, candSet, left, right) }

        return candSet.filterTo(HashSet()) { g ->
            val lm = left.getValue(g)
            val rm = right.getValue(g)
            val lt = lm.values.sum()
            val rt = rm.values.sum()
            if (isZhiName(g) || isSurnameName(g)) {
                // 「X之Y」型名称/称号常在固定语境出现（如句首），放宽信息熵，仅要求左右均有邻字。
                // 姓名则要求左邻字多样（≥2 个不同左邻字）：若左邻恒为同一个字，说明该「姓」实为
                // 前一词典词的末字（华所/华如 的「华」是「耶和华」末字、左邻恒为「耶」），是跨界碎片、
                // 非独立姓名；真名位置多变、左邻字多样，能通过。
                if (isSurnameName(g)) lm.size >= 2 && rt >= 1
                else lt >= 1 && rt >= 1
            } else {
                lt >= 2 && rt >= 2 && minOf(entropy(lm, lt), entropy(rm, rt)) >= MIN_ENTROPY
            }
        }
    }

    /** 新词候选：2~6 字 CJK n-gram，频次/凝固度达标、非词典词/功能词/停用词/成语、非碎片。 */
    private fun discoverNewWordCandidates(freq: Map<String, Int>): Set<String> {
        val total = freq.filter { it.key.length == 1 }.values.sum().coerceAtLeast(1)
        val candSet = HashSet<String>()
        for ((t, f) in freq) {
            if (t.length !in NGRAM_MIN..NGRAM_MAX) continue
            if (!isAllCjk(t)) continue
            if (t in jiebaDict.freq) continue
            if (t in stopwords || t in functionWords || t in idiomWords) continue
            if (t in FRAGMENT_BLOCKLIST) continue
            // 「X之Y」型名称：跳过「功能字」剔除（「自由」含「由」、万军之耶和华含「和」不能误杀），
            // 但仍需凝固度达标（阈值放宽到 2.0）——否则「自由之翼女」这类「名称+尾字」碎片会把前缀和撑大、反噬真名；
            // 首尾是功能字仍是碎片（由之翼/由之翼的）。
            if (isZhiName(t)) {
                if (isEdgeFuncChar(t)) continue
                if (f < MIN_FREQ) continue
                if (minCohesion(t, freq, total) < MIN_PMI_ZHI) continue
                candSet.add(t); continue
            }
            if (containsFuncChar(t)) continue
            if (isDateBoundaryFragment(t)) continue
            // 姓氏名放宽到 freq≥2（极小配角也要识别，好删字辈前缀碎片 姬无/蔺无）
            if (isSurnameName(t)) { if (f >= MIN_NAME_FREQ) candSet.add(t); continue }
            if (f < MIN_FREQ) continue
            if (isFragment(t)) continue
            if (minCohesion(t, freq, total) < MIN_PMI) continue
            candSet.add(t)
        }
        return candSet
    }

    /** 二次扫描：统计每个候选的左右邻字分布（CJK 邻字；run 边界记 '\n' 哨兵）。 */
    private fun scanNeighbors(
        text: String,
        candSet: Set<String>,
        left: HashMap<String, HashMap<Char, Int>>,
        right: HashMap<String, HashMap<Char, Int>>,
    ) {
        var i = 0
        val n = text.length
        while (i < n) {
            if (isCjk(text[i])) {
                val start = i
                while (i < n && isCjk(text[i])) i++
                val end = i
                for (p in start until end) {
                    for (l in NGRAM_MIN..NGRAM_MAX) {
                        if (p + l > end) break
                        val sub = text.substring(p, p + l)
                        if (sub !in candSet) continue
                        val lch = if (p > start) text[p - 1] else '\n'
                        val rch = if (p + l < end) text[p + l] else '\n'
                        val lm = left[sub]!!
                        val rm = right[sub]!!
                        lm[lch] = (lm[lch] ?: 0) + 1
                        rm[rch] = (rm[rch] ?: 0) + 1
                    }
                }
            } else {
                i++
            }
        }
    }

    /** 信息熵（自然对数）。 */
    private fun entropy(counts: Map<Char, Int>, total: Int): Double {
        var h = 0.0
        for (c in counts.values) {
            val p = c.toDouble() / total
            h -= p * ln(p)
        }
        return h
    }

    /**
     * 功能字剔除（新词发现用）：候选若含助词/代词/介词/连词/语气等功能字即剔。
     * 用于拦截「视着」「说得了」这类「实义字 + 功能字」碎片；音译人名（艾洛丝/芙蕾朵/
     * 杰拉尔德）不含这些字，不误伤。
     */
    private fun containsFuncChar(term: String): Boolean = term.any { it in FUNC_CHARS }

    /** 首尾功能字检测：用于「X之Y」名称，拦截「由之翼的」「由之翼」这类首尾带功能字的碎片。 */
    private fun isEdgeFuncChar(term: String): Boolean =
        term.isNotEmpty() && (term.first() in FUNC_CHARS || term.last() in FUNC_CHARS)

    /** 碎片剔除：候选若去掉首字后是一个 ≥2 字的词典词，说明是「单字 + 已知词」跨界碎片（如 到高潮→高潮、文译本→译本）。 */
    private fun isFragment(term: String): Boolean {
        if (term.length < 3) return false
        val rest = term.substring(1)
        return rest.length >= 2 && rest in jiebaDict.freq
    }

    /** 「X之Y」型名称/称号：「之」在中间（如 自由之翼、王者之路）。
     *  「之」后紧跟数字则是日期/比例/序数碎片（三分之一、月十之…），非名称，故排除。 */
    private fun isZhiName(term: String): Boolean {
        val i = term.indexOf('之')
        if (i <= 0 || i >= term.length - 1) return false
        return !isCjkNumeral(term[i + 1])
    }

    /** 汉字数字（含「两」「〇」「零」）。 */
    private fun isCjkNumeral(ch: Char): Boolean = ch in CJK_NUMERALS

    private val CJK_NUMERALS: Set<Char> = "〇零一二三四五六七八九十百千万亿两".toSet()

    /** 日期边界碎片：首字为「月/日/年/时/号」且次字为数字（如 月十、日十），
     *  是「八月十日」之类日期跨词切分产生的碎片，非主题词。 */
    private val DATE_BOUNDARY_CHARS: Set<Char> = "月日年时号".toSet()

    private fun isDateBoundaryFragment(term: String): Boolean =
        term.length >= 2 && term[0] in DATE_BOUNDARY_CHARS && isCjkNumeral(term[1])

    /** 不能作为姓名末字的常见动作字（拦截「李强做/卓青说/薛牧笑/薛牧道/薛牧看/薛牧想」这类把后续动词吞进姓名的碎片）。
     *  覆盖小说里高频的「说/道/笑/看/想/望/叹/惊/怒/哭/喊」等名字后跟动词；人名很少以这些字结尾
     *  （「李想」会漏，但远少于「薛牧想」这类噪音，宁可多拦）。 */
    private val NAME_TAIL_BLOCKED: Set<Char> =
        "做说问答听走吃喝拿打放坐站跑跳带送陪等跟找买卖谈聊唱写读学教睡醒道笑看想望叹惊怒哭喊".toSet()

    /** 已确认的跨界碎片黑名单（单字姓 + 常见字，isFragment/凝固度/功能字均未兜住，直接剔除）：
     *  成一（成+一）、华面/华阿（耶和华·面/阿 跨界）。 */
    private val FRAGMENT_BLOCKLIST: Set<String> = setOf("成一", "华面", "华阿")

    /** 姓名锚点：首字是单姓（长度 2~3）或前两字是复姓（长度 3~4），且末字不是动作字。 */
    private fun isSurnameName(term: String): Boolean {
        if (term.length < 2 || term.last() in NAME_TAIL_BLOCKED) return false
        return (term.length in 2..3 && term[0].toString() in surnames) ||
            (term.length in 3..4 && term.substring(0, 2) in surnames)
    }

    /** 姓氏长度：0=非姓，1=单姓，2=复姓（复姓优先，「司马」开头算 2 而非「司」的单姓）。 */
    private fun surnameLength(term: String): Int {
        if (term.length >= 2 && term.substring(0, 2) in surnames) return 2
        if (term.length >= 1 && term[0].toString() in surnames) return 1
        return 0
    }

    /** 是否「可能姓名的开头」（首字单姓 或 前两字复姓，长度 2~4）。流式计数用：这类 n-gram 放宽到 2 次即物化。 */
    private fun isPotentialSurnameName(g: String): Boolean {
        if (g.length !in 2..4) return false
        return g[0].toString() in surnames || g.substring(0, 2) in surnames
    }

    /** 子串去重：候选若被更长的候选包含、且频次不高于更长者（即只作为其子串出现、无独立出现），丢弃短者。
     *  对「词典词 + 新词」合并后的完整候选集执行，覆盖「X之Y」碎片（巫之锤 ⊂ 女巫之锤）
     *  与音译名切取（罗伯斯庇 ⊂ 罗伯斯庇尔，跨词典/新词边界）。 */
    private fun removeSubstrings(cands: MutableMap<String, Int>) {
        val entries = cands.entries.toList()
        if (entries.size < 2) return
        val remove = HashSet<String>()
        // ① 通用子串去重：a 只作为 b 的子串出现（频次不高于 b）→ 删 a
        for ((a, fa) in entries) {
            for ((b, fb) in entries) {
                if (a == b) continue
                if (b.length <= a.length) continue
                if (!b.contains(a)) continue
                if (fa <= fb) { remove.add(a); break }
            }
        }
        // ② 通用「前缀/后缀」碎片：a 非词典词，且 a 主要以更长候选的前缀/后缀身份出现——
        //    fa ≤ 2 × 所有以 a 为前缀/后缀的更长候选频次之和 → 删 a。
        //    覆盖 姬无/蔺无/李公/罗伯斯庇 等非姓名前缀，也覆盖词典外音译名后缀。
        //    2× 留出「碎片偶有单独出现（简称/敬称单用）」的余量；主角 2 字名（薛牧）不受影响——
        //    它后面跟的「看/想/道/笑」等动词已被 NAME_TAIL_BLOCKED 拦下、不计入前缀和。
        for ((a, fa) in entries) {
            if (a in jiebaDict.freq) continue
            var prefixSum = 0L
            var suffixSum = 0L
            for ((b, fb) in entries) {
                if (b.length <= a.length) continue
                if (b.startsWith(a)) prefixSum += fb
                if (b.endsWith(a)) suffixSum += fb
            }
            if ((prefixSum > 0 && fa.toLong() <= 2L * prefixSum) ||
                (suffixSum > 0 && fa.toLong() <= 2L * suffixSum)) {
                remove.add(a)
            }
        }
        if (remove.isNotEmpty()) cands.keys.removeAll(remove)
    }

    /** ③「名字+动词/后缀」碎片：3 字姓名 B 去掉末字的前缀 A 本身也是姓名候选、且 A 频次远高于 B
     *   （A 是主角，B 只是「A+来/去/看/想/道…」的偶然组合，如 薛牧来/薛牧看 ⊂ 薛牧）→ 删 B。
     *   必须在 removeSubstrings 之前执行：先把「名字+动词」清掉，主角 2 字名（薛牧）的前缀和
     *   才不会被撑大而遭误删。仅当 A ≥3×B 才删：避开「姬无忧(15)/姬无(30)」这类 A≈B 的字辈名。 */
    private fun removeNameVerbFragments(cands: MutableMap<String, Int>) {
        val names = cands.keys.filter { it.length >= 3 && isSurnameName(it) }
        if (names.isEmpty()) return
        val remove = HashSet<String>()
        for (name in names) {
            val prefix = name.substring(0, name.length - 1)
            if (prefix in cands && isSurnameName(prefix) &&
                cands.getValue(prefix).toLong() >= 3L * cands.getValue(name)) {
                remove.add(name)
            }
        }
        if (remove.isNotEmpty()) cands.keys.removeAll(remove)
    }

    /** 姓名「名」整体后缀碎片删除：无涯 ⊂ 蔺无涯、青青 ⊂ 卓青青、无忧 ⊂ 姬无忧。
     *  频次 ≤2× 全名（主要以名字身份出现）才删，避免误删「无忧无虑/无涯」这类独立高频词。
     *  注：前缀碎片（姬无/蔺无/李公）已由 removeSubstrings 的通用前缀规则删除；
     *     「名字+动词」碎片（薛牧来/薛牧看）已由 removeNameVerbFragments 删除。 */
    private fun removeNameFragments(cands: MutableMap<String, Int>) {
        val names = cands.keys.filter { isSurnameName(it) }
        if (names.isEmpty()) return
        val remove = HashSet<String>()
        for (name in names) {
            val sLen = surnameLength(name)
            if (sLen == 0) continue
            val given = name.substring(sLen)
            if (given.length >= 2 && given in cands && cands.getValue(given).toLong() <= 2L * cands.getValue(name)) {
                remove.add(given)
            }
        }
        if (remove.isNotEmpty()) cands.keys.removeAll(remove)
    }

    private val FUNC_CHARS: Set<Char> = (
        "的了得着过被把们吗呢吧啊呀嘛哦嗯呃哈嘎这那每各某我你他她它们没只但却将已不很更最太别再也就才" +
            "在里中地上下前后内外间处于以向朝往对从由又却仍虽且若便即第个些种样其者等此彼何谁怎多少几哪" +
            "和与及或而是到另俩两句未会还什么都让使叫给来去出进要应能可愿该用为"
        ).toSet()

    /** 全切分凝固度：对所有二分点取 PMI 的最小值。 */
    private fun minCohesion(term: String, freq: Map<String, Int>, total: Int): Double {
        val fTerm = freq[term] ?: return Double.NEGATIVE_INFINITY
        var minPmi = Double.MAX_VALUE
        for (k in 1 until term.length) {
            val a = freq[term.substring(0, k)] ?: return Double.NEGATIVE_INFINITY
            val b = freq[term.substring(k)] ?: return Double.NEGATIVE_INFINITY
            if (a <= 0 || b <= 0) return Double.NEGATIVE_INFINITY
            val pmi = ln(fTerm.toDouble() * total / (a.toDouble() * b.toDouble()))
            if (pmi < minPmi) minPmi = pmi
        }
        return minPmi
    }

    // ── TF-IDF 打分 ───────────────────────────────────────────────────────

    /** jieba TF-IDF：词频 × IDF（idf.txt 命中直接用；否则 THUOCL DF 兜底；再否则中位 IDF）。 */
    private fun idf(term: String): Double {
        jiebaIdf[term]?.let { return it }
        val df = thuoclDf[term]
        return if (df != null && df > 0) 1.0 + ln(1.0 + N_REF / df) else medianIdf
    }

    /** 词性权重：动词/形容词是小说叙事噪声主力，压低；名词/专名/动名词保持。新词（无词性）默认 1.0。 */
    private fun posWeight(pos: String?): Double = when (pos) {
        "v" -> 0.2
        "vn" -> 0.85
        "a", "an" -> 0.6
        else -> 1.0
    }

    // ── MMR 去近义 + 归一化 ───────────────────────────────────────────────

    private fun mmr(scored: List<Pair<String, Double>>): List<Pair<String, Double>> {
        val remaining = scored.toMutableList()
        val selected = mutableListOf<String>()
        val result = mutableListOf<Pair<String, Double>>()

        while (remaining.isNotEmpty() && result.size < TOP_N) {
            var bestIdx = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for ((i, entry) in remaining.withIndex()) {
                val (term, score) = entry
                var maxSim = 0.0
                for (sel in selected) {
                    // 「X之Y」型名称（如 自由之翼）若含已选词（自由/翼）不再抑制，避免整名被碎片吞掉；
                    // 但作为已选长词的子串（sel.contains(term)）仍是碎片，照常抑制。
                    if (term == sel || sel.contains(term) || (!isZhiName(term) && term.contains(sel))) {
                        maxSim = 1.0
                        break
                    }
                }
                val mmrScore = score - MMR_LAMBDA * maxSim
                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestIdx = i
                }
            }
            val (term, score) = remaining.removeAt(bestIdx)
            selected.add(term)
            result.add(term to score)
        }
        return result
    }

    private fun normalize(scored: List<Pair<String, Double>>): List<Pair<String, Double>> {
        if (scored.isEmpty()) return scored
        val sorted = scored.sortedByDescending { it.second }
        val max = sorted.first().second.coerceAtLeast(1e-9)
        // 线性归一：w = s / max。首词必为 1.00，第 2 名骤降、尾部挤在 0.1 左右。
        return sorted.map { (t, s) ->
            t to (s / max).coerceIn(0.05, 1.0)
        }
    }

    // ── 资产加载 ──────────────────────────────────────────────────────────

    private fun loadJiebaDict(): Dict {
        val freq = HashMap<String, Int>()
        val pos = HashMap<String, String>()
        val prefixes = HashSet<String>()
        var total = 0L
        // 基础词典 + 自定义补充词典（custom_dict.txt 补充译名变体/含功能字长词等基础词典缺失的专名；
        // 与基础词典去重：已存在的词跳过、不覆盖）
        loadDictFile("ml/jieba_dict.txt", freq, pos, prefixes) { total += it }
        loadDictFile("ml/custom_dict.txt", freq, pos, prefixes) { total += it }
        return Dict(freq, pos, total, prefixes)
    }

    /** 读取一个「词 词频 词性」分词词典文件，并入 freq/pos/prefixes；跳过已存在的词与注释行。 */
    private fun loadDictFile(
        path: String,
        freq: HashMap<String, Int>,
        pos: HashMap<String, String>,
        prefixes: HashSet<String>,
        onNew: (Int) -> Unit,
    ) {
        try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    if (raw.isEmpty() || raw[0] == '#') continue
                    val sp = raw.split(' ', limit = 3)
                    if (sp.size < 3) continue
                    val word = sp[0]
                    val f = sp[1].toIntOrNull() ?: continue
                    val tag = sp[2]
                    if (word.isEmpty() || word in freq) continue
                    freq[word] = f
                    pos[word] = tag
                    onNew(f)
                    if (word.length <= MAX_DICT_WORD_LEN) {
                        for (k in 1 until word.length) prefixes.add(word.substring(0, k))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadTabIntAsset(path: String): Map<String, Int> {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                val map = HashMap<String, Int>()
                for (line in lines) {
                    val idx = line.indexOf('\t')
                    if (idx <= 0) continue
                    val word = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().toIntOrNull() ?: continue
                    if (word.isNotEmpty()) map[word] = value
                }
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun loadSpaceDoubleAsset(path: String): Map<String, Double> {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                val map = HashMap<String, Double>()
                for (line in lines) {
                    val idx = line.indexOf(' ')
                    if (idx <= 0) continue
                    val word = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().toDoubleOrNull() ?: continue
                    if (word.isNotEmpty()) map[word] = value
                }
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun loadWordSetAsset(path: String): Set<String> {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '一'..'鿿' || ch in '㐀'..'䶿' || ch in '豈'..'﫿'

    private fun isAllCjk(s: String): Boolean = s.all { isCjk(it) }

    private fun isAsciiAlnum(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    // ── 停用词 ─────────────────────────────────────────────────────────────

    private val STOPWORDS: Set<String> = setOf(
        // 代词 / 指代
        "一个", "这个", "那个", "我们", "你们", "他们", "她们", "它们", "自己",
        "这些", "那些", "有些", "每个", "一些", "一种", "这样", "那样", "这篇", "那种",
        // 疑问 / 关联
        "什么", "怎么", "为什么", "因为", "所以", "但是", "可是", "不过", "而且",
        "然后", "或者", "还是", "就是", "只是", "如果", "虽然", "然而", "于是", "并且",
        "以及", "还有", "其中", "之间", "之后", "之前",
        // 时间 / 副词
        "已经", "正在", "现在", "时候", "一直", "开始", "这里", "那里", "如何",
        "非常", "比较", "一定", "依然", "依旧", "渐渐", "逐渐", "忽然", "立刻", "立即",
        // 助动词 / 动词
        "知道", "觉得", "认为", "可以", "应该", "能够", "没有", "不是", "不能", "不会", "不要",
        "进行", "使用", "需要", "可能", "出来", "起来", "过来", "过去",
        // 名词 / 泛指
        "其他", "东西", "事情", "地方", "人们", "大家", "所有", "任何",
        // 叙事高频词（小说里出现多但非主题）
        "此刻", "浮现", "瞳孔", "视线", "目光", "眼神", "嘴角", "心底", "脑海",
        "身影", "声音", "语气", "心中", "心里", "仿佛", "似乎", "突然", "瞬间",
        "顿时", "缓缓", "轻轻", "微微",
        // 通用动词/名词实词
        "发出", "感受到", "看着", "部分", "部分人", "令人",
        // 短语碎片
        "重新转向", "才行", "没错", "也就", "载于", "写于",
        // 泛义内容词（用户实测噪音）
        "近期", "筹备", "后撤", "遵命", "粘液", "带来", "说道", "继续",
        // 叙事高频通用词（身体/情态/心理/状态，用户实测：表情/原本/无法/呼吸/想要…）
        "表情", "原本", "无法", "呼吸", "想要", "眼睛", "身体", "明白", "成为", "感到",
        "看到", "点头", "作为", "变得", "感觉", "意识", "情绪", "心情", "念头", "想法",
        "回忆", "记忆", "存在", "出现", "发生", "经过", "曾经", "未来", "当时", "如今",
        "终于", "变成",
        // 身体部位（几乎不可能是主题词）
        "手", "脸", "头", "心", "嘴", "脚", "胸口", "心脏", "脸颊", "额头", "手指",
        "肩膀", "嘴唇", "眼泪", "喉咙", "眉头", "掌心", "指尖", "脚步", "脸庞",
        "脑袋", "双手", "双腿", "心跳", "气息",
        // 面部/表情/神态（叙事高频）
        "眼眸", "双眸", "模样", "微笑", "笑容", "笑颜", "笑意", "笑靥", "神色", "神情",
        "面貌", "相貌", "容颜", "容貌", "面容", "脸蛋", "眉宇", "眉眼",
        // 程度副词（无比/非常/十分…）
        "无比", "十分", "极其", "相当", "格外", "特别", "尤其", "尤为", "更加", "越发",
        "愈加", "更为", "极为", "极度", "万分", "多么", "何等", "如此", "较为", "稍微",
        "略微", "稍许", "略略", "颇为", "过于", "分外", "出奇", "绝顶", "透顶",
        // 数量/情态短语（另一/俩人/可能会/还未/便是/有点/句话…）
        "另一", "俩人", "两人", "可能会", "还未", "便是", "有点", "句话", "一句话",
    )
}

/** 布隆过滤器：仅用于「是否见过」判定（无漏报），压掉海量单次 n-gram。 */
private class BloomFilter(expectedItems: Int) {
    private val numBits: Int
    private val numHashes: Int
    private val bits: LongArray

    init {
        val n = expectedItems.coerceAtLeast(1000).toDouble()
        val m = (-n * Math.log(FPR) / (LN2 * LN2)).toLong()
        numBits = m.coerceAtMost(100_000_000L).coerceAtLeast(1024L).toInt()
        numHashes = ((numBits.toDouble() / n * LN2).toInt()).coerceIn(1, 16)
        bits = LongArray((numBits + 63) / 64)
    }

    fun add(h1: Int, h2: Int) {
        var i = 0
        while (i < numHashes) {
            val idx = positiveMod(h1 + i * h2, numBits)
            bits[idx ushr 6] = bits[idx ushr 6] or (1L shl (idx and 63))
            i++
        }
    }

    fun mightContain(h1: Int, h2: Int): Boolean {
        var i = 0
        while (i < numHashes) {
            val idx = positiveMod(h1 + i * h2, numBits)
            if ((bits[idx ushr 6] and (1L shl (idx and 63))) == 0L) return false
            i++
        }
        return true
    }

    private fun positiveMod(h: Int, m: Int): Int {
        val r = h % m
        return if (r < 0) r + m else r
    }

    private companion object {
        private const val FPR = 0.01
        private const val LN2 = 0.6931471805599453
    }
}
