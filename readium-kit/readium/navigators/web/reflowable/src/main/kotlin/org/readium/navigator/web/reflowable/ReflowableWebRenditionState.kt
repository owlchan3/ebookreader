/*
 * Copyright 2025 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.navigator.web.reflowable

import android.app.Application
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.navigator.common.DecorationController
import org.readium.navigator.common.HtmlId
import org.readium.navigator.common.NavigationController
import org.readium.navigator.common.Overflow
import org.readium.navigator.common.OverflowController
import org.readium.navigator.common.PreferencesController
import org.readium.navigator.common.Progression
import org.readium.navigator.common.RenditionState
import org.readium.navigator.common.Selection
import org.readium.navigator.common.SelectionController
import org.readium.navigator.common.SimpleOverflow
import org.readium.navigator.common.TextAnchor
import org.readium.navigator.common.TextQuote
import org.readium.navigator.web.common.FontFamilyDeclaration
import org.readium.navigator.web.common.WebDecorationTemplates
import org.readium.navigator.web.internals.pager.RenditionScrollState
import org.readium.navigator.web.internals.server.WebViewClient
import org.readium.navigator.web.internals.server.WebViewServer
import org.readium.navigator.web.internals.server.WebViewServer.Companion.assetsBaseHref
import org.readium.navigator.web.internals.util.AbsolutePaddingValues
import org.readium.navigator.web.internals.util.HyperlinkProcessor
import org.readium.navigator.web.internals.util.toLayoutDirection
import org.readium.navigator.web.internals.util.toOrientation
import org.readium.navigator.web.internals.webapi.ReflowableSelectionApi
import org.readium.navigator.web.internals.webview.WebViewScrollController
import org.readium.navigator.web.reflowable.css.ReadiumCssInjector
import org.readium.navigator.web.reflowable.css.RsProperties
import org.readium.navigator.web.reflowable.css.UserProperties
import org.readium.navigator.web.reflowable.css.withLayout
import org.readium.navigator.web.reflowable.css.withSettings
import org.readium.navigator.web.reflowable.injection.injectHtmlReflowable
import org.readium.navigator.web.reflowable.layout.LayoutConstants
import org.readium.navigator.web.reflowable.layout.LayoutResolver
import org.readium.navigator.web.reflowable.preferences.ReflowableWebDefaults
import org.readium.navigator.web.reflowable.preferences.ReflowableWebPreferences
import org.readium.navigator.web.reflowable.preferences.ReflowableWebSettings
import org.readium.navigator.web.reflowable.preferences.ReflowableWebSettingsResolver
import org.readium.navigator.web.reflowable.resource.ReflowableResourceLocation
import org.readium.navigator.web.reflowable.resource.ReflowableResourceState
import org.readium.navigator.web.reflowable.resource.ReflowableWebViewport
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

/**
 * State holder for the rendition of a reflowable Web publication.
 *
 * You can interact with it mainly through its [controller] witch will be available as soon
 * as the first layout has completed.
 */
