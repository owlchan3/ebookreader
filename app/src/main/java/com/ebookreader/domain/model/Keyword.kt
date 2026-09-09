package com.ebookreader.domain.model

/** 一本书的关键词及其权重（0~1 归一化，词云字号/居中据此）。 */
data class Keyword(
    val keyword: String,
    val weight: Float,
    val rank: Int,
)
