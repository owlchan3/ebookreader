package com.ebookreader.domain.model

/** AI 拆书结果。 */
data class BookDecomposition(
    val bookId: Long,
    val bookType: String,   // novel / history / stem / humanities / general
    val tier: String,       // compact / standard / deep
    val outline: OutlineNode? = null,            // 全书大纲树
    val chapters: List<DecomposedChapter> = emptyList(),
    val bookSummary: String = "",                 // 全书总结（主旨 + 主线）
    val characters: String = "",                  // 人物关系（deep 档）
    val timeline: String = "",                    // 时间线（deep 档）
    val quotes: String = "",                      // 金句（deep 档）
    val characterBios: String = "",               // 人物小传（deep 档）
    val worldSetting: String = "",                // 世界观与设定（deep 档）
    val chapterOverview: String = "",             // 每章概述（deep 档）
    val extendedReading: String = "",             // 拓展阅读（deep 档）
    val status: String = "none",                  // none / generating / done
)

/** 单章拆书结果。 */
data class DecomposedChapter(
    val title: String,
    val summary: String,           // 梗概（或 compact 档的一句话粗纲）
    val events: String = "",       // 关键事件（deep 档）
    val characters: String = "",   // 出场人物（deep 档）
    val charCount: Int = 0,        // 本章原文字数（用于字数分布图）
)

/** 大纲树节点（全书 → 卷/部/部分 → 章）。 */
data class OutlineNode(
    val title: String,
    val summary: String = "",
    val children: List<OutlineNode> = emptyList(),
)
