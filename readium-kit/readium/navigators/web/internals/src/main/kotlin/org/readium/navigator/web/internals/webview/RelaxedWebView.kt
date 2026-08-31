/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.web.internals.webview

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.View
import android.webkit.WebView

/**
 * WebView allowing access to some protected fields.
 */
public class RelaxedWebView(context: Context) : WebView(context) {

    public val maxScrollX: Int get() =
        horizontalScrollRange - horizontalScrollExtent

    public val maxScrollY: Int get() =
        verticalScrollRange - verticalScrollExtent

    public val canScrollRight: Boolean get() =
        scrollX < maxScrollX

    public val canScrollLeft: Boolean get() =
        scrollX > 0

    public val canScrollTop: Boolean get() =
        scrollY > 0

    public val canScrollBottom: Boolean get() =
        scrollY < maxScrollY

    public val verticalScrollRange: Int get() =
        computeVerticalScrollRange()

    public val horizontalScrollRange: Int get() =
        computeHorizontalScrollRange()

    public val verticalScrollExtent: Int get() =
        computeVerticalScrollExtent()

    public val horizontalScrollExtent: Int get() =
        computeHorizontalScrollExtent()


    private var nextLayoutListener: (() -> Unit) = {}

    public fun setNextLayoutListener(block: () -> Unit) {
        nextLayoutListener = block
    }

    private var actionModeCallback: ActionMode.Callback? = null

    public fun setCustomSelectionActionModeCallback(
        callback: ActionMode.Callback?,
    ) {
        actionModeCallback = callback
    }

    /**
     * The text-selection action mode currently shown, whether it was started by the WebView itself
     * or re-triggered manually after a selection "flicker".
     */
    private var selectionMode: ActionMode? = null

    /**
     * When `true`, the WebView refuses to over-scroll its viewport. Used in paginated mode while a
     * text selection is active: dragging a selection handle near the viewport edge makes the Android
     * WebView over-scroll back to the start of the chapter, which expands the selection there.
     * Freezing the scroll keeps the selection anchored to the current page. This is the same
     * workaround as the legacy navigator's `onOverScrolled` suppression.
     * See https://github.com/readium/kotlin-toolkit/issues/325
     */
    public var freezeScroll: Boolean = false

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        if (freezeScroll) {
            return
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    /**
     * Invoked whenever the text-selection action mode is destroyed, whether by the user dismissing it
     * or by Chromium dropping it during a CSS-column "flicker". Lets the caller re-show the menu when
     * the selection is still active.
     */
    private var onSelectionModeDestroyedListener: (() -> Unit)? = null

    public fun setOnSelectionModeDestroyedListener(listener: (() -> Unit)?) {
        onSelectionModeDestroyedListener = listener
    }

    @Suppress("Deprecation")
    @Deprecated("Deprecated in Java")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        nextLayoutListener.invoke()
        nextLayoutListener = {}
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? {
        return startActionMode(callback, ActionMode.TYPE_PRIMARY)
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val wrapper = Callback2Wrapper(
            callback = actionModeCallback ?: callback,
            callback2 = callback as? ActionMode.Callback2,
            onDestroyed = {
                selectionMode = null
                onSelectionModeDestroyedListener?.invoke()
            },
        )

        val mode = if (actionModeCallback == null) {
            super.startActionMode(wrapper, type)
        } else {
            val parent = parent ?: return null
            parent.startActionModeForChild(this, wrapper, type)
        }
        selectionMode = mode
        return mode
    }

    /**
     * Re-shows the text-selection floating menu after it was dismissed by a selection "flicker"
     * (the selection transiently collapsing and re-expanding while a handle is dragged across a CSS
     * column boundary). Chromium fails to re-show the menu in that case, so we start it manually.
     *
     * @param contentRect the selection's bounding rect in this view's coordinates, used to position
     * the floating toolbar; may be null to fall back to the whole view.
     */
    public fun isSelectionActionModeActive(): Boolean = selectionMode != null

    /**
     * Re-queries the selection's content rect and repositions the floating toolbar. Used to recover
     * the menu after a CSS-column "flicker" leaves it positioned off-screen while the selection is
     * still active: Chromium re-runs `onGetContentRect` and moves the toolbar back over the selection.
     */
    public fun invalidateSelectionActionMode() {
        selectionMode?.invalidateContentRect()
    }

    public fun showSelectionActionMode(contentRect: Rect?): ActionMode? {
        val callback = actionModeCallback
        Log.d("SelectionFix", "show: callback=${callback != null} selectionMode=${selectionMode != null} rect=$contentRect parent=${parent != null}")
        if (callback == null) return null
        selectionMode?.let { return it }
        val wrapper = Callback2Wrapper(
            callback = callback,
            callback2 = null,
            contentRect = contentRect,
            onDestroyed = {
                selectionMode = null
                onSelectionModeDestroyedListener?.invoke()
            },
        )
        val parent = parent ?: return null
        val mode = parent.startActionModeForChild(this, wrapper, ActionMode.TYPE_FLOATING)
        selectionMode = mode
        Log.d("SelectionFix", "show: result mode=${mode != null}")
        return mode
    }

    /**
     * Finishes a manually re-shown selection menu. Unlike a menu started by the WebView itself, this
     * one is not tracked by Chromium, so it has to be dismissed explicitly when the selection ends.
     */
    public fun finishSelectionActionMode() {
        Log.d("SelectionFix", "finish: selectionMode=${selectionMode != null}")
        selectionMode?.finish()
        selectionMode = null
    }
}

private class Callback2Wrapper(
    val callback: ActionMode.Callback,
    val callback2: ActionMode.Callback2?,
    val contentRect: Rect? = null,
    val onDestroyed: () -> Unit = {},
) : ActionMode.Callback by callback, ActionMode.Callback2() {

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        val rect = contentRect
        when {
            rect != null -> outRect.set(rect)
            callback2 != null -> callback2.onGetContentRect(mode, view, outRect)
            else -> super.onGetContentRect(mode, view, outRect)
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        Log.d("SelectionFix", "onDestroyActionMode")
        onDestroyed()
        callback.onDestroyActionMode(mode)
    }
}

/**
 * Best effort to delay the execution of a block until the Webview
 * has received data up-to-date at the moment when the call occurs or newer.
 */
public fun RelaxedWebView.invokeOnWebViewUpToDate(block: WebView.() -> Unit) {
    requestLayout()
    setNextLayoutListener {
        invokeOnReadyToBeDrawn(block)
    }
}
