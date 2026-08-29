package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** AI 拆书结果。每本书一条，存整本的大纲/逐章梗概/全书总结及 deep 档附加内容。 */
@Entity(tableName = "book_decomposition")
data class BookDecompositionEntity(
    @PrimaryKey val bookId: Long,
    val bookType: String,             // novel / history / stem / humanities / general
    val tier: String,                 // compact / standard / deep
    val outlineJson: String,          // 全书大纲（树结构 JSON）
    val chapterSummariesJson: String, // 逐章梗概（JSON）
    val bookSummary: String,          // 全书总结
    val charactersJson: String,       // 人物关系（deep 档）
    val timelineJson: String,         // 时间线（deep 档）
    val quotesJson: String,           // 金句（deep 档）
    val characterBiosJson: String,    // 人物小传（deep 档）
    val worldSettingJson: String,     // 世界观与设定（deep 档）
    val chapterOverviewJson: String,  // 每章概述（deep 档）
    val extendedReadingJson: String,  // 拓展阅读（deep 档）
    val status: String,               // none / generating / done
    val updatedAt: Long,
)
