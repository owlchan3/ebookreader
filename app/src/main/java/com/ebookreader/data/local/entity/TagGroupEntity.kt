package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tag_groups")
data class TagGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val logicType: String,
    val isTab: Boolean = false,
    val tagIdsJson: String = "[]",
    val query: String = "",
)
