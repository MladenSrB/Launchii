package com.oneuiclone.home.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import com.oneuiclone.home.motion.Easing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Horizontal pager with One UI Home's FastRecyclerView physics (spec §9):
 * fling ≥ 500 dp/s, 40 % significant move, quintic scroller, snap duration
 * min(round(|(sin((f−0.5)·0.4712389)·W/2 + W/2) / max(1500dp/s, |v|)|·1000)·5, 900),
 * 750 ms default snap, edge pull beyond half a screen + bounce scale ≥ 0.96.
 */
open class PagedView(context: Context) : ViewGroup(context) {

    interface Listener {
        fun onPageChanged(page: Int) {}
        fun onScrollProgress(position: Float) {}
    }

    var listener: Listener? = null
    var currentPage = 0; private set
    val pageCount get() = childCount

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingThreshold = 500f * density           // fling_threshold_velocity
    private val minFlingVelocity = 250f * density         // min_fling_velocity
    private val minSnapVelocity = 1500f * density         // min_page_snap_velocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val scroller = OverScroller(context, Easing.QUINT_OUT)
    private var velocityTracker: VelocityTracker? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var downScroll = 0
    var isDragging = false; private set
    /** Unclamped scroll while dragging (for overscroll). */
    private var rawScroll = 0f
    private var overscroll = 0f
    private var edgeReturn: ValueAnimator? = null

    private fun catchEdgeReturn(): Boolean {
        val a = edgeReturn ?: return false
        edgeReturn = null
        a.cancel()
        return true
    }

