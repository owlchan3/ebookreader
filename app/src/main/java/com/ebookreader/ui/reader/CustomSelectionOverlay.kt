package com.ebookreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp

private enum class Handle { NONE, START, END }

/**
 * Custom selection overlay: draws a translucent highlight over the programmatic selection (per line
 * fragment), two draggable handles for adjusting it, and a small menu (copy / share / highlight /
 * annotate) near it. Replaces the system's native selection ActionMode, which is disabled for
 * reflowable content.
 */
@Composable
internal fun CustomSelectionOverlay(
    rect: DpRect,
    startHandleRect: DpRect?,
    endHandleRect: DpRect?,
    selectionRects: List<DpRect>,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onHighlight: () -> Unit,
    onAnnotate: () -> Unit,
    onDictionary: () -> Unit,
    onDismiss: () -> Unit,
    onExtendStart: (DpOffset) -> Unit,
    onExtendEnd: (DpOffset) -> Unit,
) {
    val density = LocalDensity.current

    // Precise collapsed caret rects for multi-line selections, falling back to the bounding rect's
    // corners for the initial single-word selection. The handle circles are shifted outward into the
    // page margin so they sit beside the text instead of covering the first/last characters. The
    // shift direction follows READING ORDER (start handle points toward the reading-start side, end
    // handle toward the reading-end side), so a multi-line selection whose last line is ragged-right
    // (end handle x < bounding-box center) still keeps both handles outward, and so they stay outward
    // after being crossed (swapped).
    val handleShift = 12.dp
    val handleShiftPx = with(density) { handleShift.toPx() }
    val rawStartCenter = (startHandleRect?.let {
        DpOffset(it.left + (it.right - it.left) / 2, it.top + (it.bottom - it.top) / 2)
    } ?: DpOffset(rect.left, rect.top))
    val rawEndCenter = (endHandleRect?.let {
        DpOffset(it.left + (it.right - it.left) / 2, it.top + (it.bottom - it.top) / 2)
    } ?: DpOffset(rect.right, rect.bottom))
    // 阅读顺序判定：先比 y（上一行优先），同行再比 x（左侧优先）。
    val startBeforeEnd = rawStartCenter.y < rawEndCenter.y ||
        (rawStartCenter.y == rawEndCenter.y && rawStartCenter.x <= rawEndCenter.x)
    val startDirRight = !startBeforeEnd
    val endDirRight = startBeforeEnd
    val startCenter = DpOffset(rawStartCenter.x + (if (startDirRight) handleShift else -handleShift), rawStartCenter.y)
    val endCenter = DpOffset(rawEndCenter.x + (if (endDirRight) handleShift else -handleShift), rawEndCenter.y)

    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val centers = rememberUpdatedState(startCenter to endCenter)
    // 拖动补偿用的像素位移（当前方向的 outward 偏移，可正可负）。
    val shiftPxState = rememberUpdatedState(
        (if (startDirRight) handleShiftPx else -handleShiftPx) to
            (if (endDirRight) handleShiftPx else -handleShiftPx)
    )

    // True while a handle is being dragged; the menu is hidden during the drag so it never
    // covers the handle being dragged (e.g. into a blank line).
    var isDragging by remember { mutableStateOf(false) }

    // Per-line highlight rects (from Range.getClientRects()); falls back to the bounding rect.
    val highlightRects = selectionRects.ifEmpty { listOf(rect) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Translucent highlight drawn per line fragment so multi-line selections are accurate
        // instead of a single coarse bounding rectangle.
        for (r in highlightRects) {
            Box(
                modifier = Modifier
                    .offset(x = r.left, y = r.top)
                    .size(
                        width = (r.right - r.left).coerceAtLeast(0.dp),
                        height = (r.bottom - r.top).coerceAtLeast(0.dp)
                    )
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
        }

        val menuWidth = 256.dp
        val menuHeight = 52.dp
        val menuX = (rect.left + (rect.right - rect.left) / 2 - menuWidth / 2)
            .coerceIn(0.dp, (maxWidth - menuWidth).coerceAtLeast(0.dp))

        // 把两个手柄也纳入避让范围：菜单放在「选区 + 手柄」整体的下方/上方（含手柄圆 11dp
        // 余量），从根上避免盖住被拖到空白行的手柄。
        val handlePad = 11.dp
        val topBound = minOf(rect.top, startCenter.y - handlePad, endCenter.y - handlePad)
        val bottomBound = maxOf(rect.bottom, startCenter.y + handlePad, endCenter.y + handlePad)
        val menuY = (if (bottomBound + 8.dp + menuHeight <= maxHeight) {
            bottomBound + 8.dp
        } else {
            topBound - 8.dp - menuHeight
        }).coerceIn(0.dp, (maxHeight - menuHeight).coerceAtLeast(0.dp))

        // The gesture layer is the PARENT of both the handles and the menu. It uses
        // `requireUnconsumed = true`, so any tap the menu buttons consume never reaches it; only
        // unconsumed taps (on a handle or empty space) drive the drag / dismiss. This avoids relying
        // on coordinate measurement or sibling consumption order, both of which dropped menu taps.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val (start, end) = centers.value
                        val handle = when {
                            (down.position - Offset(start.x.toPx(), start.y.toPx())).getDistance() <= hitRadiusPx -> Handle.START
                            (down.position - Offset(end.x.toPx(), end.y.toPx())).getDistance() <= hitRadiusPx -> Handle.END
                            else -> Handle.NONE
                        }
                        down.consume()
                        if (handle == Handle.NONE) {
                            // Empty space: swallow the gesture and dismiss once the finger lifts.
                            drag(down.id) { change -> change.consume() }
                            onDismiss()
                        } else {
                            isDragging = true
                            drag(down.id) { change ->
                                change.consume()
                                val px = change.position
                                // Compensate the visual handle offset so the caret follows the
                                // finger (the circle stays under the finger instead of shift away).
                                val (startSxPx, endSxPx) = shiftPxState.value
                                when (handle) {
                                    Handle.START -> onExtendStart(DpOffset((px.x - startSxPx).toDp(), px.y.toDp()))
                                    Handle.END -> onExtendEnd(DpOffset((px.x - endSxPx).toDp(), px.y.toDp()))
                                    Handle.NONE -> Unit
                                }
                            }
                            isDragging = false
                        }
                    }
                }
        ) {
            // Handle visuals (non-interactive; the gesture layer above drives the drag).
            HandleCircle(startCenter, MaterialTheme.colorScheme.primary)
            HandleCircle(endCenter, MaterialTheme.colorScheme.primary)

            // Menu anchored below the selection, or above when it would overflow the bottom.
            // Hidden while dragging a handle so it never covers the dragged handle.
            if (!isDragging) {
                Card(
                    modifier = Modifier.offset(x = menuX, y = menuY),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                ) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.ContentCopy, "复制", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, "分享", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onHighlight) {
                            Icon(Icons.Default.Create, "划线", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onAnnotate) {
                            Icon(Icons.AutoMirrored.Filled.Message, "批注", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onDictionary) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, "词典", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HandleCircle(center: DpOffset, color: Color) {
    Box(
        Modifier
            .offset(x = center.x - 11.dp, y = center.y - 11.dp)
            .size(22.dp)
            .background(color, CircleShape)
    )
}
