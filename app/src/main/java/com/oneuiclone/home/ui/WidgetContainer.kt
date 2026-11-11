package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.oneuiclone.home.model.HomeItem
import kotlin.math.abs

/** Hosts an AppWidgetHostView in a cell span and detects long-press over it (CheckLongPressHelper). */
@SuppressLint("ViewConstructor")
class WidgetContainer(context: Context, val item: HomeItem.Widget, child: View, pad: Int,
                      contentSize: Pair<Int, Int>? = null) : FrameLayout(context) {
    var onLongPress: ((WidgetContainer) -> Unit)? = null
    /** After the long-press, moving past the slop drags the widget (raw screen coordinates). */
    var onDragStart: ((WidgetContainer, Float, Float) -> Boolean)? = null
    var onDragMove: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onDragCancel: (() -> Unit)? = null
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var dx = 0f
    private var dy = 0f
    private var fired = false
    private val lp = Runnable { fired = true; parent?.requestDisallowInterceptTouchEvent(true); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPress?.invoke(this) }

    init {
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
        addView(child, if (contentSize == null) LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            else LayoutParams(contentSize.first, contentSize.second, android.view.Gravity.CENTER))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { fired = false; dragging = false; dx = ev.x; dy = ev.y; postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - dx) > slop || abs(ev.y - dy) > slop) removeCallbacks(lp)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(lp)
        }
        return fired
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(lp)
        if (dragging) onDragCancel?.invoke()
        dragging = false
        fired = false
        super.onDetachedFromWindow()
    }

    /**
     * Reached when the widget itself didn't take the touch (non-interactive area): claim it so the
     * long-press belongs to the widget instead of falling through to the page (which would enter page edit).
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> when {
                !fired -> if (abs(event.x - dx) > slop || abs(event.y - dy) > slop) removeCallbacks(lp)
                dragging -> onDragMove?.invoke(event.rawX, event.rawY)
                abs(event.x - dx) > slop || abs(event.y - dy) > slop ->
                    dragging = onDragStart?.invoke(this, event.rawX, event.rawY) == true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lp)
                if (dragging) {
                    dragging = false
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) onDragCancel?.invoke() else onDragEnd?.invoke()
                }
                fired = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
