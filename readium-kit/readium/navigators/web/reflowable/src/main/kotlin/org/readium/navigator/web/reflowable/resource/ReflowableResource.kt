/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */
@file:OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)

package org.readium.navigator.web.reflowable.resource

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.readium.navigator.common.DecorationChange
import org.readium.navigator.common.DecorationListener
import org.readium.navigator.common.TapEvent
import org.readium.navigator.common.changesByHref
import org.readium.navigator.web.common.WebDecorationTemplate
import org.readium.navigator.web.common.WebDecorationTemplates
import org.readium.navigator.web.internals.server.WebViewClient
import org.readium.navigator.web.internals.util.AbsolutePaddingValues
import org.readium.navigator.web.internals.util.absolutePadding
import org.readium.navigator.web.internals.util.getValue
import org.readium.navigator.web.internals.util.rememberUpdatedRef
import org.readium.navigator.web.internals.util.shift
import org.readium.navigator.web.internals.webapi.Decoration
import org.readium.navigator.web.internals.webapi.DelegatingDocumentApiListener
import org.readium.navigator.web.internals.webapi.DelegatingGesturesListener
import org.readium.navigator.web.internals.webapi.DelegatingReflowableApiStateListener
import org.readium.navigator.web.internals.webapi.DelegatingSelectionListener
import org.readium.navigator.web.internals.webapi.DocumentStateApi
import org.readium.navigator.web.internals.webapi.GesturesApi
import org.readium.navigator.web.internals.webapi.ReadiumCssApi
import org.readium.navigator.web.internals.webapi.ReflowableApiStateApi
import org.readium.navigator.web.internals.webapi.ReflowableDecorationApi
import org.readium.navigator.web.internals.webapi.ReflowableMoveApi
import org.readium.navigator.web.internals.webapi.ReflowableSelectionApi
import org.readium.navigator.web.internals.webapi.SelectionListenerApi
import org.readium.navigator.web.internals.webview.RelaxedWebView
import org.readium.navigator.web.internals.webview.WebView
import org.readium.navigator.web.internals.webview.WebViewScrollController
import org.readium.navigator.web.internals.webview.evaluateJavaScriptSuspend
import org.readium.navigator.web.internals.webview.invokeOnWebViewUpToDate
import org.readium.navigator.web.internals.webview.rememberWebViewState
import org.readium.navigator.web.reflowable.ReflowableWebDecoration
import org.readium.navigator.web.reflowable.ReflowableWebDecorationCssSelectorLocation
import org.readium.navigator.web.reflowable.ReflowableWebDecorationLocation
import org.readium.navigator.web.reflowable.ReflowableWebDecorationTextQuoteLocation
import org.readium.navigator.web.reflowable.css.ReadiumCssInjector
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun ReflowableResource(
    resourceState: ReflowableResourceState,
    servedUrl: AbsoluteUrl,
    webViewClient: WebViewClient,
    backgroundColor: Color,
    padding: AbsolutePaddingValues,
    scroll: Boolean,
    orientation: Orientation,
    layoutDirection: LayoutDirection,
    readiumCssInjector: ReadiumCssInjector,
    decorationTemplates: WebDecorationTemplates,
    decorations: ImmutableMap<String, List<ReflowableWebDecoration>>,
    actionModeCallback: ActionMode.Callback?,
    onSelectionApiChanged: (ReflowableSelectionApi?) -> Unit,
    onSelectionChanged: (Boolean) -> Unit,
    onTap: (TapEvent) -> Unit,
    onLinkActivated: (AbsoluteUrl, String) -> Unit,
    onDecorationActivated: (DecorationListener.OnActivatedEvent<ReflowableWebDecorationLocation>) -> Unit,
    onLocationChange: () -> Unit,
    onDocumentResized: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        propagateMinConstraints = true
    ) {
        val webViewState = rememberWebViewState<RelaxedWebView>(
            url = servedUrl.toString()
        )

        var documentStateApi by remember(webViewState.webView) {
            mutableStateOf<DocumentStateApi?>(null)
        }

        var gesturesApi by remember(webViewState.webView) {
            mutableStateOf<GesturesApi?>(null)
        }

        var selectionListenerApi by remember(webViewState.webView) {
            mutableStateOf<SelectionListenerApi?>(null)
        }

        LaunchedEffect(webViewState.webView) {
            webViewState.webView?.let { webView ->
                gesturesApi = GesturesApi(webView)
                documentStateApi = DocumentStateApi(webView)
                selectionListenerApi = SelectionListenerApi(webView)
            }
        }

        var cssApi by remember(webViewState.webView) {
            mutableStateOf<ReadiumCssApi?>(null)
        }

        var decorationApi by remember(webViewState.webView) {
            mutableStateOf<ReflowableDecorationApi?>(null)
        }

        var selectionApi by remember(webViewState.webView) {
            mutableStateOf<ReflowableSelectionApi?>(null)
        }

        var moveApi by remember(webViewState.webView) {
            mutableStateOf<ReflowableMoveApi?>(null)
        }

        val decorations = remember(webViewState.webView) { mutableStateOf(decorations) }
            .apply { value = decorations }

        val showPlaceholder =
            remember(webViewState.webView) { mutableStateOf(true) }

        val paddingShift = DpOffset(padding.left, padding.top)

        val onSelectionApiChangedRef by rememberUpdatedRef(onSelectionApiChanged)

        val onSelectionChangedRef by rememberUpdatedRef(onSelectionChanged)

        LaunchedEffect(webViewState.webView, padding) {
            webViewState.webView?.let { webView ->
                val listener = DelegatingReflowableApiStateListener(
                    onCssApiAvailableDelegate = {
                        cssApi = ReadiumCssApi(webView)
                    },
                    onSelectionApiAvailableDelegate = {
                        selectionApi = ReflowableSelectionApi(webView) { it.shift(paddingShift) }
                        onSelectionApiChangedRef(selectionApi)
                    },
                    onDecorationApiAvailableDelegate = {
                        decorationApi = ReflowableDecorationApi(webView, decorationTemplates)
                    },
                    onMoveApiAvailableDelegate = {
                        moveApi = ReflowableMoveApi(webView)
                    }
                )
                ReflowableApiStateApi(webView, listener)
            }
        }

        LaunchedEffect(
            documentStateApi,
            webViewState.webView,
            resourceState,
            showPlaceholder,
            orientation,
            layoutDirection
        ) {
            webViewState.webView?.let { webView ->
                documentStateApi?.let { documentStateApi ->
                    documentStateApi.listener = DelegatingDocumentApiListener(
                        onDocumentLoadedAndSizedDelegate = {
                            Timber.d("resource ${resourceState.index} onDocumentLoadedAndResized")
                            webView.invokeOnWebViewUpToDate {
                                val scrollController = WebViewScrollController(webView)
                                resourceState.scrollController.value = scrollController
                                Timber.d("resource ${resourceState.index} ready to scroll")

                                when (val pending = resourceState.pendingLocation) {
                                    is ReflowableResourceLocation.HtmlId,
                                    is ReflowableResourceLocation.CssSelector,
                                    is ReflowableResourceLocation.TextAnchor,
                                    -> {
                                        // Wait for the MoveApi
                                    }
                                    is ReflowableResourceLocation.Progression -> {
                                        scrollController.moveToProgression(
                                            progression = pending.value.value,
                                            snap = !scroll,
                                            orientation = orientation,
                                            direction = layoutDirection
                                        )
                                        resourceState.acknowledgePendingLocation(
                                            location = pending,
                                            orientation = orientation,
                                            direction = layoutDirection
                                        )
                                        onLocationChange()
                                        showPlaceholder.value = false
                                    }
                                    null -> {
                                        scrollController.moveToProgression(
                                            progression = resourceState.currentProgression!!.value,
                                            snap = !scroll,
                                            orientation = orientation,
                                            direction = layoutDirection
                                        )

                                        resourceState.updateProgression(
                                            orientation = orientation,
                                            direction = layoutDirection
                                        )
                                        onLocationChange()
                                        showPlaceholder.value = false
                                    }
                                }

                                webView.setOnScrollChangeListener { view, scrollX, scrollY, oldScrollX, oldScrollY ->
                                    resourceState.updateProgression(orientation, layoutDirection)
                                    onLocationChange()
                                }
                            }
                        },
                        onDocumentResizedDelegate = {
                            Timber.d("resource ${resourceState.index} onDocumentResized")
                            resourceState.updateProgression(
                                orientation = orientation,
                                direction = layoutDirection
                            )
                            onDocumentResized.invoke()
                        }
                    )
                }
            }
        }

        val density = LocalDensity.current

        LaunchedEffect(moveApi, resourceState.scrollController.value) {
            moveApi?.let { moveApi ->
                resourceState.scrollController.value?.let { scrollController ->
                    snapshotFlow {
                        resourceState.pendingLocation
                    }.onEach { pendingLocation ->
                        pendingLocation?.let {
                            showPlaceholder.value = true

                            when (pendingLocation) {
                                is ReflowableResourceLocation.Progression -> {
                                    scrollController.moveToProgression(
                                        progression = pendingLocation.value.value,
                                        snap = !scroll,
                                        orientation = orientation,
                                        direction = layoutDirection
                                    )
                                }
                                else -> {
                                    val offset = when (pendingLocation) {
                                        is ReflowableResourceLocation.CssSelector -> {
                                            moveApi.getOffsetForLocation(
                                                progression = null,
                                                cssSelector = pendingLocation.value,
                                                orientation = orientation
                                            )
                                        }
                                        is ReflowableResourceLocation.TextAnchor -> {
                                            moveApi.getOffsetForLocation(
                                                progression = null,
                                                textAnchor = pendingLocation.value,
                                                orientation = orientation
                                            )
                                        }
                                        is ReflowableResourceLocation.HtmlId -> {
                                            moveApi.getOffsetForLocation(
                                                progression = null,
                                                htmlId = pendingLocation.value,
                                                orientation = orientation
                                            )
                                        }
                                    }
                                    offset?.let { offset ->
                                        scrollController.moveToOffset(
                                            offset = with(density) { offset.dp.roundToPx() },
                                            snap = !scroll,
                                            orientation = orientation,
                                        )
                                    }
                                }
                            }
                            resourceState.acknowledgePendingLocation(
                                location = pendingLocation,
                                orientation = orientation,
                                direction = layoutDirection
                            )
                            onLocationChange()
                            showPlaceholder.value = false
                        }
                    }.launchIn(this)
                }
            }
        }

        LaunchedEffect(gesturesApi, selectionListenerApi, onTap, onLinkActivated, padding, orientation, webViewState.webView) {
            gesturesApi?.let { gesturesApi ->
                selectionListenerApi?.let { selectionListenerApi ->
                    val webView = webViewState.webView
                    val scope = this
                    var isSelecting = false

                    // In paginated mode, freeze the scroll while a text selection is active so that
                    // dragging a selection handle near a column boundary is not interpreted as a page
                    // turn (which over-scrolls the viewport back to the chapter start and expands the
                    // selection there). See https://github.com/readium/kotlin-toolkit/issues/325
                    val horizontal = orientation == Orientation.Horizontal

                    selectionListenerApi.listener = DelegatingSelectionListener(
                        onSelectionStartDelegate = {
                            Log.d("SelectionFix", "onSelectionStart")
                            isSelecting = true
                            webView?.freezeScroll = horizontal
                            onSelectionChangedRef(true)
                        },
                        onSelectionEndDelegate = {
                            Log.d("SelectionFix", "onSelectionEnd")
                            isSelecting = false
                            webView?.freezeScroll = false
                            onSelectionChangedRef(false)
                        }
                    )

                    gesturesApi.listener = DelegatingGesturesListener(
                        onTapDelegate = { offset ->
                            // There's an on-going selection, the tap will dismiss it so we don't forward it.
                            if (isSelecting) {
                                return@DelegatingGesturesListener
                            }

                            val shiftedOffset = offset + paddingShift
                            onTap(TapEvent(shiftedOffset))
                        },
                        onLinkActivatedDelegate = onLinkActivated,
                        onDecorationActivatedDelegate = { id, group, rect, offset ->
                            val decoration = decorations.value[group]?.firstOrNull { it.id.value == id }
                                ?: return@DelegatingGesturesListener

                            val event = DecorationListener.OnActivatedEvent(
                                decoration = decoration,
                                group = group,
                                rect = rect.shift(paddingShift),
                                offset = offset + paddingShift
                            )
                            onDecorationActivated(event)
                        }
                    )
                }
            }
        }

        LaunchedEffect(decorationApi, decorations) {
            decorationApi?.let { decorationApi ->
                var lastDecorations = emptyMap<String, List<ReflowableWebDecoration>>()
                snapshotFlow { decorations.value }
                    .onEach {
                        val oldAndUpdatesGroups = it.keys + lastDecorations.keys
                        for (group in oldAndUpdatesGroups) {
                            val updatedInGroup = it[group].orEmpty()
                            val lastInGroup = lastDecorations[group].orEmpty()
                            for ((_, changes) in lastInGroup.changesByHref(updatedInGroup)) {
                                for (change in changes) {
                                    when (change) {
                                        is DecorationChange.Added -> {
                                            val template = decorationTemplates[change.decoration.style::class]
                                                ?: continue
                                            decorationApi.addDecoration(change.decoration.toWebApiDecoration(template), group)
                                        }
                                        is DecorationChange.Moved -> {}
                                        is DecorationChange.Removed -> {
                                            decorationApi.removeDecoration(change.id, group)
                                        }
                                        is DecorationChange.Updated -> {
                                            decorationApi.removeDecoration(change.decoration.id, group)
                                            val template = decorationTemplates[change.decoration.style::class]
                                                ?: continue
                                            decorationApi.addDecoration(change.decoration.toWebApiDecoration(template), group)
                                        }
                                    }
                                }
                            }
                        }

                        lastDecorations = it
                    }.launchIn(this)
            }
        }

        LaunchedEffect(cssApi, readiumCssInjector) {
            val cssProperties = readiumCssInjector.userProperties.toCssProperties() +
                readiumCssInjector.rsProperties.toCssProperties()
            cssApi?.setProperties(cssProperties)
            // FIXME: resource is laid out again, so we should apply progression again
        }

        // Hide content before initial position is settled
        if (showPlaceholder.value) {
            Box(
                modifier = Modifier
                    .background(backgroundColor)
                    .zIndex(1f)
                    .fillMaxSize(),
                content = {}
            )
        }

        LaunchedEffect(webViewState.webView, actionModeCallback) {
            webViewState.webView?.let { wv ->
                wv.setCustomSelectionActionModeCallback(actionModeCallback)
            }
        }

        val orientationRef by rememberUpdatedRef(orientation)

        // Recreate WebView when Readium CSS layout changes because injected stuff depends on it
        key(readiumCssInjector.layout) {
            WebView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .absolutePadding(padding),
                state = webViewState,
                factory = { RelaxedWebView(it) },
                client = webViewClient,
                onCreated = { webview ->
                    webview.settings.javaScriptEnabled = true
                    webview.settings.setSupportZoom(false)
                    webview.settings.builtInZoomControls = false
                    webview.settings.displayZoomControls = false
                    webview.settings.loadWithOverviewMode = true
                    webview.settings.useWideViewPort = true
                    webview.isVerticalScrollBarEnabled = false
                    webview.isHorizontalScrollBarEnabled = false
                    webview.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    // Enable long press text selection only in scroll mode. In paginated (CSS
                    // multi-column) mode, cross-column selection "flickers", so long-press selection
                    // is disabled there; programmatic selection APIs are unaffected.
                    webview.isLongClickable = scroll
                    // Prevents vertical scrolling towards blank space.
                    // See https://github.com/readium/readium-css/issues/158
                    webview.setOnTouchListener { view, event ->
                        orientationRef == Orientation.Horizontal &&
                            event.action == MotionEvent.ACTION_MOVE
                    }
                },
                onDispose = {
                    resourceState.scrollController.value = null
                    Timber.d("resource ${resourceState.index} disposed")
                }
            )
        }
    }
}

