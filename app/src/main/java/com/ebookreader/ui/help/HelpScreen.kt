package com.ebookreader.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class HelpItem(val title: String, val description: String)

data class HelpSection(val category: String, val items: List<HelpItem>)

val helpSections = listOf(
    HelpSection("书架与书籍", listOf(
        HelpItem("导入书籍", "在书架点击右上角「+」，选择本地文件（支持 EPUB、PDF 等格式）导入。"),
        HelpItem("打开书籍", "点击书籍封面进入详情页，再点击「阅读」开始阅读；长按书籍进入多选，可批量操作。"),
    )),
    HelpSection("阅读器", listOf(
        HelpItem("翻页", "左右翻页模式下左右滑动（或点击屏幕左右两侧）翻页；上下滚动模式下上下滑动阅读。"),
        HelpItem("显示工具栏", "点击屏幕中央可显示或隐藏顶部与底部工具栏。"),
        HelpItem("选择文字", "上下滚动模式下长按可选中并复制文字；左右翻页模式下暂不支持长按选择。"),
        HelpItem("阅读设置", "底部「设置」可调整字号、亮度、主题，并在「左右翻页 / 上下滚动」之间切换。"),
        HelpItem("目录、书签与搜索", "顶部「目录」进入目录跳转；底部「书签」添加书签与批注、「搜索」搜索全书。"),
    )),
    HelpSection("语音朗读", listOf(
        HelpItem("开始朗读", "在阅读器底部点击「听书」，朗读当前章节（按设置选择的合成方案）。"),
        HelpItem("播放控制", "朗读中可暂停/继续，并切换上一句/下一句。"),
        HelpItem("配置", "在「设置 → 语音朗读」中选择合成方案（系统引擎 / OpenAI 兼容）与语速。"),
    )),
    HelpSection("AI 智能助手", listOf(
        HelpItem("对话问答", "对当前书籍提问，基于正文内容回答。"),
        HelpItem("智能简介", "自动生成书籍简介。"),
        HelpItem("配置", "在「设置 → AI 智能助手」中启用并填写 API Key 与 API 地址。"),
    )),
    HelpSection("AI 拆书", listOf(
        HelpItem("逐章总结", "逐章生成摘要并合并为全书总结，适合快速了解一本书。"),
    )),
    HelpSection("智能推荐", listOf(
        HelpItem("个性化推荐", "根据你的阅读喜好推荐书籍。"),
        HelpItem("偏好题材", "在「设置 → 智能推荐 → 偏好题材」中添加题材关键词，提高推荐相关度。"),
    )),
    HelpSection("数据与统计", listOf(
        HelpItem("书籍统计", "查看阅读时长、进度等统计信息。"),
        HelpItem("存储与索引", "在「设置 → 数据管理」查看存储占用、清理空间、管理检索索引。"),
    )),
    HelpSection("开源许可", listOf(
        HelpItem("查看许可", "在「设置 → 开源许可」查看 EBookReader 使用的开源组件及其许可协议。"),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用帮助") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            helpSections.forEach { section ->
                item(key = "header_${section.category}") {
                    Text(
                        section.category,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                items(section.items, key = { "${section.category}_${it.title}" }) { item ->
                    HelpCard(item)
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HelpCard(item: HelpItem) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                item.description,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
