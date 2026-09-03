/*
 * Copyright 2025 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalUuidApi::class)

package org.readium.navigator.web.internals.webapi

import android.webkit.WebView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.readium.navigator.web.internals.util.shift
import org.readium.navigator.web.internals.webview.evaluateJavaScriptSuspend

public class ReflowableSelectionApi(
    private val webView: WebView,
    private val paddingShift: DpOffset,
) {

    private fun adjustRect(rect: DpRect): DpRect = rect.shift(paddingShift)

    public suspend fun getCurrentSelection(): Selection? =
        withContext(Dispatchers.Main) {
            getCurrentSelectionUnsafe()
        }

    public fun clearSelection() {
        val script = "selection.clearSelection()"
        webView.evaluateJavascript(script) {}
    }

    /**
     * Programmatically selects the word at the given [offset] (in the WebView content's dp
     * coordinates). Native selection is disabled (`user-select:none`), but this still sets the DOM
     * range so the selected text and its rect can be read back and rendered by the app.
     */
    public suspend fun selectAtPoint(offset: DpOffset): Selection? =
        withContext(Dispatchers.Main) {
            val x = offset.x.value
            val y = offset.y.value
            // `Range.expand('word')` was removed from modern Chromium (WebView), so we segment the
            // word ourselves with `Intl.Segmenter` (available since Chromium 87).
            val script = """
                (function(){
                    var x = $x, y = $y;
                    function tag(px, py) {
                        var el = null;
                        try { el = document.elementFromPoint(px, py); } catch(e) {}
                        return el ? el.tagName : 'null';
                    }
                    var r = null;
                    try { r = document.caretRangeFromPoint(x, y); } catch(e) {}
                    // 重置两个手柄的矩形缓存与留白标记，避免残留上一次选区的旧值。
                    window.__selStartRect = null;
                    window.__selEndRect = null;
                    window.__selBlank = false;

                    // 统一「吸附修正」：caretRangeFromPoint 会把靠近行末/留白的触点吸附到相邻行或
                    // 远处文字。若命中的 caret 行与触点不在同一行（垂直越界），左右试探取回同行的
                    // caret；整行无文字（页边距/段间距/空白）则放弃，避免「选中下一行第一个字」。
                    if (r && r.startContainer) {
                        var rect0 = null;
                        try { rect0 = r.getBoundingClientRect(); } catch(e) {}
                        if (rect0 && rect0.height > 0) {
                            if (y < rect0.top || y > rect0.bottom) {
                                var fixed = null;
                                for (var s1 = -4; s1 >= -48 && !fixed; s1 -= 4) {
                                    var q1 = null;
                                    try { q1 = document.caretRangeFromPoint(x + s1, y); } catch(e) {}
                                    if (!q1 || !q1.startContainer) continue;
                                    var q1Rect = null;
                                    try { q1Rect = q1.getBoundingClientRect(); } catch(e) {}
                                    if (q1Rect && q1Rect.height > 0 && q1Rect.top <= y && y <= q1Rect.bottom) { fixed = q1; }
                                }
                                if (!fixed) {
                                    for (var s2 = 4; s2 <= 48 && !fixed; s2 += 4) {
                                        var q2 = null;
                                        try { q2 = document.caretRangeFromPoint(x + s2, y); } catch(e) {}
                                        if (!q2 || !q2.startContainer) continue;
                                        var q2Rect = null;
                                        try { q2Rect = q2.getBoundingClientRect(); } catch(e) {}
                                        if (q2Rect && q2Rect.height > 0 && q2Rect.top <= y && y <= q2Rect.bottom) { fixed = q2; }
                                    }
                                }
                                if (fixed) { r = fixed; }
                                else { r = null; }   // 触点整行无文字：垂直留白，放弃选择
                            }
                            if (r) {
                                // 水平留白：触点横向偏离文字中心过远（左右页边距），放弃选择
                                var rect1 = null;
                                try { rect1 = r.getBoundingClientRect(); } catch(e) {}
                                if (rect1 && rect1.height > 0) {
                                    var cx = (rect1.left + rect1.right) / 2;
                                    if (Math.abs(x - cx) > rect1.height * 1.2) { r = null; }
                                }
                            }
                        }
                    }

                    var parent = 'null';
                    var word = '';
                    if (r && r.startContainer) {
                        var sc = r.startContainer;
                        var nodeTxt = '';
                        if (sc.nodeType === 3) {
                            parent = sc.parentNode ? sc.parentNode.tagName : 'null';
                            nodeTxt = sc.data || '';
                        } else {
                            parent = sc.tagName || sc.nodeName || '?';
                        }
                        var offset = Math.max(0, Math.min(r.startOffset, nodeTxt.length));
                        if (nodeTxt.length > 0) {
                            var start = offset, end = offset;
                            // 光标处的字符：决定按中文单字、拉丁单词还是标点处理
                            var idx = Math.min(offset, nodeTxt.length - 1);
                            var ch = nodeTxt.charAt(idx);
                            var cjkRe = /[぀-ヿ㐀-䶿一-鿿豈-﫿가-힯]/;
                            var wordRe = /[\w'’-]/;

                            // 返回覆盖 o 的单个 grapheme 范围 [s,e)；找不到则退化为单个 code unit
                            var graphemeRange = function(o) {
                                try {
                                    var gs = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
                                    var gi = gs.segment(nodeTxt), gc = gi.next();
                                    while (!gc.done) {
                                        var ga = gc.value.index, gb = ga + gc.value.segment.length;
                                        if (o >= ga && o < gb) return [ga, gb];
                                        gc = gi.next();
                                    }
                                } catch (e) {}
                                var k = Math.max(0, Math.min(o, nodeTxt.length - 1));
                                return [k, k + 1];
                            };

                            // 返回 o 左侧最近的 grapheme 范围 [s,e)（end <= o 的最后一个字）
                            var graphemeBefore = function(o) {
                                try {
                                    var gs2 = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
                                    var gi2 = gs2.segment(nodeTxt), gc2 = gi2.next(), prev = null;
                                    while (!gc2.done) {
                                        var ga2 = gc2.value.index, gb2 = ga2 + gc2.value.segment.length;
                                        if (gb2 > o) break;
                                        prev = [ga2, gb2];
                                        gc2 = gi2.next();
                                    }
                                    if (prev) return prev;
                                } catch (e) {}
                                var k2 = Math.max(0, Math.min(o - 1, nodeTxt.length - 1));
                                return [k2, k2 + 1];
                            };

                            // 点 (px,py) 到矩形 r 的距离平方（比较用）
                            var distToRect = function(px, py, r) {
                                if (!r) return null;
                                var dx = 0, dy = 0;
                                if (px < r.left) dx = r.left - px;
                                else if (px > r.right) dx = px - r.right;
                                if (py < r.top) dy = r.top - py;
                                else if (py > r.bottom) dy = py - r.bottom;
                                return dx * dx + dy * dy;
                            };

                            if (cjkRe.test(ch)) {
                                // 中文等：只选单个字。caret 是字符边界：长按行末最后一字右边缘时，
                                // caret 落在该字之后（即下一行首字之前，同一文本节点），照 offset 向右
                                // 选会误选下一行首字。改用「左右候选字的几何距离」判断，不依赖换行点
                                // caret 矩形（其 left 在换行点可能跳到下一行首）。
                                var zoomNow = (document.body.currentCSSZoom || 1);
                                var gRight = graphemeRange(offset);
                                var gLeft = graphemeBefore(offset);
                                var px = x / zoomNow, py = y / zoomNow;
                                var distL = null, distR = null;
                                try {
                                    var rl = document.createRange();
                                    rl.setStart(sc, gLeft[0]); rl.setEnd(sc, gLeft[1]);
                                    distL = distToRect(px, py, rl.getBoundingClientRect());
                                } catch(e) {}
                                try {
                                    var rr = document.createRange();
                                    rr.setStart(sc, gRight[0]); rr.setEnd(sc, gRight[1]);
                                    distR = distToRect(px, py, rr.getBoundingClientRect());
                                } catch(e) {}
                                var pickL = (distL !== null && distR !== null && distL <= distR);
                                if (pickL) {
                                    start = gLeft[0]; end = gLeft[1];
                                } else {
                                    start = gRight[0]; end = gRight[1];
                                }
                            } else if (wordRe.test(ch)) {
                                // 拉丁字母/数字：选完整单词
                                while (start > 0 && wordRe.test(nodeTxt.charAt(start - 1))) start--;
                                while (end < nodeTxt.length && wordRe.test(nodeTxt.charAt(end))) end++;
                            } else {
                                // 标点等：选光标处单个字，保证长按标点也有反应
                                var g2 = graphemeRange(offset);
                                start = g2[0]; end = g2[1];
                            }
                            if (start < end) {
                                r.setStart(sc, start);
                                r.setEnd(sc, end);
                                word = nodeTxt.slice(start, end);
                            }
                        }
                    }

                    var s = window.getSelection();
                    s.removeAllRanges();
                    if (r) {
                        window.__selStart = { node: r.startContainer, off: r.startOffset };
                        window.__selEnd = { node: r.endContainer, off: r.endOffset };
                        s.addRange(r);
                    }

                    var selText = rangeText(r);
                    function rangeText(r) {
                        if (!r || r.collapsed) return '';
                        var t = '';
                        try { t = r.toString(); } catch(e) {}
                        if (t) return t;
                        try {
                            var f = r.cloneContents();
                            if (f && f.textContent) return f.textContent;
                        } catch(e) {}
                        var sc = r.startContainer, so = r.startOffset, ec = r.endContainer, eo = r.endOffset;
                        if (sc === ec) return (sc.nodeType === 3) ? (sc.data || '').slice(so, eo) : '';
                        try {
                            var w = document.createTreeWalker(r.commonAncestorContainer, NodeFilter.SHOW_TEXT, null);
                            var parts = [], n = w.nextNode();
                            while (n) {
                                if (r.intersectsNode(n)) {
                                    var tx = n.data || '';
                                    if (n === sc) tx = tx.slice(so);
                                    if (n === ec) tx = tx.slice(0, eo);
                                    parts.push(tx);
                                }
                                n = w.nextNode();
                            }
                            return parts.join('');
                        } catch(e) { return t; }
                    }
                    var rect = null;
                    if (!s.isCollapsed && r) {
                        try {
                            var cr = r.getBoundingClientRect();
                            if (cr && (cr.width > 0 || cr.height > 0)) {
                                var zoom = (document.body.currentCSSZoom || 1);
                                rect = {
                                    left: cr.left / zoom,
                                    top: cr.top / zoom,
                                    right: cr.right / zoom,
                                    bottom: cr.bottom / zoom,
                                    width: cr.width / zoom,
                                    height: cr.height / zoom
                                };
                            }
                        } catch (e) {}
                    }
                    return {
                        x: x, y: y,
                        el: tag(x, y),
                        hf: tag(x / 2, y / 2),
                        parent: parent,
                        word: word,
                        col: s.isCollapsed,
                        sel: selText.slice(0, 24),
                        selection: (rect ? { selectedText: selText, textBefore: '', textAfter: '', selectionRect: rect } : null)
                    };
                })()
            """.trimIndent()
            val result = webView.evaluateJavaScriptSuspend(script)
            val debug = try {
                Json.decodeFromString<SelectAtPointDebug>(result)
            } catch (e: Exception) {
                return@withContext null
            }
            val selection = debug.selection?.toSelection() ?: return@withContext null
            selection.copy(
                selectionRect = adjustRect(selection.selectionRect)
            )
        }

    private suspend fun getCurrentSelectionUnsafe(): Selection? {
        // `selection.getCurrentSelection()` relies on Hypothesis text anchoring
        // (`TextRange.fromRange(...).relativeTo(document.body)`), which fails on this DOM
        // structure, so compute the selection directly instead.
        val script = """
            (function(){
                var s = window.getSelection();
                if (!s || s.rangeCount === 0 || s.isCollapsed) return null;
                var r = s.getRangeAt(0);
                var selText = rangeText(r);
                var cr = r.getBoundingClientRect();
                var zoom = (document.body.currentCSSZoom || 1);
                return {
                    selectedText: selText,
                    textBefore: '',
                    textAfter: '',
                    selectionRect: { left: cr.left / zoom, top: cr.top / zoom, right: cr.right / zoom, bottom: cr.bottom / zoom, width: cr.width / zoom, height: cr.height / zoom }
                };

                function rangeText(r) {
                    if (!r || r.collapsed) return '';
                    var t = '';
                    try { t = r.toString(); } catch(e) {}
                    if (t) return t;
                    try {
                        var f = r.cloneContents();
                        if (f && f.textContent) return f.textContent;
                    } catch(e) {}
                    var sc = r.startContainer, so = r.startOffset, ec = r.endContainer, eo = r.endOffset;
                    if (sc === ec) return (sc.nodeType === 3) ? (sc.data || '').slice(so, eo) : '';
                    try {
                        var w = document.createTreeWalker(r.commonAncestorContainer, NodeFilter.SHOW_TEXT, null);
                        var parts = [], n = w.nextNode();
                        while (n) {
                            if (r.intersectsNode(n)) {
                                var tx = n.data || '';
                                if (n === sc) tx = tx.slice(so);
                                if (n === ec) tx = tx.slice(0, eo);
                                parts.push(tx);
                            }
                            n = w.nextNode();
                        }
                        return parts.join('');
                    } catch(e) { return t; }
                }
            })()
        """.trimIndent()
        val result = webView.evaluateJavaScriptSuspend(script)
        val selection = Json.decodeFromString<JsonSelection?>(result)
            ?.toSelection()
            ?: return null

        return selection.copy(
            selectionRect = adjustRect(selection.selectionRect)
        )
    }

    /** Caret rect at the start of the current selection (Box coords), for the start handle. */
    public suspend fun getStartHandleRect(): DpRect? =
        getHandleRect(collapseToStart = true)

    /** Caret rect at the end of the current selection (Box coords), for the end handle. */
    public suspend fun getEndHandleRect(): DpRect? =
        getHandleRect(collapseToStart = false)

    private suspend fun getHandleRect(collapseToStart: Boolean): DpRect? =
        withContext(Dispatchers.Main) {
            val script = """
                (function(){
                    var s = window.getSelection();
                    if (!s || s.rangeCount === 0 || s.isCollapsed) return null;
                    var r = s.getRangeAt(0).cloneRange();
                    r.collapse(${if (collapseToStart) "true" else "false"});
                    var cr = r.getBoundingClientRect();
                    var zoom = (document.body.currentCSSZoom || 1);
                    return { left: cr.left / zoom, top: cr.top / zoom, right: cr.right / zoom, bottom: cr.bottom / zoom, width: cr.width / zoom, height: cr.height / zoom };
                })()
            """.trimIndent()
            val result = webView.evaluateJavaScriptSuspend(script)
            val rect = Json.decodeFromString<JsonRect?>(result) ?: return@withContext null
            adjustRect(rect.toDpRect())
        }

    /** Extends the selection start to the pointer at [offset] (Box coordinates). */
    public suspend fun extendStart(offset: DpOffset): Selection? =
        extend(offset, isStart = true)

    /** Extends the selection end to the pointer at [offset] (Box coordinates). */
    public suspend fun extendEnd(offset: DpOffset): Selection? =
        extend(offset, isStart = false)

    private suspend fun extend(offset: DpOffset, isStart: Boolean): Selection? =
        withContext(Dispatchers.Main) {
            // The app passes Box coordinates; `caretRangeFromPoint` needs WebView (CSS) coordinates.
            val webViewOffset = offset - paddingShift
            val x = webViewOffset.x.value
            val y = webViewOffset.y.value
            val handle = if (isStart) "start" else "end"
            val script = """
                (function(){
                    var x = $x, y = $y, handle = '$handle';
                    var zoom = (document.body.currentCSSZoom || 1);
                    function clientRects(range) {
                        var list = [];
                        try {
                            var crl = range.getClientRects();
                            for (var i = 0; i < crl.length; i++) {
                                var c = crl[i];
                                if (c && (c.width > 0 || c.height > 0)) {
                                    list.push({ left: c.left / zoom, top: c.top / zoom, right: c.right / zoom, bottom: c.bottom / zoom, width: c.width / zoom, height: c.height / zoom });
                                }
                            }
                        } catch(e) {}
                        return list;
                    }
                    // Build a DOM range from two logical endpoints, trying both orders. Chromium
                    // collapses (rather than throws) a reversed setStart/setEnd, so when the first
                    // order collapses we fall back to the reverse order. This is the swap: dragging
                    // one handle past the other still selects the text in between.
                    function makeRange(aNode, aOff, bNode, bOff) {
                        var r = document.createRange();
                        try { r.setStart(aNode, aOff); r.setEnd(bNode, bOff); } catch(e) { r = null; }
                        if (r && !r.collapsed) return r;
                        var r2 = document.createRange();
                        try { r2.setStart(bNode, bOff); r2.setEnd(aNode, aOff); } catch(e) { return null; }
                        if (r2 && !r2.collapsed) return r2;
                        return null;
                    }
                    var s = window.getSelection();
                    if (!s || s.rangeCount === 0 || s.isCollapsed) return null;
                    var range = s.getRangeAt(0);
                    var pr = null;
                    try { pr = document.caretRangeFromPoint(x, y); } catch(e) {}
                    if (!pr || !pr.startContainer) return null;

                    // 留白钳制：拖到留白/段间距时，caretRangeFromPoint 可能吸附到相邻行、远处文字，
                    // 甚至返回元素节点（不可用于 makeRange）。处理策略：
                    //   1) 触点位于最近字符上方/下方（末行之后/首行之前）→ 直接钳制到该字符，
                    //      让选区向拖动方向扩展、覆盖两个手柄之间的文字（而不是冻结手柄）。
                    //   2) 同行（或无效位置）→ 左右试探回到同一行的文本 caret；整行无文本则放弃。
                    // 所有钳制目标都须落在当前可视页内（[0, vpW]），防止吸附到相邻页/栏的文字。
                    if (pr && pr.startContainer) {
                        var prRect = null;
                        try { prRect = pr.getBoundingClientRect(); } catch(e) {}
                        var vpW = (document.documentElement.clientWidth || window.innerWidth || 400);
                        var prIsText = (pr.startContainer.nodeType === 3);
                        var prInViewport = prIsText && prRect && prRect.height > 0 && prRect.left >= 0 && prRect.right <= vpW;
                        var belowLine = prInViewport && (y > prRect.bottom);
                        var aboveLine = prInViewport && (y < prRect.top);

                        if (!(belowLine || aboveLine)) {
                            // 水平留白：触点横向偏离最近字符过远（左右页边距），放弃本次移动，避免跳选到远处字符。
                            // 带迟滞：一旦判定进入留白（window.__selBlank），需更贴近文字才解除，避免在阈值附近抖动（闪烁）。
                            var wasBlank = !!window.__selBlank;
                            if (prRect && prRect.height > 0) {
                                var prCx = (prRect.left + prRect.right) / 2;
                                var hGap = Math.abs(x - prCx);
                                var hThr = prRect.height * (wasBlank ? 0.35 : 0.6);
                                if (hGap > hThr) { window.__selBlank = true; return null; }
                            }
                            var needFix = !prIsText;
                            var clamped = null;
                            if (!needFix && prRect && prRect.height > 0) {
                                needFix = (y < prRect.top || y > prRect.bottom);
                            }
                            if (needFix) {
                                // 同行文字探测范围需覆盖整栏文字宽度。短句独立成段时短句只占左侧，
                                // 触点落在其右侧留白、而 caretRangeFromPoint 又吸附到上方长句（横向对齐、纵向更近）时，
                                // 只有把探测范围放远，才能找到触点同一行的短句文字并正确钳制，避免选区粘滞后跳变（异常扩大/闪烁）。
                                // 探测限定在当前可视页内（[0, vpW]），防止吸附到相邻栏（上/下一页）的文字。
                                for (var dLeft = -4; (x + dLeft) >= 0 && !clamped; dLeft -= 4) {
                                    var ql = null;
                                    try { ql = document.caretRangeFromPoint(x + dLeft, y); } catch(e) {}
                                    if (!ql || !ql.startContainer || ql.startContainer.nodeType !== 3) continue;
                                    var qlRect = null;
                                    try { qlRect = ql.getBoundingClientRect(); } catch(e) {}
                                    if (qlRect && qlRect.height > 0 && qlRect.top <= y && y <= qlRect.bottom
                                        && qlRect.left >= 0 && qlRect.right <= vpW) clamped = ql;
                                }
                                if (!clamped) {
                                    for (var dRight = 4; (x + dRight) <= vpW && !clamped; dRight += 4) {
                                        var qr2 = null;
                                        try { qr2 = document.caretRangeFromPoint(x + dRight, y); } catch(e) {}
                                        if (!qr2 || !qr2.startContainer || qr2.startContainer.nodeType !== 3) continue;
                                        var qr2Rect = null;
                                        try { qr2Rect = qr2.getBoundingClientRect(); } catch(e) {}
                                        if (qr2Rect && qr2Rect.height > 0 && qr2Rect.top <= y && y <= qr2Rect.bottom
                                            && qr2Rect.left >= 0 && qr2Rect.right <= vpW) { clamped = qr2; break; }
                                    }
                                }
                                if (clamped) {
                                    pr = clamped;
                                } else {
                                    // 无同行文本（元素节点或整行留白）→ 放弃，避免选区膨胀。
                                    window.__selBlank = true;
                                    return null;
                                }
                            }
                        }
                    }
                    // 通过全部留白检查：清除留白标记。
                    window.__selBlank = false;

                    // Keep the two logical handle endpoints in window globals so dragging one handle
                    // past the other swaps them (selecting the text in between) instead of collapsing
                    // the range. The non-dragged endpoint stays fixed for the whole drag, which also
                    // makes a continued drag after a swap stay correct.
                    var pNode = pr.startContainer, pOff = pr.startOffset;   // finger
                    var sNode, sOff, eNode, eOff;                           // logical start / end
                    if (handle === 'start') {
                        var ee = window.__selEnd || { node: range.endContainer, off: range.endOffset };
                        sNode = pNode; sOff = pOff;                         // start follows finger
                        eNode = ee.node; eOff = ee.off;                     // end fixed
                    } else {
                        var ss = window.__selStart || { node: range.startContainer, off: range.startOffset };
                        sNode = ss.node; sOff = ss.off;                     // start fixed
                        eNode = pNode; eOff = pOff;                         // end follows finger
                    }
                    // The DOM range must be start-before-end; try both orders so a crossed drag
                    // swaps the endpoints (selecting the text in between) instead of collapsing.
                    var nr = makeRange(sNode, sOff, eNode, eOff);
                    if (!nr) return null;
                    window.__selStart = { node: sNode, off: sOff };
                    window.__selEnd = { node: eNode, off: eOff };

                    s.removeAllRanges();
                    s.addRange(nr);
                    var selText = rangeText(nr);
                    function rangeText(r) {
                        if (!r || r.collapsed) return '';
                        var t = '';
                        try { t = r.toString(); } catch(e) {}
                        if (t) return t;
                        try {
                            var f = r.cloneContents();
                            if (f && f.textContent) return f.textContent;
                        } catch(e) {}
                        var sc = r.startContainer, so = r.startOffset, ec = r.endContainer, eo = r.endOffset;
                        if (sc === ec) return (sc.nodeType === 3) ? (sc.data || '').slice(so, eo) : '';
                        try {
                            var w = document.createTreeWalker(r.commonAncestorContainer, NodeFilter.SHOW_TEXT, null);
                            var parts = [], n = w.nextNode();
                            while (n) {
                                if (r.intersectsNode(n)) {
                                    var tx = n.data || '';
                                    if (n === sc) tx = tx.slice(so);
                                    if (n === ec) tx = tx.slice(0, eo);
                                    parts.push(tx);
                                }
                                n = w.nextNode();
                            }
                            return parts.join('');
                        } catch(e) { return t; }
                    }
                    var cr = null;
                    try { cr = nr.getBoundingClientRect(); } catch(e) {}
                    var rect = null;
                    if (cr && (cr.width > 0 || cr.height > 0)) {
                        rect = { left: cr.left / zoom, top: cr.top / zoom, right: cr.right / zoom, bottom: cr.bottom / zoom, width: cr.width / zoom, height: cr.height / zoom };
                    }
                    if (!rect) return null;

                    // Caret rect for a collapsed position. On a blank line the browser returns a
                    // degenerate (0,0) rect, so fall back to the finger position instead of the origin.
                    function caretRect(node, off) {
                        var c = { left: x, top: y, right: x, bottom: y, width: 0, height: 0 };
                        try {
                            var r = document.createRange();
                            r.setStart(node, off);
                            r.setEnd(node, off);
                            var b = r.getBoundingClientRect();
                            if (b && (b.width > 0 || b.height > 0)) {
                                c = { left: b.left / zoom, top: b.top / zoom, right: b.right / zoom, bottom: b.bottom / zoom, width: b.width / zoom, height: b.height / zoom };
                            }
                        } catch(e) {}
                        return c;
                    }
                    function isDegenerate(c) { return !c || (c.width <= 0 && c.height <= 0); }

                    var fingerRect = caretRect(pNode, pOff);
                    var fixedNode = (handle === 'start') ? eNode : sNode;
                    var fixedOff = (handle === 'start') ? eOff : sOff;
                    // 固定手柄在空白行时现算矩形会退化成手指位置；回退到该手柄上次的缓存矩形，
                    // 使其稳稳停在原空白行，而不是被拽到正在移动的另一手柄旁边。
                    var fixedRect = caretRect(fixedNode, fixedOff);
                    if (isDegenerate(fixedRect)) {
                        var cached = (handle === 'start') ? window.__selEndRect : window.__selStartRect;
                        if (cached) fixedRect = cached;
                    }
                    var startRect = (handle === 'start') ? fingerRect : fixedRect;
                    var endRect = (handle === 'start') ? fixedRect : fingerRect;

                    // 缓存两个手柄的矩形，供下一次拖动回退使用。
                    window.__selStartRect = startRect;
                    window.__selEndRect = endRect;

                    return { selectedText: selText, textBefore: '', textAfter: '', selectionRect: rect, startHandleRect: startRect, endHandleRect: endRect, selectionRects: clientRects(nr) };
                })()
            """.trimIndent()
            val result = webView.evaluateJavaScriptSuspend(script)
            val selection = Json.decodeFromString<JsonSelection?>(result)?.toSelection() ?: return@withContext null
            selection.copy(
                selectionRect = adjustRect(selection.selectionRect),
                startHandleRect = selection.startHandleRect?.let { adjustRect(it) },
                endHandleRect = selection.endHandleRect?.let { adjustRect(it) },
                selectionRects = selection.selectionRects.map { adjustRect(it) }
            )
        }
}

