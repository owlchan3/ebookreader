package com.ebookreader.data.local

import android.content.Context
import com.ebookreader.domain.model.DictionaryDefinition
import com.ebookreader.domain.model.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference

/**
 * 离线中文-中文词典（教育部《重編國語辭典修訂本》，萌典整理版）。
 * 数据为 assets/dictionaries/moedict.jsonl（每行一条词条，简体已转换）。
 *
 * 每行格式：`简体 \t 繁体 \t <异读JSON数组>`，其中 JSON 数组形如
 * `[{"p":"拼音","d":[{"d":"释义","t":"词性","q":["例句","典故"]}]}]`。
 *
 * 首次查询时懒加载：逐行拆出简/繁词头建索引（值存原始行），命中时再解析该单条，
 * 避免为全部词条构建对象图，省内存、首载快。
 */
class MoedictParser(private val context: Context) {

    private val indexRef = AtomicReference<Map<String, String>?>(null)

    companion object {
        private const val ASSET_PATH = "dictionaries/moedict.jsonl"
        private const val SOURCE_LABEL = "教育部《重編國語辭典修訂本》"
    }

    /** 精确匹配（简体 / 繁体均可）。未命中返回 null。 */
    suspend fun lookup(query: String): DictionaryEntry? {
        val key = query.trim()
        if (key.isEmpty()) return null
        val index = indexRef.get() ?: loadIndex()
        val line = index[key] ?: return null
        return parseEntry(key, line)
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
        val map = HashMap<String, String>(170_000)
        context.assets.open(ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1)
                    if (t2 <= t1) continue
                    val simp = line.substring(0, t1)
                    val trad = line.substring(t1 + 1, t2)
                    map[simp] = line
                    if (trad != simp) map[trad] = line
                }
            }
        return map
    }

    private fun parseEntry(matchedWord: String, line: String): DictionaryEntry? {
        val t2 = line.indexOf('\t', line.indexOf('\t') + 1)
        if (t2 < 0) return null
        val hJson = line.substring(t2 + 1)
        return try {
            val hArr = JSONArray(hJson)
            val phonetics = mutableListOf<String>()
            val defs = mutableListOf<DictionaryDefinition>()
            for (i in 0 until hArr.length()) {
                val h = hArr.optJSONObject(i) ?: continue
                val pinyin = h.optString("p", "").trim()
                if (pinyin.isNotEmpty() && pinyin !in phonetics) phonetics.add(pinyin)
                val dArr = h.optJSONArray("d") ?: continue
                for (j in 0 until dArr.length()) {
                    val d = dArr.optJSONObject(j) ?: continue
                    val defText = d.optString("d", "").trim()
                    if (defText.isEmpty()) continue
                    val type = d.optString("t", "").trim().ifEmpty { null }
                    val qArr = d.optJSONArray("q")
                    val examples = mutableListOf<String>()
                    if (qArr != null) {
                        for (k in 0 until qArr.length()) {
                            val ex = qArr.optString(k, "").trim()
                            if (ex.isNotEmpty()) examples.add(ex)
                        }
                    }
                    defs.add(DictionaryDefinition(defText, type, examples))
                }
            }
            if (defs.isEmpty()) return null
            DictionaryEntry(
                word = matchedWord,
                phonetic = phonetics.joinToString(" / ").ifEmpty { null },
                definitions = defs,
                source = SOURCE_LABEL,
            )
        } catch (_: Exception) {
            null
        }
    }
}