@ExperimentalReadiumApi
@Stable
public class ReflowableWebRenditionState internal constructor(
    application: Application,
    internal val publication: ReflowableWebPublication,
    initialPreferences: ReflowableWebPreferences,
    initialLocation: ReflowableWebGoLocation,
    configuration: ReflowableWebConfiguration,
    disableSelection: Boolean,
) : RenditionState<ReflowableWebRenditionController> {

    private val controllerState: MutableState<ReflowableWebRenditionController?> =
        mutableStateOf(null)

    override val controller: ReflowableWebRenditionController? by controllerState

    private val indexedInitialLocation: IndexedGoLocation = initialLocation
        .let { location ->
            publication.readingOrder.indexOfHref(location.href)
                ?.let { IndexedGoLocation(it, location) }
                ?: IndexedGoLocation(0, ReflowableWebGoLocation(href = publication.readingOrder[0].href))
        }

    internal val pagerState: PagerState =
        PagerState(
            currentPage = indexedInitialLocation.index,
            pageCount = { publication.readingOrder.size }
        )

    internal val resourceStates: List<ReflowableResourceState> =
        publication.getResourceLocations(
            destinationIndex = indexedInitialLocation.index,
            destinationLocation = indexedInitialLocation.location.toResourceLocation()
        ).zip(publication.readingOrder.items)
            .mapIndexed { index, (location, item) ->
                ReflowableResourceState(
                    index = index,
                    href = item.href,
                    initialLocation = location
                )
            }

    private val fontFamilyDeclarations: List<FontFamilyDeclaration> =
        buildList {
            addAll(configuration.fontFamilyDeclarations.declarations)
            add(
                FontFamilyDeclaration(
                    fontFamily = FontFamily.OPEN_DYSLEXIC.name,
                    alternates = persistentListOf()
                ) {
                    addFontFace {
                        addSource("readium/fonts/OpenDyslexic-Regular.otf")
                    }
                }
            )
        }

    internal val layoutDelegate: ReflowableLayoutDelegate =
        ReflowableLayoutDelegate(
            fontFamilyDeclarations = fontFamilyDeclarations,
            publication = publication,
            initialPreferences = initialPreferences,
            defaults = configuration.defaults
        )

    internal val scrollState: RenditionScrollState =
        RenditionScrollState(
            pagerState = pagerState,
            pageStates = resourceStates,
            overflow = layoutDelegate.overflow,
        )

    internal val selectionDelegate: ReflowableSelectionDelegate =
        ReflowableSelectionDelegate(
            publication = publication,
            pagerState = pagerState
        )

    internal val decorationDelegate: ReflowableDecorationDelegate =
        ReflowableDecorationDelegate(configuration.decorationTemplates)

    internal val hyperlinkProcessor =
        HyperlinkProcessor(publication.container)

    private val webViewServer: WebViewServer = run {
        val htmlInjector: (Resource, MediaType) -> Resource = { resource, mediaType ->
            resource.injectHtmlReflowable(
                charset = mediaType.charset,
                readiumCss = layoutDelegate.readiumCssInjector,
                injectableScript = RelativeUrl("readium/navigator/web/internals/generated/reflowable-injectable-script.js")!!,
                assetsBaseHref = assetsBaseHref,
                disableSelection = disableSelection
            )
        }

        WebViewServer(
            application = application,
            container = publication.container,
            mediaTypes = publication.mediaTypes,
            baseUrl = publication.baseUrl,
            errorPage = RelativeUrl("readium/navigator/web/internals/error.xhtml")!!,
            htmlInjector = htmlInjector,
            servedAssets = configuration.servedAssets + listOf("readium/.*"),
            onResourceLoadFailed = { _, _ -> } // TODO: pass errors to the app
        )
    }

    internal val webViewClient: WebViewClient =
        WebViewClient(webViewServer)

    internal fun servedUrlToHref(url: AbsoluteUrl) =
        webViewServer.servedUrlToHref(url)

    internal fun hrefToServedUrl(href: Url) =
        webViewServer.hrefToServedUrl(href)

    internal lateinit var navigationDelegate: ReflowableNavigationDelegate

    internal fun updateLocation() {
        val location = computeLocation() ?: return
        val viewport = computeViewport() ?: return
        if (!::navigationDelegate.isInitialized) {
            initController(location, viewport)
        } else {
            navigationDelegate.updateLocation(location, viewport)
        }
    }

    private fun computeLocation(): ReflowableWebLocation? {
        val currentIndex = pagerState.currentPage
        val currentItem = publication.readingOrder.items[currentIndex]
        val progression = resourceStates[currentIndex].progressionRange?.start ?: return null
        val position = publication.positionForProgression(currentIndex, progression)
        return ReflowableWebLocation(
            href = currentItem.href,
            mediaType = currentItem.mediaType,
            progression = progression,
            position = position,
            totalProgression = publication.totalProgressionForPosition(position)
        )
    }

    private fun computeViewport(): ReflowableWebViewport? {
        val indexedVisibleItems = pagerState.layoutInfo.visiblePagesInfo
            .map { it.index to publication.readingOrder[it.index] }

        check(indexedVisibleItems.isNotEmpty())

        val progressions = indexedVisibleItems
            .mapNotNull { (index, _) -> resourceStates[index].progressionRange }

        // Have all visible items already set a progressionRange?
        if (progressions.size != indexedVisibleItems.size) {
            return null
        }

        val startPosition = publication
            .positionForProgression(indexedVisibleItems.first().first, progressions.first().start)

        val endPosition = publication
            .positionForProgression(indexedVisibleItems.last().first, progressions.last().endInclusive)

        return ReflowableWebViewport(
            readingOrder = indexedVisibleItems.map { it.second.href },
            progressions = indexedVisibleItems.zip(progressions)
                .associate { (indexedItem, progression) -> indexedItem.second.href to progression },
            positions = startPosition..endPosition
        )
    }

    internal fun initController(location: ReflowableWebLocation, viewport: ReflowableWebViewport) {
        navigationDelegate =
            ReflowableNavigationDelegate(
                publication,
                resourceStates,
                pagerState,
                layoutDelegate.overflow,
                location,
                viewport
            )
        controllerState.value =
            ReflowableWebRenditionController(
                navigationDelegate,
                layoutDelegate,
                decorationDelegate,
                selectionDelegate
            )
    }
}

