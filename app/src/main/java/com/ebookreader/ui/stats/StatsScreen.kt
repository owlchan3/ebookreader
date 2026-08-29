package com.ebookreader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.data.local.entity.DailyReadingSessionEntity
import com.ebookreader.domain.model.Book
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel(),
) {
    val books by viewModel.books.collectAsState()
    val dailySessions by viewModel.dailySessions.collectAsState()
    val totalReadingSeconds by viewModel.totalReadingSeconds.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()
    val selectedDayTopBooks by viewModel.selectedDayTopBooks.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val readBookIds by viewModel.readBookIds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书籍统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                StatsOverview(books, totalReadingSeconds, readBookIds)
                Spacer(Modifier.height(20.dp))
                DailyReadingChart(
                    sessions = dailySessions,
                    selectedDay = selectedDay,
                    topBooks = selectedDayTopBooks,
                    onSelectDay = viewModel::selectDay,
                )
                Spacer(Modifier.height(20.dp))
                FormatDistribution(books)
                Spacer(Modifier.height(20.dp))
                TopAuthors(books)
                Spacer(Modifier.height(20.dp))
                RecentlyAdded(books)
            }
        }
    }
}

@Composable
private fun DailyReadingChart(
    sessions: List<DailyReadingSessionEntity>,
    selectedDay: String?,
    topBooks: List<TopBookInfo>,
    onSelectDay: (String?) -> Unit,
) {
    Text("近七天阅读时长", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Build last 7 days data
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val labelFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
            val cal = Calendar.getInstance()
            val today = dateFormat.format(cal.time)

            val days = (6 downTo 0).map { offset ->
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -offset)
                val dateStr = dateFormat.format(c.time)
                val seconds = sessions.find { it.date == dateStr }?.seconds ?: 0L
                val label = labelFormat.format(c.time)
                Triple(dateStr, seconds, label)
            }

            val maxSeconds = days.maxOf { it.second }.coerceAtLeast(1L)

            // Chart
            val chartHeight = 160.dp
            val primaryColor = MaterialTheme.colorScheme.primary
            val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

            Canvas(
                Modifier.fillMaxWidth().height(chartHeight)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .pointerInput(days.size) {
                        detectTapGestures { offset ->
                            val w = size.width.toFloat()
                            val stepX = if (days.size > 1) w / (days.size - 1) else w
                            val idx = (offset.x / stepX).roundToInt().coerceIn(0, days.size - 1)
                            onSelectDay(days[idx].first)
                        }
                    },
            ) {
                val width = size.width
                val height = size.height
                val paddingBottom = 24f
                val chartWidth = width
                val chartH = height - paddingBottom
                val stepX = if (days.size > 1) chartWidth / (days.size - 1) else chartWidth

                // Grid lines
                for (i in 0..4) {
                    val y = chartH * i / 4
                    drawLine(surfaceVariant, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
                }

                // Points and line
                val points = days.mapIndexed { index, (_, seconds, _) ->
                    val x = stepX * index
                    val y = chartH - (seconds.toFloat() / maxSeconds * chartH)
                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val path = Path()
                    path.moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        val midX = (points[i - 1].x + points[i].x) / 2
                        path.cubicTo(midX, points[i - 1].y, midX, points[i].y, points[i].x, points[i].y)
                    }
                    drawPath(path, primaryColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }

                // Data points — highlight the selected day
                points.forEachIndexed { index, point ->
                    val isSelected = days[index].first == selectedDay
                    if (isSelected) {
                        drawCircle(primaryColor.copy(alpha = 0.25f), radius = 10f, center = point)
                    }
                    drawCircle(if (isSelected) primaryColor else Color.White, radius = if (isSelected) 7f else 5f, center = point)
                    drawCircle(if (isSelected) Color.White else primaryColor, radius = if (isSelected) 4f else 3f, center = point)
                }
            }

            // X-axis labels — clickable, aligned under points
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
                days.forEach { (dateStr, _, label) ->
                    val isSel = dateStr == selectedDay
                    Text(
                        label,
                        Modifier.weight(1f).clickable { onSelectDay(dateStr) },
                        fontSize = 11.sp,
                        color = if (isSel) primaryColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            // Legend
            Spacer(Modifier.height(8.dp))
            val todaySeconds = sessions.find { it.date == today }?.seconds ?: 0L
            val todayMinutes = todaySeconds / 60
            val weekTotal = days.sumOf { it.second }
            val weekHours = weekTotal / 3600
            val weekMins = (weekTotal % 3600) / 60
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("今日: ${todayMinutes}分钟", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text("本周: ${weekHours}小时${weekMins}分钟", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }

            // 选中某天的详情
            selectedDay?.let { sel ->
                val day = days.find { it.first == sel }
                if (day != null) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$sel · ${formatDuration(day.second)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (topBooks.isEmpty()) {
                        Text("当日暂无阅读记录", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    } else {
                        topBooks.forEachIndexed { i, b ->
                            Text(
                                "${i + 1}. ${b.title}  ·  ${formatDuration(b.seconds)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            if (i < topBooks.lastIndex) Spacer(Modifier.height(3.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}小时${m}分钟"
        m > 0 -> "${m}分钟"
        else -> "${seconds}秒"
    }
}

@Composable
private fun StatsOverview(books: List<Book>, totalReadingSeconds: Long, readBookIds: Set<Long>) {
    val totalBooks = books.size
    val totalPages = books.sumOf { it.totalPages.toLong() }
    // 「已阅」书籍全本视为已读
    val pagesRead = books.sumOf { book ->
        if (book.id in readBookIds) book.totalPages.toLong() else book.currentPage.toLong()
    }
    val progressPercent = if (totalPages > 0) (pagesRead * 100 / totalPages).toInt() else 0
    val readingHours = totalReadingSeconds / 3600
    val readingMinutes = (totalReadingSeconds % 3600) / 60

    Text("阅读概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("藏书", "$totalBooks 本", Modifier.weight(1f))
        StatCard("总页数", formatNumber(totalPages), Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("已读页数", formatNumber(pagesRead), Modifier.weight(1f))
        StatCard("阅读进度", "$progressPercent%", Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("总阅读时长", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(
                    "${readingHours}小时${readingMinutes}分钟",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FormatDistribution(books: List<Book>) {
    Text("格式分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val formatCounts = books.groupBy { it.format.uppercase() }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
            if (formatCounts.isEmpty()) {
                Text("暂无数据", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            } else {
                val total = books.size.toFloat()
                formatCounts.forEach { (format, count) ->
                    val percent = if (total > 0) (count / total * 100).toInt() else 0
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(format, Modifier.width(60.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Box(
                                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Box(
                                Modifier.fillMaxWidth(fraction = percent / 100f).height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Text("${count}本", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopAuthors(books: List<Book>) {
    Text("热门作者 TOP 5", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val authorCounts = books
                .filter { it.author.isNotBlank() && it.author != "未知作者" && it.author != "佚名" && it.author != "Unknown" }
                .groupBy { it.author }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }.take(5)
            if (authorCounts.isEmpty()) {
                Text("暂无数据", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            } else {
                authorCounts.forEachIndexed { index, (author, count) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(author, Modifier.weight(1f), fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${count}本", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyAdded(books: List<Book>) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    Text("最近添加", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val recent = books.sortedByDescending { it.addedTimestamp }.take(5)
            if (recent.isEmpty()) {
                Text("暂无数据", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            } else {
                recent.forEach { book ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(book.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(book.author.ifEmpty { "未知作者" }, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Text(
                            dateFormat.format(Date(book.addedTimestamp)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

private fun formatNumber(n: Long): String {
    return when {
        n >= 100_000_000 -> "${n / 100_000_000}.${(n % 100_000_000) / 10_000_000}亿"
        n >= 10_000 -> "${n / 10_000}.${(n % 10_000) / 1_000}万"
        else -> "$n"
    }
}
