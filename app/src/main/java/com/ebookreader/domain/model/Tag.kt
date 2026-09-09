package com.ebookreader.domain.model

/** 「已阅」特殊标签的名称：不可删除，标记的书籍在统计时全本视为已读。 */
const val READ_TAG_NAME = "已阅"

/** 「置顶」特殊标签的名称：不可删除，最近阅读排序时置顶。 */
const val PIN_TAG_NAME = "置顶"

data class Tag(
    val id: Long = 0,
    val name: String,
    val createdTimestamp: Long = 0,
) {
    /** 是否为「已阅」特殊标签。 */
    val isReadTag: Boolean get() = name == READ_TAG_NAME
    /** 是否为「置顶」特殊标签。 */
    val isPinTag: Boolean get() = name == PIN_TAG_NAME
}

