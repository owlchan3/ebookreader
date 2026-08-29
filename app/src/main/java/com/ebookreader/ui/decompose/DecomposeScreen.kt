package com.ebookreader.ui.decompose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.domain.model.BookDecomposition
import com.ebookreader.domain.model.DecomposedChapter
import com.ebookreader.domain.model.OutlineNode
import com.ebookreader.ui.common.VerticalScrollbar
import com.ebookreader.ui.common.computeScrollFraction
import com.ebookreader.ui.common.fractionToScrollPosition
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecomposeScreen(
    bookId: Long,
    bookType: String,
    onBack: () -> Unit,
    viewModel: DecomposeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookId, bookType) {
        viewModel.start(bookId, bookType)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 拆书") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state is DecomposeState.Done || state is DecomposeState.Generating) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is DecomposeState.Idle -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                }
                is DecomposeState.Generating -> {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("正在拆解…", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("进度 ${s.done}/${s.total} · 当前章节：${shortTitle(s.currentChapter)}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("已生成的章节会自动保存，中途返回可从断点继续。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
                is DecomposeState.NeedsRetry -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在拆解…", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("进度 ${s.done + 1}/${s.total} · 当前章节：${shortTitle(s.chapterTitle)}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    AlertDialog(
                        onDismissRequest = { /* 必须明确选择，禁止点击外部关闭 */ },
                        title = { Text("章节生成失败") },
                        text = { Text("「${shortTitle(s.chapterTitle)}」已自动重试 3 次仍失败。是否重试？") },
                        confirmButton = {
                            TextButton(onClick = { viewModel.answerRetry(true) }) { Text("重试") }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.answerRetry(false) }) { Text("标记失败并继续") }
                        },
                    )
                }
                is DecomposeState.Done -> {
                    DecomposeResult(decomposition = s.decomposition, typeLabel = viewModel.bookTypeLabel(s.decomposition.bookType))
                }
                is DecomposeState.Error -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("拆书失败：${s.message}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.start(bookId, bookType) }) { Text("重试") }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        val isDone = state is DecomposeState.Done
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(if (isDone) "删除拆书内容" else "删除拆书进度") },
            text = {
                Text(
                    if (isDone) "确定删除这本书的拆书结果吗？删除后再次点击「AI 拆书」将重新开始。"
                    else "确定删除当前的拆书进度并重新开始吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    if (isDone) {
                        viewModel.delete(bookId) { onBack() }
                    } else {
                        viewModel.deleteProgressAndRestart(bookId, bookType)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

private sealed interface Row {
    val title: String
    data class Section(val sectionTitle: String, val body: String, val highlight: Boolean = false) : Row {
        override val title get() = sectionTitle
    }
    data class OutlineRow(val node: OutlineNode) : Row {
        override val title get() = "全书大纲"
    }
    data class ChapterRow(val chapter: DecomposedChapter) : Row {
        override val title get() = chapter.title
    }
}

@Composable
private fun DecomposeResult(decomposition: BookDecomposition, typeLabel: String) {
    val listState = rememberLazyListState()
    val tocListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showToc by remember { mutableStateOf(false) }

    val rows = buildRows(decomposition)
    val visibleRows = if (query.isBlank()) rows
        else rows.filter { it is Row.ChapterRow && it.chapter.matches(query) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索章节标题或梗概…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.height(18.dp)) },
                singleLine = true,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(visibleRows, key = { _, r -> r.key() }) { index, row ->
                    when (row) {
                        is Row.Section -> {
                            if (row.body.isBlank()) {
                                // 纯标题行（如「每章概述」作为章节列表的标题）
                                Text(row.sectionTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            } else {
                                SectionCard(row.sectionTitle, row.highlight) {
                                    Text(row.body, fontSize = 14.sp, lineHeight = 22.sp)
                                }
                            }
                        }
                        is Row.OutlineRow -> SectionCard("全书大纲", highlight = false) {
                            OutlineTree(row.node)
                        }
                        is Row.ChapterRow -> ChapterRow(row.chapter)
                    }
                }
            }
        }
        // 右侧滑条（无级滚动，参考通用 VerticalScrollbar）
        val listLayoutInfo = listState.layoutInfo
        val firstItem = listLayoutInfo.visibleItemsInfo.firstOrNull()
        val itemExtent = firstItem?.size?.toFloat() ?: 0f
        val viewportExtent = (listLayoutInfo.viewportEndOffset - listLayoutInfo.viewportStartOffset).toFloat()
        VerticalScrollbar(
            fraction = computeScrollFraction(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset,
                itemExtent = itemExtent,
                totalItems = listLayoutInfo.totalItemsCount,
                columns = 1,
                viewportExtent = viewportExtent,
            ),
            onScrollFraction = { frac ->
                val (index, offset) = fractionToScrollPosition(frac, itemExtent, listLayoutInfo.totalItemsCount, 1, viewportExtent)
                scope.launch { listState.scrollToItem(index, offset) }
            },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp),
            canScroll = listLayoutInfo.totalItemsCount > listLayoutInfo.visibleItemsInfo.size,
        )
        // 目录按钮（悬浮在右下）
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            androidx.compose.material3.FloatingActionButton(
                onClick = { showToc = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.AutoMirrored.Filled.List, "目录")
            }
        }
    }

    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录") },
            text = {
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    LazyColumn(state = tocListState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(visibleRows, key = { _, r -> "toc_${r.key()}" }) { index, row ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        scope.launch { listState.animateScrollToItem(index) }
                                        showToc = false
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    when (row) {
                                        is Row.ChapterRow -> row.chapter.title
                                        else -> row.title
                                    },
                                    fontSize = 14.sp,
                                    color = if (row is Row.ChapterRow) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.primary,
                                    fontWeight = if (row is Row.ChapterRow) FontWeight.Normal else FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    val tocLayoutInfo = tocListState.layoutInfo
                    val tocFirstItem = tocLayoutInfo.visibleItemsInfo.firstOrNull()
                    val tocItemExtent = tocFirstItem?.size?.toFloat() ?: 0f
                    val tocViewportExtent = (tocLayoutInfo.viewportEndOffset - tocLayoutInfo.viewportStartOffset).toFloat()
                    VerticalScrollbar(
                        fraction = computeScrollFraction(
                            firstVisibleIndex = tocListState.firstVisibleItemIndex,
                            firstVisibleScrollOffset = tocListState.firstVisibleItemScrollOffset,
                            itemExtent = tocItemExtent,
                            totalItems = tocLayoutInfo.totalItemsCount,
                            columns = 1,
                            viewportExtent = tocViewportExtent,
                        ),
                        onScrollFraction = { frac ->
                            val (index, offset) = fractionToScrollPosition(frac, tocItemExtent, tocLayoutInfo.totalItemsCount, 1, tocViewportExtent)
                            scope.launch { tocListState.scrollToItem(index, offset) }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        canScroll = tocLayoutInfo.totalItemsCount > tocLayoutInfo.visibleItemsInfo.size,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showToc = false }) { Text("关闭") } },
        )
    }
}

