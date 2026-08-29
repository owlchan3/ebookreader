package com.ebookreader.data.network

import android.content.Context

/** 用户自定义章节识别正则（含开关状态）。 */
data class ChapterPattern(val pattern: String, val enabled: Boolean = true)

/** 用户偏好题材（带权重 + 手动英文标签，用于推荐）。 */
data class PreferredGenre(
    val name: String,
    val weight: Double = 1.0,
    val englishTags: String = "",
)

class ApiKeyManager(context: Context) {
    private val prefs = context.getSharedPreferences("ai_plugin_prefs", Context.MODE_PRIVATE)

    companion object {
        /** Quick-select model presets. User can also type any custom model name. */
        val MODEL_PRESETS = listOf(
            "deepseek-chat" to "DeepSeek-Chat (通用)",
            "deepseek-reasoner" to "DeepSeek-R1 (推理)",
            "claude-sonnet-5" to "Claude Sonnet 5",
            "claude-opus-5" to "Claude Opus 5",
            "gpt-4o" to "GPT-4o",
            "gpt-4o-mini" to "GPT-4o Mini",
        )
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        /** Pixiv App API 公开 client 凭据（与 pixivpy 一致，用户可在设置里覆盖）。 */
        const val PIXIV_CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT"
        const val PIXIV_CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj"
    }

    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    fun setApiKey(key: String) = prefs.edit().putString("api_key", key).apply()

    fun isEnabled(): Boolean = prefs.getBoolean("ai_plugin_enabled", false)

    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean("ai_plugin_enabled", enabled).apply()

    fun clearApiKey() = prefs.edit().remove("api_key").apply()

