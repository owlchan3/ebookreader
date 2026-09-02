package com.ebookreader.data.dictionary

import android.content.Context
import com.ebookreader.data.local.EcdictParser
import com.ebookreader.data.local.MoedictParser
import com.ebookreader.domain.model.DictionaryEntry

/** 词典查询失败，携带可直接展示给用户的信息。 */
class LookupException(message: String) : Exception(message)

/**
 * 词典查询入口（纯离线，不发网络请求）。
 *
 * 查询链：
 * - 含中文（中文 / 古汉语 / 典故）→ 萌典（中文释义 + 拼音 + 典故）；未命中 → 报「未找到」。
 * - 纯拉丁字符（外文）→ ECDICT 英汉词典（英→中）；未命中 → 报「未找到」。
 */
class DictionaryService(context: Context) {

    private val offline = MoedictParser(context.applicationContext)
    private val ecdict = EcdictParser(context.applicationContext)

    suspend fun lookup(query: String): DictionaryEntry {
        val q = query.trim().take(80)
        if (q.isEmpty()) throw LookupException("请输入要查询的内容")
        return if (containsChinese(q)) {
            offline.lookup(q)
                ?: throw LookupException("未找到「$q」的释义")
        } else {
            ecdict.lookup(q)
                ?: throw LookupException("未找到「$q」的翻译")
        }
    }

    private fun containsChinese(text: String): Boolean =
        Regex("[\\u4e00-\\u9fff]").containsMatchIn(text)
}
