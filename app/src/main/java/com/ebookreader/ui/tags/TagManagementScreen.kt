package com.ebookreader.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup
import com.ebookreader.domain.model.TagToken
import com.ebookreader.domain.model.TokenType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagManagementScreen(viewModel: TagManagementViewModel = viewModel()) {
    val allTags by viewModel.tags.collectAsState()
    val tagGroups by viewModel.tagGroups.collectAsState()
    val shelfGroups = tagGroups.filter { it.isTab }

    var showDeleteShelfDialog by remember { mutableStateOf<TagGroup?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架管理") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (shelfGroups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Sell, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))
                Text("还没有书架", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "在书库页面的标签搜索模式中可将筛选条件保存为书架",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelfGroups, key = { it.id }) { group ->
                    ShelfCard(
                        group = group,
                        onDelete = { showDeleteShelfDialog = group },
                    )
                }
            }
        }
    }

    // Delete shelf confirmation
    showDeleteShelfDialog?.let { group ->
        AlertDialog(
            onDismissRequest = { showDeleteShelfDialog = null },
            title = { Text("删除书架") },
            text = { Text("确定要删除书架「${group.name}」吗？\n书架包含 ${group.tags.size} 个标签，删除书架不会删除标签本身。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTagGroup(group)
                    showDeleteShelfDialog = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteShelfDialog = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfCard(
    group: TagGroup,
    onDelete: () -> Unit,
) {
    // Reconstruct tokens from group data for display
    val displayTokens = buildDisplayTokens(group)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Sell, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (displayTokens.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    displayTokens.forEach { token ->
                        key(token) {
                            ShelfTokenChip(token = token)
                        }
                    }
                }
            } else {
                Text("暂未添加标签", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            }
        }
    }
}

private fun buildDisplayTokens(group: TagGroup): List<TagToken> {
    val tokens = mutableListOf<TagToken>()
    // 优先用保存时的完整查询串重建 token（保留括号/运算符），这样书架管理里看到的与输入一致
    if (group.query.isNotBlank()) {
        for (word in group.query.trim().split(Regex("\\s+"))) {
            if (word.isBlank()) continue
            when (word) {
                "(" -> tokens.add(TagToken(TokenType.LPAREN, "("))
                ")" -> tokens.add(TagToken(TokenType.RPAREN, ")"))
                "AND" -> tokens.add(TagToken(TokenType.AND, "AND"))
                "OR" -> tokens.add(TagToken(TokenType.OR, "OR"))
                "NO" -> tokens.add(TagToken(TokenType.NO, "NO"))
                else -> tokens.add(TagToken(TokenType.TAG, word))
            }
        }
    }
    // 旧书架（无 query）回退到按 tags + logicType 重建
    if (tokens.isEmpty()) {
        val tags = group.tags
        if (tags.isEmpty()) return emptyList()
        val logicType = group.logicType.uppercase()
        val tokenType = when (logicType) {
            "OR" -> TokenType.OR
            "NO" -> TokenType.NO
            else -> TokenType.AND
        }
        if (tags.size == 1) {
            if (logicType == "NO") tokens.add(TagToken(TokenType.NO, "NO"))
            tokens.add(TagToken(TokenType.TAG, tags[0].name, tags[0].id))
        } else {
            tokens.add(TagToken(TokenType.LPAREN, "("))
            if (logicType == "NO") tokens.add(TagToken(TokenType.NO, "NO"))
            tags.forEachIndexed { i, tag ->
                tokens.add(TagToken(TokenType.TAG, tag.name, tag.id))
                if (i < tags.lastIndex) {
                    tokens.add(TagToken(tokenType, when (tokenType) {
                        TokenType.AND -> "AND"; TokenType.OR -> "OR"; TokenType.NO -> "NO"; else -> "AND"
                    }))
                }
            }
            tokens.add(TagToken(TokenType.RPAREN, ")"))
        }
    }
    // 计算括号深度（用于着色）
    var depth = 0
    return tokens.map { token ->
        when (token.type) {
            TokenType.LPAREN -> { val d = depth; depth++; token.copy(depth = d) }
            TokenType.RPAREN -> { depth = (depth - 1).coerceAtLeast(0); token.copy(depth = depth) }
            else -> token.copy(depth = depth)
        }
    }
}

@Composable
private fun ShelfTokenChip(token: TagToken) {
    val (bgColor, textColor, label) = when (token.type) {
        TokenType.TAG -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            token.text,
        )
        TokenType.AND -> Triple(
            Color(0xFFE8DEF8), Color(0xFF4A148C), "AND",
        )
        TokenType.OR -> Triple(
            Color(0xFFFFF3E0), Color(0xFFE65100), "OR",
        )
        TokenType.NO -> Triple(
            Color(0xFFFFEBEE), Color(0xFFC62828), "NO",
        )
        TokenType.LPAREN, TokenType.RPAREN -> {
            val alpha = (0.3f + token.depth * 0.25f).coerceAtMost(1.0f)
            val parenColor = Color(0xFF90CAF9).copy(alpha = alpha.coerceIn(0.3f, 1f))
            Triple(parenColor, Color(0xFF0D47A1), token.text)
        }
    }

    val fontWeight = when (token.type) {
        TokenType.AND, TokenType.OR, TokenType.NO -> FontWeight.Bold
        else -> FontWeight.Normal
    }

    val shape = RoundedCornerShape(
        when (token.type) {
            TokenType.TAG -> 16.dp
            else -> 8.dp
        }
    )

    Box(
        modifier = Modifier
            .background(bgColor, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = if (token.type == TokenType.TAG) 13.sp else 11.sp,
            color = textColor,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