public sealed interface FixedSelectionApi

public class FixedSingleSelectionApi(
    private val webView: WebView,
    listener: FixedSingleSelectionListener,
    private val adjustRect: (DpRect) -> DpRect,
) : FixedSelectionApi, FixedSingleSelectionListener.Listener {

    private val requests = mutableMapOf<String, Continuation<Selection?>>()

    init {
        listener.listener = this
    }

    public fun clearSelection() {
        val script = "singleSelection.clearSelection()"
        webView.evaluateJavascript(script) {}
    }

    public suspend fun getCurrentSelection(): Selection? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val requestId = Uuid.random().toString()
                requests[requestId] = cont
                val script = "singleSelection.requestSelection(${requestId.toJavaScriptLiteral()})"
                webView.evaluateJavascript(script) {}
            }
        }

    override fun onSelectionAvailable(requestId: String, selection: String) {
        val cont = requests.remove(requestId) ?: return

        val selection = Json.decodeFromString<JsonSelection?>(selection)
            ?.toSelection()

        val adjustedSelection = selection?.let { selection ->
            selection.copy(selectionRect = adjustRect(selection.selectionRect))
        }

        cont.resume(adjustedSelection)
    }
}

public class FixedSingleSelectionListener(
    webView: WebView,
    public var listener: Listener? = null,
) {
    public interface Listener {

        public fun onSelectionAvailable(requestId: String, selection: String)
    }

    init {
        webView.addJavascriptInterface(this, "singleSelectionListener")
    }

    @android.webkit.JavascriptInterface
    public fun onSelectionAvailable(requestId: String, selection: String) {
        checkNotNull(listener).onSelectionAvailable(requestId, selection)
    }
}

