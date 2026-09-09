package com.ebookreader.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pixiv 小说客户端，走 Pixiv App API（与 pixivpy 同款接口）。
 * 凭据（refresh_token / client_id / client_secret）由用户在设置里填写，client_id/secret 有默认值。
 * 请求需带 X-Client-Time / X-Client-Hash 头（Pixiv App API 校验），否则会 403。
 */
class PixivClient(
    private val refreshToken: String,
    private val clientId: String,
    private val clientSecret: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun clientTimeHeaders(): Pair<String, String> {
        val time = (System.currentTimeMillis() / 1000).toString()
        return time to md5(time + HASH_SECRET)
    }

    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiry) return@withContext cachedToken
        try {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("get_secure_url", "1")
                .build()
            val (time, hash) = clientTimeHeaders()
            val request = Request.Builder()
                .url("https://oauth.secure.pixiv.net/auth/token")
                .post(form)
                .header("User-Agent", UA)
                .header("X-Client-Time", time)
                .header("X-Client-Hash", hash)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string() ?: "")
            val token = json.optString("access_token", "").takeIf { it.isNotBlank() }
            if (token != null) {
                cachedToken = token
                val expiresIn = json.optLong("expires_in", 3600L)
                tokenExpiry = now + expiresIn * 1000 - 60_000
            }
            token
        } catch (_: Exception) {
            null
        }
    }

    /** 按标签搜索小说（partial_match_for_tags）。 */
    suspend fun searchNovels(query: String, maxResults: Int = 5): List<OnlineBook> =
        fetchNovels(
            "https://app-api.pixiv.net/v1/search/novel?word=" +
                URLEncoder.encode(query, "UTF-8") + "&search_target=partial_match_for_tags",
            maxResults,
        )

    /** 按书名/简介搜索小说（title_and_caption），用于「相似书名」推荐。 */
    suspend fun searchNovelsByTitle(query: String, maxResults: Int = 6): List<OnlineBook> =
        fetchNovels(
            "https://app-api.pixiv.net/v1/search/novel?word=" +
                URLEncoder.encode(query, "UTF-8") + "&search_target=title_and_caption",
            maxResults,
        )

    /** Pixiv 官方推荐小说（按登录账号的浏览/收藏推荐）。 */
    suspend fun recommendedNovels(maxResults: Int = 5): List<OnlineBook> =
        fetchNovels("https://app-api.pixiv.net/v1/novel/recommended", maxResults)

    /** 测试登录是否成功（用 refresh_token 换 access_token）。 */
    suspend fun testLogin(): Boolean = getAccessToken() != null

    private suspend fun fetchNovels(url: String, maxResults: Int): List<OnlineBook> =
        withContext(Dispatchers.IO) {
            if (refreshToken.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
                return@withContext emptyList()
            }
            try {
                val token = getAccessToken() ?: return@withContext emptyList()
                val (time, hash) = clientTimeHeaders()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", UA)
                    .header("X-Client-Time", time)
                    .header("X-Client-Hash", hash)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: "")
                val novels = json.optJSONArray("novels") ?: return@withContext emptyList()
                buildList {
                    val n = minOf(novels.length(), maxResults)
                    for (i in 0 until n) {
                        val obj = novels.optJSONObject(i) ?: continue
                        val id = obj.optString("id", "")
                        val title = obj.optString("title", "")
                        if (title.isBlank()) continue
                        val author = obj.optJSONObject("user")?.optString("name").orEmpty()
                        val cover = obj.optJSONObject("image_urls")?.optString("medium").orEmpty()
                        val caption = obj.optString("caption", "").take(120)
                        val bookmarks = obj.optInt("total_bookmarks", 0)
                        val tags = obj.optJSONArray("tags")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                                listOf(t.optString("name"), t.optString("translated_name"))
                                    .firstOrNull { it.isNotBlank() }
                            }.take(10)
                        } ?: emptyList()
                        add(
                            OnlineBook(
                                key = "pixiv:$id",
                                title = title,
                                author = author.ifBlank { "未知作者" },
                                coverUrl = cover.takeIf { it.isNotBlank() },
                                description = caption.takeIf { it.isNotBlank() },
                                rating = null,
                                link = "https://www.pixiv.net/novel/show.php?id=$id",
                                source = "Pixiv",
                                popularity = Math.log10(bookmarks + 1.0),
                                tags = tags,
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    companion object {
        private const val UA = "PixivAndroidApp/5.0.234 (Android 6.0; PixivAndroidApp)"
        /** Pixiv App API 请求签名用的 hash_secret（与 pixivpy 一致，不是 client_secret）。 */
        private const val HASH_SECRET = "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c"
    }
}
