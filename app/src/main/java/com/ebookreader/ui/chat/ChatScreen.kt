package com.ebookreader.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.domain.model.Book
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    LaunchedEffect(bookId) { viewModel.init(bookId) }

    val book by viewModel.book.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val selectedConvId by viewModel.selectedConversationId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val reasoningContent by viewModel.reasoningContent.collectAsState()
    val isReasoning by viewModel.isReasoning.collectAsState()
    val thinkingTimeMs by viewModel.thinkingTimeMs.collectAsState()
    val showReasoning by viewModel.showReasoning.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    // 只有「正在处理的对话」与「当前选中的对话」一致时，才显示流式/思考气泡
    val isActiveConversation = activeConversationId == selectedConvId
    val showDrawer by viewModel.showConversationDrawer.collectAsState()
    val allBooks by viewModel.allBooks.collectAsState()
    val showImportDialog by viewModel.showImportDialog.collectAsState()
    val importedIds by viewModel.importedBookIds.collectAsState()
    val decomposedBookIds by viewModel.decomposedBookIds.collectAsState()
    val isLoadingBook by viewModel.isLoadingBook.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val diagnostic by viewModel.diagnostic.collectAsState()
    val indexProgress by viewModel.indexProgress.collectAsState()
    val tokenEstimate by viewModel.tokenEstimate.collectAsState()
    val chapterFilter by viewModel.chapterFilter.collectAsState()
    val allChapters by viewModel.allChapters.collectAsState()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val inputText by viewModel.inputText.collectAsState()
    var showDiagnostic by remember { mutableStateOf(false) }

    // Scroll to bottom when new messages arrive (always)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // During streaming, only auto-scroll if user is already at the bottom.
    // If user has scrolled up to read, don't yank them back.
    LaunchedEffect(streamingContent, isActiveConversation) {
        if (streamingContent.isNotEmpty() && isActiveConversation) {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount
            // User is "at bottom" if the last item is visible
            val atBottom = lastVisible != null && lastVisible.index >= totalItems - 1
            if (atBottom) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book?.title ?: "AI 对话", maxLines = 1, fontSize = 16.sp)
                        if (selectedConvId != null) {
                            Text("已选择对话", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDiagnostic = !showDiagnostic }) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = "诊断信息",
                            modifier = Modifier.size(20.dp),
                            tint = if (showDiagnostic)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { viewModel.createNewConversation() }) {
                        Icon(Icons.Default.Add, "新建对话")
                    }
                    IconButton(onClick = { viewModel.toggleConversationDrawer() }) {
                        Icon(Icons.AutoMirrored.Filled.Message, "对话列表", Modifier.size(22.dp))
                    }
                    if (selectedConvId != null) {
                        IconButton(onClick = {
                            val exported = viewModel.exportConversation()
                            if (exported.isNotEmpty()) {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, exported)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "导出对话"))
                            }
                        }) {
                            Icon(Icons.Default.Share, "导出", Modifier.size(22.dp))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxSize()) {
                // Conversation drawer
                if (showDrawer) {
                    Column(
                        Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp)
                    ) {
                        Text("历史对话", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        if (conversations.isEmpty()) {
                            Text("暂无对话", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(conversations, key = { it.id }) { conv ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clickable {
                                                viewModel.selectConversation(conv.id)
                                                viewModel.toggleConversationDrawer()
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selectedConvId == conv.id)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface
                                        ),
                                    ) {
                                        Row(Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(conv.title, fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(formatTimestamp(conv.updatedTimestamp),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                            }
                                            IconButton(
                                                onClick = { viewModel.deleteConversation(conv.id) },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(Icons.Default.Delete, "删除", Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Main chat area
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedConvId == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isLoadingBook) {
                                    CircularProgressIndicator(Modifier.size(36.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载《${book?.title ?: "…"}》…", fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.Message, null, Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                    Spacer(Modifier.height(16.dp))
                                    if (loadError != null) {
                                        Text("⚠ 书籍导入异常", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.height(4.dp))
                                        Text(loadError!!, fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(horizontal = 32.dp))
                                    } else {
                                        Text("点击 + 新建对话",
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { viewModel.createNewConversation() }) {
                                        Text("开始对话")
                                    }
                                    // Diagnostic toggle
                                    Spacer(Modifier.height(4.dp))
                                    TextButton(onClick = { showDiagnostic = !showDiagnostic }) {
                                        Icon(
                                            Icons.Default.Build,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (showDiagnostic) "隐藏诊断" else "诊断信息",
                                            fontSize = 12.sp)
                                    }
                                    if (showDiagnostic && diagnostic.isNotEmpty()) {
                                        val scrollStateV = rememberScrollState()
                                        val scrollStateH = rememberScrollState()
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .padding(horizontal = 12.dp)
                                                .verticalScroll(scrollStateV)
                                                .horizontalScroll(scrollStateH)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .padding(8.dp),
                                        ) {
                                            Text(
                                                diagnostic,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Diagnostic panel (collapsible, scrollable)
                        if (showDiagnostic && diagnostic.isNotEmpty()) {
                            val scrollStateV = rememberScrollState()
                            val scrollStateH = rememberScrollState()
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(8.dp)
                                    .verticalScroll(scrollStateV)
                                    .horizontalScroll(scrollStateH),
                            ) {
                                Text(
                                    diagnostic,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                            state = listState,
                        ) {
                            // 对话开始前的水印：发送第一条消息后消失
                            if (messages.isEmpty()) {
                                item {
                                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                "进行ai拆书以获得更好的对话效果",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                            )
                                            indexProgress?.let {
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    it,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            items(messages, key = { it.id }) { msg ->
                                MessageBubble(msg, onToggleReasoning = null)
                            }
                            // Thinking indicator during retrieval pipeline
                            if (isThinking && streamingContent.isEmpty() && isActiveConversation) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.Start,
                                    ) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(12.dp),
                                        ) {
                                            Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(
                                                    Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "正在检索相关内容…",
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                )
                                            }
                                            if (tokenEstimate.isNotEmpty()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    tokenEstimate,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                        }
                                    }
                                }
                            }
                            if (streamingContent.isNotEmpty() && isActiveConversation) {
                                item {
                                    StreamingBubble(
                                        content = streamingContent,
                                        reasoningContent = reasoningContent,
                                        isReasoning = isReasoning,
                                        thinkingTimeMs = thinkingTimeMs,
                                        showReasoning = showReasoning,
                                        onToggleReasoning = { viewModel.toggleShowReasoning() },
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }

                        // ── Chapter filter (dialog-based, friendly for hundreds of chapters) ──
                        val flatChapters = allChapters.values.flatten().distinct()
                        if (flatChapters.isNotEmpty()) {
                            var showChapterDialog by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (chapterFilter.isNotEmpty())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (chapterFilter.isEmpty()) "指定章节："
                                    else "已选 ${chapterFilter.size} 章：",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                                if (chapterFilter.isNotEmpty()) {
                                    // Show selected chapter names as compact chips
                                    chapterFilter.take(3).forEach { name ->
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            name,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    RoundedCornerShape(6.dp),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (chapterFilter.size > 3) {
                                        Text(
                                            " +${chapterFilter.size - 3}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(
                                        onClick = { viewModel.clearChapterFilter() },
                                        modifier = Modifier.height(24.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    ) {
                                        Text("清空", fontSize = 10.sp)
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { showChapterDialog = true },
                                    modifier = Modifier.height(24.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        if (chapterFilter.isEmpty()) "选择章节" else "修改",
                                        fontSize = 11.sp,
                                    )
                                }
                            }

                            // Chapter selection dialog
                            if (showChapterDialog) {
                                var searchText by remember { mutableStateOf("") }
                                val filtered = if (searchText.isBlank()) flatChapters
                                else flatChapters.filter { it.contains(searchText) }
                                AlertDialog(
                                    onDismissRequest = { showChapterDialog = false },
                                    title = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("选择章节", fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "${chapterFilter.size}/${flatChapters.size}",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            )
                                        }
                                    },
                                    text = {
                                        Column {
                                            OutlinedTextField(
                                                value = searchText,
                                                onValueChange = { searchText = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                placeholder = { Text("搜索章节…") },
                                                singleLine = true,
                                                shape = RoundedCornerShape(12.dp),
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            LazyColumn(Modifier.height(400.dp)) {
                                                items(filtered) { chapter ->
                                                    val selected = chapter in chapterFilter
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .clickable { viewModel.toggleChapterFilter(chapter) }
                                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Checkbox(
                                                            checked = selected,
                                                            onCheckedChange = { viewModel.toggleChapterFilter(chapter) },
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            chapter,
                                                            fontSize = 14.sp,
                                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (selected)
                                                                MaterialTheme.colorScheme.primary
                                                            else
                                                                MaterialTheme.colorScheme.onSurface,
                                                        )
                                                    }
                                                }
                                                if (filtered.isEmpty()) {
                                                    item {
                                                        Text(
                                                            "无匹配章节",
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                            modifier = Modifier.padding(16.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { showChapterDialog = false }) {
                                            Text("完成")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = {
                                            viewModel.clearChapterFilter()
                                            showChapterDialog = false
                                        }) {
                                            Text("清空全部")
                                        }
                                    },
                                )
                            }
                        }

                        // Input bar
                        Row(Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.Bottom) {
                            IconButton(
                                onClick = { viewModel.showImportDialog() },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, "导入书籍", Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { viewModel.setInputText(it) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入消息…") },
                                maxLines = 4,
                                shape = RoundedCornerShape(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank() && !isStreaming) {
                                        viewModel.sendMessage(inputText)
                                        viewModel.setInputText("")
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                enabled = inputText.isNotBlank() && !isStreaming,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "发送", Modifier.size(22.dp),
                                    tint = if (inputText.isNotBlank() && !isStreaming)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            }
                        }

                        // Imported books chips
                        if (importedIds.size > 1) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                importedIds.forEach { id ->
                                    val b = allBooks.find { it.id == id }
                                    if (b != null) {
                                        Card(
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                        ) {
                                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(b.title, fontSize = 11.sp, maxLines = 1)
                                                if (id != bookId) {
                                                    Spacer(Modifier.width(4.dp))
                                                    IconButton(
                                                        onClick = { viewModel.removeImportedBook(id) },
                                                        modifier = Modifier.size(16.dp),
                                                    ) {
                                                        Icon(Icons.Default.Close, "移除", Modifier.size(12.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Import book dialog
    if (showImportDialog) {
        var searchQuery by remember { mutableStateOf("") }
        val filteredBooks = allBooks.filter { b ->
            b.id != bookId && !importedIds.contains(b.id) &&
                (searchQuery.isEmpty() || b.title.contains(searchQuery, ignoreCase = true) ||
                    b.author.contains(searchQuery, ignoreCase = true))
        }.sortedWith(
            compareByDescending<Book> { it.id in decomposedBookIds }
                .thenByDescending { it.addedTimestamp }
        )
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportDialog() },
            title = { Text("导入书籍作为参考资料") },
            text = {
                Column {
                    Text("选择要加入对话上下文的书籍，AI 将会参考这些书籍的内容回答问题。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("搜索书籍") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (filteredBooks.isEmpty()) {
                        Text(if (searchQuery.isEmpty()) "没有更多可导入的书籍" else "未找到匹配的书籍",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.height(300.dp)) {
                            items(filteredBooks.take(30)) { b ->
                                Row(Modifier.fillMaxWidth().clickable { viewModel.importBook(b.id) }
                                    .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(b.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            if (b.id in decomposedBookIds) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("已拆书", fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                            RoundedCornerShape(4.dp),
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 1.dp))
                                            }
                                        }
                                        if (b.author.isNotEmpty())
                                            Text(b.author, fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }
                                    IconButton(
                                        onClick = { viewModel.importBook(b.id) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Default.Add, "导入", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImportDialog() }) { Text("完成") }
            },
        )
    }
}

/** Parses stored message content to separate reasoning from answer body. */
private data class ParsedMessage(val reasoning: String, val answer: String)

private fun parseStoredMessage(content: String): ParsedMessage {
    val reasoningMarker = "[思考过程]"
    val answerMarker = "[正文]"
    val rStart = content.indexOf(reasoningMarker)
    val aStart = content.indexOf(answerMarker)
    if (rStart >= 0 && aStart > rStart) {
        val reasoning = content.substring(rStart + reasoningMarker.length, aStart).trim()
        val answer = content.substring(aStart + answerMarker.length).trim()
        return ParsedMessage(reasoning, answer)
    }
    return ParsedMessage("", content)
}

@Composable
private fun MessageBubble(
    msg: com.ebookreader.domain.model.ChatMessage,
    onToggleReasoning: (() -> Unit)?,
) {
    val isUser = msg.role == "user"
    val parsed = if (!isUser) parseStoredMessage(msg.content) else null
    var showStoredReasoning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(if (isUser) "你" else "AI", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp))
            .background(if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)) {
            Column {
                // Collapsible reasoning section for stored messages
                if (parsed != null && parsed.reasoning.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showStoredReasoning = !showStoredReasoning }
                            .padding(bottom = if (showStoredReasoning) 6.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("思考过程",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        Text(if (showStoredReasoning) "▲" else "▼",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    }
                    if (showStoredReasoning) {
                        Box(Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                RoundedCornerShape(6.dp))
                            .padding(6.dp)) {
                            SelectionContainer {
                                Text(parsed.reasoning,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    lineHeight = 18.sp)
                            }
                        }
                    }
                }
                // Answer text (always selectable)
                SelectionContainer {
                    val answerText = if (parsed != null) parsed.answer else msg.content
                    if (isUser) {
                        Text(answerText,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 15.sp)
                    } else {
                        Text(parseMarkdown(answerText),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    content: String,
    reasoningContent: String,
    isReasoning: Boolean,
    thinkingTimeMs: Long,
    showReasoning: Boolean,
    onToggleReasoning: () -> Unit,
) {
    val thinkingSec = if (thinkingTimeMs > 0) thinkingTimeMs / 1000.0 else 0.0
    val hasReasoning = reasoningContent.isNotEmpty()

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.Start) {
        Text("AI", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))

        // Reasoning panel (collapsible)
        if (hasReasoning) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggleReasoning() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isReasoning) "思考中…" else "已思考 " + "%.1f".format(thinkingSec) + "秒",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (showReasoning) Icons.Default.Close else Icons.AutoMirrored.Filled.Message,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        )
                    }
                    if (showReasoning) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                RoundedCornerShape(6.dp))
                            .padding(6.dp)) {
                            SelectionContainer {
                                Text(
                                    reasoningContent,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Answer bubble — 流式输出期间文本每个 token 都会重绘，SelectionContainer 的长按选择
        // 会被下一次重绘瞬间重置（表现为"有时选不中"）。流式阶段用普通 Text，落库后由
        // MessageBubble 里的 SelectionContainer 提供可选文本。
        Box(Modifier.widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)) {
            Text(parseMarkdown(content.ifEmpty { "…" }),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        ) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (isReasoning) "思考中…"
                else if (thinkingTimeMs > 0) "已思考 " + "%.1f".format(thinkingSec) + "秒 · 正在输入…"
                else "正在输入…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    if (ts == 0L) return ""
    return try {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(ts))
    } catch (_: Exception) { "" }
}

/**
 * 轻量 markdown 渲染：把 AI 答案里的 `**加粗**`、`*斜体*`、`` `代码` ``、
 * `# / ## / ### 标题`、`- 列表` 转成 AnnotatedString，其余按原样输出。
 * 刻意只做最常见标记，避免引入完整 markdown 库；未闭合的标记按普通文本处理（流式截断容错）。
 */
private fun parseMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    for ((idx, rawLine) in lines.withIndex()) {
        if (idx > 0) append('\n')
        val t = rawLine.trim()
        when {
            t.startsWith("### ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineMarkdown(t.removePrefix("### "))
            }
            t.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineMarkdown(t.removePrefix("## "))
            }
            t.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineMarkdown(t.removePrefix("# "))
            }
            t.startsWith("- ") -> {
                append("• ")
                appendInlineMarkdown(t.removePrefix("- "))
            }
            else -> appendInlineMarkdown(rawLine)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text, i + 2, end) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text, i + 1, end) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text, i + 1, end) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