@ExperimentalReadiumApi
@Stable
public class ReflowableWebRenditionController internal constructor(
    internal val navigationDelegate: ReflowableNavigationDelegate,
    private val layoutDelegate: ReflowableLayoutDelegate,
    decorationDelegate: ReflowableDecorationDelegate,
    private val selectionDelegate: ReflowableSelectionDelegate,
) : NavigationController<ReflowableWebLocation, ReflowableWebGoLocation> by navigationDelegate,
    OverflowController by navigationDelegate,
    PreferencesController<ReflowableWebPreferences, ReflowableWebSettings> by layoutDelegate,
    DecorationController<ReflowableWebDecorationLocation> by decorationDelegate,
    SelectionController<ReflowableWebSelectionLocation> by selectionDelegate {

    public val viewport: ReflowableWebViewport get() =
        navigationDelegate.viewport

    /** 平滑滚动到当前章内的 [progression]（0..1），用于听书自动跟随，不触发导航锁/顶层对齐。 */
    public fun smoothScrollTo(progression: Double): Unit =
        navigationDelegate.smoothScrollTo(progression)

    /** 计算当前章节内一段文字 [textAnchor] 的渲染进度（0..1），供听书定位/翻页。 */
    public suspend fun getProgressionForTextAnchor(textAnchor: TextAnchor): Double? =
        navigationDelegate.getProgressionForTextAnchor(textAnchor)

    /** 将当前选区的起点扩展到 [offset]（Box 坐标），返回更新后的选区与手柄矩形。 */
    public suspend fun extendSelectionStart(offset: DpOffset): ReflowableSelectionGeometry? =
        selectionDelegate.extendStart(offset)

    /** 将当前选区的终点扩展到 [offset]（Box 坐标），返回更新后的选区与手柄矩形。 */
    public suspend fun extendSelectionEnd(offset: DpOffset): ReflowableSelectionGeometry? =
        selectionDelegate.extendEnd(offset)

    /** 当前选区起点 caret 的矩形（Box 坐标），用于绘制起点手柄。 */
    public suspend fun getSelectionStartHandleRect(): DpRect? =
        selectionDelegate.getStartHandleRect()

    /** 当前选区终点 caret 的矩形（Box 坐标），用于绘制终点手柄。 */
    public suspend fun getSelectionEndHandleRect(): DpRect? =
        selectionDelegate.getEndHandleRect()
}

@OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)
internal class ReflowableLayoutDelegate(
    fontFamilyDeclarations: List<FontFamilyDeclaration>,
    publication: ReflowableWebPublication,
    initialPreferences: ReflowableWebPreferences,
    defaults: ReflowableWebDefaults,
) : PreferencesController<ReflowableWebPreferences, ReflowableWebSettings> {

    private val layoutResolver =
        LayoutResolver(
            baseMinMargins = LayoutConstants.baseMinMargins,
            baseMinLineLength = LayoutConstants.baseMinLineLength,
            baseOptimalLineLength = LayoutConstants.baseOptimalLineLength,
            baseMaxLineLength = LayoutConstants.baseMaxLineLength
        )

    private val settingsResolver =
        ReflowableWebSettingsResolver(
            metadata = publication.metadata,
            defaults = defaults
        )

    internal var viewportSize: DpSize? by mutableStateOf(null)

    internal var safeDrawing: AbsolutePaddingValues? by mutableStateOf(null)

    internal var fontScale: Float? by mutableStateOf(null)

    override var preferences: ReflowableWebPreferences by mutableStateOf(initialPreferences)

    override val settings: ReflowableWebSettings by derivedStateOf {
        settingsResolver.settings(preferences)
    }

    internal val overflow: State<Overflow> = derivedStateOf {
        with(settings) {
            SimpleOverflow(
                readingProgression = readingProgression,
                scroll = scroll,
                axis = if (scroll && !verticalText) Axis.VERTICAL else Axis.HORIZONTAL
            )
        }
    }

    internal val readiumCssInjector: ReadiumCssInjector by derivedStateOf {
        ReadiumCssInjector(
            assetsBaseHref = assetsBaseHref,
            readiumCssAssets = RelativeUrl("readium/navigator/web/internals/generated/readium-css/")!!,
            rsProperties = RsProperties(disableVerticalPagination = true),
            userProperties = UserProperties(),
            googleFonts = emptyList(),
            fontFamilyDeclarations = fontFamilyDeclarations
        ).withSettings(
            settings = settings,
        ).let { injector ->
            if (viewportSize == null || safeDrawing == null || fontScale == null) {
                injector
            } else {
                injector.withLayout(
                    fontSize = settings.fontSize,
                    verticalText = settings.verticalText,
                    safeDrawing = safeDrawing!!,
                    layout = layoutResolver.layout(
                        settings = settings,
                        systemFontScale = fontScale!!,
                        viewportSize = viewportSize!!,
                        safeDrawing = safeDrawing!!
                    )
                )
            }
        }
    }
}

internal val Overflow.orientation: Orientation get() =
    axis.toOrientation()

@OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)
internal class ReflowableNavigationDelegate(
    private val publication: ReflowableWebPublication,
    private val resourceStates: List<ReflowableResourceState>,
    private val pagerState: PagerState,
    overflowState: State<Overflow>,
    initialLocation: ReflowableWebLocation,
    initialViewport: ReflowableWebViewport,
) : NavigationController<ReflowableWebLocation, ReflowableWebGoLocation>, OverflowController {

    private val navigatorMutex: MutatorMutex =
        MutatorMutex()

    private val locationMutable: MutableState<ReflowableWebLocation> =
        mutableStateOf(initialLocation)

    private val viewportMutable: MutableState<ReflowableWebViewport> =
        mutableStateOf(initialViewport)

    fun updateLocation(location: ReflowableWebLocation, viewport: ReflowableWebViewport) {
        locationMutable.value = location
        viewportMutable.value = viewport
    }

    /** 平滑滚动到当前资源内的 [progression]（0..1），逐帧 scrollTo，不走导航锁。 */
    fun smoothScrollTo(progression: Double) {
        val resourceState = resourceStates[pagerState.currentPage]
        val scrollController = resourceState.scrollController.value ?: return
        scrollController.smoothMoveToProgression(
            progression = progression,
            orientation = overflow.axis.toOrientation(),
            direction = overflow.readingProgression.toLayoutDirection(),
        )
    }

    /** 计算当前资源内一段文字 [textAnchor] 的渲染进度（0..1），供听书定位/翻页。 */
    suspend fun getProgressionForTextAnchor(textAnchor: TextAnchor): Double? {
        val scrollController = resourceStates[pagerState.currentPage].scrollController.value ?: return null
        return scrollController.getProgressionForTextAnchor(
            textAnchor = textAnchor,
            orientation = overflow.axis.toOrientation(),
            direction = overflow.readingProgression.toLayoutDirection(),
        )
    }

    override val overflow: Overflow by overflowState

    override val location: ReflowableWebLocation by locationMutable

    val viewport: ReflowableWebViewport by viewportMutable

    override suspend fun goTo(url: Url) {
        val location = ReflowableWebGoLocation(
            href = url.removeFragment(),
            htmlId = url.fragment?.let { HtmlId(it) }
        )
        goTo(location)
    }

    override suspend fun goTo(location: ReflowableWebLocation) {
        goTo(ReflowableWebGoLocation(location.href, location.progression))
    }

    override suspend fun goTo(location: ReflowableWebGoLocation) {
        coroutineScope {
            navigatorMutex.mutateWith(
                receiver = this,
                priority = MutatePriority.UserInput
            ) {
                withContext(Dispatchers.Main) {
                    val destIndex = publication.readingOrder.indexOfHref(location.href)
                        ?: return@withContext

                    val destLocation = location.toResourceLocation()
                    val resourceLocations = publication.getResourceLocations(destIndex, destLocation)

                    fun cleanUp() {
                        resourceStates.zip(resourceLocations)
                            .forEach { (state, location) ->
                                state.cancelPendingLocation(location)
                            }
                    }

                    try {
                        pagerState.scrollToPage(destIndex)

                        // 导航超时兜底：目标章节 WebView 若迟迟不回调「加载完成」（如新导入书末章），
                        // suspendCancellableCoroutine 永不返回、navigatorMutex 被永久占用，导致后续翻页全部失效。
                        // 超时后取消等待（cleanUp 会清理 pending 并释放 mutex），用户仍停留在已滚动的目标页，
                        // 且可通过 moveForward/moveBackward 回退到相邻章节脱离卡死。
                        withTimeoutOrNull(5000L) {
                            suspendCancellableCoroutine { continuation ->
                                continuation.invokeOnCancellation {
                                    cleanUp()
                                }
                                resourceStates.zip(resourceLocations)
                                    .forEach { (state, location) ->
                                        state.go(
                                            location = location,
                                            continuation = continuation.takeIf { state === resourceStates[destIndex] }
                                        )
                                    }
                            }
                        }
                    } catch (e: Exception) { // Mainly for CancellationException
                        cleanUp()
                        throw e
                    }
                }
            }
        }
    }

    // This information is not available when the WebView has not yet been composed or laid out.
    // We assume that the best UI behavior would be to have a possible forward button disabled
    // and return false when we can't tell.
    // FIXME: should probably be observable.
    override val canMoveForward: Boolean
        get() = pagerState.currentPage < publication.readingOrder.items.size - 1 || run {
            val currentResourceState = resourceStates[pagerState.currentPage]
            val scrollController = currentResourceState.scrollController.value ?: return false
            return scrollController.canMoveForward()
        }

    override val canMoveBackward: Boolean
        get() = pagerState.currentPage > 0 || run {
            val currentResourceState = resourceStates[0]
            val scrollController = currentResourceState.scrollController.value ?: return false
            return scrollController.canMoveBackward()
        }

    override suspend fun moveForward() {
        coroutineScope {
            navigatorMutex.tryMutate {
                val currentResourceState = resourceStates[pagerState.currentPage]
                val scrollController = currentResourceState.scrollController.value
                if (scrollController != null && scrollController.canMoveForward()) {
                    scrollController.moveForward()
                } else if (pagerState.currentPage < publication.readingOrder.items.size - 1) {
                    // scrollController 为空（当前章节资源尚未就绪）时仍允许跳到下一章，
                    // 避免跳转后翻页被永久禁用。
                    pagerState.scrollToPage(pagerState.currentPage + 1)
                }
            }
        }
    }

    override suspend fun moveBackward() {
        coroutineScope {
            navigatorMutex.tryMutate {
                val currentResourceState = resourceStates[pagerState.currentPage]
                val scrollController = currentResourceState.scrollController.value
                if (scrollController != null && scrollController.canMoveBackward()) {
                    scrollController.moveBackward()
                } else if (pagerState.currentPage > 0) {
                    // scrollController 为空（当前章节资源尚未就绪）时仍允许跳到上一章，
                    // 避免跳转后翻页被永久禁用。
                    pagerState.scrollToPage(pagerState.currentPage - 1)
                }
            }
        }
    }

    private fun WebViewScrollController.moveForward() =
        moveForward(
            orientation = overflow.axis.toOrientation(),
            direction = overflow.readingProgression.toLayoutDirection()
        )

    private fun WebViewScrollController.moveBackward() =
        moveBackward(
            orientation = overflow.axis.toOrientation(),
            direction = overflow.readingProgression.toLayoutDirection()
        )

    private fun WebViewScrollController.canMoveForward(): Boolean =
        canMoveForward(
            orientation = overflow.axis.toOrientation(),
            direction = overflow.readingProgression.toLayoutDirection()
        )
    private fun WebViewScrollController.canMoveBackward(): Boolean =
        canMoveBackward(
            orientation = overflow.axis.toOrientation(),
            direction = overflow.readingProgression.toLayoutDirection()
        )
}

internal class ReflowableDecorationDelegate(
    val decorationTemplates: WebDecorationTemplates,
) : DecorationController<ReflowableWebDecorationLocation> {

    override var decorations: PersistentMap<String, PersistentList<ReflowableWebDecoration>> by
        mutableStateOf(persistentMapOf())
}

