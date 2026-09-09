package com.ebookreader.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ebookreader.domain.model.Book
import com.ebookreader.ui.recommend.RecommendationItem
import com.ebookreader.ui.theme.readTagChipColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    onReadClick: () -> Unit,
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit = {},
    onDecomposeClick: (String) -> Unit = {},
    viewModel: BookDetailViewModel = viewModel(),
) {
    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    // Reload book data when returning from reader (screen RESUMEs)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadBook()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val book by viewModel.book.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val isDeleted by viewModel.isDeleted.collectAsState()
    val relatedBooks by viewModel.relatedBooks.collectAsState()
    val allBooksForDialog by viewModel.allBooks.collectAsState()
    val showAddRelatedDialog by viewModel.showAddRelatedDialog.collectAsState()
    val hasDecomposition by viewModel.hasDecomposition.collectAsState()
    val decomposeStatus by viewModel.decomposeStatus.collectAsState()
    val existingDecomposeBookType by viewModel.existingDecomposeBookType.collectAsState()
    val keywords by viewModel.keywords.collectAsState()
    val isGeneratingKeywords by viewModel.isGeneratingKeywords.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val isGeneratingRecommendations by viewModel.isGeneratingRecommendations.collectAsState()

    LaunchedEffect(isDeleted) { if (isDeleted) onBack() }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNewTagDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showEnlargeCover by remember { mutableStateOf(false) }
    var showDecomposeDialog by remember { mutableStateOf(false) }
    var showKeywords by remember { mutableStateOf(false) }
    var showRecommend by remember { mutableStateOf(false) }
    val recommendEnabled = remember { viewModel.isRecommendEnabled() }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coverMessage by viewModel.coverMessage.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    val keywordMessage by viewModel.keywordMessage.collectAsState()
    val isUpdating by viewModel.isUpdating.collectAsState()
    val readTagColors = readTagChipColors()

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.updateCover(it) } }

    val updateFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.updateBook(it) } }

    LaunchedEffect(coverMessage) {
        coverMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCoverMessage()
        }
    }
    LaunchedEffect(updateMessage) {
        updateMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUpdateMessage()
        }
    }
    LaunchedEffect(keywordMessage) {
        keywordMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearKeywordMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "书籍详情") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.Menu, contentDescription = "更多") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("更新") },
                                onClick = { showMenu = false; updateFilePicker.launch(arrayOf("*/*")) },
                                enabled = !isUpdating,
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = { showMenu = false; showDeleteDialog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val currentBook = book ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Cover + basic info
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.width(120.dp).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp))
                        .clickable { showEnlargeCover = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (currentBook.coverPath != null)
                        AsyncImage(model = currentBook.coverPath, contentDescription = null, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentBook.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (currentBook.author.isNotEmpty())
                        Text(currentBook.author, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text(" ${currentBook.format}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                    if (currentBook.fileSize > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text(" ${formatFileSize(currentBook.fileSize)}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 14.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccessTime, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text(" 累计阅读 ${formatReadingTime(currentBook.totalReadingTime)}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            val displayPage = (currentBook.currentPage + 1).coerceAtMost(currentBook.totalPages)
            Button(onClick = onReadClick, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(if (currentBook.currentPage > 0) "继续阅读 (${displayPage * 100 / maxOf(currentBook.totalPages, 1)}%)" else "开始阅读")
            }
            Spacer(Modifier.height(8.dp))
            // AI 拆书属于 AI 插件功能，插件关闭时不显示
            if (viewModel.isAiEnabled()) {
                OutlinedButton(
                    onClick = {
                        // 已拆完/拆到一半：直接打开（结果页/续传进度）；未拆：弹出二次确认选类型
                        if (hasDecomposition) {
                            onDecomposeClick(existingDecomposeBookType)
                        } else {
                            showDecomposeDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoStories, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (decomposeStatus) {
                            "done" -> "查看拆书结果"
                            "generating" -> "继续拆书"
                            else -> "AI 拆书"
                        }
                    )
                }
            }

            // Reading progress bar
            if (currentBook.totalPages > 0) {
                Spacer(Modifier.height(12.dp))
                val progress = displayPage.toFloat() / currentBook.totalPages.toFloat()
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("阅读进度", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).then(
                            Modifier.fillMaxWidth()
                        )) {
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.CenterStart) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                )
                            }
                        }
                        Text("第 $displayPage / ${currentBook.totalPages} 页 (${(progress * 100).toInt()}%)",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }

            // Description
            if (currentBook.description.isNotEmpty()) {
                Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text("简介", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(currentBook.description, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }

            // Tags / Keywords（切换按钮在「添加标签」旁，点击在标签与关键词之间切换）
            Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showKeywords) "关键词" else "标签", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showKeywords = !showKeywords }) {
                    Icon(Icons.Filled.SyncAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (showKeywords) "标签" else "关键词")
                }
                if (!showKeywords) {
                    IconButton(onClick = { showNewTagDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, "新建标签", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (showKeywords) {
                when {
                    isGeneratingKeywords -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "正在提取关键词…",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                    keywords.isEmpty() -> {
                        Column {
                            OutlinedButton(onClick = { viewModel.generateKeywords() }) {
                                Icon(Icons.Default.Cloud, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("生成关键词")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "本地统计 + AI 语义提取本书关键词（首次生成约需数秒）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                    else -> {
                        WordCloud(keywords = keywords)
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { viewModel.generateKeywords() }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重新生成")
                        }
                    }
                }
            } else {
                FlowRow {
                    val sortedTags = allTags.sortedByDescending { tags.any { selected -> selected.id == it.id } }
                    sortedTags.forEach { tag ->
                        key(tag.id) {
                            val isSelected = tags.any { it.id == tag.id }
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) viewModel.removeTag(tag.id)
                                    else viewModel.addTag(tag.id)
                                },
                                label = { Text(tag.name) },
                                colors = if (tag.isReadTag || tag.isPinTag) {
                                    FilterChipDefaults.filterChipColors(
                                        containerColor = readTagColors.container,
                                        labelColor = readTagColors.label,
                                        selectedContainerColor = readTagColors.selectedContainer,
                                        selectedLabelColor = readTagColors.selectedLabel,
                                    )
                                } else FilterChipDefaults.filterChipColors(),
                                modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                if (allTags.isEmpty() && tags.isEmpty()) {
                    Text("点击 + 新建标签", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }

            // Related Books / 推荐书籍（切换按钮，仿标签/关键词切换）
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showRecommend) "推荐书籍" else "相关书籍", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (recommendEnabled) {
                    TextButton(onClick = {
                        showRecommend = !showRecommend
                        if (showRecommend) viewModel.ensureRecommendations()
                    }) {
                        Icon(Icons.Filled.SyncAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showRecommend) "相关书籍" else "推荐书籍")
                    }
                }
                if (!showRecommend) {
                    IconButton(onClick = { viewModel.showAddRelatedDialog() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, "添加相关书籍", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (showRecommend) {
                when {
                    isGeneratingRecommendations -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在生成推荐…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    recommendations.isEmpty() -> {
                        Text("暂无推荐，多读几本或补充标签/简介后再试", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            recommendations.forEach { item ->
                                key(item.dismissKey) {
                                    RecommendationRow(
                                        item = item,
                                        onClick = {
                                            if (item.isLocal) {
                                                onBookClick(item.bookId)
                                            } else {
                                                item.link?.let { url ->
                                                    runCatching {
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (relatedBooks.isEmpty()) {
                    Text("点击 + 添加相关书籍", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        relatedBooks.forEach { related ->
                            key(related.id) {
                                RelatedBookCard(
                                    book = related,
                                    onClick = { onBookClick(related.id) },
                                    onRemove = { viewModel.removeRelatedBook(related.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除《${book?.title}》吗？\n书籍文件不会被删除，仅从书架移除。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteBook(); showDeleteDialog = false }) {
                Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }

    if (showNewTagDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewTagDialog = false },
            title = { Text("新建标签") },
            text = {
                Column {
                    OutlinedTextField(newName, { newName = it }, label = { Text("标签名称") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { viewModel.createAndAddTag(newName.trim()); showNewTagDialog = false } }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showNewTagDialog = false }) { Text("取消") } },
        )
    }

    // Add related book dialog
    if (showAddRelatedDialog) {
        val relatedIds = relatedBooks.map { it.id }
        AddRelatedBookDialog(
            allBooks = allBooksForDialog,
            currentBookId = bookId,
            relatedBookIds = relatedIds,
            onDismiss = { viewModel.dismissAddRelatedDialog() },
            onConfirm = { selectedIds ->
                selectedIds.forEach { viewModel.addRelatedBook(it) }
                viewModel.dismissAddRelatedDialog()
            },
        )
    }

    // Edit metadata dialog
    if (showEditDialog && book != null) {
        val b = book!!
        var editTitle by remember { mutableStateOf(TextFieldValue(b.title)) }
        var editAuthor by remember { mutableStateOf(TextFieldValue(b.author)) }
        var editDesc by remember { mutableStateOf(b.description) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑书籍信息") },
            text = {
                Column {
                    // Title — OutlinedTextField auto-grows for long text
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        minLines = 1,
                        maxLines = 3,
                        label = { Text("书名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    // Author — OutlinedTextField auto-grows for long text
                    OutlinedTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        minLines = 1,
                        maxLines = 3,
                        label = { Text("作者") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    // Description
                    OutlinedTextField(
                        editDesc, { editDesc = it },
                        label = { Text("简介") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    // AI generate button (only when plugin enabled)
                    if (viewModel.isAiEnabled()) {
                        Spacer(Modifier.height(8.dp))
                        val isGenerating by viewModel.isGeneratingDesc.collectAsState()
                        Button(
                            onClick = {
                                viewModel.generateDescription { generated ->
                                    editDesc = generated
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating,
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("AI 正在生成…")
                            } else {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("AI 生成简介")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateBookInfo(editTitle.text.trim(), editAuthor.text.trim(), editDesc.trim())
                    showEditDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("取消") } },
        )
    }

    // Enlarge cover dialog
    if (showEnlargeCover && book != null) {
        val b = book!!
        Dialog(onDismissRequest = { showEnlargeCover = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(b.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (b.coverPath != null)
                        AsyncImage(model = b.coverPath, contentDescription = null, modifier = Modifier.fillMaxSize())
                    else
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { viewModel.saveCoverToGallery() }) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存到相册")
                    }
                    TextButton(onClick = { coverPicker.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("更换封面")
                    }
                }
            }
        }
    }

    // AI 拆书二次确认 dialog
    if (showDecomposeDialog) {
        AlertDialog(
            onDismissRequest = { showDecomposeDialog = false },
            title = { Text("AI 拆书") },
            text = {
                Text(
                    "将用 AI 拆解本书（逐章摘要 + 全书总结），会消耗当前 AI 模型的 token，费用与书籍字数成正比，可能需要较长时间。\n\n书籍类型将自动识别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDecomposeDialog = false
                    onDecomposeClick("auto")
                }) { Text("开始拆书") }
            },
            dismissButton = { TextButton(onClick = { showDecomposeDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RelatedBookCard(
    book: Book,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.width(100.dp)) {
        Column(
            modifier = Modifier
                .width(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverPath != null)
                    AsyncImage(model = book.coverPath, contentDescription = book.title)
                else
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook, null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                book.title,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Remove button overlay
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            Icon(
                Icons.Default.Close, "移除关联",
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun RecommendationRow(item: RecommendationItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp)) {
            Box(
                Modifier.width(56.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.cover != null) {
                    AsyncImage(
                        model = item.cover,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (item.isLocal) "本地" else item.source.ifBlank { "联网" },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (item.author.isNotBlank()) {
                    Text(item.author, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                }
                if (item.reason.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.reason, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.description, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when { bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"; bytes >= 1024 -> "${bytes / 1024} KB"; else -> "$bytes B" }
}
private fun formatReadingTime(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}
