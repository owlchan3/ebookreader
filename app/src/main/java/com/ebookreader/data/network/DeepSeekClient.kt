package com.ebookreader.data.network

import com.ebookreader.domain.model.BookChunk
import com.ebookreader.domain.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

class DeepSeekClient(private val apiKeyManager: ApiKeyManager) {

    companion object {
        private const val CHAT_PATH = "/chat/completions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY) // bypass system proxy to avoid response buffering
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun chatUrl(): String = apiKeyManager.getBaseUrl().trimEnd('/') + CHAT_PATH

    /** Non-streaming completion (for smart description generation). */
    suspend fun complete(prompt: String, systemPrompt: String): Result<String> =
        complete(prompt, systemPrompt, null, "", "", 2048)

    /** Non-streaming completion with explicit model override (null/blank = 沿用主模型)。 */
    suspend fun complete(prompt: String, systemPrompt: String, model: String?): Result<String> =
        complete(prompt, systemPrompt, model, "", "", 2048)

    /**
     * 非流式补全，可整体覆盖 model / apiKey / baseUrl（空 = 沿用主配置）。
     * maxTokens 控制输出上限；拆书的大纲/总结需要更大的值避免截断。
     */
    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        model: String?,
        apiKeyOverride: String,
        baseUrlOverride: String,
        maxTokens: Int = 2048,
    ): Result<String> {
        val apiKey = apiKeyOverride.takeIf { it.isNotBlank() } ?: apiKeyManager.getApiKey()
        if (apiKey.isBlank()) return Result.failure(Exception("API Key 未配置"))
        val effectiveModel = model?.takeIf { it.isNotBlank() } ?: apiKeyManager.getModel()
        val baseUrl = baseUrlOverride.takeIf { it.isNotBlank() }
            ?: apiKeyManager.getBaseUrl().trimEnd('/')

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        }
        val body = JSONObject().apply {
            put("model", effectiveModel)
            put("messages", messages)
            put("stream", false)
            put("max_tokens", maxTokens)
        }

