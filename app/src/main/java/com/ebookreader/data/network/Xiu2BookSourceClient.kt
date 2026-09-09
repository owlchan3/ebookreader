package com.ebookreader.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * XIU2 精品书源（Legado 阅读规则）搜索客户端。仅「搜索 + 跳外链」，不做正文抓取。
 *
 * 规则引擎只实现常用子集：CSS 选择器（tag./class./id./.N 下标/@ 链）、XPath 的 meta 特例、
 * JSON 源（`$.a.b` / `$[*]` / `{{$.x}}` 模板）、`##正则##替换`。`@js:` / `<js>` 规则已在
 * 生成书源资产时剔除，这里遇到无法识别的规则静默跳过（单个源失败不影响整体推荐）。
 */
class Xiu2BookSourceClient(context: Context, private val assetName: String = "xiu2_shuyuan.json") {

    private data class BookSource(
        val sourceName: String,
        val baseUrl: String,
        val searchUrl: String,
        val bookList: String,
        val nameRule: String,
        val authorRule: String,
        val coverRule: String,
        val urlRule: String,
        val descRule: String = "",
        val keyPrefix: String = "xs",
    )

    private data class SearchSpec(
        val path: String,
        val method: String,
        val body: String,
        val charset: String,
    )

    private val sources: List<BookSource> = loadSources(context, assetName)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 随机抽若干源逐个尝试，凑够 maxResults 为止；单源失败/超时静默跳过。 */
    suspend fun search(query: String, maxResults: Int = 6): List<OnlineBook> = withContext(Dispatchers.IO) {
        if (query.isBlank() || sources.isEmpty()) return@withContext emptyList()
        val out = mutableListOf<OnlineBook>()
        val seen = mutableSetOf<String>()
        var tried = 0
        for (src in sources.shuffled()) {
            if (out.size >= maxResults || tried >= 6) break
            tried++
            val r = try {
                searchOne(src, query, maxResults - out.size)
            } catch (_: Exception) {
                emptyList()
            }
            for (b in r) {
                if (b.author == "未知作者") continue  // 过滤没解析到作者的项（如铅笔小说）
                if (seen.add(b.title)) out.add(b)
            }
        }
        val results = out.take(maxResults)
        // 按搜索返回顺序给热度：越靠前越热门（接进推荐打分的热度项）
        results.mapIndexed { i, b -> b.copy(popularity = (results.size - i).toDouble().coerceAtMost(6.0)) }
    }

    private suspend fun searchOne(src: BookSource, query: String, max: Int): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            val spec = parseSearchUrl(src.searchUrl)
            val enc = URLEncoder.encode(query, "UTF-8")
            val url = resolve(src.baseUrl, spec.path.replace("{{key}}", enc).replace("{{page}}", "1"))
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .apply {
                    if (spec.method == "POST") {
                        val bodyStr = spec.body.replace("{{key}}", enc).replace("{{page}}", "1")
                        post(FormBody.Builder().apply {
                            for (pair in bodyStr.split("&")) {
                                val i = pair.indexOf('=')
                                if (i > 0) add(pair.substring(0, i), pair.substring(i + 1))
                                else if (pair.isNotBlank()) add(pair, "")
                            }
                        }.build())
                    }
                }
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val bytes = response.body?.bytes() ?: return@withContext emptyList()
            val charset = runCatching { Charset.forName(spec.charset) }.getOrDefault(Charsets.UTF_8)
            val content = String(bytes, charset)

