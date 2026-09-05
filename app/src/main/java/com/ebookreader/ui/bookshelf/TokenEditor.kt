package com.ebookreader.ui.bookshelf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ebookreader.domain.model.TagToken
import com.ebookreader.domain.model.TokenType
import kotlinx.coroutines.delay

/** Renders tag tokens as chips with cursor navigation between them.
 *  Parens get darker backgrounds the deeper they nest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenEditor(
    tokens: List<TagToken>,
    cursorPos: Int,
    onCursorClick: (Int) -> Unit,
    onDeleteAtCursor: () -> Unit,
    onClearAll: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    isTagMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (isTagMode && tokens.isEmpty()) {
            // Empty state placeholder with close button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .border(
                        1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Text(
                    "选择标签筛选",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.Default.Close, "关闭", Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (isTagMode && tokens.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .border(
                        1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 40.dp),
                ) {
                    tokens.forEachIndexed { i, token ->
                        CursorSlot(
                            isActive = cursorPos == i,
                            onClick = { onCursorClick(i) },
                        )
                        TokenChip(token = token)
                    }
                    CursorSlot(
                        isActive = cursorPos == tokens.size,
                        onClick = { onCursorClick(tokens.size) },
                    )
                }
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.Default.Close, "关闭", Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!isTagMode) {
            // TEXT mode: normal text input
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .heightIn(min = 56.dp, max = 160.dp),
                singleLine = false,
                minLines = 1,
                maxLines = 4,
                placeholder = { Text("搜索书名 / 作者 / 格式（空格多词）") },
                leadingIcon = {
                    Icon(Icons.Default.Search, "搜索")
                },
                trailingIcon = {
                    IconButton(onClick = onClearAll) {
                        Icon(Icons.Default.Close, "关闭")
                    }
                },
            )
        }
    }
}

@Composable
private fun CursorSlot(isActive: Boolean, onClick: () -> Unit) {
    val baseColor = if (isActive) MaterialTheme.colorScheme.primary
    else Color.Transparent

    var visible by remember { mutableStateOf(true) }

    if (isActive) {
        LaunchedEffect(Unit) {
            while (true) {
                visible = true; delay(500)
                visible = false; delay(500)
            }
        }
    }

    Box(
        modifier = Modifier
            .width(if (isActive) 3.dp else 6.dp)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(1.dp))
            .then(
                if (isActive && visible)
                    Modifier.background(MaterialTheme.colorScheme.primary)
                else if (!isActive)
                    Modifier.clickable { onClick() }
                else Modifier
            ),
    ) {
        // Invisible spacer for click target when not active
        if (!isActive) {
            Spacer(Modifier.size(6.dp, 28.dp))
        } else {
            Spacer(Modifier.size(3.dp, 28.dp))
        }
    }
}

@Composable
private fun TokenChip(token: TagToken) {
    val (bgColor, textColor, label) = when (token.type) {
        TokenType.TAG -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            token.text,
        )
        TokenType.AND -> Triple(
            Color(0xFFE8DEF8), // light purple
            Color(0xFF4A148C),
            "AND",
        )
        TokenType.OR -> Triple(
            Color(0xFFFFF3E0), // light orange
            Color(0xFFE65100),
            "OR",
        )
        TokenType.NO -> Triple(
            Color(0xFFFFEBEE), // light red/pink
            Color(0xFFC62828),
            "NO",
        )
        TokenType.LPAREN, TokenType.RPAREN -> {
            // Darker with nesting depth
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