/** The result of extending a selection: the updated selection plus its precise handle caret rects. */
public data class ReflowableSelectionGeometry(
    public val selection: Selection<ReflowableWebSelectionLocation>,
    public val startHandleRect: DpRect,
    public val endHandleRect: DpRect,
    public val selectionRects: List<DpRect>,
)

internal class ReflowableSelectionDelegate(
    private val publication: ReflowableWebPublication,
    private val pagerState: PagerState,
) : SelectionController<ReflowableWebSelectionLocation> {

    val selectionApis: SnapshotStateMap<Int, ReflowableSelectionApi?> =
        mutableStateMapOf()

    override suspend fun currentSelection(): Selection<ReflowableWebSelectionLocation>? {
        val (index, selection) = withVisibleSelectionApi { it.getCurrentSelection() } ?: return null
        return buildSelection(index, selection)
    }

    suspend fun extendStart(offset: DpOffset): ReflowableSelectionGeometry? {
        val (index, selection) = withVisibleSelectionApi { it.extendStart(offset) } ?: return null
        return ReflowableSelectionGeometry(
            selection = buildSelection(index, selection),
            startHandleRect = selection.startHandleRect ?: selection.selectionRect,
            endHandleRect = selection.endHandleRect ?: selection.selectionRect,
            selectionRects = selection.selectionRects,
        )
    }

    suspend fun extendEnd(offset: DpOffset): ReflowableSelectionGeometry? {
        val (index, selection) = withVisibleSelectionApi { it.extendEnd(offset) } ?: return null
        return ReflowableSelectionGeometry(
            selection = buildSelection(index, selection),
            startHandleRect = selection.startHandleRect ?: selection.selectionRect,
            endHandleRect = selection.endHandleRect ?: selection.selectionRect,
            selectionRects = selection.selectionRects,
        )
    }

    suspend fun getStartHandleRect(): DpRect? =
        withVisibleSelectionApi { it.getStartHandleRect() }?.second

    suspend fun getEndHandleRect(): DpRect? =
        withVisibleSelectionApi { it.getEndHandleRect() }?.second

    private suspend fun <T> withVisibleSelectionApi(
        transform: suspend (ReflowableSelectionApi) -> T?,
    ): Pair<Int, T>? {
        val visiblePages = pagerState.layoutInfo.visiblePagesInfo.map { it.index }
        for (index in visiblePages) {
            val api = selectionApis[index] ?: continue
            val result = transform(api) ?: continue
            return index to result
        }
        return null
    }

    private fun buildSelection(
        index: Int,
        selection: org.readium.navigator.web.internals.webapi.Selection,
    ): Selection<ReflowableWebSelectionLocation> {
        val selectionItem = publication.readingOrder.items[index]
        return Selection(
            selection.selectedText,
            selection.selectionRect,
            ReflowableWebSelectionLocation(
                href = selectionItem.href,
                mediaType = selectionItem.mediaType,
                selectedText = selection.selectedText,
                textQuote = TextQuote(
                    text = selection.selectedText,
                    prefix = selection.textBefore,
                    suffix = selection.textAfter
                )
            )
        )
    }

    override fun clearSelection() {
        for (api in selectionApis.values) {
            api?.clearSelection()
        }
    }
}

private data class IndexedGoLocation(
    val index: Int,
    val location: ReflowableWebGoLocation,
)

private fun ReflowableWebGoLocation.toResourceLocation() =
    textAnchor?.let { ReflowableResourceLocation.TextAnchor(it) }
        ?: cssSelector?.let { ReflowableResourceLocation.CssSelector(it) }
        ?: htmlId?.let { ReflowableResourceLocation.HtmlId(it) }
        ?: ReflowableResourceLocation.Progression(progression ?: Progression(0.0)!!)

private fun ReflowableWebPublication.getResourceLocations(
    destinationIndex: Int,
    destinationLocation: ReflowableResourceLocation,
): List<ReflowableResourceLocation> {
    return List(readingOrder.items.size) { index ->
        when {
            index < destinationIndex ->
                ReflowableResourceLocation.Progression(Progression(1.0)!!)
            index > destinationIndex ->
                ReflowableResourceLocation.Progression(Progression(0.0)!!)
            else ->
                destinationLocation
        }
    }
}
