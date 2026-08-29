package com.ebookreader.domain.model

enum class TokenType { TAG, AND, OR, NO, LPAREN, RPAREN }

data class TagToken(
    val type: TokenType,
    val text: String,
    val tagId: Long = 0,
    val depth: Int = 0,
)
