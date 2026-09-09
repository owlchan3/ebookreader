package com.ebookreader.ui.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.data.network.PreferredGenre
import com.ebookreader.domain.model.Tag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateToStats: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateToHelp: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    var isDarkTheme by remember { mutableStateOf(false) }
    var autoNightMode by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val backupMessage by viewModel.backupMessage.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { viewModel.exportBackup(it) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBackup(it) }
    }

    val tags by viewModel.tags.collectAsState()
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var showDeleteTagDialog by remember { mutableStateOf<Tag?>(null) }
    var showReadTagIntro by remember { mutableStateOf(false) }
    var showPinTagIntro by remember { mutableStateOf(false) }

    val aiEnabled by viewModel.aiEnabled.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val decomposeTier by viewModel.decomposeTier.collectAsState()
    val decomposeMapModel by viewModel.decomposeMapModel.collectAsState()
    val decomposeApiKey by viewModel.decomposeApiKey.collectAsState()
    val decomposeBaseUrl by viewModel.decomposeBaseUrl.collectAsState()
    val cleanupMessage by viewModel.cleanupMessage.collectAsState()
    val bookIndexCounts by viewModel.bookIndexCounts.collectAsState()
    val bookIndexList by viewModel.bookIndexList.collectAsState()
    val storageDetail by viewModel.storageDetail.collectAsState()
    var showDeleteIndexDialog by remember { mutableStateOf(false) }
    var showClearAllIndexDialog by remember { mutableStateOf(false) }
    var showStorageDetailDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var showDecomposeDialog by remember { mutableStateOf(false) }

    // Chapter regex patterns
    val chapterPatterns by viewModel.chapterPatterns.collectAsState()
    var showAddPatternDialog by remember { mutableStateOf(false) }

    // TTS plugin
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val ttsSpeed by viewModel.ttsSpeed.collectAsState()
    val selectedTtsEngine by viewModel.selectedTtsEngine.collectAsState()
    val ttsEngines by viewModel.ttsEngines.collectAsState()
    val ttsProvider by viewModel.ttsProvider.collectAsState()
    val ttsOpenAiUrl by viewModel.ttsOpenAiUrl.collectAsState()
    val ttsOpenAiKey by viewModel.ttsOpenAiKey.collectAsState()
    val ttsOpenAiModel by viewModel.ttsOpenAiModel.collectAsState()
    val ttsOpenAiVoice by viewModel.ttsOpenAiVoice.collectAsState()
    val ttsModelOptions by viewModel.ttsModelOptions.collectAsState()
    val ttsVoiceOptions by viewModel.ttsVoiceOptions.collectAsState()
    val ttsListStatus by viewModel.ttsListStatus.collectAsState()
    var showTtsSpeedDialog by remember { mutableStateOf(false) }
    var showTtsEngineDialog by remember { mutableStateOf(false) }
    var showTtsProviderDialog by remember { mutableStateOf(false) }
    var showTtsOpenAiUrlDialog by remember { mutableStateOf(false) }
    var showTtsOpenAiKeyDialog by remember { mutableStateOf(false) }
    var showTtsOpenAiModelDialog by remember { mutableStateOf(false) }
    var showTtsOpenAiVoiceDialog by remember { mutableStateOf(false) }

    // Recommendation plugin
    val recommendEnabled by viewModel.recommendEnabled.collectAsState()
    val googleBooksApiKey by viewModel.googleBooksApiKey.collectAsState()
    val customSearchUrls by viewModel.customSearchUrls.collectAsState()
    val pixivRefreshToken by viewModel.pixivRefreshToken.collectAsState()
    val pixivClientId by viewModel.pixivClientId.collectAsState()
    val pixivClientSecret by viewModel.pixivClientSecret.collectAsState()
    val pixivLoginStatus by viewModel.pixivLoginStatus.collectAsState()
    val customUrlStatus by viewModel.customUrlStatus.collectAsState()
    val preferredGenres by viewModel.preferredGenres.collectAsState()
    var showGoogleBooksKeyDialog by remember { mutableStateOf(false) }
    var showCustomSearchUrlsDialog by remember { mutableStateOf(false) }
    var showPixivDialog by remember { mutableStateOf(false) }
    var showPreferredGenresDialog by remember { mutableStateOf(false) }
    var weightGenre by remember { mutableStateOf<PreferredGenre?>(null) }

    // Load TTS engine labels on first composition so the summary row shows
    // human-readable names instead of raw package IDs.
    LaunchedEffect(Unit) {
        viewModel.refreshTtsEngines()
    }

    // 已配置地址与 Key 且选用 OpenAI 兼容方案时，自动拉取模型/音色列表。
    LaunchedEffect(ttsProvider, ttsOpenAiUrl, ttsOpenAiKey) {
        if (ttsProvider == "openai" && ttsOpenAiUrl.isNotBlank() && ttsOpenAiKey.isNotBlank()) {
            viewModel.fetchTtsLists()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ── 插件仓库 ──────────────────────────────────────────────
            SettingsSection("插件仓库")
            var repoExpanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    // Header row — always visible
                    Row(
                        Modifier.fillMaxWidth().clickable { repoExpanded = !repoExpanded }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Extension, null, Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("已安装插件", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium)
                            Text("管理已安装的功能插件", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = if (repoExpanded) "收起" else "展开",
                            modifier = Modifier.size(20.dp).rotate(if (repoExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    // Expandable plugin list
                    AnimatedVisibility(
                        visible = repoExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            // AI Chat Plugin
                            PluginItem(
                                icon = Icons.Default.SmartToy,
                                name = "AI 智能助手",
                                description = "AI 对话、智能简介生成等功能",
                                version = "1.0",
                                enabled = aiEnabled,
                                onToggle = { viewModel.setAiEnabled(it) },
                            )
                            if (aiEnabled) {
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    PluginConfigItem("API Key",
                                        if (apiKey.isNotBlank()) "已配置 (${apiKey.take(4)}...${apiKey.takeLast(4)})" else "未配置",
                                        onClick = { showApiKeyDialog = true })
                                    PluginConfigItem("API 地址", baseUrl,
                                        onClick = { showBaseUrlDialog = true })
                                    val currentModel by viewModel.model.collectAsState()
                                    val modelLabel = viewModel.modelPresets.find { it.first == currentModel }?.second ?: currentModel
                                    PluginConfigItem("AI 模型", modelLabel,
                                        onClick = { showModelDialog = true })
                                    PluginConfigItem("AI 拆书", "深度",
                                        onClick = { showDecomposeDialog = true })
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            // TTS Plugin
                            PluginItem(
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                name = "语音朗读 (TTS)",
                                description = "系统引擎或云端 TTS 朗读",
                                version = "3.0",
                                enabled = ttsEnabled,
                                onToggle = { viewModel.setTtsEnabled(it) },
                            )
                            if (ttsEnabled) {
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    PluginConfigItem("朗读语速",
                                        "${"%.1f".format(ttsSpeed)}x",
                                        onClick = { showTtsSpeedDialog = true })
                                    val ttsProviderLabel = when (ttsProvider) {
                                        "openai" -> "OpenAI 兼容"
                                        else -> "系统语音引擎"
                                    }
                                    PluginConfigItem("合成方案", ttsProviderLabel,
                                        onClick = { showTtsProviderDialog = true })
                                    when (ttsProvider) {
                                        "openai" -> {
                                            PluginConfigItem("API 地址",
                                                ttsOpenAiUrl.ifBlank { "未配置" },
                                                onClick = { showTtsOpenAiUrlDialog = true })
                                            PluginConfigItem("API Key",
                                                if (ttsOpenAiKey.isNotBlank()) "已配置 (${ttsOpenAiKey.take(4)}…${ttsOpenAiKey.takeLast(4)})" else "未配置",
                                                onClick = { showTtsOpenAiKeyDialog = true })
                                            PluginConfigItem("获取模型与音色",
                                                ttsListStatus.ifBlank { "点击获取" },
                                                onClick = { viewModel.fetchTtsLists() })
                                            PluginConfigItem("模型", ttsOpenAiModel.ifBlank { "未设置" },
                                                onClick = { showTtsOpenAiModelDialog = true })
                                            PluginConfigItem("音色", ttsOpenAiVoice.ifBlank { "未设置" },
                                                onClick = { showTtsOpenAiVoiceDialog = true })
                                        }
                                        else -> {
                                            PluginConfigItem("TTS 引擎",
                                                viewModel.ttsEngineLabel,
                                                onClick = {
                                                    viewModel.refreshTtsEngines()
                                                    showTtsEngineDialog = true
                                                })
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            // 推荐 Plugin
                            PluginItem(
                                icon = Icons.Default.AutoAwesome,
                                name = "智能推荐",
                                description = "根据阅读喜好推荐书库内外的书籍",
                                version = "1.0",
                                enabled = recommendEnabled,
                                onToggle = { viewModel.setRecommendEnabled(it) },
                            )
                            if (recommendEnabled) {
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    PluginConfigItem("偏好题材",
                                        if (preferredGenres.isEmpty()) "未设置" else "${preferredGenres.size} 个题材",
                                        onClick = { showPreferredGenresDialog = true })
                                    PluginConfigItem("Google Books 密钥",
                                        if (googleBooksApiKey.isNotBlank()) "已配置" else "可选（提升额度）",
                                        onClick = { showGoogleBooksKeyDialog = true })
                                    PluginConfigItem("自定义补充搜索地址",
                                        if (customSearchUrls.isEmpty()) "已关闭" else "${customSearchUrls.size} 个地址",
                                        onClick = { showCustomSearchUrlsDialog = true })
                                    PluginConfigItem("Pixiv 小说",
                                        if (pixivRefreshToken.isNotBlank()) "已配置" else "可选（需 refresh_token）",
                                        onClick = { showPixivDialog = true })
                                    PluginConfigItem("清空不感兴趣记录", "点击清空",
                                        onClick = { viewModel.clearDismissedRecommendations() })
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            // ── 章节识别 ──────────────────────────────────────────────
            SettingsSection("章节识别")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自定义正则表达式", fontWeight = FontWeight.Medium)
                            Text("添加自定义正则规则来识别章节标题，支持标准 Regex 语法",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        IconButton(onClick = { showAddPatternDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, "添加规则", Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (chapterPatterns.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            chapterPatterns.forEach { p ->
                                key(p.pattern) {
                                    InputChip(
                                        selected = p.enabled,
                                        onClick = { viewModel.setChapterPatternEnabled(p.pattern, !p.enabled) },
                                        label = {
                                            Text(
                                                p.pattern,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                color = if (p.enabled) MaterialTheme.colorScheme.onSurface
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { viewModel.removeChapterPattern(p.pattern) },
                                                modifier = Modifier.size(16.dp),
                                            ) {
                                                Icon(Icons.Default.Delete, "删除规则", Modifier.size(14.dp),
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text("未添加自定义规则。点击 + 添加正则表达式，如 ^\\s*第[\\d]+章\\s+.*$",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SettingsSection("标签管理")
            var tagExpanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { tagExpanded = !tagExpanded }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("管理所有标签", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = if (tagExpanded) "收起" else "展开",
                            modifier = Modifier.size(20.dp).rotate(if (tagExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    AnimatedVisibility(visible = tagExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            HorizontalDivider(Modifier.padding(bottom = 12.dp))
                            if (tags.isEmpty()) {
                                Text("暂无标签", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    tags.forEach { tag ->
                                        key(tag.id) {
                                            if (tag.isReadTag) {
                                                InputChip(
                                                    selected = false,
                                                    onClick = { showReadTagIntro = true },
                                                    label = { Text(tag.name, fontSize = 13.sp) },
                                                    colors = InputChipDefaults.inputChipColors(
                                                        containerColor = Color(0xFF363029),
                                                        labelColor = Color(0xFFC9BFA8),
                                                    ),
                                                )
                                            } else if (tag.isPinTag) {
                                                InputChip(
                                                    selected = false,
                                                    onClick = { showPinTagIntro = true },
                                                    label = { Text(tag.name, fontSize = 13.sp) },
                                                    colors = InputChipDefaults.inputChipColors(
                                                        containerColor = Color(0xFF363029),
                                                        labelColor = Color(0xFFC9BFA8),
                                                    ),
                                                )
                                            } else {
                                                InputChip(
                                                    selected = false,
                                                    onClick = { editingTag = tag },
                                                    label = { Text(tag.name, fontSize = 13.sp) },
                                                    trailingIcon = {
                                                        IconButton(
                                                            onClick = { showDeleteTagDialog = tag },
                                                            modifier = Modifier.size(16.dp),
                                                        ) {
                                                            Icon(Icons.Default.Delete, "删除", Modifier.size(14.dp),
                                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showCreateTagDialog = true }) {
                                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("新建标签", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SettingsSection("数据管理")
            SettingsItem(Icons.AutoMirrored.Filled.List, "书籍统计", "查看阅读统计信息",
                onClick = onNavigateToStats)
            SettingsItem(Icons.Default.Storage, "存储详情", "查看各部分占用空间",
                onClick = { viewModel.refreshStorageDetail(); showStorageDetailDialog = true })
            SettingsItem(Icons.Default.CleaningServices, "清理存储", "删除重复文件释放空间",
                onClick = { viewModel.cleanupStorage() })
            SettingsItem(Icons.Default.Delete, "删除某本书的索引", "选择一本书删除其检索索引",
                onClick = { viewModel.refreshBookIndexCounts(); showDeleteIndexDialog = true })
            SettingsItem(Icons.Default.DeleteSweep, "清空全部索引", "删除所有书的检索索引并回收空间",
                onClick = { showClearAllIndexDialog = true })
            SettingsItem(Icons.Default.Backup, "导出备份", "把书库、书签、设置打包保存为一个文件",
                onClick = {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(
                            Intent.EXTRA_TITLE,
                            "EBookReader_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
                        )
                    }
                    exportLauncher.launch(intent)
                },
                trailing = if (isBackingUp) {
                    { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else null,
            )
            SettingsItem(Icons.Default.Restore, "导入备份", "从备份文件恢复（会覆盖当前所有数据）",
                onClick = { importLauncher.launch(arrayOf("*/*")) })

            Spacer(Modifier.height(20.dp))
            SettingsSection("关于")
            SettingsItem(Icons.Default.Info, "EBookReader", "版本 ${com.ebookreader.BuildConfig.VERSION_NAME}")
            SettingsItem(Icons.Default.Description, "开源许可", "查看使用的开源组件许可",
                onClick = onNavigateToLicenses)
            SettingsItem(Icons.AutoMirrored.Filled.Help, "使用帮助", "了解如何使用 EBookReader",
                onClick = onNavigateToHelp)
        }
    }

    // API Key dialog
    if (showApiKeyDialog) {
        var keyInput by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("配置 API Key") },
            text = {
                Column {
                    Text(
                        "请输入您的 API Key，支持 OpenAI/Anthropic 兼容接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setApiKey(keyInput.trim())
                    showApiKeyDialog = false
                    Toast.makeText(context, "API Key 已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showApiKeyDialog = false }) { Text("取消") } },
        )
    }

    // Google Books API key dialog
    if (showGoogleBooksKeyDialog) {
        var keyInput by remember { mutableStateOf(googleBooksApiKey) }
        AlertDialog(
            onDismissRequest = { showGoogleBooksKeyDialog = false },
            title = { Text("配置 Google Books 密钥") },
            text = {
                Column {
                    Text(
                        "可选。留空使用匿名额度（容易触顶），填写自己的 API Key 可获得更高配额。\n" +
                            "可在 Google Cloud Console 免费申请 Books API 密钥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setGoogleBooksApiKey(keyInput.trim())
                    showGoogleBooksKeyDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showGoogleBooksKeyDialog = false }) { Text("取消") } },
        )
    }

    // 自定义补充搜索地址 dialog（多地址管理）
    if (showCustomSearchUrlsDialog) {
        var newUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustomSearchUrlsDialog = false },
            title = { Text("自定义补充搜索地址") },
            text = {
                Column {
                    Text(
                        "可添加多个网文搜索地址（如笔趣阁镜像），搜索时逐个尝试。\n" +
                            "这类站点域名经常变动、靠解析网页，属尽力而为。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (customSearchUrls.isNotEmpty()) {
                        customSearchUrls.forEach { url ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(url, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1)
                                val status = customUrlStatus[url]
                                when (status) {
                                    true -> Text("生效", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFF2E7D32))
                                    false -> Text("失效", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    else -> {}
                                }
                                IconButton(
                                    onClick = { viewModel.removeCustomSearchUrl(url) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newUrl,
                            onValueChange = { newUrl = it },
                            label = { Text("新地址") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            viewModel.addCustomSearchUrl(newUrl.trim())
                            newUrl = ""
                        }) { Text("添加") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.testCustomSearchUrls() }) { Text("测试地址是否生效") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomSearchUrlsDialog = false }) { Text("完成") }
            },
        )
    }

    // Pixiv 小说 dialog
    if (showPixivDialog) {
        var tokenInput by remember { mutableStateOf(pixivRefreshToken) }
        var clientIdInput by remember { mutableStateOf(pixivClientId) }
        var clientSecretInput by remember { mutableStateOf(pixivClientSecret) }
        AlertDialog(
            onDismissRequest = { showPixivDialog = false },
            title = { Text("Pixiv 小说") },
            text = {
                Column {
                    Text(
                        "用 Pixiv App API 推荐小说。只需填写 refresh_token（密码登录已取消），\n" +
                            "client_id / client_secret 已默认填入 pixivpy 的公开值，一般无需修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(tokenInput, { tokenInput = it }, label = { Text("refresh_token") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(clientIdInput, { clientIdInput = it }, label = { Text("client_id") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(clientSecretInput, { clientSecretInput = it }, label = { Text("client_secret") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            viewModel.setPixivRefreshToken(tokenInput.trim())
                            viewModel.setPixivClientId(clientIdInput.trim())
                            viewModel.setPixivClientSecret(clientSecretInput.trim())
                            viewModel.testPixivLogin()
                        }) { Text("测试登录") }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pixivLoginStatus ?: "",
                            fontSize = 13.sp,
                            color = when (pixivLoginStatus) {
                                "连接成功" -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                "测试中…" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setPixivRefreshToken(tokenInput.trim())
                    viewModel.setPixivClientId(clientIdInput.trim())
                    viewModel.setPixivClientSecret(clientSecretInput.trim())
                    showPixivDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPixivDialog = false }) { Text("取消") } },
        )
    }

    // 偏好题材 dialog
    if (showPreferredGenresDialog) {
        var newGenre by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPreferredGenresDialog = false },
            title = { Text("偏好题材") },
            text = {
                Column {
                    Text(
                        "输入你喜欢的题材关键词（如「克苏鲁」「无限流」「ABO」），\n" +
                            "推荐时会优先参考这些题材（权重越高越优先，默认 1）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (preferredGenres.isNotEmpty()) {
                        Column(
                            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                        ) {
                            preferredGenres.forEach { g ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${g.name}（权重 ${g.weight}）", Modifier.weight(1f), fontSize = 13.sp)
                                    TextButton(onClick = { weightGenre = g }) { Text("权重", fontSize = 12.sp) }
                                    IconButton(onClick = { viewModel.removePreferredGenre(g.name) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newGenre,
                            onValueChange = { newGenre = it },
                            label = { Text("新题材") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            viewModel.addPreferredGenre(newGenre.trim())
                            newGenre = ""
                        }) { Text("添加") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreferredGenresDialog = false }) { Text("完成") }
            },
        )
    }

    // 题材权重/英文标签 dialog
    weightGenre?.let { g ->
        var weightInput by remember(g.name) { mutableStateOf(g.weight.toString()) }
        var englishInput by remember(g.name) { mutableStateOf(g.englishTags) }
        AlertDialog(
            onDismissRequest = { weightGenre = null },
            title = { Text("编辑题材：${g.name}") },
            text = {
                Column {
                    Text("权重越高，推荐时该题材越优先（默认 1，可为负表示排除）。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = { weightInput = it },
                        label = { Text("权重") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("英文标签（多个用 | 或逗号分隔，单个标签内可含空格，如 Science Fiction|Sci-Fi；留空则用内置对照）",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = englishInput,
                        onValueChange = { englishInput = it },
                        label = { Text("英文标签") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    weightInput.toDoubleOrNull()?.let { viewModel.setPreferredGenreWeight(g.name, it) }
                    viewModel.setPreferredGenreEnglishTags(g.name, englishInput.trim())
                    weightGenre = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { weightGenre = null }) { Text("取消") } },
        )
    }

    // Base URL dialog
    if (showBaseUrlDialog) {
        var urlInput by remember { mutableStateOf(baseUrl) }
        AlertDialog(
            onDismissRequest = { showBaseUrlDialog = false },
            title = { Text("配置 API 地址") },
            text = {
                Column {
                    Text(
                        "输入兼容 OpenAI/Anthropic 格式的 API 端点地址（Base URL）。\n默认：https://api.deepseek.com/v1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "实际请求路径为 {Base URL}/chat/completions",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setBaseUrl(urlInput.trim())
                    showBaseUrlDialog = false
                    Toast.makeText(context, "API 地址已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showBaseUrlDialog = false }) { Text("取消") } },
        )
    }

    // Model selection dialog
    if (showModelDialog) {
        val currentModel by viewModel.model.collectAsState()
        val isPreset = viewModel.modelPresets.any { it.first == currentModel }
        var customModelInput by remember { mutableStateOf(if (isPreset) "" else currentModel) }
        var showCustomInput by remember { mutableStateOf(!isPreset) }
        val fetchedModels by viewModel.fetchedModels.collectAsState()
        val isFetching by viewModel.isFetchingModels.collectAsState()
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("选择 AI 模型") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    // Preset options
                    viewModel.modelPresets.forEach { (id, label) ->
                        val isSelected = id == currentModel
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    viewModel.setModel(id)
                                    showModelDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = isSelected && !showCustomInput,
                                onClick = {
                                    viewModel.setModel(id)
                                    showModelDialog = false
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    // Custom model option
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { showCustomInput = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = showCustomInput,
                            onClick = { showCustomInput = true },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("自定义模型", fontWeight = if (showCustomInput) FontWeight.Bold else FontWeight.Normal)
                    }
                    if (showCustomInput) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it },
                            label = { Text("模型名称") },
                            placeholder = { Text("如 gpt-4o, claude-opus-5") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    // Divider + fetch section
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("从 API 获取模型列表",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.weight(1f))
                        if (isFetching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { viewModel.fetchModels() }) {
                                Text("获取", fontSize = 13.sp)
                            }
                        }
                    }
                    if (fetchedModels.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        fetchedModels.forEach { modelId ->
                            val isFetchedSelected = modelId == currentModel && showCustomInput
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        viewModel.setModel(modelId)
                                        showCustomInput = false
                                        showModelDialog = false
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = isFetchedSelected,
                                    onClick = {
                                        viewModel.setModel(modelId)
                                        showCustomInput = false
                                        showModelDialog = false
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(modelId, fontSize = 13.sp,
                                    fontWeight = if (isFetchedSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (showCustomInput && customModelInput.isNotBlank()) {
                        viewModel.setModel(customModelInput.trim())
                    }
                    showModelDialog = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showModelDialog = false }) { Text("关闭") } },
        )
    }

    // Create tag dialog
    if (showCreateTagDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateTagDialog = false },
            title = { Text("新建标签") },
            text = {
                OutlinedTextField(newName, { newName = it },
                    label = { Text("标签名称") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.createTag(newName.trim())
                        showCreateTagDialog = false
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCreateTagDialog = false }) { Text("取消") } },
        )
    }

    // Edit tag dialog
    editingTag?.let { tag ->
        var editName by remember { mutableStateOf(tag.name) }
        AlertDialog(
            onDismissRequest = { editingTag = null },
            title = { Text("编辑标签") },
            text = {
                OutlinedTextField(editName, { editName = it },
                    label = { Text("标签名称") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.isNotBlank()) {
                        viewModel.updateTag(tag.copy(name = editName.trim()))
                        editingTag = null
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingTag = null }) { Text("取消") } },
        )
    }

    // Add chapter regex pattern dialog
    if (showAddPatternDialog) {
        var patternInput by remember { mutableStateOf("") }
        var validationError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddPatternDialog = false; validationError = null },
            title = { Text("添加章节识别规则") },
            text = {
                Column {
                    Text(
                        "输入正则表达式来匹配章节标题。\n示例：^\\s*第[\\d]+章\\s+.*$ 可匹配「第1章 开端」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = patternInput,
                        onValueChange = {
                            patternInput = it
                            validationError = if (it.isNotBlank() && !viewModel.isValidPattern(it))
                                "正则表达式语法无效" else null
                        },
                        label = { Text("正则表达式") },
                        placeholder = { Text("如 ^\\s*第[\\d]+章\\s+.*$") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = validationError != null,
                        supportingText = if (validationError != null) {
                            { Text(validationError!!, color = MaterialTheme.colorScheme.error) }
                        } else null,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = patternInput.trim()
                    if (trimmed.isNotBlank() && viewModel.isValidPattern(trimmed)) {
                        viewModel.addChapterPattern(trimmed)
                        showAddPatternDialog = false
                        validationError = null
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPatternDialog = false; validationError = null }) { Text("取消") }
            },
        )
    }

    // TTS speed dialog
    if (showTtsSpeedDialog) {
        var speedSlider by remember { mutableStateOf(ttsSpeed) }
        AlertDialog(
            onDismissRequest = { showTtsSpeedDialog = false },
            title = { Text("朗读语速") },
            text = {
                Column {
                    Text("调整语音朗读速度：${"%.1f".format(speedSlider)}x",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("0.5x", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Slider(
                            value = speedSlider,
                            onValueChange = { speedSlider = it },
                            valueRange = 0.5f..2.0f,
                            steps = 14, // 0.1 increments
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text("2.0x", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTtsSpeed(speedSlider)
                    showTtsSpeedDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTtsSpeedDialog = false }) { Text("取消") }
            },
        )
    }

    // AI 拆书 dialog（模型分级 + 成本提示）
    if (showDecomposeDialog) {
        var mapModel by remember { mutableStateOf(decomposeMapModel) }
        var apiKeyInput by remember { mutableStateOf(decomposeApiKey) }
        var baseUrlInput by remember { mutableStateOf(decomposeBaseUrl) }
        AlertDialog(
            onDismissRequest = { showDecomposeDialog = false },
            title = { Text("AI 拆书") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "拆书会逐章调用 AI 生成摘要并合并为全书总结，消耗 AI 模型的 token（输入 + 输出），费用与书籍字数和所选挡位成正比。逐章用低价模型可显著省钱。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("逐章模型（低价）", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = mapModel,
                        onValueChange = { mapModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("留空沿用主模型", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("逐章 API 地址", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("留空沿用主 API 地址", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("逐章 API Key", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("留空沿用主 API Key", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("总结模型：沿用主设置", fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDecomposeMapModel(mapModel)
                    viewModel.setDecomposeApiKey(apiKeyInput)
                    viewModel.setDecomposeBaseUrl(baseUrlInput)
                    showDecomposeDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showDecomposeDialog = false }) { Text("取消") }
            },
        )
    }

    // 存储清理结果提示
    LaunchedEffect(cleanupMessage) {
        cleanupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearCleanupMessage()
        }
    }

    // 删除某本书的索引 dialog
    if (showDeleteIndexDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteIndexDialog = false },
            title = { Text("删除某本书的索引") },
            text = {
                if (bookIndexList.isEmpty()) {
                    Text("当前没有书籍索引（对书籍使用 AI 对话或拆书后才会生成索引）。", fontSize = 13.sp)
                } else {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        bookIndexList.forEach { info ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        viewModel.deleteBookIndex(info.bookId)
                                        showDeleteIndexDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(info.title, fontSize = 14.sp, maxLines = 1)
                                    Text("索引块数：${info.count}", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                                Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDeleteIndexDialog = false }) { Text("关闭") } },
        )
    }

    // 清空全部索引 confirm dialog
    if (showClearAllIndexDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllIndexDialog = false },
            title = { Text("清空全部索引") },
            text = { Text("确定删除所有书籍的检索索引吗？删除后再次使用 AI 对话/拆书时会自动重建。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllIndexDialog = false
                    viewModel.clearAllIndexes()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearAllIndexDialog = false }) { Text("取消") } },
        )
    }

    // 存储详情 dialog
    if (showStorageDetailDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDetailDialog = false },
            title = { Text("存储详情") },
            text = {
                val d = storageDetail
                if (d == null) {
                    Text("统计中…", fontSize = 13.sp)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        StorageRow("书籍文件", d.booksBytes)
                        Text("文件 ${d.bookFileCount} 个 / 书架 ${d.bookCount} 本", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text(d.bookBreakdown, fontSize = 12.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        StorageRow("封面", d.coversBytes)
                        StorageRow("检索索引", d.indexBytes)
                        StorageRow("拆书结果", d.decomposeBytes)
                        StorageRow("聊天记录", d.chatBytes)
                        StorageRow("数据库文件", d.dbFileBytes)
                        Spacer(Modifier.height(8.dp))
                        Text("检索索引是「对书籍使用 AI 对话/拆书」时，把全书正文按段存进数据库以便快速检索；删除索引可释放这部分空间，下次使用会自动重建。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStorageDetailDialog = false }) { Text("关闭") } },
        )
    }

    // TTS engine picker dialog
    if (showTtsEngineDialog) {
        AlertDialog(
            onDismissRequest = { showTtsEngineDialog = false },
            title = { Text("选择 TTS 引擎") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    // System default option
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setTtsEngine("")
                                showTtsEngineDialog = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedTtsEngine.isEmpty(),
                            onClick = {
                                viewModel.setTtsEngine("")
                                showTtsEngineDialog = false
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("系统默认", fontWeight = if (selectedTtsEngine.isEmpty()) FontWeight.Bold else FontWeight.Normal)
                    }
                    HorizontalDivider()
                    // Installed engines
                    if (ttsEngines.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("未找到已安装的语音引擎", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    ttsEngines.forEach { engine ->
                        val isSelected = engine.packageName == selectedTtsEngine
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    viewModel.setTtsEngine(engine.packageName)
                                    showTtsEngineDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = isSelected,
                                onClick = {
                                    viewModel.setTtsEngine(engine.packageName)
                                    showTtsEngineDialog = false
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(engine.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                Text(engine.packageName, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTtsEngineDialog = false }) { Text("关闭") }
            },
        )
    }

    // TTS provider picker dialog
    if (showTtsProviderDialog) {
        AlertDialog(
            onDismissRequest = { showTtsProviderDialog = false },
            title = { Text("选择合成方案") },
            text = {
                Column {
                    ProviderOption(
                        selected = ttsProvider == "system",
                        title = "系统语音引擎",
                        subtitle = "使用设备已安装的语音引擎，无需联网",
                        onClick = {
                            viewModel.setTtsProvider("system")
                            showTtsProviderDialog = false
                        },
                    )
                    HorizontalDivider()
                    ProviderOption(
                        selected = ttsProvider == "openai",
                        title = "OpenAI 兼容",
                        subtitle = "调用 /audio/speech 协议，需配置地址与 Key",
                        onClick = {
                            viewModel.setTtsProvider("openai")
                            showTtsProviderDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTtsProviderDialog = false }) { Text("关闭") }
            },
        )
    }

    // OpenAI 兼容 TTS 配置 dialogs
    if (showTtsOpenAiUrlDialog) {
        TtsConfigTextDialog(
            title = "API 地址",
            description = "云端 TTS 接口的 Base URL（不含 /audio/speech 路径）。",
            initialValue = ttsOpenAiUrl,
            onDismiss = { showTtsOpenAiUrlDialog = false },
            onSave = {
                viewModel.setTtsOpenAiUrl(it)
                showTtsOpenAiUrlDialog = false
            },
        )
    }
    if (showTtsOpenAiKeyDialog) {
        TtsConfigTextDialog(
            title = "API Key",
            description = "云端 TTS 的 API Key（Bearer Token）。",
            initialValue = ttsOpenAiKey,
            onDismiss = { showTtsOpenAiKeyDialog = false },
            onSave = {
                viewModel.setTtsOpenAiKey(it)
                showTtsOpenAiKeyDialog = false
            },
        )
    }
    if (showTtsOpenAiModelDialog) {
        TtsPickerDialog(
            title = "模型",
            description = "从已获取的模型中选择，或手动输入。",
            initialValue = ttsOpenAiModel,
            options = ttsModelOptions,
            onDismiss = { showTtsOpenAiModelDialog = false },
            onSave = {
                viewModel.setTtsOpenAiModel(it)
                showTtsOpenAiModelDialog = false
            },
        )
    }
    if (showTtsOpenAiVoiceDialog) {
        TtsPickerDialog(
            title = "音色",
            description = "从已获取的音色中选择，或手动输入。",
            initialValue = ttsOpenAiVoice,
            options = ttsVoiceOptions,
            onDismiss = { showTtsOpenAiVoiceDialog = false },
            onSave = {
                viewModel.setTtsOpenAiVoice(it)
                showTtsOpenAiVoiceDialog = false
            },
        )
    }

    // Delete tag confirmation
    showDeleteTagDialog?.let { tag ->
        AlertDialog(
            onDismissRequest = { showDeleteTagDialog = null },
            title = { Text("删除标签") },
            text = { Text("确定要删除标签「${tag.name}」吗？\n该标签将从所有书籍和书架中移除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(tag)
                    showDeleteTagDialog = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteTagDialog = null }) { Text("取消") } },
        )
    }

    // 「已阅」特殊标签功能介绍
    if (showReadTagIntro) {
        AlertDialog(
            onDismissRequest = { showReadTagIntro = false },
            title = { Text("「已阅」标签") },
            text = {
                Text(
                    "「已阅」是一个特殊标签，用于标记你已读完的书籍。\n\n" +
                        "· 不可删除\n" +
                        "· 标记为「已阅」的书籍，在「书籍统计」中全本视为已读（阅读进度按 100% 计算）。\n\n" +
                        "你可以在任意一本书的详情页，把「已阅」标签添加到该书，即可标记为已读。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showReadTagIntro = false }) { Text("知道了") }
            },
        )
    }

    // 「置顶」特殊标签功能介绍
    if (showPinTagIntro) {
        AlertDialog(
            onDismissRequest = { showPinTagIntro = false },
            title = { Text("「置顶」标签") },
            text = {
                Text(
                    "「置顶」是一个特殊标签，用于把你常用的书籍固定到书架顶部。\n\n" +
                        "· 不可删除\n" +
                        "· 当书架按「最近阅读」排列时，带「置顶」标签的书籍会排在最前面；多本置顶的书彼此仍按最近阅读顺序排列（其它排列方式不受影响）。\n\n" +
                        "你可以在任意一本书的详情页，把「置顶」标签添加到该书，即可置顶。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showPinTagIntro = false }) { Text("知道了") }
            },
        )
    }

    // 备份 / 恢复结果对话框
    backupMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearBackupMessage() },
            title = { Text("备份与恢复") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearBackupMessage() }) { Text("知道了") }
            },
        )
    }

}

@Composable
private fun ProviderOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(subtitle, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun TtsConfigTextDialog(
    title: String,
    description: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var input by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(title) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(input.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TtsPickerDialog(
    title: String,
    description: String,
    initialValue: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var input by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(title) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    ) {
                        options.forEach { opt ->
                            Text(
                                opt,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { input = opt }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(input.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingsSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = (if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.padding(end = 16.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        }
    }
}

/** A plugin entry in the plugin repository. */
@Composable
private fun PluginItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    description: String,
    version: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(36.dp).padding(4.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text("v$version", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
            Text(description, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** A configuration row within a plugin's expanded settings. */
@Composable
private fun PluginConfigItem(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1)
    }
}
