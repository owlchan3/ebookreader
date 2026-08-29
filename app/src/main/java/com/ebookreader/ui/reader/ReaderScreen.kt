package com.ebookreader.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.ui.common.VerticalScrollbar
import com.ebookreader.ui.common.computeScrollFraction
import com.ebookreader.ui.common.fractionToScrollPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.navigator.common.InputListener
import org.readium.navigator.common.TapContext
import org.readium.navigator.common.TapEvent
import org.readium.navigator.common.defaultInputListener
import org.readium.navigator.web.fixedlayout.FixedWebRendition
import org.readium.navigator.web.reflowable.ReflowableWebRendition
import org.readium.navigator.web.reflowable.preferences.ReflowableWebPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onChatClick: () -> Unit = {},
    viewModel: ReaderViewModel = viewModel(),
) {
    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    // 回到阅读页时重置计时起点，避免聊天等停留时间被算进阅读时长；
    // 离开阅读页（后台/返回）时立刻结算本次阅读时长，避免切到后台这段时间被漏记导致统计偏短。
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenResume()
        onPauseOrDispose { viewModel.saveProgress() }
    }

    val book by viewModel.book.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val pageLabel by viewModel.currentPageLabel.collectAsState()
    val currentPageIndex by viewModel.currentPageIndex.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val tocItems by viewModel.tocItems.collectAsState()
    val currentChapterHref by viewModel.currentChapterHref.collectAsState()
    val currentChapterTitle by viewModel.currentChapterTitle.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val themeKey by viewModel.themeKey.collectAsState()
    val scrollMode by viewModel.scrollMode.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isRefreshingToc by viewModel.isRefreshingToc.collectAsState()
    val tocRefreshMessage by viewModel.tocRefreshMessage.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val ttsSynthesisProgress by viewModel.ttsSynthesisProgress.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 首次打开听书时若未授予通知权限，先申请（Android 13+）；结果不影响播放，
    // 仅决定前台服务的通知是否可见。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    fun startTtsWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.toggleTtsPlayPause()
    }

    var isUiVisible by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDraggingProgress by remember { mutableStateOf(false) }

    // Chapter refresh / delete dialogs
    var showRefreshTocDialog by remember { mutableStateOf(false) }
    var showDeleteChapterDialog by remember { mutableStateOf<String?>(null) }

    // TTS drag position & minimize state
    var ttsOffsetX by remember { mutableStateOf(0f) }
    var ttsOffsetY by remember { mutableStateOf(0f) }
    var ttsMinimized by remember { mutableStateOf(false) }

    // Reset position / minimized when TTS stops (returns to Idle)
    LaunchedEffect(ttsState) {
        if (ttsState is TtsPlaybackState.Idle) {
            ttsOffsetX = 0f
            ttsOffsetY = 0f
            ttsMinimized = false
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val tocListState = rememberLazyListState()

    // 当前阅读位置对应的目录项：取「最后一个 readingOrderIndex ≤ 当前章节索引」的条目，
    // 这样目录比正文层级更粗（只含大标题）或更细（含小标题）时都能正确定位。
    val currentTocIndex = if (currentChapterIndex >= 0) {
        var result = -1
        for ((i, item) in tocItems.withIndex()) {
            if (item.readingOrderIndex in 0..currentChapterIndex) result = i
        }
        result
    } else -1

    LaunchedEffect(drawerState.isOpen, currentTocIndex) {
        if (drawerState.isOpen && currentTocIndex >= 0) {
            tocListState.animateScrollToItem(currentTocIndex)
        }
    }

    LaunchedEffect(isUiVisible, isDraggingProgress, drawerState.isOpen, showBookmarksSheet, showSearchSheet, showSettingsSheet) {
        // 拖动进度条、目录展开或任一面板打开时不要自动收起 UI，等它们都关闭后再计时收起
        val panelOpen = showBookmarksSheet || showSearchSheet || showSettingsSheet
        if (isUiVisible && !isDraggingProgress && !drawerState.isOpen && !panelOpen) {
            delay(3000)
            isUiVisible = false
        }
    }

    // Show toast for TOC refresh/delete messages
    LaunchedEffect(tocRefreshMessage) {
        tocRefreshMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearRefreshMessage()
        }
    }

    LaunchedEffect(currentPageIndex, totalPages) {
        if (totalPages > 1) {
            sliderPosition = currentPageIndex.toFloat() / (totalPages - 1).coerceAtLeast(1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // PDF 用左右滑动翻页，目录收起时禁用边缘手势避免和翻页冲突；
        // 目录展开时开启手势，让 scrim 点击 / 左滑关闭目录生效（否则 Material3 内置 scrim 的
        // onClose 会被 gesturesEnabled=false 拦截，导致点击非目录区域无法收回目录）
        gesturesEnabled = uiState !is ReaderUiState.PdfReady || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)) {
                        Text("目录", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        if (isRefreshingToc) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { showRefreshTocDialog = true },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, "刷新章节", Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (tocItems.isEmpty()) {
                        Text("无目录", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    } else {
                        Box(Modifier.fillMaxHeight().weight(1f, fill = false)) {
                            LazyColumn(Modifier.fillMaxSize(), state = tocListState) {
                                itemsIndexed(tocItems) { index, item ->
                                    val isCurrentChapter = index == currentTocIndex
                                    Text(item.title,
                                        Modifier.fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    viewModel.goToTocItem(item.href)
                                                    scope.launch { drawerState.close() }
                                                },
                                                onLongClick = {
                                                    showDeleteChapterDialog = item.title
                                                },
                                            )
                                            .padding(vertical = 10.dp, horizontal = (item.level * 16).dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrentChapter) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    HorizontalDivider()
                                }
                            }
                            val tocInfo = tocListState.layoutInfo
                            val firstTocItem = tocInfo.visibleItemsInfo.firstOrNull()
                            val tocItemExtent = firstTocItem?.size?.toFloat() ?: 0f
                            val tocViewportExtent = (tocInfo.viewportEndOffset - tocInfo.viewportStartOffset).toFloat()
                            val tocFraction = computeScrollFraction(
                                firstVisibleIndex = tocListState.firstVisibleItemIndex,
                                firstVisibleScrollOffset = tocListState.firstVisibleItemScrollOffset,
                                itemExtent = tocItemExtent,
                                totalItems = tocInfo.totalItemsCount,
                                columns = 1,
                                viewportExtent = tocViewportExtent,
                            )
                            VerticalScrollbar(
                                fraction = tocFraction,
                                onScrollFraction = { frac ->
                                    val (index, offset) = fractionToScrollPosition(frac, tocItemExtent, tocInfo.totalItemsCount, 1, tocViewportExtent)
                                    scope.launch { tocListState.scrollToItem(index, offset) }
                                },
                                modifier = Modifier.align(Alignment.CenterEnd),
                                canScroll = tocInfo.totalItemsCount > tocInfo.visibleItemsInfo.size,
                            )
                        }
                    }
                }
            }
        },
    ) {
        val centerTapListener = remember {
            object : InputListener {
                override fun onTap(event: TapEvent, context: TapContext) {
                    isUiVisible = !isUiVisible
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Reader content
            when (val state = uiState) {
                is ReaderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReaderUiState.Error -> Text(state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                is ReaderUiState.Ready -> {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    val controller = state.controller
                    val selectionActionMode = remember(controller) {
                        controller?.let { TextSelectionActionModeCallback(context, it, scope) }
                    }
                    ReflowableWebRendition(
                        modifier = Modifier.fillMaxSize(),
                        state = state.state,
                        inputListener = defaultInputListener(
                            controller = state.controller,
                            tapEdges = setOf(androidx.compose.foundation.gestures.Orientation.Horizontal),
                            minimumHorizontalEdgeSize = 120.dp,
                            horizontalEdgeThresholdPercent = null,
                            fallbackListener = centerTapListener,
                        ),
                        textSelectionActionModeCallback = selectionActionMode,
                    )
                }
                is ReaderUiState.FixedReady -> {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    val controller = state.fixedState.controller
                    val selectionActionMode = remember(controller) {
                        controller?.let { TextSelectionActionModeCallback(context, it, scope) }
                    }
                    FixedWebRendition(
                        modifier = Modifier.fillMaxSize(),
                        state = state.fixedState,
                        inputListener = defaultInputListener(
                            controller = state.fixedState.controller,
                            tapEdges = setOf(androidx.compose.foundation.gestures.Orientation.Horizontal),
                            minimumHorizontalEdgeSize = 120.dp,
                            horizontalEdgeThresholdPercent = null,
                            fallbackListener = centerTapListener,
                        ),
                        textSelectionActionModeCallback = selectionActionMode,
                    )
                }
                is ReaderUiState.PdfReady -> {
                    val pdfPage by viewModel.pdfPage.collectAsState()
                    val pdfBackground = when (themeKey) {
                        "sepia" -> android.graphics.Color.parseColor("#faf4e8")
                        "dark" -> android.graphics.Color.parseColor("#000000")
                        else -> android.graphics.Color.WHITE
                    }
                    key(scrollMode) {
                        AndroidView(
                            factory = { ctx ->
                                val initialPage = book?.currentPage ?: 0
                                val pdfView = com.github.barteksc.pdfviewer.PDFView(ctx, null)
                                pdfView.setBackgroundColor(pdfBackground)
                                pdfView.fromFile(java.io.File(state.filePath))
                                    .defaultPage(initialPage)
                                    .onLoad { _ -> pdfView.jumpTo(initialPage, false) }
                                    .onPageChange { page, count ->
                                        viewModel.onPdfPageChanged(page, count)
                                    }
                                    .onTap { e ->
                                        if (drawerState.isOpen) {
                                            // 目录展开时点击侧边：关闭目录，而不是翻页/切换 UI
                                            scope.launch { drawerState.close() }
                                        } else {
                                            val density = pdfView.resources.displayMetrics.density
                                            val xDp = e.x / density
                                            when {
                                                xDp < 120f -> viewModel.goToPdfPage(pdfPage - 1)
                                                xDp > pdfView.width / density - 120f -> viewModel.goToPdfPage(pdfPage + 1)
                                                else -> isUiVisible = !isUiVisible
                                            }
                                        }
                                        true
                                    }
                                    .pageFitPolicy(com.github.barteksc.pdfviewer.util.FitPolicy.BOTH)
                                    .swipeHorizontal(!scrollMode)
                                    .enableSwipe(true)
                                    .spacing(0)
                                    .load()
                                pdfView
                            },
                            update = { pdfView ->
                                pdfView.jumpTo(pdfPage, false)
                                pdfView.setBackgroundColor(pdfBackground)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 亮度遮罩（亮度 < 1 时叠加半透明黑色）
            if (brightness < 1.0f) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 1.0f - brightness))
                        .zIndex(1f),
                )
            }

            // 顶部留白处小字灰色显示当前章节名（靠左、最多两行）
            if (currentChapterTitle.isNotEmpty() && !isUiVisible) {
                Text(
                    currentChapterTitle,
                    Modifier.align(Alignment.TopStart).padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    fontSize = 11.sp,
                    color = Color.Gray.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Top bar
            AnimatedVisibility(
                visible = isUiVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(book?.title ?: "阅读", maxLines = 1, fontSize = 16.sp)
                            if (pageLabel.isNotEmpty()) {
                                Text(pageLabel, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    ),
                    navigationIcon = {
                        IconButton(onClick = { viewModel.saveProgress(); onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        if (com.ebookreader.di.Injector.apiKeyManager().isEnabled()) {
                            IconButton(onClick = { viewModel.saveProgress(); onChatClick() }) {
                                Icon(Icons.AutoMirrored.Filled.Message, "AI 聊天", Modifier.size(22.dp))
                            }
                        }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.Toc, "目录", Modifier.size(22.dp))
                        }
                    },
                )
            }

            // TTS playback panel — draggable, visible when TTS is active
            if (ttsState !is TtsPlaybackState.Idle) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .offset { IntOffset(ttsOffsetX.roundToInt(), ttsOffsetY.roundToInt()) }
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp),
                ) {
                    if (ttsMinimized) {
                        // Minimized: small floating chip
                        Card(
                            modifier = Modifier
                                .clickable { ttsMinimized = false }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        ttsOffsetX += dragAmount.x
                                        ttsOffsetY += dragAmount.y
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            ),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (ttsState is TtsPlaybackState.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    "播放/暂停", Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "朗读中…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    } else {
                        // Expanded panel
                        TtsPlaybackPanel(
                            state = ttsState,
                            onPlayPause = { startTtsWithNotificationPermission() },
                            onStop = { viewModel.stopTts() },
                            onNext = { viewModel.ttsNextSentence() },
                            onPrev = { viewModel.ttsPrevSentence() },
                            onMinimize = { ttsMinimized = true },
                            onDrag = { dx, dy ->
                                ttsOffsetX += dx
                                ttsOffsetY += dy
                            },
                            synthesisProgress = ttsSynthesisProgress,
                        )
                    }
                }
            }

            // Bottom control bar
            AnimatedVisibility(
                visible = isUiVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column {
                    if (totalPages > 1) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${currentPageIndex + 1}", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Slider(
                                value = sliderPosition,
                                onValueChange = {
                                    isDraggingProgress = true
                                    sliderPosition = it
                                },
                                onValueChangeFinished = {
                                    isDraggingProgress = false
                                    val targetPage = (sliderPosition * (totalPages - 1)).roundToInt()
                                    viewModel.goToPage(targetPage)
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text("$totalPages", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showBookmarksSheet = true }) {
                            Icon(Icons.Default.Bookmark, "书签/批注", Modifier.size(22.dp))
                        }
                        IconButton(onClick = { viewModel.moveToPrevChapter() }) {
                            Icon(Icons.Default.ChevronLeft, "上一章", Modifier.size(28.dp))
                        }
                        IconButton(onClick = { showSearchSheet = true }) {
                            Icon(Icons.Default.Search, "搜索", Modifier.size(22.dp))
                        }
                        IconButton(onClick = { viewModel.moveToNextChapter() }) {
                            Icon(Icons.Default.ChevronRight, "下一章", Modifier.size(28.dp))
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.FormatSize, "设置", Modifier.size(22.dp))
                        }
                        if (com.ebookreader.di.Injector.apiKeyManager().isTtsEnabled()) {
                            IconButton(onClick = { startTtsWithNotificationPermission() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    "听书",
                                    Modifier.size(22.dp),
                                    tint = if (ttsState !is TtsPlaybackState.Idle)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Bookmarks sheet — now also serves as page notes
    if (showBookmarksSheet) {
        // State for editing a bookmark's note
        var editingBookmarkId by remember { mutableStateOf<Long?>(null) }
        var editingNote by remember { mutableStateOf("") }

        ModalBottomSheet(onDismissRequest = {
            showBookmarksSheet = false
            editingBookmarkId = null
        }) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("书签 / 页批注", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        viewModel.addBookmark(pageLabel.ifEmpty { "书签 ${bookmarks.size + 1}" })
                    }) { Text("+ 当前页") }
                }
                Spacer(Modifier.height(8.dp))
                if (bookmarks.isEmpty()) {
                    Text("暂无书签。添加书签后可在此记录页批注。",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                } else {
                    LazyColumn(Modifier.height(400.dp)) {
                        itemsIndexed(bookmarks) { _, bm ->
                            Card(
                                Modifier.fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        if (bm.locatorJson != "{}") viewModel.goToLocator(bm.locatorJson)
                                        showBookmarksSheet = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Bookmark, null, Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(viewModel.bookmarkPageLabel(bm) ?: bm.title, Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(formatTimestamp(bm.createdTimestamp), fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                        IconButton(onClick = { viewModel.removeBookmark(bm.id) }) {
                                            Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    // Note section
                                    if (editingBookmarkId == bm.id) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = editingNote,
                                            onValueChange = { editingNote = it },
                                            label = { Text("页批注") },
                                            modifier = Modifier.fillMaxWidth(),
                                            minLines = 2,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = {
                                                viewModel.updateBookmarkNote(bm.id, editingNote)
                                                editingBookmarkId = null
                                            }) { Text("保存") }
                                            TextButton(onClick = { editingBookmarkId = null }) { Text("取消") }
                                        }
                                    } else {
                                        if (bm.note.isNotEmpty()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(bm.note, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                        TextButton(onClick = {
                                            editingBookmarkId = bm.id
                                            editingNote = bm.note
                                        }) { Text(if (bm.note.isEmpty()) "添加批注" else "编辑批注", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Search sheet
    if (showSearchSheet) {
        ModalBottomSheet(onDismissRequest = {
            showSearchSheet = false
            viewModel.clearSearch()
        }) {
            Column(Modifier.padding(16.dp)) {
                Text("书籍内搜索", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    label = { Text("输入关键词") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 200.dp),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { viewModel.performSearch(searchQuery) }) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("搜索")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (isSearching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                else if (searchResults.isNotEmpty()) {
                    Text("${searchResults.size} 个结果", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.height(350.dp)) {
                        itemsIndexed(searchResults) { _, result ->
                            Text(
                                result.title,
                                Modifier.fillMaxWidth().clickable {
                                    viewModel.goToLocator(result.locator.toJSON().toString())
                                    showSearchSheet = false
                                }.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            HorizontalDivider()
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    Text("无结果", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    }

    // Settings sheet
    if (showSettingsSheet) {
        ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("阅读设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text("字体大小", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatSize, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Slider(value = fontSize.toFloat(),
                        onValueChange = {
                            // 有级调节：吸附到 0.1 步进，避免滑动时出现连续无级值
                            val stepped = ((it * 10).roundToInt() / 10.0).coerceIn(0.5, 2.5)
                            viewModel.applyFontSize(stepped)
                        },
                        valueRange = 0.5f..2.5f,
                        steps = 19,
                        modifier = Modifier.weight(1f))
                    Icon(Icons.Default.FormatSize, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                Text("${"%.1f".format(fontSize)}x", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text("亮度", fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(brightness * 100).toInt()}%", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.width(40.dp))
                    Slider(
                        value = brightness,
                        onValueChange = { viewModel.applyBrightness(it) },
                        valueRange = 0.2f..1.0f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text("主题", fontWeight = FontWeight.Medium); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("默认", androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                        androidx.compose.ui.graphics.Color(0xFF121212)) {
                        viewModel.applyTheme("default")
                    }
                    ThemeChip("棕褐", androidx.compose.ui.graphics.Color(0xFFfaf4e8),
                        androidx.compose.ui.graphics.Color(0xFF121212)) {
                        viewModel.applyTheme("sepia")
                    }
                    ThemeChip("暗黑", androidx.compose.ui.graphics.Color(0xFF000000),
                        androidx.compose.ui.graphics.Color(0xFFFFEFD5)) {
                        viewModel.applyTheme("dark")
                    }
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text("翻页模式", fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.applyScrollMode(false) }) { Text("左右翻页") }
                    TextButton(onClick = { viewModel.applyScrollMode(true) }) { Text("上下滚动") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // Refresh TOC confirmation dialog
    if (showRefreshTocDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshTocDialog = false },
            title = { Text("刷新章节") },
            text = { Text("将使用内置规则和自定义正则表达式重新识别章节标题。\n\n这将会关闭并重新打开书籍，当前阅读位置可能丢失。\n\n确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshTocDialog = false
                    viewModel.refreshToc()
                }) { Text("刷新") }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshTocDialog = false }) { Text("取消") }
            },
        )
    }

    // Delete chapter confirmation dialog
    showDeleteChapterDialog?.let { chapterTitle ->
        AlertDialog(
            onDismissRequest = { showDeleteChapterDialog = null },
            title = { Text("合并章节") },
            text = { Text("将「${chapterTitle}」的内容合并到上一章，删除该章节标题。\n\n内容不会丢失，刷新目录后可恢复原章节划分。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChapter(chapterTitle)
                    showDeleteChapterDialog = null
                }) { Text("合并") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChapterDialog = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ThemeChip(
    label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Box(Modifier.size(36.dp).background(bg, RoundedCornerShape(18.dp)).padding(1.dp)) {
            Box(Modifier.fillMaxSize().background(fg.copy(alpha = 0.15f), RoundedCornerShape(17.dp)))
        }
        Text(label, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun formatTimestamp(ts: Long): String {
    if (ts == 0L) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(ts))
    } catch (_: Exception) { "" }
}

@Composable
private fun TtsPlaybackPanel(
    state: TtsPlaybackState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onMinimize: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    synthesisProgress: String = "",
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            // Drag handle indicator
            Box(Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier.width(32.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                )
            }

            // Header: sentence info + minimize button
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is TtsPlaybackState.Initializing -> {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            synthesisProgress.ifEmpty { "正在初始化语音引擎…" },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    is TtsPlaybackState.Error -> {
                        Text(state.message, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f))
                    }
                    is ActiveTts -> {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "第 ${state.sentenceIndex + 1} / ${state.totalSentences} 句",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.sentence,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    else -> {}
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onMinimize, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "收起", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Controls row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.SkipPrevious, "上一句", Modifier.size(20.dp))
                }
                val isPlaying = state is TtsPlaybackState.Playing
                IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        Modifier.size(if (isPlaying) 28.dp else 32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.SkipNext, "下一句", Modifier.size(20.dp))
                }
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Stop, "停止", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                }
            }
        }
    }
}
