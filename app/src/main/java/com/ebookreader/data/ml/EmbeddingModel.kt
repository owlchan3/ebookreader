package com.ebookreader.data.ml

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import timber.log.Timber
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device sentence embedding model (bge-small-zh-v1.5, quantized ONNX) loaded
 * from `assets/ml/`. Produces L2-normalized dense vectors used for RAG retrieval.
 *
 * If the model asset or vocab is missing/corrupt, [isAvailable] returns false and
 * callers fall back to BM25 keyword search — the app never hard-depends on this.
 *
 * Model contract (from `assets/ml/`):
 *  - `bge-small-zh.onnx` — quantized BERT encoder. Inputs `input_ids` / `attention_mask`
 *    / optional `token_type_ids` (int64), output either a pooled `[batch, dim]` vector or
 *    `last_hidden_state` `[batch, seq, hidden]` (mean-pooled here).
 *  - `vocab.txt` — BERT WordPiece vocabulary, one token per line (line number = token id).
 */
class EmbeddingModel(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var vocab: Map<String, Int> = emptyMap()
    private var pooled: Boolean = false
    private var dimension: Int = 0
    private var available: Boolean = false
    private var loadError: String? = null
    private var embedError: String? = null

    companion object {
        private const val MAX_LEN = 512
        private const val PAD_ID = 0
        private const val UNK_ID = 100
        private const val CLS_ID = 101
        private const val SEP_ID = 102
    }

    init {
        try {
            val modelBytes = context.assets.open("ml/bge-small-zh.onnx").use { it.readBytes() }
            val loadedVocab = loadVocab(context)
            if (loadedVocab.isEmpty()) {
                loadError = "词表为空"
                Timber.w("EmbeddingModel: vocab empty, model unavailable")
            } else {
                val sess = env.createSession(modelBytes)
                // Determine output mode (pooled vs last_hidden_state) and embedding dim.
                val probe = runProbe(sess)
                session = sess
                vocab = loadedVocab
                pooled = probe.first
                dimension = probe.second
                available = dimension > 0
                Timber.i("EmbeddingModel ready: available=$available, pooled=$pooled, dim=$dimension, vocab=${vocab.size}")
            }
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
            Timber.e(e, "EmbeddingModel init failed — falling back to BM25")
            available = false
        }
    }

    fun isAvailable(): Boolean = available

    /** Human-readable status for the diagnostic panel (surfaces load/inference failures). */
    fun statusText(): String = when {
        !available -> "不可用" + (loadError?.let { " · $it" } ?: "")
        embedError != null -> "已就绪 · ${dimension}维 · 推理异常: $embedError"
        else -> "已就绪 · ${dimension}维"
    }

    /** Embed each text into an L2-normalized vector. Returns empty list on any failure. */
    fun embed(texts: List<String>): List<FloatArray> {
        val sess = session ?: return emptyList()
        if (texts.isEmpty() || !available) return emptyList()

        val batch = texts.map { tokenize(it) }
        val maxLen = batch.maxOfOrNull { it.size }?.coerceAtMost(MAX_LEN)?.coerceAtLeast(2) ?: 2
        val n = texts.size

        val inputIds = LongArray(n * maxLen)
        val attention = LongArray(n * maxLen)
        val typeIds = LongArray(n * maxLen)
        for (b in batch.indices) {
            val ids = batch[b]
            val clamped = ids.take(maxLen)
            for (j in clamped.indices) {
                val idx = b * maxLen + j
                inputIds[idx] = clamped[j].toLong()
                attention[idx] = 1L
            }
            // remaining positions stay 0 (PAD) / 0 (mask) / 0 (type)
        }

        return try {
            val tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(n.toLong(), maxLen.toLong()))
            val tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(attention), longArrayOf(n.toLong(), maxLen.toLong()))
            val tType = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), longArrayOf(n.toLong(), maxLen.toLong()))

            val names = sess.inputNames
            val feeds = HashMap<String, OnnxTensor>(names.size)
            for (name in names) {
                when (name) {
                    "input_ids" -> feeds[name] = tIds
                    "attention_mask" -> feeds[name] = tMask
                    "token_type_ids", "segment_ids" -> feeds[name] = tType
                }
            }

            val vectors = try {
                sess.run(feeds).use { result ->
                    val tensor = result.get(0) as OnnxTensor
                    extractEmbeddings(tensor, n, maxLen, attention)
                }
            } finally {
                tIds.close(); tMask.close(); tType.close()
            }
            val result = vectors.map { normalize(it) }
            embedError = null
            result
        } catch (e: Exception) {
            embedError = e.message ?: e.javaClass.simpleName
            Timber.e(e, "EmbeddingModel.embed failed")
            emptyList()
        }
    }

    // ── Tokenizer (BERT WordPiece, simplified for Chinese) ──────────────────

    private fun tokenize(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        ids.add(CLS_ID)
        for (word in basicTokenize(text)) {
            wordpiece(word, ids)
            if (ids.size >= MAX_LEN - 1) break
        }
        ids.add(SEP_ID)
        return if (ids.size > MAX_LEN) ids.take(MAX_LEN) else ids
    }

    /** Split into CJK chars (one token each) and Latin/digit runs; drop other punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val lower = text.lowercase()
        val sb = StringBuilder()
        var i = 0
        while (i < lower.length) {
            val ch = lower[i]
            when {
                ch.isWhitespace() -> { flushWord(sb, result); i++ }
                isCjk(ch) -> { flushWord(sb, result); result.add(ch.toString()); i++ }
                ch.isLetterOrDigit() -> { sb.append(ch); i++ }
                else -> { flushWord(sb, result); i++ }
            }
        }
        flushWord(sb, result)
        return result
    }

    private fun flushWord(sb: StringBuilder, out: MutableList<String>) {
        if (sb.isNotEmpty()) {
            out.add(sb.toString())
            sb.clear()
        }
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '一'..'鿿' || ch in '㐀'..'䶿' || ch in '豈'..'﫿'

    /** Greedy longest-match WordPiece: first piece as-is, continuation pieces prefixed with `##`. */
    private fun wordpiece(token: String, out: MutableList<Int>) {
        var start = 0
        var isFirst = true
        while (start < token.length) {
            var end = token.length
            var matched = false
            while (end > start) {
                val sub = if (isFirst) token.substring(start, end) else "##" + token.substring(start, end)
                val id = vocab[sub]
                if (id != null) {
                    out.add(id)
                    matched = true
                    break
                }
                end--
            }
            if (!matched) {
                out.add(UNK_ID)
                break
            }
            start = end
            isFirst = false
        }
    }

    private fun loadVocab(context: Context): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        try {
            context.assets.open("ml/vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, token ->
                    val t = token.trim()
                    if (t.isNotEmpty()) map[t] = idx
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "EmbeddingModel.loadVocab failed")
        }
        return map
    }

    // ── Inference helpers ────────────────────────────────────────────────────

    /** Run `[CLS][SEP]` to determine output layout and embedding dimension. */
    private fun runProbe(sess: OrtSession): Pair<Boolean, Int> {
        val maxLen = 2
        val inputIds = LongArray(maxLen) { if (it == 0) CLS_ID.toLong() else SEP_ID.toLong() }
        val attention = LongArray(maxLen) { 1L }
        val typeIds = LongArray(maxLen)

        val tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1L, maxLen.toLong()))
        val tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(attention), longArrayOf(1L, maxLen.toLong()))
        val tType = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), longArrayOf(1L, maxLen.toLong()))

        try {
            val names = sess.inputNames
            val feeds = HashMap<String, OnnxTensor>(names.size)
            for (name in names) {
                when (name) {
                    "input_ids" -> feeds[name] = tIds
                    "attention_mask" -> feeds[name] = tMask
                    "token_type_ids", "segment_ids" -> feeds[name] = tType
                }
            }
            sess.run(feeds).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val shape = tensor.info.shape
                val remaining = tensor.floatBuffer.remaining()
                return if (shape.size <= 2) {
                    // pooled: [batch, dim]
                    val dim = if (shape.size == 2 && shape[1] > 0) shape[1].toInt() else remaining
                    true to dim
                } else {
                    // last_hidden_state: [batch, seq, hidden]
                    val hidden = if (shape.size == 3 && shape[2] > 0) shape[2].toInt() else remaining / maxLen
                    false to hidden
                }
            }
        } finally {
            tIds.close(); tMask.close(); tType.close()
        }
    }

    private fun extractEmbeddings(
        tensor: OnnxTensor,
        batch: Int,
        seqLen: Int,
        mask: LongArray,
    ): List<FloatArray> {
        val buf = tensor.floatBuffer
        val d = dimension
        return if (pooled) {
            (0 until batch).map { b ->
                FloatArray(d) { buf.get(b * d + it) }
            }
        } else {
            (0 until batch).map { b ->
                val sum = FloatArray(d)
                var count = 0
                for (s in 0 until seqLen) {
                    if (mask[b * seqLen + s] == 1L) {
                        val base = (b * seqLen + s) * d
                        for (h in 0 until d) sum[h] += buf.get(base + h)
                        count++
                    }
                }
                if (count > 0) FloatArray(d) { sum[it] / count } else FloatArray(d)
            }
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        norm = sqrt(norm)
        if (norm < 1e-9) return v
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }
}
