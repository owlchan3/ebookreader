/*
 * Copyright 2024 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.web.internals.webview

import android.content.Context
import android.view.ActionMode
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView

/**
 * WebView allowing access to some protected fields.
 *
 * Native text selection is disabled here: the app drives selection programmatically
 * (`selection.selectAtPoint`) and renders its own highlight/menu/handles, so the system's selection
 * ActionMode (drag handles + toolbar) must never appear. Long-presses are still detected via a
 * [GestureDetector] and forwarded to [setSelectionLongPressListener] so the app can start a custom
 * selection at the touch position.
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

    init {
        // Prevent Chromium from starting native text selection on long-press.
        isLongClickable = false
        isHapticFeedbackEnabled = false
    }

    /**
     * Long-press listener, invoked with the touch position in this WebView's local (physical) pixels,
     * excluding any padding applied by the caller.
     */
    private var onLongPressListener: ((Float, Float) -> Unit)? = null

    public fun setSelectionLongPressListener(listener: ((Float, Float) -> Unit)?) {
        onLongPressListener = listener
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onLongPressListener?.invoke(e.x, e.y)
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    @Suppress("Deprecation")
    @Deprecated("Deprecated in Java")
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        nextLayoutListener.invoke()
        nextLayoutListener = {}
    }

    /**
     * Never start the native selection ActionMode (drag handles + toolbar). Returning null is a
     * defensive backstop; `user-select:none` already prevents Chromium from initiating selection.
     */
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = null

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = null

    public fun setCustomSelectionActionModeCallback(
        @Suppress("UNUSED_PARAMETER") callback: ActionMode.Callback?,
    ) {
        // Native selection ActionMode is disabled; kept only for API compatibility with the
        // ReflowableResource wiring.
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