            if (isJsonRule(src.bookList)) {
                parseJsonSource(src, content, max)
            } else {
                parseHtmlSource(src, content, max)
            }
        }

    // ── HTML（CSS 规则）源 ─────────────────────────────────────

    private fun parseHtmlSource(src: BookSource, html: String, max: Int): List<OnlineBook> {
        val doc = Jsoup.parse(html)
        val items = selectBookList(doc, src.bookList)
        val out = mutableListOf<OnlineBook>()
        for (item in items) {
            val name = extractCss(item, src.nameRule)?.trim() ?: continue
            if (name.length < 2) continue
            val author = extractCss(item, src.authorRule)?.trim().orEmpty()
            val cover = extractCss(item, src.coverRule)?.trim()?.let { normalizeCover(it) }
            val desc = extractCss(item, src.descRule)?.trim()
            val link = extractCss(item, src.urlRule)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { resolve(src.baseUrl, it) }
                ?: fallbackLink(item, src.baseUrl)
            out.add(toBook(src, name, author, cover, desc, link))
            if (out.size >= max) break
        }
        return out
    }

    // ── JSON（API）源 ─────────────────────────────────────────

    private fun parseJsonSource(src: BookSource, content: String, max: Int): List<OnlineBook> {
        val root = try { JSONObject(content) } catch (_: Exception) { JSONArray(content) }
        val list = resolveJsonPath(root, src.bookList)
        val items = when (list) {
            is JSONArray -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            is JSONObject -> listOf(list)
            else -> emptyList()
        }
        val out = mutableListOf<OnlineBook>()
        for (item in items) {
            val name = resolveJsonPath(item, src.nameRule)?.toString()?.trim() ?: continue
            if (name.length < 2) continue
            val author = resolveJsonPath(item, src.authorRule)?.toString()?.trim().orEmpty()
            val cover = resolveJsonPath(item, src.coverRule)?.toString()?.trim()?.let { normalizeCover(it) }
            val desc = resolveJsonPath(item, src.descRule)?.toString()?.trim()
            val link = if (src.urlRule.isNotBlank())
                resolve(src.baseUrl, renderTemplate(src.urlRule, item)) else ""
            out.add(toBook(src, name, author, cover, desc?.takeIf { it.isNotBlank() }, link.takeIf { it.isNotBlank() }))
            if (out.size >= max) break
        }
        return out
    }

    private fun toBook(src: BookSource, name: String, author: String, cover: String?, desc: String?, link: String?): OnlineBook =
        OnlineBook(
            key = "${src.keyPrefix}:${link ?: name}",
            title = name,
            author = author.ifBlank { "未知作者" },
            coverUrl = cover,
            description = desc,
            rating = null,
            link = link,
            source = src.sourceName,
        )

    private fun fallbackLink(item: Element, baseUrl: String): String? {
        val a = item.selectFirst("a[href]") ?: return null
        return resolve(baseUrl, a.attr("href"))
    }

    private fun normalizeCover(url: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        val low = u.lowercase()
        val looksImage = low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") ||
            low.endsWith(".webp") || low.endsWith(".gif") ||
            "/cover" in low || "/image" in low || "cover." in low
        if (!looksImage) return null
        return resolveUrl(u)
    }

    // ── 规则解析：CSS 选择器链 ─────────────────────────────────

    private val ATTRS = setOf("text", "href", "src", "alt", "title", "content", "onclick")

    /** 单个 @-分段 → (jsoup 选择器, 下标，默认 0)。`tag.x`/`class.x`/`id.x`/`.x`/`#x`/裸标签，尾部 `.N` 为下标。 */
    private fun parseCssSegment(seg: String): Pair<String, Int> {
        var s = seg.trim()
        var index = 0
        val mIdx = Regex("\\.(\\d+)$").find(s)
        if (mIdx != null) {
            index = mIdx.groupValues[1].toInt()
            s = s.substring(0, mIdx.range.first)
        }
        val selector = when {
            s.startsWith("tag.") -> s.removePrefix("tag.")
            s.startsWith("class.") -> "." + s.removePrefix("class.")
            s.startsWith("id.") -> "#" + s.removePrefix("id.")
            else -> s
        }
        return selector to index
    }

    /** 选择结果列表：链式导航，最后一步返回全部匹配（bookList 用）；`!N` 排除下标。 */
    private fun selectBookList(doc: Element, rule: String): List<Element> {
        val mExc = Regex("!(\\d+)$").find(rule)
        val exclude = mExc?.groupValues?.get(1)?.toIntOrNull()
        val expr = if (mExc != null) rule.substring(0, mExc.range.first) else rule
        val segs = expr.split("@").filter { it.isNotBlank() }
        if (segs.isEmpty()) return emptyList()
        var els: List<Element> = listOf(doc)
        for ((i, seg) in segs.withIndex()) {
            val (sel, idx) = parseCssSegment(seg)
            els = if (i == segs.size - 1) {
                els.flatMap { it.select(sel) }
            } else {
                els.mapNotNull { it.select(sel).getOrNull(idx) }
            }
        }
        return if (exclude != null) els.filterIndexed { i, _ -> i != exclude } else els
    }

    /** 抽取单个元素值（name/author/cover/url）：链式导航到最后一层元素，再取 @attr 或文本。 */
    private fun extractCss(item: Element, rule: String): String? {
        if (rule.isBlank()) return null
        val main = rule.substringBefore("##")
        val replaces = rule.substringAfter("##", "").split("##")
        val segs = main.split("@").filter { it.isNotBlank() }
        if (segs.isEmpty()) return null
        val last = segs.last()
        val attr: String
        val stepSegs: List<String>
        if (last in ATTRS || last.contains("-")) {
            attr = last
            stepSegs = segs.dropLast(1)
        } else {
            attr = "text"
            stepSegs = segs
        }
        var el: Element? = item
        for (seg in stepSegs) {
            val (sel, idx) = parseCssSegment(seg)
            el = el?.select(sel)?.getOrNull(idx) ?: return null
        }
        val e = el ?: return null
        val raw = if (attr == "text") e.text() else e.attr(attr)
        return applyReplaces(raw, replaces)
    }

    private fun applyReplaces(value: String, replaces: List<String>): String {
        var v = value
        var i = 0
        while (i < replaces.size) {
            val pattern = replaces[i]
            val replacement = if (i + 1 < replaces.size) replaces[i + 1] else ""
            v = try { Regex(pattern).replace(v, replacement) } catch (_: Exception) { v }
            i += 2
        }
        return v
    }

    // ── JSON 规则：最小 JSONPath ───────────────────────────────

    private fun isJsonRule(s: String): Boolean =
        s.startsWith("$") || s.startsWith("@json:")

    private fun resolveJsonPath(obj: Any?, path: String): Any? {
        var cur: Any? = obj
        val p = path.trim().removePrefix("@json:").removePrefix("$")
        if (p.isBlank()) return cur
        for (part in p.split(".")) {
            if (part.isBlank()) continue
            cur = when (cur) {
                is JSONObject -> if (cur.has(part)) cur.opt(part) else null
                is JSONArray -> {
                    val idx = part.trim('[', ']').toIntOrNull()
                    when {
                        idx != null -> cur.opt(idx)
                        part == "[*]" || part == "*" -> cur
                        else -> null
                    }
                }
                else -> null
            }
            if (cur == null) break
        }
        return cur
    }

    /** 把 `{{$.a.b}}` 占位符替换为 JSON 字段值（JSON 源 urlRule 模板用）。 */
    private fun renderTemplate(template: String, json: JSONObject): String =
        Regex("\\{\\{\\$([^}]+)\\}\\}").replace(template) { m ->
            resolveJsonPath(json, "$" + m.groupValues[1])?.toString() ?: ""
        }

    // ── searchUrl / URL 工具 ───────────────────────────────────

    /** 解析 `路径,{'method':'post','body':'...','charset':'gbk'}` 形式；无逗号则按 GET。 */
    private fun parseSearchUrl(searchUrl: String): SearchSpec {
        val comma = searchUrl.indexOf(',')
        if (comma > 0 && searchUrl.substring(comma + 1).trimStart().startsWith("{")) {
            val path = searchUrl.substring(0, comma).trim()
            val opts = runCatching {
                JSONObject(searchUrl.substring(comma + 1).trim().replace('\'', '"'))
            }.getOrDefault(JSONObject())
            return SearchSpec(
                path = path,
                method = opts.optString("method", "GET").uppercase(),
                body = opts.optString("body", ""),
                charset = opts.optString("charset", "utf-8"),
            )
        }
        return SearchSpec(path = searchUrl.trim(), method = "GET", body = "", charset = "utf-8")
    }

    private fun resolve(baseUrl: String, href: String): String {
        val h = href.trim()
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:$h"
        return baseUrl.trimEnd('/') + "/" + h.trimStart('/')
    }

    /** 相对路径转绝对（封面可能是相对路径）。 */
    private fun resolveUrl(url: String): String? {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u
        else if (u.startsWith("//")) "https:$u"
        else null
    }

    private fun loadSources(context: Context, assetName: String): List<BookSource> = try {
        val text = context.assets.open(assetName)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val s = BookSource(
                sourceName = o.optString("sourceName"),
                baseUrl = o.optString("baseUrl"),
                searchUrl = o.optString("searchUrl"),
                bookList = o.optString("bookList"),
                nameRule = o.optString("nameRule"),
                authorRule = o.optString("authorRule"),
                coverRule = o.optString("coverRule"),
                urlRule = o.optString("urlRule"),
                descRule = o.optString("descRule"),
                keyPrefix = o.optString("keyPrefix", "xs"),
            )
            if (s.sourceName.isNotBlank() && s.baseUrl.isNotBlank() && s.searchUrl.isNotBlank() &&
                s.bookList.isNotBlank() && s.nameRule.isNotBlank()
            ) s else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
