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

        /**
         * 单次请求输出上限的**默认值**。8192 是 deepseek-chat 官方允许的最大值，
         * 对官方端点最安全；接中转站或输出上限更高的模型时，调用方可显式传更大的值。
         *
         * 注意这里只是默认值，不再对调用方传入的值做上夹取 —— 各方端点合法区间不一致，
         * 传了超出该端点范围的值会被服务端拒绝（HTTP 400）。
         */
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 8192

        /**
         * 撞上单次上限后自动续写的最大轮数。
         * 8 × 8192 ≈ 64K，正好顶到 deepseek-chat 的上下文窗口，再多也没有意义。
         */
        private const val MAX_CONTINUATION_ROUNDS = 8

        /** 续写时回填的「已写内容」只取末尾这么多字符，否则上下文会被自己写的东西撑爆。 */
        private const val CONTINUATION_TAIL_CHARS = 4000

        /**
         * 续写轮附加的指令。要点：接着写、不重复、不加开场白。
         * 配合 assistant 前缀回填，让模型把续写当成「同一段话还没写完」而不是一次新请求。
         */
        private const val CONTINUE_INSTRUCTION =
            "请直接从上面最后一个字接着往下写，把还没写完的部分写完。" +
                "不要重复任何已经写过的内容，不要出现「好的」「继续」「以下是」这类开场白，" +
                "不要重新开始，不要加任何解释或标题。"

        /** 续写轮数用尽仍未写完时追加到结尾的提示，让用户知道内容不完整。 */
        const val TRUNCATION_NOTICE = "\n\n> ⚠️ 内容较长，已达单次生成上限，以上内容可能未写完。"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY) // bypass system proxy to avoid response buffering
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun chatUrl(): String = apiKeyManager.getBaseUrl().trimEnd('/') + CHAT_PATH

    /** 非流式补全的完整结果。[truncated] 为 true 表示续写轮数已用尽、模型仍未写完。 */
    data class Completion(val content: String, val truncated: Boolean)

    /** Non-streaming completion (for smart description generation). */
    suspend fun complete(prompt: String, systemPrompt: String): Result<String> =
        complete(prompt, systemPrompt, null, "", "", DEFAULT_MAX_OUTPUT_TOKENS)

    /** Non-streaming completion with explicit model override (null/blank = 沿用主模型)。 */
    suspend fun complete(prompt: String, systemPrompt: String, model: String?): Result<String> =
        complete(prompt, systemPrompt, model, "", "", DEFAULT_MAX_OUTPUT_TOKENS)

    /**
     * 非流式补全，可整体覆盖 model / apiKey / baseUrl（空 = 沿用主配置）。
     *
     * 撞上单次输出上限会自动续写（见 [completeWithMeta]），返回的内容通常已经完整；
     * 只有续写轮数用尽仍未写完时，才在末尾追加 [TRUNCATION_NOTICE] 作为提示。
     */
    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        model: String?,
        apiKeyOverride: String,
        baseUrlOverride: String,
        maxTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): Result<String> =
        completeWithMeta(prompt, systemPrompt, model, apiKeyOverride, baseUrlOverride, maxTokens)
            .map { if (it.truncated) it.content + TRUNCATION_NOTICE else it.content }

    /**
     * 非流式补全，撞上单次输出上限时自动续写，直到模型自然写完。
     *
     * 续写做法：把已写内容作为 assistant 消息回填，再补一条极简的续写指令，让模型把这件事
     * 当成「同一段话还没写完」而不是一次新请求 —— 这样它不会加开场白、不会复述已写内容。
     *
     * maxTokens 原样透传给服务端，不再做上夹取 —— 各厂商合法区间不一致，
     * 传了超出该端点范围的值会被服务端拒绝（HTTP 400），调用方需自己确认所用模型的上限。
     */
    suspend fun completeWithMeta(
        prompt: String,
        systemPrompt: String,
        model: String?,
        apiKeyOverride: String,
        baseUrlOverride: String,
        maxTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): Result<Completion> {
        val apiKey = apiKeyOverride.takeIf { it.isNotBlank() } ?: apiKeyManager.getApiKey()
        if (apiKey.isBlank()) return Result.failure(Exception("API Key 未配置"))
        val effectiveModel = model?.takeIf { it.isNotBlank() } ?: apiKeyManager.getModel()
        val baseUrl = baseUrlOverride.takeIf { it.isNotBlank() }
            ?: apiKeyManager.getBaseUrl().trimEnd('/')
        // 只保底不保顶：非正数会被服务端拒绝，其余一律按调用方的意图原样发出。
        val budget = maxTokens.coerceAtLeast(1)

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        }

        return withContext(Dispatchers.IO) {
            try {
                val accumulated = StringBuilder()
                var truncated = false
                var round = 0
                while (round < MAX_CONTINUATION_ROUNDS) {
                    val body = JSONObject().apply {
                        put("model", effectiveModel)
                        put("messages", messages)
                        put("stream", false)
                        put("max_tokens", budget)
                    }
                    val request = Request.Builder()
                        .url(baseUrl + CHAT_PATH)
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(body.toString().toRequestBody(jsonMediaType))
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("API 错误 ${response.code}: $responseBody"))
                    }
                    val choice = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0)
                    val content = choice.getJSONObject("message").getString("content")
                    accumulated.append(content)

                    // 只有明确因长度停下才续写；自然结束（stop / 无该字段）就此打住。
                    if (choice.optString("finish_reason", "") != "length") {
                        truncated = false
                        break
                    }
                    truncated = true
                    // 本轮一个字都没产出还报 length：模型不肯再写，再续也是空转。
                    if (content.isEmpty() || round == MAX_CONTINUATION_ROUNDS - 1) break
                    round++
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", accumulated.toString().takeLast(CONTINUATION_TAIL_CHARS))
                    })
                    messages.put(JSONObject().apply { put("role", "user"); put("content", CONTINUE_INSTRUCTION) })
                }
                Result.success(Completion(accumulated.toString().trim(), truncated))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** 单轮流式请求的结果：该轮的结束原因 + 实际产出的 token 数。 */
    private class StreamRound(val finishReason: String, val tokenCount: Int)

    /**
     * Streaming chat completion. Invokes callbacks for each token.
     * For deepseek-reasoner, reasoning_content is delivered via onReasoningToken.
     *
     * 撞上单次输出上限时自动续写：把已写内容作为 assistant 前缀回填后接着发下一轮，
     * 新 token 继续追加到同一条回复上，调用方（UI）完全无感 —— 用户看到的就是一口气写完。
     * 只有续写轮数用尽仍未写完时，才通过 onToken 追加一条 [TRUNCATION_NOTICE]。
     */
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

        Thread {
            try {
                val diag = StringBuilder()
                val history = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    messages.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        })
                    }
                }
                val accumulated = StringBuilder()
                var truncated = false
                var round = 0
                while (true) {
                    diag.appendLine()
                    diag.appendLine("=== 第 ${round + 1} 轮请求 ===")
                    onDiagnostic?.invoke(diag.toString())

                    val result = streamRound(
                        apiKey = apiKey,
                        history = history,
                        round = round,
                        onToken = { token ->
                            accumulated.append(token)
                            onToken(token)
                        },
                        // 思考过程只在第一轮透出；续写轮不重复展示思维链
                        onReasoningToken = if (round == 0) onReasoningToken else null,
                        diag = diag,
                        onDiagnostic = onDiagnostic,
                    )

                    if (result.finishReason != "length") {
                        truncated = false
                        break
                    }
                    truncated = true
                    // 本轮一个字都没产出还报 length：模型不肯再写，再续也是空转
                    if (result.tokenCount == 0 || round >= MAX_CONTINUATION_ROUNDS - 1) break

                    round++
                    diag.appendLine("--- 撞上单次上限，自动续写第 ${round + 1} 轮 ---")
                    history.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", accumulated.toString().takeLast(CONTINUATION_TAIL_CHARS))
                    })
                    history.put(JSONObject().apply { put("role", "user"); put("content", CONTINUE_INSTRUCTION) })
                }
                diag.appendLine(
                    "=== 流结束: 共 ${round + 1} 轮, 累计 ${accumulated.length} 字" +
                        (if (truncated) " (续写轮数用尽仍未写完)" else "") + " ==="
                )
                onDiagnostic?.invoke(diag.toString())
                if (truncated) onToken(TRUNCATION_NOTICE)
                onComplete()
            } catch (e: Exception) {
                onDiagnostic?.invoke("!!! 异常: ${e.message}\n${e.stackTraceToString().take(1000)}")
                onError(e)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 发一轮流式请求，把 token 透出给调用方，返回该轮的 finish_reason 与 token 数，
     * 由 [streamChat] 决定是否需要续写。出错直接抛异常，交给 [streamChat] 统一兜住。
     */
    private fun streamRound(
        apiKey: String,
        history: JSONArray,
        round: Int,
        onToken: (String) -> Unit,
        onReasoningToken: ((String) -> Unit)?,
        diag: StringBuilder,
        onDiagnostic: ((String) -> Unit)?,
    ): StreamRound {
        val body = JSONObject().apply {
            put("model", apiKeyManager.getModel())
            put("messages", history)
            put("stream", true)
            // 流式每轮的单轮预算。撞到这个数不是终点 —— 外层循环会自动续写。
            put("max_tokens", DEFAULT_MAX_OUTPUT_TOKENS)
        }

        val request = Request.Builder()
            .url(chatUrl())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        diag.appendLine("URL: ${chatUrl()}")
        diag.appendLine("Model: ${apiKeyManager.getModel()}")
        diag.appendLine("Messages: ${history.length()}条 (含system)")
        diag.appendLine("SystemPrompt: ${history.getJSONObject(0).optString("content").length}字")
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
            throw Exception("API 错误 ${response.code}: $errorBody")
        }
        val source = response.body?.source() ?: run {
            diag.appendLine("!!! 响应体为空")
            onDiagnostic?.invoke(diag.toString())
            throw Exception("空响应")
        }

        var lineCount = 0
        var dataLineCount = 0
        var tokenCount = 0
        var reasoningCount = 0
        var finishReason = ""
        var firstReasoningToken = true
        var firstContentToken = true
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            lineCount++
            // 只有第一轮 dump 原始行，否则续写几轮下来诊断页会被刷爆
            if (round == 0 && lineCount <= 20) {
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
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    if (choice == null) {
                        if (dataLineCount <= 3) {
                            diag.appendLine("NULL-DELTA$dataLineCount: ${data.take(100)}")
                        }
                        continue
                    }
                    // finish_reason 只在最后一个 chunk 上非空；"length" = 撞上单次输出上限
                    val fr = choice.optString("finish_reason", "")
                    if (fr.isNotEmpty() && fr != "null") finishReason = fr

                    val delta = choice.optJSONObject("delta")
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
                    }
                } catch (e: Exception) {
                    diag.appendLine("PARSE-ERR$dataLineCount: ${data.take(100)} -- ${e.message}")
                }
            }
        }
        diag.appendLine("--- 本轮结束: lines=$lineCount dataLines=$dataLineCount tokens=$tokenCount finish=${finishReason.ifEmpty { "无" }} ---")
        onDiagnostic?.invoke(diag.toString())
        return StreamRound(finishReason, tokenCount)
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