    fun getModel(): String = prefs.getString("ai_model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(model: String) = prefs.edit().putString("ai_model", model).apply()

    fun getBaseUrl(): String = prefs.getString("ai_base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBaseUrl(url: String) = prefs.edit().putString("ai_base_url", url).apply()

    // ── AI 拆书 ─────────────────────────────────────────────

    /** 拆书档位：compact（精简）/ standard（标准，默认）/ deep（深度）。 */
    fun getDecomposeTier(): String = prefs.getString("decompose_tier", "standard") ?: "standard"

    fun setDecomposeTier(tier: String) = prefs.edit().putString("decompose_tier", tier).apply()

    /** 拆书 Map（逐章摘要）用的低价模型；空 = 沿用主模型。 */
    fun getDecomposeMapModel(): String = prefs.getString("decompose_map_model", "") ?: ""

    fun setDecomposeMapModel(model: String) =
        prefs.edit().putString("decompose_map_model", model.trim()).apply()

    /** 拆书 Reduce（全书总结）用的强模型；空 = 沿用主模型。 */
    fun getDecomposeReduceModel(): String = prefs.getString("decompose_reduce_model", "") ?: ""

    fun setDecomposeReduceModel(model: String) =
        prefs.edit().putString("decompose_reduce_model", model.trim()).apply()

    /** 拆书独立 API Key；空 = 沿用主 API Key。 */
    fun getDecomposeApiKey(): String = prefs.getString("decompose_api_key", "") ?: ""

    fun setDecomposeApiKey(key: String) =
        prefs.edit().putString("decompose_api_key", key.trim()).apply()

    /** 拆书独立 API 地址；空 = 沿用主 API 地址。 */
    fun getDecomposeBaseUrl(): String = prefs.getString("decompose_base_url", "") ?: ""

    fun setDecomposeBaseUrl(url: String) =
        prefs.edit().putString("decompose_base_url", url.trim()).apply()

    // ── Chat input persistence (survives ViewModel destruction on back-nav) ──

    fun getChatInputText(bookId: Long): String =
        prefs.getString("chat_input_$bookId", "") ?: ""

    fun setChatInputText(bookId: Long, text: String) =
        prefs.edit().putString("chat_input_$bookId", text).apply()

    fun clearChatInputText(bookId: Long) =
        prefs.edit().remove("chat_input_$bookId").apply()

    // ── Custom chapter regex patterns ─────────────────────────────────

    /** 返回所有自定义正则（含开关状态），供设置界面展示。 */
    fun getChapterPatterns(): List<ChapterPattern> {
        val json = prefs.getString("chapter_patterns", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    ChapterPattern(obj.optString("pattern", ""), obj.optBoolean("enabled", true))
                } else {
                    // 向后兼容：旧格式是纯字符串数组
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { ChapterPattern(it, true) }
                }
            }.filter { it.pattern.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    /** 仅返回「启用」的自定义正则，供章节识别使用。 */
    fun getEnabledChapterPatterns(): List<String> =
        getChapterPatterns().filter { it.enabled }.map { it.pattern }

    fun addCustomChapterPattern(pattern: String) {
        val patterns = getChapterPatterns().toMutableList()
        if (patterns.none { it.pattern == pattern }) {
            patterns.add(ChapterPattern(pattern, true))
            saveChapterPatterns(patterns)
        }
    }

    fun removeCustomChapterPattern(pattern: String) {
        val patterns = getChapterPatterns().toMutableList()
        patterns.removeAll { it.pattern == pattern }
        saveChapterPatterns(patterns)
    }

    fun setChapterPatternEnabled(pattern: String, enabled: Boolean) {
        val patterns = getChapterPatterns().map {
            if (it.pattern == pattern) it.copy(enabled = enabled) else it
        }
        saveChapterPatterns(patterns)
    }

    private fun saveChapterPatterns(patterns: List<ChapterPattern>) {
        val arr = org.json.JSONArray()
        patterns.forEach { p ->
            arr.put(org.json.JSONObject().apply {
                put("pattern", p.pattern)
                put("enabled", p.enabled)
            })
        }
        prefs.edit().putString("chapter_patterns", arr.toString()).apply()
    }

    // ── Hidden chapter titles (per-book) ─────────────────────────────

    fun getHiddenChapterTitles(bookId: Long): Set<String> {
        val json = prefs.getString("hidden_chapters_$bookId", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    fun addHiddenChapterTitle(bookId: Long, title: String) {
        val titles = getHiddenChapterTitles(bookId).toMutableSet()
        titles.add(title)
        val arr = org.json.JSONArray(titles.toList())
        prefs.edit().putString("hidden_chapters_$bookId", arr.toString()).apply()
    }

    fun removeHiddenChapterTitle(bookId: Long, title: String) {
        val titles = getHiddenChapterTitles(bookId).toMutableSet()
        if (titles.remove(title)) {
            val arr = org.json.JSONArray(titles.toList())
            prefs.edit().putString("hidden_chapters_$bookId", arr.toString()).apply()
        }
    }

    fun clearHiddenChapterTitles(bookId: Long) {
        prefs.edit().remove("hidden_chapters_$bookId").apply()
    }

    // ── TTS (Text-to-Speech) plugin ────────────────────────────────

    fun isTtsEnabled(): Boolean = prefs.getBoolean("tts_enabled", false)

    fun setTtsEnabled(enabled: Boolean) = prefs.edit().putBoolean("tts_enabled", enabled).apply()

    fun getTtsSpeed(): Float = prefs.getFloat("tts_speed", 1.0f)

    fun setTtsSpeed(speed: Float) = prefs.edit().putFloat("tts_speed", speed.coerceIn(0.5f, 2.0f)).apply()

    fun getTtsEngine(): String = prefs.getString("tts_engine", "") ?: ""

    fun setTtsEngine(engine: String) = prefs.edit().putString("tts_engine", engine).apply()

    /**
     * TTS 提供方：`system`（系统语音引擎）、`openai`（OpenAI 兼容云端接口）。
     */
    fun getTtsProvider(): String = prefs.getString("tts_provider", "system") ?: "system"

    fun setTtsProvider(provider: String) = prefs.edit().putString("tts_provider", provider).apply()

    /** OpenAI 兼容 TTS 接口地址（Base URL，不含 /audio/speech 路径）。 */
    fun getTtsOpenAiUrl(): String = prefs.getString("tts_openai_url", "") ?: ""

    fun setTtsOpenAiUrl(url: String) = prefs.edit().putString("tts_openai_url", url).apply()

    /** OpenAI 兼容 TTS API Key。 */
    fun getTtsOpenAiKey(): String = prefs.getString("tts_openai_key", "") ?: ""

    fun setTtsOpenAiKey(key: String) = prefs.edit().putString("tts_openai_key", key).apply()

    /** OpenAI 兼容 TTS 模型。 */
    fun getTtsOpenAiModel(): String = prefs.getString("tts_openai_model", "") ?: ""

    fun setTtsOpenAiModel(model: String) = prefs.edit().putString("tts_openai_model", model).apply()

    /** OpenAI 兼容 TTS 音色（裸名或 `模型:音色` 等完整写法，由服务方约定）。 */
    fun getTtsOpenAiVoice(): String = prefs.getString("tts_openai_voice", "") ?: ""

    fun setTtsOpenAiVoice(voice: String) = prefs.edit().putString("tts_openai_voice", voice).apply()

    // ── 智能推荐 (Recommendation) 插件 ─────────────────────────────

    fun isRecommendEnabled(): Boolean = prefs.getBoolean("recommend_enabled", false)

    fun setRecommendEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("recommend_enabled", enabled).apply()

    /** 「不感兴趣」记录：本地书用 "book:${id}"，联网书用 "gb:${id}" 或 "ol:${key}"。 */
    fun getDismissedRecommendations(): Set<String> {
        val json = prefs.getString("dismissed_recommendations", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    fun addDismissedRecommendation(key: String) {
        val set = getDismissedRecommendations().toMutableSet()
        set.add(key)
        prefs.edit()
            .putString("dismissed_recommendations", org.json.JSONArray(set.toList()).toString())
            .apply()
    }

    fun clearDismissedRecommendations() =
        prefs.edit().remove("dismissed_recommendations").apply()

    /** 「最近展示」记录（key -> 展示时间戳），用于一段时间内不重复推荐。 */
    fun getRecentlyShownRecommendations(): Map<String, Long> {
        val json = prefs.getString("recently_shown_recommendations", "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, Long>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = obj.optLong(k, 0L)
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun addRecentlyShownRecommendations(keys: Set<String>) {
        val now = System.currentTimeMillis()
        val map = getRecentlyShownRecommendations().toMutableMap()
        for (k in keys) map[k] = now
        // 清理超过 1 天的记录
        val cutoff = now - 24L * 3600 * 1000
        val pruned = map.filterValues { it >= cutoff }
        val obj = org.json.JSONObject()
        for ((k, v) in pruned) obj.put(k, v)
        prefs.edit().putString("recently_shown_recommendations", obj.toString()).apply()
    }

    fun clearRecentlyShownRecommendations() =
        prefs.edit().remove("recently_shown_recommendations").apply()

    /** Google Books API Key（可选，留空则用匿名配额，容易触顶；填自己的 key 可提升额度）。 */
    fun getGoogleBooksApiKey(): String = prefs.getString("google_books_api_key", "") ?: ""

    fun setGoogleBooksApiKey(key: String) =
        prefs.edit().putString("google_books_api_key", key).apply()

    /** 自定义补充搜索地址（笔趣阁等基础地址），可配置多个，空列表则关闭。 */
    fun getCustomSearchUrls(): List<String> {
        val json = prefs.getString("custom_search_urls", null)
        if (json == null) {
            // 迁移旧字段
            val old = prefs.getString("web_novel_base_url", "") ?: ""
            return if (old.isNotBlank()) listOf(old) else listOf("https://www.52bqg.net")
        }
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }

    fun setCustomSearchUrls(urls: List<String>) {
        val arr = org.json.JSONArray()
        urls.filter { it.isNotBlank() }.distinct().forEach { arr.put(it) }
        prefs.edit().putString("custom_search_urls", arr.toString()).apply()
    }

    fun addCustomSearchUrl(url: String) {
        val urls = getCustomSearchUrls().toMutableList()
        if (url.isNotBlank() && url !in urls) urls.add(url)
        setCustomSearchUrls(urls)
    }

    fun removeCustomSearchUrl(url: String) {
        setCustomSearchUrls(getCustomSearchUrls().filter { it != url })
    }

    // ── 偏好题材（用户手动输入，带权重，用于推荐）────────────────────

    fun getPreferredGenres(): List<PreferredGenre> {
        val json = prefs.getString("preferred_genres", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    PreferredGenre(
                        obj.optString("name"),
                        obj.optDouble("weight", 1.0),
                        obj.optString("englishTags", ""),
                    )
                } else {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { PreferredGenre(it, 1.0) }
                }
            }.filter { it.name.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    fun setPreferredGenres(genres: List<PreferredGenre>) {
        val arr = org.json.JSONArray()
        genres.filter { it.name.isNotBlank() }.forEach { g ->
            arr.put(org.json.JSONObject().apply {
                put("name", g.name)
                put("weight", g.weight)
                put("englishTags", g.englishTags)
            })
        }
        prefs.edit().putString("preferred_genres", arr.toString()).apply()
    }

    fun setPreferredGenreEnglishTags(name: String, englishTags: String) {
        setPreferredGenres(getPreferredGenres().map { if (it.name == name) it.copy(englishTags = englishTags) else it })
    }

    fun addPreferredGenre(name: String) {
        val list = getPreferredGenres().toMutableList()
        if (name.isNotBlank() && list.none { it.name == name }) {
            // 若内置对照表里有该题材，自动填充英文标签，方便用户修改
            val autoEnglish = GenreTranslations.CHINESE_TO_ENGLISH[name] ?: ""
            list.add(PreferredGenre(name, 1.0, autoEnglish))
        }
        setPreferredGenres(list)
    }

    fun removePreferredGenre(name: String) {
        setPreferredGenres(getPreferredGenres().filter { it.name != name })
    }

    fun setPreferredGenreWeight(name: String, weight: Double) {
        setPreferredGenres(getPreferredGenres().map { if (it.name == name) it.copy(weight = weight) else it })
    }

    // ── Pixiv 小说推荐（凭据由用户自行填写，不硬编码）──────────────────

    fun getPixivRefreshToken(): String = prefs.getString("pixiv_refresh_token", "") ?: ""

    fun setPixivRefreshToken(token: String) =
        prefs.edit().putString("pixiv_refresh_token", token).apply()

    fun getPixivClientId(): String =
        prefs.getString("pixiv_client_id", PIXIV_CLIENT_ID) ?: PIXIV_CLIENT_ID

    fun setPixivClientId(id: String) = prefs.edit().putString("pixiv_client_id", id).apply()

    fun getPixivClientSecret(): String =
        prefs.getString("pixiv_client_secret", PIXIV_CLIENT_SECRET) ?: PIXIV_CLIENT_SECRET

    fun setPixivClientSecret(secret: String) =
        prefs.edit().putString("pixiv_client_secret", secret).apply()

    // ── 精确页数缓存（逐章，按 bookId + 字号 + 屏幕宽度 区分）──────────

    fun getCachedPageCounts(key: String): Map<Int, Int> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<Int, Int>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k.toIntOrNull() ?: continue] = obj.optInt(k)
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun setCachedPageCounts(key: String, counts: Map<Int, Int>) {
        val obj = org.json.JSONObject()
        for ((k, v) in counts) obj.put(k.toString(), v)
        prefs.edit().putString(key, obj.toString()).apply()
    }

    // ── 逐章 position 计数缓存（按 bookId，字号无关）──────────
    // positionsByReadingOrder() 对长书很慢；把每章的 position 数持久化，后续打开可跳过计算。

    fun getCachedPositionCounts(key: String): IntArray {
        val json = prefs.getString(key, null) ?: return IntArray(0)
        return try {
            val arr = org.json.JSONArray(json)
            IntArray(arr.length()) { arr.optInt(it, 0) }
        } catch (_: Exception) { IntArray(0) }
    }

    fun setCachedPositionCounts(key: String, counts: IntArray) {
        val arr = org.json.JSONArray()
        for (c in counts) arr.put(c)
        prefs.edit().putString(key, arr.toString()).apply()
    }

}
