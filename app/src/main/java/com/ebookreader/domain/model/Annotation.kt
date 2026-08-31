package com.ebookreader.domain.model

/** 批注/划线的样式。 */
enum class AnnotationStyle {
    /** 高光 */
    HIGHLIGHT,

    /** 底部横线 */
    UNDERLINE,

    /** 底部波浪线 */
    WAVY,
}

/**
 * 一条批注或划线。与书签（[Bookmark]）相互独立。
 *
 * @param locatorJson 选择型 Locator JSON（`href` + `text{highlight,before,after}`），用于在正文中重建装饰。
 * @param selectedText 选中文字（列表展示用，冗余）。
 * @param pageIndex 创建时的全局页码（列表展示 + 跳转用）。
 * @param style 样式（高光/横线/波浪线）。
 * @param color ColorInt。
 * @param note 批注笔记（划线为空串）。
 */
data class Annotation(
    val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val selectedText: String,
    val pageIndex: Int,
    val style: AnnotationStyle = AnnotationStyle.HIGHLIGHT,
    val color: Int,
    val note: String = "",
    val createdTimestamp: Long = System.currentTimeMillis(),
)
