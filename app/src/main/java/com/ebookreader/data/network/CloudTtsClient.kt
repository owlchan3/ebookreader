package com.ebookreader.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 云端 TTS 客户端：对接 OpenAI 兼容 `/audio/speech` 协议
 * （SiliconFlow / Moonshot / 火山 Doubao / OpenAI 等）。
 *
 * 强制请求 WAV 输出，便于上游按 PCM 级合并为单段音频；
 * 并提供模型列表（`GET /models`）与音色列表（`GET /audio/voice/list`）获取。
 * 请求可被协程取消（停止朗读时立即中断网络）。
 */
class CloudTtsClient(private val apiKeyManager: ApiKeyManager) {

    companion object {
        private const val MAX_RETRIES = 3
        /** TTS 相关模型的 ID 关键字，用于在 /models 全量结果里筛选。 */
        private val TTS_MODEL_REGEX = Regex(
            "(?i)tts|audio|cosyvoice|voice|speech|fish|melotts|sovits|bert-vits|gpt-sovits",
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 合成 [text] 为 WAV 字节。可被取消；429/5xx 自动重试。 */
    suspend fun synthesize(text: String): ByteArray {
        val base = requireBaseUrl()
        val key = requireKey()
        val model = apiKeyManager.getTtsOpenAiModel().trim()
        if (model.isBlank()) throw IOException("TTS 模型未配置")
        val voice = apiKeyManager.getTtsOpenAiVoice().trim()
        if (voice.isBlank()) throw IOException("TTS 音色未配置")

        return if (isMiMo(base)) {
            synthesizeMiMo(base, key, model, voice, text)
        } else {
            synthesizeOpenAi(base, key, model, voice, text)
        }
    }

    /** 是否为小米 MiMo 端点：其 TTS 走 `/chat/completions` + `audio` 字段，而非 `/audio/speech`。 */
    private fun isMiMo(base: String): Boolean = base.contains("xiaomimimo", ignoreCase = true)

    /** OpenAI 兼容 `/audio/speech` 协议（SiliconFlow / OpenAI 等）。 */
    private suspend fun synthesizeOpenAi(
        base: String,
        key: String,
        model: String,
        voice: String,
        text: String,
    ): ByteArray {
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "wav")
            .toString()

        val request = Request.Builder()
            .url("$base/audio/speech")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val bytes = executeBytes(request)
        if (bytes.size < 44) throw IOException("TTS 返回空音频")
        return bytes
    }

    /** 小米 MiMo TTS：`/chat/completions` + `audio` 字段，音频以 Base64 返回。 */
    private suspend fun synthesizeMiMo(
        base: String,
        key: String,
        model: String,
        voice: String,
        text: String,
    ): ByteArray {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", "用自然、清晰的语气朗读"))
                .put(JSONObject().put("role", "assistant").put("content", text)))
            .put("audio", JSONObject().put("format", "wav").put("voice", voice))
            .put("stream", false)
            .toString()

        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("api-key", key)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val bytes = executeBytes(request)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val data = root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONObject("audio")?.optString("data")
        if (data.isNullOrBlank()) throw IOException("MiMo 未返回音频数据")
        val wav = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
        if (wav.size < 44) throw IOException("MiMo 返回空音频")
        return wav
    }

    /**
     * 获取 TTS 模型列表。走 OpenAI 兼容 `GET /models`，返回 `data[].id`；
     * 优先返回名字里带 TTS 关键字的模型，若无匹配则返回全部 ID。
     */
    suspend fun listModels(): List<String> {
        val request = Request.Builder()
            .url("${requireBaseUrl()}/models")
            .header("Authorization", "Bearer ${requireKey()}")
            .get()
            .build()
        val body = String(executeBytes(request), Charsets.UTF_8)
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() }
        }
        val tts = ids.filter { TTS_MODEL_REGEX.containsMatchIn(it) }
        return if (tts.isNotEmpty()) tts else ids
    }

    /**
     * 获取音色列表。多数 OpenAI 兼容服务没有标准音色接口，这里尝试
     * SiliconFlow 风格的 `GET /audio/voice/list`（返回用户自定义音色）。
     * 失败/为空时返回空列表，由调用方叠加内置音色目录。
     */
    suspend fun listVoices(): List<String> {
        val base = requireBaseUrl()
        if (isMiMo(base)) return emptyList() // MiMo 预置音色由内置目录提供
        val request = Request.Builder()
            .url("$base/audio/voice/list")
            .header("Authorization", "Bearer ${requireKey()}")
            .get()
            .build()
        val body = String(executeBytes(request), Charsets.UTF_8)
        val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() }
        }
    }

    private fun requireBaseUrl(): String =
        apiKeyManager.getTtsOpenAiUrl().trim().trimEnd('/').ifBlank { throw IOException("TTS API 地址未配置") }

    private fun requireKey(): String =
        apiKeyManager.getTtsOpenAiKey().trim().ifBlank { throw IOException("TTS API Key 未配置") }

    /** 执行请求并返回原始响应字节，429/5xx 自动重试。可被取消。 */
    private suspend fun executeBytes(request: Request): ByteArray {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_RETRIES) {
            val result = executeOnce(request)
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
            if (lastError is RateLimited && attempt < MAX_RETRIES) {
                delay(500L * attempt)
            } else {
                break
            }
        }
        throw lastError ?: IOException("TTS 云端请求失败")
    }

    /** 单次请求，返回原始响应字节。可被取消。 */
    private suspend fun executeOnce(request: Request): Result<ByteArray> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val code = response.code
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        if (code == 429 || code >= 500) {
                            if (cont.isActive) cont.resume(Result.failure(RateLimited("HTTP $code")))
                            return
                        }
                        if (!response.isSuccessful) {
                            val hint = if (bytes.isNotEmpty()) String(bytes, Charsets.UTF_8).take(160) else "HTTP $code"
                            if (cont.isActive) cont.resume(Result.failure(IOException("TTS API 错误: $hint")))
                            return
                        }
                        if (cont.isActive) cont.resume(Result.success(bytes))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(Result.failure(e))
                    } finally {
                        response.close()
                    }
                }
            })
        }

    /** 429/5xx：可重试。 */
    private class RateLimited(message: String) : IOException(message)
}
