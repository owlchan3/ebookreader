package com.ebookreader.domain.model

data class TagGroup(
    val id: Long = 0,
    val name: String,
    val logicType: String,
    val isTab: Boolean = false,
    val tags: List<Tag> = emptyList(),
    val query: String = "",
)
