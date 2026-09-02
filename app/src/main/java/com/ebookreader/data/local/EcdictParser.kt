package com.ebookreader.data.local

import android.content.Context
import com.ebookreader.domain.model.DictionaryDefinition
import com.ebookreader.domain.model.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 离线英汉词典（ECDICT 常用词子集）。
 * 数据为 assets/dictionaries/ecdict.jsonl（每行一条词条）。
 *
 * 每行格式：`小写词头 \t {"w":"原始词头","p":"音标","t":"中文翻译(多行)"}`。
 *
 * 首次查询时懒加载：按小写词头建索引（值存原始行），命中时再解析该单条。
 */
class EcdictParser(private val context: Context) {

    private val indexRef = AtomicReference<Map<String, String>?>(null)

    companion object {
        private const val ASSET_PATH = "dictionaries/ecdict.jsonl"
        private const val SOURCE_LABEL = "ECDICT 英汉词典"
    }

    /** 小写精确匹配。未命中返回 null。 */
    suspend fun lookup(query: String): DictionaryEntry? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        val index = indexRef.get() ?: loadIndex()
        val line = index[key] ?: return null
        return parseEntry(line)
    }

    private suspend fun loadIndex(): Map<String, String> {
        indexRef.get()?.let { return it }
        return withContext(Dispatchers.IO) {
            val built = buildIndex()
            indexRef.compareAndSet(null, built)
            indexRef.get() ?: built
        }
    }

    private fun buildIndex(): Map<String, String> {
        val map = HashMap<String, String>(70_000)
        context.assets.open(ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                for (line in lines) {
                    val t = line.indexOf('\t')
                    if (t <= 0) continue
                    map[line.substring(0, t)] = line
                }
            }
        return map
    }

    private fun parseEntry(line: String): DictionaryEntry? {
        val t = line.indexOf('\t')
        if (t < 0) return null
        return try {
            val o = JSONObject(line.substring(t + 1))
            val word = o.optString("w", "").trim()
            val phonetic = o.optString("p", "").trim().ifEmpty { null }
            val translation = o.optString("t", "").trim()
            if (word.isEmpty() || translation.isEmpty()) return null
            val defs = translation.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { DictionaryDefinition(it) }
            if (defs.isEmpty()) return null
            DictionaryEntry(
                word = word,
                phonetic = phonetic,
                definitions = defs,
                source = SOURCE_LABEL,
            )
        } catch (_: Exception) {
            null
        }
    }
}