private fun Row.key(): String = when (this) {
    is Row.Section -> "sec_$sectionTitle"
    is Row.OutlineRow -> "outline"
    is Row.ChapterRow -> "ch_${chapter.title}"
}

private fun DecomposedChapter.matches(q: String): Boolean =
    title.contains(q, true) || summary.contains(q, true) ||
        events.contains(q, true) || characters.contains(q, true)

private fun buildRows(d: BookDecomposition): List<Row> {
    val rows = mutableListOf<Row>()
    if (d.bookSummary.isNotBlank()) rows.add(Row.Section("全书总结", d.bookSummary, highlight = true))

    // deep 档模块：用类型专属标题（每章概述 = 下方逐章列表，这里不再单列）
    val titles = DecomposePrompts.deepModules(d.bookType).associateBy { it.key }
    fun section(key: String, content: String, fallbackTitle: String) {
        if (content.isNotBlank()) {
            rows.add(Row.Section(titles[key]?.title ?: fallbackTitle, content))
        }
    }
    section("characters", d.characters, "人物关系")
    section("timeline", d.timeline, "时间线")
    section("quotes", d.quotes, "金句")
    section("characterBios", d.characterBios, "人物小传")
    section("worldSetting", d.worldSetting, "世界观与设定")
    section("extendedReading", d.extendedReading, "拓展阅读")

    // 每章概述 = 逐章梗概（章节列表），用一个标题行分隔
    if (d.chapters.isNotEmpty()) {
        rows.add(Row.Section("每章概述", ""))
        d.chapters.forEach { rows.add(Row.ChapterRow(it)) }
    }
    return rows
}

@Composable
private fun OutlineTree(root: OutlineNode) {
    val flat = flattenOutline(root)
    Column {
        flat.forEach { (depth, node) ->
            Row(Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp)) {
                if (node.summary.isNotBlank()) {
                    Column {
                        Text(node.title, fontWeight = if (depth <= 1) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
                        Text(node.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                } else {
                    Text(node.title, fontWeight = if (depth <= 1) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun flattenOutline(root: OutlineNode): List<Pair<Int, OutlineNode>> {
    val out = mutableListOf<Pair<Int, OutlineNode>>()
    fun walk(node: OutlineNode, depth: Int) {
        out.add(depth to node)
        node.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return out
}

/** 截断过长的章节标题，避免进度条显示大段原文。 */
private fun shortTitle(title: String, max: Int = 30): String =
    if (title.length <= max) title else title.take(max) + "…"

@Composable
private fun ChapterRow(ch: DecomposedChapter) {
    Column(Modifier.fillMaxWidth()) {
        Text(ch.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(ch.summary, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        if (ch.events.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("关键事件：${ch.events}", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (ch.characters.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("人物：${ch.characters}", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun SectionCard(title: String, highlight: Boolean, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (highlight) {
                    Icon(Icons.Default.AutoStories, null, Modifier.padding(end = 6.dp).height(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(title, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
