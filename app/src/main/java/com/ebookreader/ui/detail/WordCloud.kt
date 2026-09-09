package com.ebookreader.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.ebookreader.domain.model.Keyword
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 词云：权重越高字号越大、越居中，错落排列（阿基米德螺线 + 碰撞检测，不整排）。
 * 点击某个词在其上方弹出气泡显示「词 · 权重」，点击屏幕任意位置收起。
 */
@Composable
fun WordCloud(
    keywords: List<Keyword>,
    modifier: Modifier = Modifier,
    cloudHeight: Dp = 340.dp,
) {
    val sorted = keywords.sortedByDescending { it.weight }
    if (sorted.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(cloudHeight),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt()
        val heightPx = with(density) { cloudHeight.toPx() }.toInt()
        val padH = with(density) { 10.dp.toPx() }.toInt()
        val padV = with(density) { 6.dp.toPx() }.toInt()
        val gap = with(density) { 6.dp.toPx() }.toInt()
        val wordGap = with(density) { 5.dp.toPx() }.toInt()

        val measured = sorted.map { kw ->
            val fs = fontSizeFor(kw.weight)
            val size = textMeasurer.measure(
                AnnotatedString(kw.keyword),
                style = TextStyle(fontSize = fs.sp, fontWeight = fontWeightFor(kw.weight)),
            ).size
            MeasuredWord(kw, size.width, size.height, fs)
        }
        val placements = spiralPlacement(measured, widthPx, heightPx, wordGap)

        var selected by remember { mutableStateOf<PlacedWord?>(null) }
        val bubbleText = selected?.let { "${it.word.keyword} · 权重 ${"%.2f".format(it.word.weight)}" }
        val bubbleSize = bubbleText?.let {
            textMeasurer.measure(
                AnnotatedString(it),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            ).size
        }

        Box(Modifier.fillMaxSize()) {
            placements.forEach { p ->
                Text(
                    text = p.word.keyword,
                    fontSize = p.fontSize.sp,
                    fontWeight = fontWeightFor(p.word.weight),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .offset { IntOffset(p.x, p.y) }
                        .clickable { selected = p },
                )
            }
            selected?.let { sel ->
                val bw = bubbleSize?.width ?: 0
                val bh = bubbleSize?.height ?: 0
                val totalW = bw + padH * 2
                val totalH = bh + padV * 2
                val bx = (sel.x + sel.w / 2 - totalW / 2).coerceIn(0, (widthPx - totalW).coerceAtLeast(0))
                val by = (sel.y - totalH - gap).coerceAtLeast(0)
                Surface(
                    modifier = Modifier.offset { IntOffset(bx, by) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = "${sel.word.keyword} · 权重 ${"%.2f".format(sel.word.weight)}",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // 点击屏幕任意位置关闭权重气泡：覆盖全屏的透明点击层（无涟漪、不使背景变暗），气泡仍锚定在词云内随滚动移动。
        if (selected != null) {
            WeightBubbleDismissScrim(onDismiss = { selected = null })
        }
    }
}

private data class MeasuredWord(val word: Keyword, val w: Int, val h: Int, val fontSize: Float)

private data class PlacedWord(
    val word: Keyword,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val fontSize: Float,
)

/** 字号随权重二次增长，放大高权重词与低权重词的字号差异。 */
private fun fontSizeFor(weight: Float): Float {
    val t = weight.coerceIn(0f, 1f)
    return 12f + 28f * t * t
}

private fun fontWeightFor(weight: Float): FontWeight =
    if (weight > 0.7f) FontWeight.SemiBold else FontWeight.Normal

/** 经典词云布局：权重最高词居中，其余沿阿基米德螺线由内向外放置，碰撞则外移。
 *  螺线 r = b·θ（斜率 b 越小越紧）、候选点更密，排布更紧凑；边界用内切椭圆，词聚成圆形。 */
private fun spiralPlacement(
    words: List<MeasuredWord>,
    width: Int,
    height: Int,
    spacing: Int = 0,
): List<PlacedWord> {
    if (words.isEmpty()) return emptyList()
    val placed = mutableListOf<PlacedWord>()
    val centerX = width / 2
    val centerY = height / 2
    val a = (width / 2.0).coerceAtLeast(1.0)
    val b = (height / 2.0).coerceAtLeast(1.0)

    for (w in words) {
        if (placed.isEmpty()) {
            placed.add(PlacedWord(w.word, centerX - w.w / 2, centerY - w.h / 2, w.w, w.h, w.fontSize))
            continue
        }
        var theta = 0.0
        var radius = 0.0
        val angleStep = 0.10      // 弧度步进：越小候选点越密、越紧凑
        val radiusCoeff = 2.0     // 螺线斜率 b（px/rad）：相邻圈距 2πb，越小越紧
        val maxRadius = sqrt(a * a + b * b) + spacing  // 超过椭圆最远半径必越界，可提前停
        var left = centerX - w.w / 2
        var top = centerY - w.h / 2
        var found = false
        while (!found && radius <= maxRadius) {
            val cx = (centerX + radius * cos(theta)).toInt()
            val cy = (centerY + radius * sin(theta)).toInt()
            left = cx - w.w / 2
            top = cy - w.h / 2
            val right = left + w.w
            val bottom = top + w.h
            val nx = (cx - centerX) / a
            val ny = (cy - centerY) / b
            val inEllipse = nx * nx + ny * ny <= 1.0
            val inBounds = inEllipse && left >= 0 && top >= 0 && right <= width && bottom <= height
            val collide = placed.any { p ->
                (left - spacing) < p.x + p.w && (right + spacing) > p.x &&
                    (top - spacing) < p.y + p.h && (bottom + spacing) > p.y
            }
            if (inBounds && !collide) {
                found = true
            } else {
                theta += angleStep
                radius = radiusCoeff * theta
            }
        }
        // 找不到位置就跳过该词（避免出界/重叠）；小词先被挤掉，云内无缝隙、无越界
        if (found) placed.add(PlacedWord(w.word, left, top, w.w, w.h, w.fontSize))
    }
    return placed
}

/** 全屏透明点击层：按下任意位置即关闭气泡，无可见涟漪，也不会使背景变暗（用 Popup 而非 Dialog）。 */
@Composable
private fun WeightBubbleDismissScrim(onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.dp
    val screenH = configuration.screenHeightDp.dp

    Popup(
        popupPositionProvider = ScreenTopLeftPositionProvider,
        onDismissRequest = onDismiss,
    ) {
        Box(
            Modifier
                .size(screenW, screenH)
                .pointerInput(Unit) { detectTapGestures(onPress = { onDismiss() }) },
        )
    }
}

/** 把 Popup 定位到窗口左上角，使其内容铺满整个屏幕（用于全屏点击层）。 */
private val ScreenTopLeftPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}
