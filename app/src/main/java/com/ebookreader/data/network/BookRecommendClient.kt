package com.ebookreader.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 联网获取的真实出版书（推荐用）。 */
data class OnlineBook(
    val key: String,        // 唯一标识，用于「不感兴趣」去重
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String?,
    val rating: String?,    // 评分（如 "4.5"），可能为 null
    val link: String?,      // 详情页链接
    val source: String,     // "Google Books" / "Open Library" / "Pixiv" / "网文"
    val popularity: Double = 0.0,  // 热度（log 缩放，用于「优先热门/经典」）
    val tags: List<String> = emptyList(),  // 分类/题材标签（参与推荐匹配）
)

/**
 * 出版书推荐客户端：Google Books + Open Library，两者都是免费、无需登录/密钥的官方接口。
 * 均返回真实存在的出版书（书名/作者/封面/简介），避免 AI 幻觉。
 */
class BookRecommendClient(private val googleBooksApiKey: String = "") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Google Books 全文搜索，`query` 支持 `subject:` / `inauthor:` 等限定。 */
    suspend fun searchGoogleBooks(query: String, maxResults: Int = 5): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            try {
                val keyParam = if (googleBooksApiKey.isNotBlank()) "&key=$googleBooksApiKey" else ""
                val url = "https://www.googleapis.com/books/v1/volumes?q=" +
                    URLEncoder.encode(query, "UTF-8") + "&maxResults=$maxResults$keyParam"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: "")
                val items = json.optJSONArray("items") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val vi = item.optJSONObject("volumeInfo") ?: continue
                        val id = item.optString("id", "")
                        val title = vi.optString("title", "")
                        if (title.isBlank()) continue
                        val authors = vi.optJSONArray("authors")
                        val author = authors?.takeIf { it.length() > 0 }?.getString(0).orEmpty()
                        val cover = vi.optJSONObject("imageLinks")?.optString("thumbnail").orEmpty()
                        val rating = vi.optDouble("averageRating", -1.0)
                        val ratingsCount = vi.optInt("ratingsCount", 0)
                        // 优先用 canonicalVolumeLink（官方规范链接），避免 infoLink 跳「我的书库」/ 搜索结果页
                        val link = vi.optString("canonicalVolumeLink", "")
                            .ifBlank { "https://books.google.com/books?id=$id" }
                        val categories = vi.optJSONArray("categories")
                        val tags = if (categories != null) {
                            (0 until categories.length()).mapNotNull { categories.optString(it).takeIf { it.isNotBlank() } }
                        } else emptyList()
                        add(
                            OnlineBook(
                                key = "gb:$id",
                                title = title,
                                author = author.ifBlank { "未知作者" },
                                coverUrl = cover.takeIf { it.isNotBlank() },
                                description = vi.optString("description", "").takeIf { it.isNotBlank() },
                                rating = if (rating > 0) String.format("%.1f", rating) else null,
                                link = link.takeIf { it.isNotBlank() },
                                source = "Google Books",
                                popularity = Math.log10(ratingsCount + 1.0),
                                tags = tags,
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** Open Library 搜索（完全免费无认证）。 */
    suspend fun searchOpenLibrary(query: String, maxResults: Int = 5): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://openlibrary.org/search.json?q=" +
                    URLEncoder.encode(query, "UTF-8") +
                    "&limit=$maxResults&fields=title,author_name,cover_i,first_publish_year,key,edition_count,subject"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: "")
                val docs = json.optJSONArray("docs") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until docs.length()) {
                        val doc = docs.optJSONObject(i) ?: continue
                        val title = doc.optString("title", "")
                        if (title.isBlank()) continue
                        val authors = doc.optJSONArray("author_name")
                        val author = authors?.takeIf { it.length() > 0 }?.getString(0).orEmpty()
                        val coverId = doc.optInt("cover_i", -1)
                        val key = doc.optString("key", "")
                        val year = doc.optInt("first_publish_year", 0)
                        val editionCount = doc.optInt("edition_count", 0)
                        val subjects = doc.optJSONArray("subject")
                        val tags = if (subjects != null) {
                            (0 until subjects.length()).mapNotNull { subjects.optString(it).takeIf { it.isNotBlank() } }
                        } else emptyList()
                        add(
                            OnlineBook(
                                key = "ol:$key",
                                title = title,
                                author = author.ifBlank { "未知作者" },
                                coverUrl = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-M.jpg" else null,
                                description = if (year > 0) "首版 $year" else null,
                                rating = null,
                                link = if (key.isNotBlank()) "https://openlibrary.org$key" else null,
                                source = "Open Library",
                                popularity = Math.log10(editionCount + 1.0),
                                tags = tags,
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * SaltyLeo 图书元数据库（个人免费 API，豆瓣数据，无需密钥）。
     * GET https://book-db-v1.saltyleo.com/?keyword=书名/作者，返回相关图书。
     * 字段名在不同版本间略有差异，这里做了宽容解析以兼容 title/author/cover/简介/出版社/评分等。
     */
    suspend fun searchSaltyLeo(query: String, maxResults: Int = 10): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://book-db-v1.saltyleo.com/?keyword=" + URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()

                // 顶层可能是数组，也可能是 {data|results|books|list: [...]}
                val arr = parseResultArray(body) ?: return@withContext emptyList()

                buildList {
                    for (i in 0 until minOf(arr.length(), maxResults)) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = firstNonBlank(o.optString("title"), o.optString("name")).ifBlank { continue }
                        val author = firstNonBlank(
                            o.optString("author"),
                            o.optString("author_name"),
                            optArrayFirst(o, "authors"),
                            optArrayFirst(o, "author_list"),
                        ).ifBlank { "未知作者" }
                        val cover = firstNonBlank(
                            o.optString("cover"),
                            o.optString("cover_url"),
                            o.optString("image"),
                            o.optString("img"),
                            o.optString("pic"),
                        ).takeIf { it.startsWith("http") }?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }

                        val publisher = firstNonBlank(o.optString("publisher"), o.optString("press")).ifBlank { null }
                        val price = o.optString("price", "").takeIf { it.isNotBlank() && it != "0" }
                        val intro = firstNonBlank(
                            o.optString("intro"),
                            o.optString("summary"),
                            o.optString("description"),
                            o.optString("content"),
                        ).ifBlank { null }
                        val rating = extractRating(o)?.let { String.format("%.1f", it) }

                        val description = listOfNotNull(
                            publisher?.let { "出版 $it" },
                            price?.let { "¥$it" },
                            intro,
                        ).joinToString(" · ").takeIf { it.isNotBlank() }

                        add(
                            OnlineBook(
                                key = "sl:${title}|${author}",
                                title = title,
                                author = author,
                                coverUrl = cover,
                                description = description,
                                rating = rating,
                                link = firstNonBlank(o.optString("url"), o.optString("link")).ifBlank { null },
                                source = "SaltyLeo",
                                popularity = 0.0,
                                tags = emptyList(),
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun parseResultArray(body: String): org.json.JSONArray? {
        try {
            return org.json.JSONArray(body)
        } catch (_: Exception) {}
        return try {
            val obj = org.json.JSONObject(body)
            obj.optJSONArray("data")
                ?: obj.optJSONArray("results")
                ?: obj.optJSONArray("books")
                ?: obj.optJSONArray("list")
        } catch (_: Exception) { null }
    }

    private fun optArrayFirst(obj: JSONObject, key: String): String {
        val arr = obj.optJSONArray(key) ?: return ""
        return if (arr.length() > 0) arr.optString(0, "") else ""
    }

    private fun extractRating(o: JSONObject): Double? {
        // 评分可能是数字、"9.4" 字符串，或 {average|value: ...} 对象
        val raw = o.opt("rating")
        when (raw) {
            is Number -> return raw.toDouble().takeIf { it > 0 }
            is String -> return raw.toDoubleOrNull()?.takeIf { it > 0 }
            is JSONObject -> {
                val v = raw.opt("average") ?: raw.opt("value") ?: raw.opt("score")
                return when (v) {
                    is Number -> v.toDouble().takeIf { it > 0 }
                    is String -> v.toDoubleOrNull()?.takeIf { it > 0 }
                    else -> null
                }
            }
        }
        val score = o.optDouble("score", -1.0)
        val average = o.optDouble("average", -1.0)
        return listOf(score, average).firstOrNull { it > 0 }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()
}
