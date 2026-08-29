package com.ebookreader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 计算可拖动垂直滚动条的连续（无级）滚动比例 0..1。
 *
 * @param firstVisibleIndex 第一个可见条目的扁平索引
 * @param firstVisibleScrollOffset 第一个可见条目内的像素子偏移（用于无级定位）
 * @param itemExtent 单个条目/行在滚动方向上的像素尺寸
 * @param totalItems 总扁平条目数
 * @param columns 列数（普通列表为 1，网格为列数）
 * @param viewportExtent 视口在滚动方向上的像素尺寸
 */
fun computeScrollFraction(
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    itemExtent: Float,
    totalItems: Int,
    columns: Int,
    viewportExtent: Float,
): Float {
    if (totalItems <= 1) return 0f
    val totalRows = (totalItems + columns - 1) / columns
    val visibleRows = (viewportExtent / itemExtent.coerceAtLeast(1f)).coerceAtLeast(1f)
    val scrollableRows = (totalRows - visibleRows).coerceAtLeast(1f)
    val firstRow = firstVisibleIndex / columns
    val subRow = firstVisibleScrollOffset.toFloat() / itemExtent.coerceAtLeast(1f)
    return ((firstRow + subRow) / scrollableRows).coerceIn(0f, 1f)
}

/**
 * 把滚动比例映射回目标扁平索引与像素子偏移，用于无级滚动（scrollToItem(index, offset)）。
 */
fun fractionToScrollPosition(
    fraction: Float,
    itemExtent: Float,
    totalItems: Int,
    columns: Int,
    viewportExtent: Float,
): Pair<Int, Int> {
    if (totalItems <= 1) return 0 to 0
    val totalRows = (totalItems + columns - 1) / columns
    val visibleRows = (viewportExtent / itemExtent.coerceAtLeast(1f)).coerceAtLeast(1f)
    val scrollableRows = (totalRows - visibleRows).coerceAtLeast(1f)
    val targetRowFloat = fraction.coerceIn(0f, 1f) * scrollableRows
    val targetRow = targetRowFloat.toInt()
    val offset = ((targetRowFloat - targetRow) * itemExtent.coerceAtLeast(1f)).toInt()
    val index = (targetRow * columns).coerceIn(0, totalItems - 1)
    return index to offset
}

/**
 * 可拖动的垂直滚动条（拉条），用于长列表/网格的快速定位。
 *
 * @param fraction 当前连续滚动比例（0..1，无级）
 * @param onScrollFraction 拖动时回调目标比例
 */
@Composable
fun VerticalScrollbar(
    fraction: Float,
    onScrollFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    canScroll: Boolean = true,
) {
    if (!canScroll) return
    var boxHeight by remember { mutableStateOf(0f) }
    // 拖动时用本地拖动态（实时跟手），非拖动时回落到真实滚动位置
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val displayFraction = (if (dragging) dragFraction else fraction).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { 40.dp.toPx() }
    // 滑块顶部可移动区间 = 轨道高 - 滑块高，避免滑到底时滑块溢出屏幕
    val travelRange = (boxHeight - thumbHeightPx).coerceAtLeast(0f)

    Box(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            .onSizeChanged { boxHeight = it.height.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val frac = (change.position.y / travelRange.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    dragFraction = frac
                    onScrollFraction(frac)
                }
            },
    ) {
        // 滑块
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(5, (displayFraction * travelRange).toInt()) }
                .width(6.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        )
    }
}