/**
 * Returns the bounding rect of the current text selection in this view's coordinates, or null when
 * there is no non-collapsed selection. The rect is used to position the floating selection menu when
 * it is re-shown manually after a selection "flicker".
 */
private suspend fun RelaxedWebView.selectionContentRect(): Rect? {
    val result = evaluateJavaScriptSuspend(
        """
        (function() {
            var s = window.getSelection();
            if (!s || s.rangeCount === 0 || s.isCollapsed) return '';
            var r = s.getRangeAt(0).getBoundingClientRect();
            var zoom = document.body.currentCSSZoom || 1;
            return [r.left / zoom, r.top / zoom, r.right / zoom, r.bottom / zoom].join(',');
        })()
        """.trimIndent()
    )
    // evaluateJavascript returns the string JSON-encoded, e.g. "\"12.0,34.0,56.0,78.0\"".
    val parts = result.trim().removeSurrounding("\"").split(',')
    if (parts.size != 4) return null
    val numbers = parts.mapNotNull { it.toDoubleOrNull() }
    if (numbers.size != 4) return null
    val density = context.resources.displayMetrics.density
    return Rect(
        (numbers[0] * density).roundToInt(),
        (numbers[1] * density).roundToInt(),
        (numbers[2] * density).roundToInt(),
        (numbers[3] * density).roundToInt(),
    )
}

private fun ReflowableWebDecoration.toWebApiDecoration(
    template: WebDecorationTemplate,
): Decoration {
    val element = template.element(style)
    val cssSelector = when (location) {
        is ReflowableWebDecorationCssSelectorLocation ->
            (location as ReflowableWebDecorationCssSelectorLocation).cssSelector
        is ReflowableWebDecorationTextQuoteLocation ->
            (location as ReflowableWebDecorationTextQuoteLocation).cssSelector
    }
    val textQuote = (location as? ReflowableWebDecorationTextQuoteLocation)?.textQuote

    return Decoration(
        id = id,
        style = style,
        element = element,
        cssSelector = cssSelector,
        textQuote = textQuote
    )
}