public class FixedDoubleSelectionApi(
    private val webView: WebView,
    listener: FixedDoubleSelectionListener,
    private val adjustRect: (DpRect) -> DpRect,
) : FixedSelectionApi, FixedDoubleSelectionListener.Listener {

    private val requests = mutableMapOf<String, Continuation<SelectionWithIframe?>>()

    init {
        listener.listener = this
    }

    public fun clearSelection() {
        val script = "doubleSelection.clearSelection()"
        webView.evaluateJavascript(script) {}
    }

    public suspend fun getCurrentSelection(): SelectionWithIframe? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val requestId = Uuid.random().toString()
                requests[requestId] = cont
                val script = "doubleSelection.requestSelection(${requestId.toJavaScriptLiteral()})"
                webView.evaluateJavascript(script) {}
            }
        }

    override fun onSelectionAvailable(requestId: String, iframe: String, selection: String) {
        val cont = requests.remove(requestId) ?: return

        val selection = Json.decodeFromString<JsonSelection?>(selection)
            ?.toSelection()

        val adjustedSelection = selection?.let { selection ->
            selection.copy(selectionRect = adjustRect(selection.selectionRect))
        }

        val iframe = requireNotNull(Iframe.get(iframe))

        val result = adjustedSelection?.let { SelectionWithIframe(iframe, it) }

        cont.resume(result)
    }
}

