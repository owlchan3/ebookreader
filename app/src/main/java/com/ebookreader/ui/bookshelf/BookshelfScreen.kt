package com.ebookreader.ui.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup
import com.ebookreader.ui.common.VerticalScrollbar
import com.ebookreader.ui.common.computeScrollFraction
import com.ebookreader.ui.common.fractionToScrollPosition
import com.ebookreader.ui.theme.readTagChipColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BookshelfScreen(
    onBookClick: (Long) -> Unit,
    onBookRead: (Long) -> Unit,
    viewModel: BookshelfViewModel = viewModel(),
) {
    val books by viewModel.books.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val tagGroups by viewModel.tagGroups.collectAsState()
    val selectedTagIds by viewModel.selectedTagIds.collectAsState()
    val selectedTagGroupId by viewModel.selectedTagGroupId.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val batchOpMessage by viewModel.batchOpMessage.collectAsState()
    val showSearch by viewModel.showSearch.collectAsState()
    val tokens by viewModel.tokens.collectAsState()
    val cursorPos by viewModel.cursorPos.collectAsState()

    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val readTagColors = readTagChipColors()

    // 切换排序或标签分组时回到列表顶部。首次组合（含从阅读器返回重新组合）时跳过，
    // 避免把 LazyGridState 从导航保存状态恢复的滚动位置又重置回顶部。
    var skipFirstScrollToTop by remember { mutableStateOf(true) }
    LaunchedEffect(sortMode, selectedTagGroupId) {
        if (skipFirstScrollToTop) {
            skipFirstScrollToTop = false
            return@LaunchedEffect
        }
        gridState.scrollToItem(0)
    }

    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBatchTagDialog by remember { mutableStateOf(false) }
    var showSaveGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) viewModel.importBooks(uris) }

    LaunchedEffect(importError) {
        importError?.let { msg ->
            launch {
                snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            }
            delay(1800L)
            snackbarHostState.currentSnackbarData?.dismiss()
            viewModel.clearImportError()
        }
    }
    LaunchedEffect(batchOpMessage) {
        batchOpMessage?.let { msg ->
            launch {
                snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            }
            delay(1800L)
            snackbarHostState.currentSnackbarData?.dismiss()
            viewModel.clearBatchMessage()
        }
    }

    Scaffold(
        topBar = {
            when {
                isSelectionMode -> {
                    TopAppBar(
                        title = { Text("已选 ${selectedIds.size} 本") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                                Icon(Icons.Default.Close, "取消")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(Icons.Default.SelectAll, "全选")
                            }
                            IconButton(onClick = { showBatchTagDialog = true }) {
                                Icon(Icons.Default.Sell, "标签")
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
                showSearch -> {
                    Column {
                        // Token-based editor (TAG mode) or text input (TEXT mode)
                        TokenEditor(
                            tokens = tokens,
                            cursorPos = cursorPos,
                            onCursorClick = { viewModel.moveCursorTo(it) },
                            onDeleteAtCursor = { viewModel.deleteAtCursor() },
                            onClearAll = { viewModel.closeSearch() },
                            query = searchQuery,
                            onQueryChange = { viewModel.setSearchQuery(it) },
                            isTagMode = searchMode == SearchMode.TAG,
                        )
                        // Search mode toggle row with delete/clear buttons
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = searchMode == SearchMode.TEXT,
                                onClick = { viewModel.toggleSearchMode() },
                                label = { Text("文本", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                            )
                            FilterChip(
                                selected = searchMode == SearchMode.TAG,
                                onClick = { viewModel.toggleSearchMode() },
                                label = { Text("标签", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            if (searchMode == SearchMode.TAG) {
                                TextButton(onClick = { viewModel.deleteAtCursor() }) {
                                    Icon(Icons.AutoMirrored.Filled.Backspace, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("删除", fontSize = 12.sp)
                                }
                                TextButton(onClick = { viewModel.clearTokens() }) {
                                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        // TAG mode: operator buttons + tag chips
                        if (searchMode == SearchMode.TAG && allTags.isNotEmpty()) {
                            // Operator buttons: AND, OR, (, )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SuggestionChip(
                                    onClick = { viewModel.insertAnd() },
                                    label = { Text("AND", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.height(28.dp),
                                )
                                SuggestionChip(
                                    onClick = { viewModel.insertOr() },
                                    label = { Text("OR", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.height(28.dp),
                                )
                                SuggestionChip(
                                    onClick = { viewModel.insertNo() },
                                    label = { Text("NO", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.height(28.dp),
                                )
                                SuggestionChip(
                                    onClick = { viewModel.insertOpenParen() },
                                    label = { Text("(", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.height(28.dp),
                                )
                                SuggestionChip(
                                    onClick = { viewModel.insertCloseParen() },
                                    label = { Text(")", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.height(28.dp),
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        showSaveGroupDialog = true
                                        newGroupName = ""
                                    },
                                    enabled = tokens.isNotEmpty(),
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("保存", fontSize = 11.sp)
                                }
                            }
                            // Tag chips to insert
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                    .height(80.dp).verticalScroll(rememberScrollState())
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    allTags.forEach { tag ->
                                        val isSelected = tag.id in selectedTagIds
                                        key(tag.id) {
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { viewModel.insertTagName(tag.name) },
                                                label = { Text(tag.name, fontSize = 12.sp) },
                                                colors = if (tag.isReadTag) {
                                                    FilterChipDefaults.filterChipColors(
                                                        containerColor = readTagColors.container,
                                                        labelColor = readTagColors.label,
                                                        selectedContainerColor = readTagColors.selectedContainer,
                                                        selectedLabelColor = readTagColors.selectedLabel,
                                                    )
                                                } else FilterChipDefaults.filterChipColors(),
                                                modifier = Modifier.height(30.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    TopAppBar(
                        title = { Text("我的书架", fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        actions = {
                            IconButton(onClick = { viewModel.openSearch() }) {
                                Icon(Icons.Default.Search, "搜索")
                            }
                            IconButton(onClick = { showSortMenu = !showSortMenu }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, "排序")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("最近阅读") },
                                    onClick = { viewModel.setSortMode(SortMode.RECENT); showSortMenu = false })
                                DropdownMenuItem(
                                    text = { Text("按书名") },
                                    onClick = { viewModel.setSortMode(SortMode.TITLE); showSortMenu = false })
                                DropdownMenuItem(
                                    text = { Text("按作者") },
                                    onClick = { viewModel.setSortMode(SortMode.AUTHOR); showSortMenu = false })
                                DropdownMenuItem(
                                    text = { Text("按添加时间") },
                                    onClick = { viewModel.setSortMode(SortMode.DATE_ADDED); showSortMenu = false })
                            }
                            IconButton(onClick = {
                                filePickerLauncher.launch(arrayOf(
                                    "application/epub+zip", "application/pdf",
                                    "application/x-mobipocket-ebook", "text/plain", "*/*"
                                ))
                            }) {
                                Icon(Icons.Default.Add, "导入")
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding() + 4.dp)) {
            // Tag group tabs — always visible as bookshelves
            if (tagGroups.isNotEmpty() && !isSelectionMode) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedTagGroupId == null,
                        onClick = { viewModel.clearTagGroup() },
                        label = { Text("全部") },
                    )
                    tagGroups.forEach { group ->
                        val isActive = selectedTagGroupId == group.id
                        FilterChip(
                            selected = isActive,
                            onClick = {
                                if (isActive) viewModel.clearTagGroup()
                                else viewModel.selectTagGroup(group)
                            },
                            label = { Text(group.name, maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }


            // Book grid
            if (books.isEmpty() && !isImporting) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text("书架空空如也", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 18.sp)
                        Text("点击右上角 + 导入你的第一本书",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), fontSize = 14.sp)
                    }
                }
            } else {
                Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                    ) {
                        if (isImporting) item { ImportingCard(importProgress) }
                        items(books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                isSelected = book.id in selectedIds,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) viewModel.toggleSelection(book.id)
                                    else onBookClick(book.id)
                                },
                                onLongClick = { viewModel.enterSelectionMode(book.id) },
                            )
                        }
                    }
                    val gridInfo = gridState.layoutInfo
                    val firstVisible = gridInfo.visibleItemsInfo.firstOrNull()
                    val itemExtent = firstVisible?.size?.height?.toFloat() ?: 0f
                    val viewportExtent = (gridInfo.viewportEndOffset - gridInfo.viewportStartOffset).toFloat()
                    val scrollFraction = computeScrollFraction(
                        firstVisibleIndex = gridState.firstVisibleItemIndex,
                        firstVisibleScrollOffset = gridState.firstVisibleItemScrollOffset,
                        itemExtent = itemExtent,
                        totalItems = gridInfo.totalItemsCount,
                        columns = 3,
                        viewportExtent = viewportExtent,
                    )
                    VerticalScrollbar(
                        fraction = scrollFraction,
                        onScrollFraction = { frac ->
                            val (index, offset) = fractionToScrollPosition(frac, itemExtent, gridInfo.totalItemsCount, 3, viewportExtent)
                            scope.launch { gridState.scrollToItem(index, offset) }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        canScroll = gridInfo.totalItemsCount > gridInfo.visibleItemsInfo.size,
                    )
                }
            }
        }
    }

    // Delete confirmation
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedIds.size} 本书吗？\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(); showDeleteDialog = false }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }

    // Batch tag management dialog
    if (showBatchTagDialog) {
        var showNewTagInput by remember { mutableStateOf(false) }
        var newTagName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBatchTagDialog = false },
            title = { Text("批量管理标签") },
            text = {
                Column {
                    Text("为选中的 ${selectedIds.size} 本书管理标签", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))
                    // Existing tags - tap to add
                    Text("点击标签添加:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        allTags.forEach { tag ->
                            AssistChip(
                                onClick = { viewModel.batchAddTag(tag.id) },
                                label = { Text(tag.name, fontSize = 12.sp) },
                                colors = if (tag.isReadTag) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = readTagColors.container,
                                        labelColor = readTagColors.label,
                                    )
                                } else AssistChipDefaults.assistChipColors(),
                                modifier = Modifier.height(32.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Remove tags
                    Text("点击移除标签:", fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        allTags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.batchRemoveTag(tag.id) },
                                label = { Text(tag.name, fontSize = 12.sp) },
                                colors = if (tag.isReadTag) {
                                    InputChipDefaults.inputChipColors(
                                        containerColor = readTagColors.container,
                                        labelColor = readTagColors.label,
                                    )
                                } else InputChipDefaults.inputChipColors(),
                                trailingIcon = {
                                    Icon(Icons.Default.Close, "移除", Modifier.size(14.dp))
                                },
                                modifier = Modifier.height(32.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showNewTagInput = !showNewTagInput },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) { Text("新建标签") }
                    if (showNewTagInput) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(newTagName, { newTagName = it }, label = { Text("标签名") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (showNewTagInput && newTagName.isNotBlank()) {
                        viewModel.createAndBatchAddTag(newTagName.trim())
                    }
                    showBatchTagDialog = false
                }) { Text("完成") }
            },
            dismissButton = { TextButton(onClick = { showBatchTagDialog = false }) { Text("取消") } },
        )
    }

    // Save filter as tag group dialog
    if (showSaveGroupDialog) {
        AlertDialog(
            onDismissRequest = { showSaveGroupDialog = false },
            title = { Text("保存为书架") },
            text = {
                Column {
                    Text("保存当前标签筛选为命名书架",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(newGroupName, { newGroupName = it },
                        label = { Text("书架名称") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newGroupName.isNotBlank()) {
                        viewModel.saveCurrentFilterAsGroup(newGroupName.trim())
                    }
                    showSaveGroupDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveGroupDialog = false }) { Text("取消") } },
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 1.dp),
    ) {
        Box {
            Column(modifier = Modifier.padding(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (book.coverPath != null) {
                        AsyncImage(model = book.coverPath, contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                if (book.author.isNotEmpty()) {
                    Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (book.currentPage > 0 && book.totalPages > 0) {
                    Text("${((book.currentPage + 1).coerceAtMost(book.totalPages)) * 100 / book.totalPages}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            if (isSelectionMode && isSelected) {
                Icon(Icons.Default.CheckCircle, null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ImportingCard(progress: Pair<Int, Int>?) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(0.75f).padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("导入中…", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            if (progress != null && progress.second > 0) {
                LinearProgressIndicator(
                    progress = { progress.first.toFloat() / progress.second },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${progress.first} / ${progress.second}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }
    }
}
