package com.ebookreader.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 自定义补充搜索（笔趣阁等）客户端。这些站点没有公开 API，只能解析搜索页 HTML，
 * 因此域名会经常变动、结构也可能调整，这里做成「可配置多个基础地址 + 泛化解析」，
 * 解析失败时静默返回空列表，不影响出版书推荐。
 */
class WebNovelClient(private val baseUrls: List<String>) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, maxResults: Int = 5): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            if (baseUrls.isEmpty()) return@withContext emptyList()
            val out = mutableListOf<OnlineBook>()
            val seen = mutableSetOf<String>()
            for (base in baseUrls) {
                val results = searchOne(base, query, maxResults)
                for (b in results) {
                    if (seen.add(b.title)) out.add(b)
                }
                if (out.size >= maxResults) break
            }
            out.take(maxResults)
        }

    /** 测试某个地址是否生效（搜索「小说」，返回是否有结果）。 */
    suspend fun testUrl(baseUrl: String): Boolean =
        searchOne(baseUrl, "小说", 1).isNotEmpty()

    private suspend fun searchOne(baseUrl: String, query: String, maxResults: Int): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "${baseUrl.trimEnd('/')}/modules/article/search.php?searchkey=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                    )
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseResults(baseUrl, body).take(maxResults)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 泛化解析：提取结果页里「书名链接」，href 含 book/info/article 或数字路径。 */
    private fun parseResults(baseUrl: String, html: String): List<OnlineBook> {
        val result = mutableListOf<OnlineBook>()
        val linkPattern = Regex("""<a[^>]+href="([^"]+)"[^>]*>([^<]{2,40})</a>""")
        val skipWords = listOf("首页", "排行", "分类", "搜索", "书签", "阅读记录", "登录", "注册")
        for (m in linkPattern.findAll(html)) {
            val href = m.groupValues[1].trim()
            val title = m.groupValues[2].trim()
            if (title.length < 2 || title.length > 40) continue
            if (skipWords.any { title.contains(it) }) continue
            val looksLikeBook = href.contains("book") || href.contains("info") ||
                href.contains("article") || Regex("""/\d+""").containsMatchIn(href)
            if (!looksLikeBook) continue
            val fullUrl = if (href.startsWith("http")) href else baseUrl.trimEnd('/') + href
            result.add(
                OnlineBook(
                    key = "wn:$href",
                    title = title,
                    author = "",
                    coverUrl = null,
                    description = null,
                    rating = null,
                    link = fullUrl,
                    source = "网文",
                )
            )
        }
        return result.distinctBy { it.title }
    }
}