        val request = Request.Builder()
            .url(baseUrl + CHAT_PATH)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API 错误 ${response.code}: $responseBody"))
                }
                val json = JSONObject(responseBody)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Result.success(content.trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Streaming chat completion. Invokes callbacks for each token.
     * For deepseek-reasoner, reasoning_content is delivered via onReasoningToken. */
    fun streamChat(
        messages: List<ChatMessage>,
        systemPrompt: String,
        onToken: (String) -> Unit,
        onReasoningToken: ((String) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit,
        onDiagnostic: ((String) -> Unit)? = null,
    ) {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isBlank()) {
            onError(Exception("API Key 未配置"))
            return
        }

        val messagesJson = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            messages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }
        val body = JSONObject().apply {
            put("model", apiKeyManager.getModel())
            put("messages", messagesJson)
            put("stream", true)
            put("max_tokens", 4096)
        }

        val request = Request.Builder()
            .url(chatUrl())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        Thread {
            try {
                val diag = StringBuilder()

                diag.appendLine("=== HTTP请求追踪 ===")
                diag.appendLine("URL: ${chatUrl()}")
                diag.appendLine("Model: ${apiKeyManager.getModel()}")
                diag.appendLine("Messages: ${messages.size + 1}条 (含system)")
                diag.appendLine("SystemPrompt: ${systemPrompt.length}字")
                diag.appendLine("Body: ${body.toString().length}字")
                diag.appendLine("--- 发送请求中 ---")
                onDiagnostic?.invoke(diag.toString())

                val response = client.newCall(request).execute()

                diag.appendLine("--- 响应 ---")
                diag.appendLine("HTTP状态: ${response.code}")
                diag.appendLine("Content-Type: ${response.header("Content-Type") ?: "无"}")
                onDiagnostic?.invoke(diag.toString())

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    diag.appendLine("!!! API错误: code=${response.code}")
                    diag.appendLine("错误体: ${errorBody.take(500)}")
                    onDiagnostic?.invoke(diag.toString())
                    onError(Exception("API 错误 ${response.code}: $errorBody"))
                    return@Thread
                }
                val source = response.body?.source() ?: run {
                    diag.appendLine("!!! 响应体为空")
                    onDiagnostic?.invoke(diag.toString())
                    onError(Exception("空响应"))
                    return@Thread
                }

                var lineCount = 0
                var dataLineCount = 0
                var tokenCount = 0
                var reasoningCount = 0
                var firstReasoningToken = true
                var firstContentToken = true
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    lineCount++
                    // Log first 20 raw lines
                    if (lineCount <= 20) {
                        diag.appendLine("RAW$lineCount: $line")
                    }
                    if (line.startsWith("data: ")) {
                        dataLineCount++
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            diag.appendLine("收到[DONE] data行=$dataLineCount token=$tokenCount")
                            onDiagnostic?.invoke(diag.toString())
                            break
                        }
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
                            if (delta != null) {
                                // reasoning_content (deepseek-reasoner: chain-of-thought)
                                if (!delta.isNull("reasoning_content")) {
                                    val reasoning = delta.getString("reasoning_content")
                                    if (reasoning.isNotEmpty()) {
                                        reasoningCount++
                                        if (firstReasoningToken) {
                                            firstReasoningToken = false
                                            diag.appendLine("REASONING-START (首token: '${reasoning.take(30)}')")
                                        }
                                        onReasoningToken?.invoke(reasoning)
                                    }
                                }
                                // content (final answer)
                                if (!delta.isNull("content")) {
                                    val content = delta.getString("content")
                                    if (content.isNotEmpty()) {
                                        if (firstContentToken) {
                                            firstContentToken = false
                                            diag.appendLine("CONTENT-START (首token: '${content.take(30)}') reasoningTokens=$reasoningCount")
                                        }
                                        tokenCount++
                                        if (tokenCount <= 3) {
                                            diag.appendLine("TOKEN$tokenCount: '$content'")
                                        }
                                        onToken(content)
                                    }
                                }
                            } else {
                                if (dataLineCount <= 3) {
                                    diag.appendLine("NULL-DELTA$dataLineCount: ${data.take(100)}")
                                }
                            }
                        } catch (e: Exception) {
                            diag.appendLine("PARSE-ERR$dataLineCount: ${data.take(100)} -- ${e.message}")
                        }
                    }
                }
                diag.appendLine("=== 流结束: lines=$lineCount dataLines=$dataLineCount tokens=$tokenCount ===")
                onDiagnostic?.invoke(diag.toString())
                onComplete()
            } catch (e: Exception) {
                onDiagnostic?.invoke("!!! 异常: ${e.message}\n${e.stackTraceToString().take(1000)}")
                onError(e)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Query expansion via LLM (non-streaming, small).
     * Expands a user query into 3 distinct search queries for better BM25 recall.
     */
    suspend fun expandQuery(userQuery: String): Result<List<String>> {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isBlank()) return Result.failure(Exception("API Key 未配置"))

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "你是一个搜索引擎助手。将用户的自然语言问题扩展为3个可独立搜索的中文查询，每个查询从不同角度覆盖问题的关键信息。只输出3行，每行一个查询，不要编号，不要其他内容。")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "请扩展以下问题为3个搜索查询：\n$userQuery")
            })
        }
        val body = JSONObject().apply {
            put("model", apiKeyManager.getModel())
            put("messages", messages)
            put("stream", false)
            put("max_tokens", 256)
        }

        val request = Request.Builder()
            .url(chatUrl())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("expandQuery 错误 ${response.code}: $responseBody"))
                }
                val json = JSONObject(responseBody)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                val queries = content.trim().lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { it.replace(Regex("""^\d+[.\、\))\s]+"""), "").trim() }
                    .take(3)
                Result.success(if (queries.isEmpty()) listOf(userQuery) else queries)
            } catch (e: Exception) {
                Result.success(listOf(userQuery))
            }
        }
    }

    /**
     * LLM-based chunk reranking (non-streaming).
     * Given a user query and candidate chunks, ask LLM to select the most relevant ones.
     * Returns an ordered list of 0-based chunk indices in relevance order.
     */
    suspend fun rerankChunks(
        userQuery: String,
        chunks: List<BookChunk>,
        topK: Int = 8,
    ): Result<List<Int>> {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isBlank()) return Result.failure(Exception("API Key 未配置"))

        if (chunks.isEmpty()) return Result.success(emptyList())
        if (chunks.size <= topK) return Result.success(chunks.indices.toList())

        val chunksText = StringBuilder()
        for ((i, chunk) in chunks.withIndex()) {
            val label = if (chunk.chapterTitle.isNotBlank()) " [${chunk.chapterTitle}]" else ""
            val bookLabel = if (chunk.bookTitle.isNotBlank()) "《${chunk.bookTitle}》" else ""
            chunksText.appendLine("--- 段落 ${i}$bookLabel$label ---")
            chunksText.appendLine(chunk.content.take(600))
            chunksText.appendLine()
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "你是信息检索专家。我会提供多个段落候选，请选出与问题最相关的${topK}个段落。\n\n输出格式：每行一个段落编号（数字），按相关性从高到低排列，不要其他内容。")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "用户问题：$userQuery\n\n候选段落：\n$chunksText\n\n请选出最相关的${topK}个段落编号：")
            })
        }
        val body = JSONObject().apply {
            put("model", apiKeyManager.getModel())
            put("messages", messages)
            put("stream", false)
            put("max_tokens", 256)
        }

        val request = Request.Builder()
            .url(chatUrl())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("rerank 错误 ${response.code}: $responseBody"))
                }
                val json = JSONObject(responseBody)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                val indices = content.trim().lines()
                    .map { it.trim() }
                    .mapNotNull { line ->
                        val num = line.filter { it.isDigit() }.take(3)
                        num.toIntOrNull()?.let { it - 1 }
                    }
                    .filter { it in chunks.indices }
                    .take(topK)
                    .toList()

                if (indices.isEmpty()) {
                    Result.success(chunks.indices.take(topK).toList())
                } else {
                    Result.success(indices)
                }
            } catch (e: Exception) {
                Result.success(chunks.indices.take(topK).toList())
            }
        }
    }

    /**
     * Batch generate event summaries for book chunks.
     * Each chunk gets a one-line summary describing what EVENT occurs in that chunk
     * (not what the chunk discusses/recalls/reflects upon).
     *
     * This is the key insight from ChronoRAG: distinguishing "event happening" from
     * "event being recalled" prevents flashback paragraphs from outranking the actual event.
     */
    suspend fun generateEventSummaries(
        bookTitle: String,
        chunks: List<Pair<Long, String>>,
    ): Result<Map<Long, String>> {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isBlank()) return Result.failure(Exception("API Key not configured"))

        if (chunks.isEmpty()) return Result.success(emptyMap())

        val chunksText = StringBuilder()
        for ((i, pair) in chunks.withIndex()) {
            val (id, text) = pair
            chunksText.appendLine("--- 段落 ${i} (id=${id}) ---")
            chunksText.appendLine(text.take(400))
            chunksText.appendLine()
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", """你是小说分析助手。为每个段落写一句话的事件摘要，描述该段中**实际发生了**什么事件。
规则：
1. 只写事件本身，不写"回忆""想起""记得"等元描述
2. 如果段落是角色在回忆某事，摘要格式为"回忆：...（实际事件）"
3. 如果段落是对话/描写/过渡，写"场景：..."或"对话：..."或"描写：..."
4. 每行输出：[段落编号]: 摘要
5. 摘要不超过30字""")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "书籍：《$bookTitle》\n\n请为以下段落生成事件摘要：\n$chunksText")
            })
        }
        val body = JSONObject().apply {
            put("model", apiKeyManager.getModel())
            put("messages", messages)
            put("stream", false)
            put("max_tokens", 1024)
        }

        val request = Request.Builder()
            .url(chatUrl())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("eventSummary 错误 ${response.code}: $responseBody"))
                }
                val json = JSONObject(responseBody)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                val result = mutableMapOf<Long, String>()
                for (line in content.trim().lines()) {
                    val trimmed = line.trim()
                    // Parse "[N]: summary" or "N: summary" format
                    val match = Regex("""^\s*\[?(\d+)\]?\s*[:：]\s*(.+)$""").find(trimmed)
                    if (match != null) {
                        val idx = match.groupValues[1].toIntOrNull() ?: continue
                        val summary = match.groupValues[2].trim().take(100)
                        if (idx in chunks.indices) {
                            result[chunks[idx].first] = summary
                        }
                    }
                }
                // Fill missing with simple fallback
                for ((id, text) in chunks) {
                    if (id !in result) {
                        result[id] = text.take(60).replace('\n', ' ').trim()
                    }
                }
                Result.success(result)
            } catch (e: Exception) {
                // Non-fatal: return empty text-based summaries
                val fallback = chunks.associate { (id, text) ->
                    id to text.take(60).replace('\n', ' ').trim()
                }
                Result.success(fallback)
            }
        }
    }

    /**
     * Fetch available models from the API's /models endpoint.
     * Returns a deduplicated list of model IDs, sorted alphabetically.
     */
    suspend fun fetchModels(): Result<List<String>> {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isBlank()) return Result.failure(Exception("API Key 未配置"))

        val baseUrl = apiKeyManager.getBaseUrl().trimEnd('/')
        val modelsUrl = "$baseUrl/models"

        val request = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("获取模型列表失败 ${response.code}: ${responseBody.take(200)}")
                    )
                }
                val json = JSONObject(responseBody)
                val data = json.optJSONArray("data")
                val models = mutableListOf<String>()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val id = item.optString("id", "")
                        if (id.isNotBlank() && id !in models) {
                            models.add(id)
                        }
                    }
                }
                Result.success(models.sorted())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