public class FixedDoubleSelectionListener(
    webView: WebView,
    public var listener: Listener? = null,
) {
    public interface Listener {

        public fun onSelectionAvailable(requestId: String, iframe: String, selection: String)
    }

    init {
        webView.addJavascriptInterface(this, "doubleSelectionListener")
    }

    @android.webkit.JavascriptInterface
    public fun onSelectionAvailable(requestId: String, iframe: String, selection: String) {
        checkNotNull(listener).onSelectionAvailable(requestId, iframe, selection)
    }
}

public data class SelectionWithIframe(
    val iframe: Iframe,
    val selection: Selection,
)

public data class Selection(
    public val selectedText: String,
    public val textBefore: String,
    public val textAfter: String,
    public val selectionRect: DpRect,
    public val startHandleRect: DpRect? = null,
    public val endHandleRect: DpRect? = null,
    public val selectionRects: List<DpRect> = emptyList(),
)

@Serializable
private data class JsonSelection(
    val selectedText: String,
    val textBefore: String,
    val textAfter: String,
    val selectionRect: JsonRect,
    val startHandleRect: JsonRect? = null,
    val endHandleRect: JsonRect? = null,
    val selectionRects: List<JsonRect> = emptyList(),
) {
    fun toSelection() =
        Selection(
            selectedText = selectedText,
            textBefore = textBefore,
            textAfter = textAfter,
            selectionRect = selectionRect.toDpRect(),
            startHandleRect = startHandleRect?.toDpRect(),
            endHandleRect = endHandleRect?.toDpRect(),
            selectionRects = selectionRects.map { it.toDpRect() }
        )
}

@Serializable
private data class SelectAtPointDebug(
    val x: Double,
    val y: Double,
    val el: String,
    val hf: String,
    val parent: String,
    val word: String,
    val col: Boolean,
    val sel: String,
    val selection: JsonSelection?,
)