    private fun returnFromEdge() {
        catchEdgeReturn()
        scroller.forceFinished(true)
        val target = rawScroll.coerceIn(0f, maxScroll.toFloat())
        edgeReturn = ValueAnimator.ofFloat(rawScroll, target).apply {
            duration = 480L
            interpolator = Easing.spring(180f, 0.85f, 480L)
            addUpdateListener {
                rawScroll = it.animatedValue as Float
                applyRawScroll()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (edgeReturn !== animation) return
                    edgeReturn = null
                    rawScroll = target
                    applyRawScroll()
                }
            })
            start()
        }
    }

    var pageSnapDuration = 750                            // config_pageSnapAnimationDuration
    var enablePaging = true
    /** Extra gap between pages (edit mode uses page_spacing_in_page_edit). */
    var pageSpacing = 0
        set(v) { if (field != v) { field = v; requestLayout(); post { scrollTo(currentPage * step, 0) } } }
    private val step get() = width + pageSpacing
    /** Long press on empty space (no child consumed the touch). */
    var onEmptyLongPress: ((Float, Float) -> Unit)? = null
    var onEmptyTap: ((Float, Float) -> Unit)? = null
    /** Pages whose contents get no touches (page edit): a tap on them is an empty tap, a swipe still pages. */
    var pageLocked: (View) -> Boolean = { false }
    private val longPressRunnable = Runnable { if (!isDragging) { longPressed = true; onEmptyLongPress?.invoke(downX, downY) } }
    private var longPressed = false

    init {
        clipChildren = false
        clipToPadding = false
        isMotionEventSplittingEnabled = false
    }

    fun pageAt(i: Int): View? = getChildAt(i)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(i * (w + pageSpacing), 0, i * (w + pageSpacing) + w, b - t)
        }
        if (changed && scroller.isFinished && !isDragging) scrollTo(currentPage * (w + pageSpacing), 0)
    }

    private val maxScroll get() = max(0, (childCount - 1) * step)

    fun setCurrentPageImmediately(page: Int) {
        catchEdgeReturn()
        overscroll = 0f
        applyOverscrollScale(0f)
        scroller.forceFinished(true)
        currentPage = page.coerceIn(0, max(0, childCount - 1))
        scrollTo(currentPage * step, 0)
        listener?.onPageChanged(currentPage)
    }

    // ---------------- touch ----------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!enablePaging || childCount == 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                downScroll = scrollX
                val caughtEdge = catchEdgeReturn()
                if (!caughtEdge) rawScroll = scrollX.toFloat()
                obtainTracker().addMovement(ev)
                if (caughtEdge || !scroller.isFinished) {
                    // catch the page mid-flight (One UI pages are always grabbable)
                    scroller.forceFinished(true)
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                // page edit: icons and widgets on the shrunken pages never open (the "+" page stays tappable)
                val page = getChildAt(((ev.x + scrollX) / step.coerceAtLeast(1)).toInt().coerceIn(0, childCount - 1))
                if (pageLocked(page)) return true
            }
            MotionEvent.ACTION_MOVE -> {
                obtainTracker().addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                    startDrag(ev)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleTracker()
        }
        return isDragging
    }

    private fun startDrag(ev: MotionEvent) {
        isDragging = true
        lastX = ev.x
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!enablePaging) return false
        obtainTracker().addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                downScroll = scrollX
                val caughtEdge = catchEdgeReturn()
                if (!caughtEdge && !isDragging) rawScroll = scrollX.toFloat()
                longPressed = false
                if (onEmptyLongPress != null) postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) removeCallbacks(longPressRunnable)
                    if (abs(ev.x - downX) > touchSlop) startDrag(ev) else return true
                }
                rawScroll += lastX - ev.x
                lastX = ev.x
                applyRawScroll()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (isDragging) release(ev) else if (!longPressed) { onEmptyTap?.invoke(ev.x, ev.y); performClick() }
                isDragging = false
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (isDragging) {
                    if (overscroll != 0f) returnFromEdge() else snapToPage(nearestPage(), pageSnapDuration)
                }
                isDragging = false
                recycleTracker()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun applyRawScroll() {
        val w = width.toFloat().coerceAtLeast(1f)
        overscroll = when {
            rawScroll < 0 -> rawScroll
            rawScroll > maxScroll -> rawScroll - maxScroll
            else -> 0f
        }
        val clamped = rawScroll.coerceIn(0f, maxScroll.toFloat())
        val damped = if (overscroll == 0f) 0f else {
            // Continuous resistance with no hard stop at one screen of finger travel.
            // A one-screen pull reveals about 54% of the screen; the asymptote is 72%.
            val distance = abs(overscroll) / w
            val influence = 0.72f * (1f - kotlin.math.exp(-distance / 0.72f))
            influence * w * (if (overscroll < 0) -1 else 1)
        }
        scrollTo((clamped + damped).roundToInt(), 0)
        applyOverscrollScale(if (overscroll == 0f) 0f else min(1f, abs(overscroll) / w))
        dispatchProgress()
    }

    /** TRANSITION_BOUNCE_MIN_SCALE 0.96 on the edge page while overscrolling. */
    private fun applyOverscrollScale(f: Float) {
        val s = max(0.96f, 1f - Easing.SINE_IN_OUT_70.getInterpolation(f) * 0.04f)
        val page = getChildAt(if (overscroll < 0) 0 else childCount - 1) ?: return
        if (f == 0f) {
            for (i in 0 until childCount) { getChildAt(i).scaleX = 1f; getChildAt(i).scaleY = 1f }
            return
        }
        page.pivotX = if (overscroll < 0) 0f else page.width.toFloat()
        page.pivotY = page.height / 2f
        page.scaleX = s
        page.scaleY = s
    }

    private fun release(ev: MotionEvent) {
        val vt = velocityTracker
        vt?.computeCurrentVelocity(1000, maxVelocity)
        val vx = vt?.xVelocity ?: 0f
        val w = step
        val deltaX = ev.x - downX
        val significant = abs(deltaX) > w * 0.4f
        val fling = abs(vx) > flingThreshold
        val start = (downScroll + w / 2) / w
        var target = start
        if (((significant && deltaX > 0 && !fling) || (fling && vx > 0)) && start > 0) target = start - 1
        else if (((significant && deltaX < 0 && !fling) || (fling && vx < 0)) && start < childCount - 1) target = start + 1
        else if (!significant && !fling) target = nearestPage()

        if (overscroll != 0f && target == nearestPage()) {
            returnFromEdge()
            return
        }
        applyOverscrollScale(0f)
        if (target == start && !significant && !fling) {
            snapToPage(target, pageSnapDuration)
            return
        }
        snapToPageWithVelocity(target, vx)
    }

    private fun nearestPage(): Int = ((scrollX + step / 2) / step.coerceAtLeast(1)).coerceIn(0, max(0, childCount - 1))

    private fun distanceInfluence(f: Float) = sin((f - 0.5f) * 0.4712389f)

    private fun snapToPageWithVelocity(page: Int, velocity: Float) {
        val delta = page * step - scrollX
        if (abs(velocity) < minFlingVelocity) {
            snapToPage(page, pageSnapDuration)
            return
        }
        val half = width / 2f
        val f = min(1f, abs(delta) / (half * 2f))
        val distance = half + half * distanceInfluence(f)
        val duration = min((abs(distance / max(minSnapVelocity, abs(velocity))) * 1000f).roundToInt() * 5, 900)
        snapToPage(page, duration)
    }

    fun snapToPage(page: Int, duration: Int = pageSnapDuration) {
        catchEdgeReturn()
        overscroll = 0f
        applyOverscrollScale(0f)
        val p = page.coerceIn(0, max(0, childCount - 1))
        val changed = p != currentPage
        currentPage = p
        val dx = p * step - scrollX
        scroller.forceFinished(true)
        scroller.startScroll(scrollX, 0, dx, 0, if (dx == 0) 0 else duration)
        postInvalidateOnAnimation()
        if (changed) listener?.onPageChanged(p)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            dispatchProgress()
            postInvalidateOnAnimation()
        }
    }

    private fun dispatchProgress() {
        listener?.onScrollProgress(scrollX / step.coerceAtLeast(1).toFloat())
    }

    private fun obtainTracker(): VelocityTracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
    private fun recycleTracker() { velocityTracker?.recycle(); velocityTracker = null }
}
