package com.ebookreader.domain.model

/** 单条释义：释义文本 + 可选词性 + 例句/典故出处。 */
data class DictionaryDefinition(
    val definition: String,
    /** 词性（名 / 形 / 動 / 副 …），可为空。 */
    val type: String? = null,
    /** 例句 / 典故出处（如「元．鄭光祖《㑳梅香．楔子》…」）。 */
    val examples: List<String> = emptyList(),
)

/** 一次词典查询的结果。统一承载在线源（谷歌翻译 / 维基）与离线萌典词库的返回。 */
data class DictionaryEntry(
    /** 查询词（或匹配到的词头）。 */
    val word: String,
    /** 音标 / 拼音，可为空。 */
    val phonetic: String? = null,
    /** 释义列表。 */
    val definitions: List<DictionaryDefinition> = emptyList(),
    /** 补充说明（如维基的长篇释文 / 典故）。 */
    val note: String? = null,
    /** 来源标签。 */
    val source: String,
)

/** 词典查询的界面状态。 */
sealed class LookupState {
    /** 未在查询（关闭 sheet 后的初始态）。 */
    data object Idle : LookupState()
    /** 查询中（在线请求 / 离线索引加载中）。 */
    data object Loading : LookupState()
    /** 查询成功。 */
    data class Success(val entry: DictionaryEntry) : LookupState()
    /** 查询失败（在线与离线均无结果，或网络异常）。 */
    data class Error(val message: String) : LookupState()
}
