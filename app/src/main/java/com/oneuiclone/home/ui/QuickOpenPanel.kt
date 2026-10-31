package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.model.QuickOpenRecents
import com.oneuiclone.home.motion.Easing
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Bubble and Split widgets' panel, after iOS 27's Search or Ask (the mockup's option A, "Island"): Home blurs and
 * darkens, and a glowing bar drops out of the camera cutout with the keyboard. Type an app, and the rest of the top
 * hit's name is filled in grey; Enter (or a tap on any result) opens it.
 *
 * Bubble: one bar; the app opens in a bubble ([onBubble]). Split: two bars, Top and Bottom, only the active one
 * glowing; Enter turns the top app into a chip in its bar and moves on to the bottom one, Backspace in the empty
 * bottom bar goes back, and Enter there opens both ([onSplit]). With nothing typed, the frosted card under the bars
 * shows 8 apps: those last used in a bubble or in split screen ([recent]), then the most used ([scores]); the app
 * already picked on top is left out of the bottom's, and those last split with it come first.
 *
 * Multi Bubble ([multi]): one bar that collects apps. Enter (or a tap) adds the app as an icon chip in the bar and
 * clears it for the next; Backspace with nothing typed, or a tap on a chip, takes one back out. Done (the bar's
 * button, or Enter with nothing typed) opens them all in bubbles ([onBubbles]), up to [MAX_BUBBLES].
 *
 * Nothing scrolls: the card shows what fits above the keyboard. A tap outside, a swipe down or Back closes it.
 *
 * On Home it comes out of the widget (the mockup's B, [origin]): the widget's tile lifts, flies up and stretches into
 * the bar, the card drops out of the bar and its apps pop in one after another; dismissed, the card folds back into
 * the bar and the bar flies back down into the widget ([flyBack]). Out of the 2×1 widget ([home]) Home shrinks and
 * fades out while the panel, a little bigger at first, shrinks into place and fades in; dismissed, the other way round
 * ([zoomOut]). Without either (another launcher's window) the bar drops from the camera cutout and the panel blurs
 * away ([blurOut]). The bars and card are frosted like Frosted
 * icons ([Frost]): the blurred wallpaper behind them under the icon plates' tint.
 */
@SuppressLint("ViewConstructor")
class QuickOpenPanel(
    context: Context,
    private val split: Boolean,
    apps: List<AppInfo>,
    private val scores: Map<AppKey, Double>,
    /** A picture of the whole window (at a quarter size) for the blurred backdrop; null where the window blurs what's behind it. */
    backdrop: Bitmap?,
    /** The widget the panel comes out of and goes back into; null to drop from the camera cutout instead. */
    private val origin: Origin?,
    /** The blurred wallpaper the frosted bars and card show (Frosted icons' glass); null for the tint alone. */
    private val glass: WidgetGlass?,
    /** The frosted tint: the icon plates' colour at their see-through-ness. */
    private val frost: Int,
    private val onBubble: (AppInfo) -> Unit,
    private val onSplit: (AppInfo, AppInfo) -> Unit,
    /** Gone; [animated] when it flew back into the widget (which then settles), not when an app opened over it. */
    private val onClosed: (animated: Boolean) -> Unit,
    /**
     * Home behind the panel, which it zooms out of (with no [origin]): its [scale], and how far it's [gone] (faded
     * out, the wallpaper blurred and dimmed in its place). Both are back at 1 and 0 by [onClosed].
     */
    private val home: ((scale: Float, gone: Float) -> Unit)? = null,
    /** An app's icon, for lists whose icons are drawn as they're first shown (QuickOpenActivity); else its own. */
    private val iconOf: (AppInfo) -> Bitmap = { it.icon },
    /**
     * How far the blurred backdrop is in (0..1), for a window that blurs what's behind it ([backdrop] null) to
     * follow: the panel's own scrim fades in and out with the open and the dismiss.
     */
    private val onBackdrop: ((Float) -> Unit)? = null,
    /** Multi Bubble: apps are collected until Done, then all open in bubbles ([onBubbles], in the order added). */
    private val multi: Boolean = false,
    private val onBubbles: (List<AppInfo>) -> Unit = {},
    /** The apps last used in a bubble (Bubble, Multi Bubble) or in split screen (Split), which the grid shows first. */
    private val recent: QuickOpenRecents.Snapshot = QuickOpenRecents.Snapshot.EMPTY,
) : FrameLayout(context) {

    /**
     * What the panel comes out of: where it is on screen, how it looks (the bar starts and ends as it) and its corner
     * radius: a widget's icon.
     */
    class Origin(val bounds: android.graphics.Rect, val tile: Bitmap,
                 val corner: Float = minOf(bounds.width(), bounds.height()) * ICON_CORNER) {
        val aspect get() = if (bounds.height() > 0) bounds.width().toFloat() / bounds.height() else 1f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).roundToInt()

    private class Entry(val app: AppInfo, val name: String, val initials: String)
    private val index = apps.map { a -> val n = normalize(a.label); Entry(a, n, n.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }) }

    private val backdropView = ImageView(context)
    private val scrim = View(context)
    /** Multi Bubble: the apps added so far (before the bars, whose hint reads it). */
    private val picks = mutableListOf<AppInfo>()
    private val bars = List(if (split) 2 else 1) { Bar(context, it) }
    private val card = object : FrameLayout(context) {
        private val glassy = Frost(this)
        private val outline = Path()
        private val box = RectF()
        init { setWillNotDraw(false) }
        override fun onDraw(c: Canvas) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            outline.reset(); outline.addRoundRect(box, dp(28f), dp(28f), Path.Direction.CW)
            glassy.draw(c, outline, box)
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); glassy.attach() }
        override fun onDetachedFromWindow() { glassy.detach(); super.onDetachedFromWindow() }
    }
    private val list = LinearLayout(context)

    /** Split: the app picked for the top half, and which bar is being typed in. */
    private var topPick: AppInfo? = null
    private var focus = 0
    /** What Enter opens. */
    private var hit: AppInfo? = null

    private var topInset = 0
    private var ime = 0
    /** 0 = folded into the camera cutout, 1 = open. */
    private var f = 0f
    private var anim: ValueAnimator? = null
    var isOpen = false
        private set
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    init {
        isClickable = true
        clipChildren = false
        backdropView.scaleType = ImageView.ScaleType.FIT_XY
        backdropView.alpha = 0f
        addView(backdropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // over a blurred picture of Home, or (in another launcher's window) over the system's blur behind it
        // only lightly dimmed, so the screen keeps the wallpaper's colours (the frosted bars and card carry the contrast)
        scrim.setBackgroundColor(if (backdrop != null) 0x29140818 else 0x26000000)
        scrim.alpha = 0f
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (backdrop != null) {
            backdropView.setImageBitmap(backdrop)
            // the picture is a quarter of the screen: 18 px there blurs like ~72 px at full size
            backdropView.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
        }

        list.orientation = LinearLayout.VERTICAL
        card.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            val p = dpi(CARD_PAD_DP); setMargins(p, dpi(14f), p, p)
        })
        card.alpha = 0f
        card.isClickable = true                         // a tap on the card isn't a tap outside
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bars.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, dpi(BAR_DP + 2 * BAR_PAD_DP))) }

        setOnApplyWindowInsetsListener { _, insets ->
            topInset = maxOf(insets.getInsets(WindowInsets.Type.statusBars()).top, insets.getInsets(WindowInsets.Type.displayCutout()).top)
            val k = insets.getInsets(WindowInsets.Type.ime()).bottom
            val moved = k != ime
            ime = k
            place()
            if (moved && k > 0) keyboardUp = true
            if (moved && isOpen) render()
            insets
        }
    }

    // ---------------------------------------------------------------------------------------------
    // layout
    // ---------------------------------------------------------------------------------------------

    private fun barTop(i: Int) = topInset + dpi(8f) + i * dpi(BAR_DP + BAR_GAP_DP)

    private fun cardTop() = barTop(bars.size - 1) + dpi(BAR_DP + 16f)

    /** Where everything sits once open; [layoutMorph] folds the bars into the cutout from there. */
    private fun place() {
        val side = dpi(SIDE_DP)
        val pad = dpi(BAR_PAD_DP)
        bars.forEachIndexed { i, b ->
            val lp = b.layoutParams as LayoutParams
            lp.leftMargin = side - pad; lp.rightMargin = side - pad; lp.topMargin = barTop(i) - pad
            b.layoutParams = lp
        }
        val lp = card.layoutParams as LayoutParams
        lp.leftMargin = side; lp.rightMargin = side; lp.topMargin = cardTop()
        card.layoutParams = lp
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        layoutMorph()
    }

    /** Height the card may take: down to just above the keyboard (or where it will be, while it's on its way). */
    private fun cardSpace(): Int {
        val h = if (height > 0) height else resources.displayMetrics.heightPixels
        val keyboard = if (ime > 0) ime else (h * 0.4f).toInt()
        return h - keyboard - cardTop() - dpi(12f)
    }

    private fun layoutMorph() {
        if (collapsing) return                          // [flyBack] / [zoomOut] / [blurOut] own every transform now
        val e = f
        if (home != null) {
            // e runs linearly here: Home shrinks and fades as the panel shrinks into place and fades in, together
            val z = Easing.STANDARD.getInterpolation(e)
            bars.forEach { b ->
                val pad = dp(BAR_PAD_DP)
                b.setShape(RectF(pad, pad, b.width - pad, b.height - pad))
                b.contentAlpha = 1f
            }
            val fade = Easing.STANDARD.getInterpolation((e / 0.6f).coerceIn(0f, 1f))
            zoomTo(lerp(ZOOM_FROM, 1f, z), fade)
            homeAt(lerp(1f, HOME_ZOOM, z), fade)
            return
        }
        val icon = originIn()
        // out of the widget or out of the cutout; the second bar follows a beat behind
        val islandW = dp(96f); val islandH = dp(30f)
        val cx = width / 2f; val cy = maxOf(topInset / 2f, islandH / 2f)
        bars.forEachIndexed { i, b ->
            val t = ((e - i * 0.12f) / (1f - i * 0.12f)).coerceIn(0f, 1f)
            val pad = dp(BAR_PAD_DP)
            val full = RectF(pad, pad, b.width - pad, b.height - pad)
            if (icon != null) {
                // e runs linearly here: the icon rises as itself, and only a beat later stretches into the bar
                val u = ((e - i * 0.08f) / (1f - i * 0.08f)).coerceIn(0f, 1f)
                val start = RectF(icon).apply { offset(-b.left.toFloat(), -b.top.toFloat()) }
                val travel = TRAVEL.getInterpolation(u)
                val stretch = STRETCH.getInterpolation(((u - 0.12f) / 0.88f).coerceIn(0f, 1f))
                b.setShape(morph(start, full, travel, stretch), lerp(origin!!.corner, full.height() / 2f, travel))
                b.alpha = 1f
                b.tileAlpha = (1f - (u / 0.35f).coerceIn(0f, 1f)) * squareness(b)
                b.contentAlpha = ((u - 0.5f) / 0.35f).coerceIn(0f, 1f)
                return@forEachIndexed
            } else {
                val start = RectF(cx - b.left - islandW / 2, cy - b.top - islandH / 2, cx - b.left + islandW / 2, cy - b.top + islandH / 2)
                b.setShape(lerpRect(start, full, t))
                b.alpha = (t * 5f).coerceAtMost(1f)
            }
            b.contentAlpha = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
        }
        if (icon != null) {
            // the card comes out of the bar as it settles, its apps rising into place with it ([pop])
            val c = DECEL.getInterpolation(((e - 0.35f) / 0.5f).coerceIn(0f, 1f))
            card.pivotY = 0f
            card.alpha = c
            card.translationY = -dp(12f) * (1f - c)
            card.scaleY = 0.94f + 0.06f * c
            backdropAt(DECEL.getInterpolation((e / 0.8f).coerceIn(0f, 1f)))
            return
        } else {
            val c = ((e - 0.3f) / 0.7f).coerceIn(0f, 1f)
            card.alpha = c
            card.translationY = -dp(24f) * (1f - c)
        }
        backdropAt(e)
    }

    /** The scrim and blurred backdrop at [v] (0..1), and the window's blur behind with them ([onBackdrop]). */
    private fun backdropAt(v: Float) {
        scrim.alpha = v
        backdropView.alpha = v
        onBackdrop?.invoke(v)
    }

    /** The bars and card as one block at [scale] about its centre, at [alpha]. */
    private fun zoomTo(scale: Float, alpha: Float) {
        val pad = dpi(BAR_PAD_DP)
        val cx = width / 2f
        val cy = ((bars[0].top + pad) + maxOf(card.bottom, bars.last().bottom - pad)) / 2f
        (bars + card).forEach { v ->
            v.pivotX = cx - v.left; v.pivotY = cy - v.top
            v.scaleX = scale; v.scaleY = scale
            v.alpha = alpha
        }
        zoomScale = scale; zoomAlpha = alpha
    }
    private var zoomScale = ZOOM_FROM
    private var zoomAlpha = 0f

    private fun homeAt(scale: Float, gone: Float) {
        home?.invoke(scale, gone)
        homeScale = scale; homeGone = gone
    }
    private var homeScale = 1f
    private var homeGone = 0f

    /** The widget's icon in this view's coordinates (null with no origin, or before layout). */
    private fun originIn(): RectF? {
        val o = origin ?: return null
        if (width == 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return RectF(o.bounds).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * Between [a] and [b]: the centre and height go by [travel], the width by [stretch], so a square icon can travel
     * while it's still icon-shaped and widen into the bar after (or narrow before landing).
     */
    private fun morph(a: RectF, b: RectF, travel: Float, stretch: Float): RectF {
        val cx = lerp(a.centerX(), b.centerX(), travel); val cy = lerp(a.centerY(), b.centerY(), travel)
        val hw = lerp(a.width(), b.width(), stretch) / 2f; val hh = lerp(a.height(), b.height(), travel) / 2f
        return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    /** 1 while [bar] has about the origin's shape, down to 0 once it's 30 % wider: the origin only shows then. */
    private fun squareness(bar: Bar) = (1f - (bar.shapeAspect / (origin?.aspect ?: 1f) - 1f) / 0.3f).coerceIn(0f, 1f)

    private fun lerpRect(a: RectF, b: RectF, t: Float) =
        RectF(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

    // ---------------------------------------------------------------------------------------------
    // open / close
    // ---------------------------------------------------------------------------------------------

    fun open() {
        isOpen = true
        f = 0f
        keyboardUp = false
        setFocus(0)
        popAt = if (origin != null) SystemClock.uptimeMillis() + (FLY_MS * 0.42f).toLong() else 0L
        render()
        animateTo(1f, if (origin != null) FLY_MS else if (home != null) ZOOM_IN_MS else OPEN_MS)
        askForKeyboard()
    }

    /** When the grid's apps start rising into place, one after another, as the card comes out (0: they don't). */
    private var popAt = 0L

    private fun pop(cell: View, i: Int) {
        val wait = popAt + i * POP_STAGGER_MS - SystemClock.uptimeMillis()
        if (popAt == 0L || wait < -POP_MS) return
        cell.alpha = 0f; cell.translationY = dp(10f)
        cell.animate().alpha(1f).translationY(0f).setStartDelay(maxOf(0L, wait)).setDuration(POP_MS)
            .setInterpolator(DECEL).start()
    }

    /** Flies back into the widget or zooms back out to Home ([animate]; blurs away with neither), or just goes (an app opens over it). */
    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        removeCallbacks(keyboardRetry)
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        bars.forEach { it.field.isCursorVisible = false }
        val icon = originIn()
        when {
            !animate || width == 0 -> { anim?.cancel(); homeAt(1f, 0f); onClosed(false) }
            icon != null -> flyBack(icon)
            home != null -> zoomOut()
            else -> blurOut()
        }
    }

    private var collapsing = false

    /**
     * The dismiss on Home, one motion: the apps and card fade back into the bar while the bar narrows toward the
     * icon's shape and travels down onto it, slowing to a stop as it turns back into the icon (no bounce: it lands
     * where it belongs). Home clears in step with it.
     */
    private fun flyBack(icon: RectF) {
        anim?.cancel()
        collapsing = true
        val pad = dp(BAR_PAD_DP)
        val from = bars.map { b -> RectF(pad, pad, b.width - pad, b.height - pad) }
        val startScrim = scrim.alpha
        val startCard = card.alpha
        card.pivotY = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLY_BACK_MS
            interpolator = Easing.LINEAR
            addUpdateListener { a ->
                val u = a.animatedValue as Float
                val fade = ACCEL.getInterpolation((u / 0.35f).coerceIn(0f, 1f))
                list.alpha = 1f - fade
                card.alpha = startCard * (1f - fade)
                card.translationY = -dp(10f) * fade
                card.scaleY = 1f - 0.05f * fade
                bars.forEachIndexed { i, b ->
                    b.contentAlpha = 1f - (u / 0.2f).coerceIn(0f, 1f)
                    val narrow = STRETCH.getInterpolation((u / 0.75f).coerceIn(0f, 1f))
                    val travel = TRAVEL.getInterpolation(((u - 0.1f - i * 0.04f) / 0.9f).coerceIn(0f, 1f))
                    val to = RectF(icon).apply { offset(-b.left.toFloat(), -b.top.toFloat()) }
                    val r = morph(from[i], to, travel, narrow)
                    b.setShape(r, minOf(r.height() / 2f, lerp(from[i].height() / 2f, origin!!.corner, travel)))
                    b.tileAlpha = ((travel - 0.55f) / 0.4f).coerceIn(0f, 1f) * squareness(b)
                }
                val clear = TRAVEL.getInterpolation((u / 0.9f).coerceIn(0f, 1f))
                backdropAt(startScrim * (1f - clear))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onClosed(true) }
            })
            start()
        }
    }

    /**
     * The dismiss out of the 2×1 widget, the open the other way round: the panel grows a little as it fades, while
     * Home grows back into place and fades in. From wherever the open had got to.
     */
    private fun zoomOut() {
        anim?.cancel()
        collapsing = true
        val scale0 = zoomScale; val alpha0 = zoomAlpha
        val home0 = homeScale; val gone0 = homeGone
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_OUT_MS
            interpolator = Easing.LINEAR
            addUpdateListener { a ->
                val u = a.animatedValue as Float
                val z = Easing.STANDARD.getInterpolation(u)
                zoomTo(lerp(scale0, ZOOM_TO, z), alpha0 * (1f - Easing.STANDARD.getInterpolation((u / 0.6f).coerceIn(0f, 1f))))
                homeAt(lerp(home0, 1f, z), gone0 * (1f - Easing.STANDARD.getInterpolation((u / 0.8f).coerceIn(0f, 1f))))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) { homeAt(1f, 0f); onClosed(true) } }
            })
            start()
        }
    }

    /**
     * The dismiss: the bars and card, as one block, blur away in place while shrinking a little about their centre
     * and fading out; the blurred Home behind clears at the same pace.
     */
    private fun blurOut() {
        anim?.cancel()
        collapsing = true
        val views = bars + card
        val pad = dpi(BAR_PAD_DP)
        // the block as seen: the bars without their halo room, down to the bottom of the card
        val cx = (card.left + card.right) / 2f
        val cy = ((bars[0].top + pad) + (if (card.alpha > 0f) card.bottom else bars.last().bottom - pad)) / 2f
        val startAlpha = views.map { it.alpha }
        val startY = views.map { it.translationY }
        val startScrim = scrim.alpha
        views.forEach { it.pivotX = cx - it.left; it.pivotY = cy - it.top }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BLUR_OUT_MS
            interpolator = Easing.STANDARD
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val k = lerp(1f, BLUR_OUT_SCALE, t)
                val r = dp(BLUR_OUT_DP) * t
                val blur = if (r >= 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null
                val fade = 1f - ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
                views.forEachIndexed { i, v ->
                    v.scaleX = k; v.scaleY = k
                    v.translationY = startY[i]
                    v.setRenderEffect(blur)
                    v.alpha = startAlpha[i] * fade
                }
                backdropAt(startScrim * (1f - t))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onClosed(false) }
            })
            start()
        }
    }

    private fun animateTo(to: Float, ms: Long, end: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(f, to).apply {
            duration = ms
            // out of the widget, each part takes its own curve from linear time (layoutMorph)
            interpolator = if (to > f) (if (origin != null || home != null) Easing.LINEAR else Easing.IME) else Easing.STANDARD
            addUpdateListener { f = it.animatedValue as Float; layoutMorph() }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Asks until the keyboard is on its way: right after a window change the IME can turn the first request down. */
    private var keyboardUp = false
    private var keyboardDeadline = 0L
    private val keyboardRetry: Runnable = object : Runnable {
        override fun run() {
            if (!isOpen || keyboardUp) return
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(bars[focus].field, 0)
            if (SystemClock.uptimeMillis() < keyboardDeadline) postDelayed(this, 80)
        }
    }

    private fun askForKeyboard() {
        removeCallbacks(keyboardRetry)
        keyboardDeadline = SystemClock.uptimeMillis() + 1500
        keyboardRetry.run()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isOpen) askForKeyboard()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel()
        removeCallbacks(keyboardRetry)
    }

    // tap outside the bars and card, or swipe down: close
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = swipe(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (swipe(ev)) return true
        if (ev.actionMasked == MotionEvent.ACTION_UP && isOpen) close()
        return true
    }

    private fun swipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dy = ev.y - downY
                if (dy > slop * 2 && dy > abs(ev.x - downX) * 1.5f) { swiping = true; if (isOpen) close() }
            }
        }
        return swiping
    }

    // ---------------------------------------------------------------------------------------------
    // typing and picking
    // ---------------------------------------------------------------------------------------------

    private fun setFocus(i: Int) {
        focus = i
        bars.forEachIndexed { j, b -> b.active = j == i }
        val field = bars[i].field
        field.isCursorVisible = true
        field.requestFocus()
        if (split) bars[1].field.hint = if (topPick == null) "" else "App below"
    }

    private fun query() = normalize(bars[focus].field.text.toString().trim())

    /** What the active bar can pick from: everything but the app already on top, or those already added. */
    private fun pool() = when {
        split && focus == 1 -> index.filter { it.app.key != topPick?.key }
        multi -> index.filter { e -> picks.none { it.key == e.app.key } }
        else -> index
    }

    /**
     * The grid with nothing typed: the apps last used in a bubble or in split screen ([recent]), newest first, in the
     * bottom bar those last split with the app on top ahead of the rest; then the most used.
     */
    private fun suggested(): List<AppInfo> {
        val partners = topPick?.let { recent.partners(it.key.component.packageName) } ?: emptyMap()
        fun pkg(e: Entry) = e.app.key.component.packageName
        return pool().sortedWith(compareByDescending<Entry> { partners[pkg(it)] ?: 0L }
            .thenByDescending { recent.apps[pkg(it)] ?: 0L }
            .thenByDescending { scores[it.app.key] ?: 0.0 })
            .take(MAX_SUGGESTED).map { it.app }
    }

    /** Name starts with the query, then a word in it does (or its initials), then anywhere; more used first (as search). */
    private fun matches(q: String): List<AppInfo> = pool().mapNotNull { e ->
        val tier = when {
            e.name.startsWith(q) -> 0
            e.name.split(' ', '-', '.').any { it.startsWith(q) } || e.initials.startsWith(q) -> 1
            e.name.contains(q) -> 2
            else -> return@mapNotNull null
        }
        Triple(e.app, tier, scores[e.app.key] ?: 0.0)
    }.sortedWith(compareBy<Triple<AppInfo, Int, Double>> { it.second }.thenByDescending { it.third }).map { it.first }

    /** Apps have been opened: the keyboard can deliver Enter twice (the key and its Go action), and once is enough. */
    private var launched = false

    private fun pick(app: AppInfo) {
        if (!isOpen || launched) return
        if (multi) {
            if (picks.size >= MAX_BUBBLES || picks.any { it.key == app.key }) return
            picks += app
            bars[0].setPicks(picks, added = app)
            bars[0].field.text = null
            render()
            return
        }
        if (!split) { launched = true; onBubble(app); return }
        if (focus == 0) {
            topPick = app
            bars[0].chip = app
            bars[0].field.text = null
            setFocus(1)
            render()
        } else {
            topPick?.let { launched = true; onSplit(it, app) }
        }
    }

    /** Multi Bubble: takes [app] back out (Backspace with nothing typed takes the last, a tap on a chip takes its own). */
    private fun unpick(app: AppInfo) {
        if (!isOpen || launched || !picks.remove(app)) return
        bars[0].setPicks(picks)
        bars[0].field.requestFocus()
        render()
    }

    /** Multi Bubble: opens what's been added. */
    private fun done() {
        if (!isOpen || launched || picks.isEmpty()) return
        launched = true
        onBubbles(picks.toList())
    }

    /**
     * Enter or the bar's button: opens (or, in Multi Bubble, adds) the top hit; in Multi Bubble with nothing typed,
     * Done. The keyboard can deliver Enter twice (the key and its Go action), so a second one right after an add is
     * dropped rather than taken for Done.
     */
    private fun enter() {
        val now = SystemClock.uptimeMillis()
        if (multi && now - lastAdd < DOUBLE_ENTER_MS) return
        val app = hit
        if (multi && (query().isEmpty() || picks.size >= MAX_BUBBLES)) { done(); return }
        if (app != null) { if (multi) lastAdd = now; pick(app) }
    }
    private var lastAdd = 0L

    /** Back to choosing the top app (Backspace in the empty bottom bar, or a tap on the top one). */
    private fun unpickTop() {
        topPick = null
        bars[0].chip = null
        bars[1].field.text = null
        setFocus(0)
        render()
    }

    private fun render() {
        val q = query()
        list.removeAllViews()
        val space = cardSpace() - dpi(14f + CARD_PAD_DP) - dpi(CAPTION_DP)
        if (multi && picks.size >= MAX_BUBBLES) {
            hit = null
            caption("$MAX_BUBBLES APPS ADDED · PRESS DONE")
        } else if (q.isEmpty()) {
            hit = null
            val rows = (space / dpi(GRID_ROW_DP)).coerceIn(1, MAX_SUGGESTED / GRID_COLS)
            val apps = suggested().take(rows * GRID_COLS)
            caption(when {
                apps.none { recent.apps.containsKey(it.key.component.packageName) } -> "MOST USED"
                split -> "RECENT IN SPLIT SCREEN"
                else -> "RECENT BUBBLES"
            })
            grid(apps)
        } else {
            val found = matches(q)
            hit = found.firstOrNull()
            if (hit == null) {
                caption("NO APPS MATCH")
            } else {
                caption("TOP HIT")
                list.addView(topHitRow(found[0]))
                val more = ((space - dpi(TOP_HIT_DP)) / dpi(ROW_DP)).coerceIn(0, MAX_MORE)
                found.drop(1).take(more).forEach { list.addView(row(it)) }
            }
        }
        bars.forEachIndexed { i, b ->
            b.showEnter(when {
                i != focus -> null
                multi && (q.isEmpty() || picks.size >= MAX_BUBBLES) -> if (picks.isEmpty()) null else "Done"
                hit == null -> null
                multi -> "add"
                split -> "split"
                else -> "bubble"
            })
        }
        updateGhost()
    }

    /** The rest of the top hit's name after what's typed, in grey, when its name starts with it. */
    private fun updateGhost() {
        bars.forEachIndexed { i, b ->
            val typed = b.field.text.toString()
            val app = hit
            val rest = if (i == focus && app != null && typed.isNotEmpty() && normalize(app.label).startsWith(normalize(typed)) &&
                typed.length < app.label.length) app.label.substring(typed.length) else ""
            b.setGhost(typed, rest)
        }
    }

    private fun subtitle(): String = when {
        multi -> if (picks.isEmpty()) "Add as a bubble" else "Add as bubble ${picks.size + 1}"
        !split -> "Open as a bubble"
        focus == 0 -> "Top half"
        else -> "Bottom half · ${topPick?.label} on top"
    }

    private fun caption(s: String) {
        list.addView(TextView(context).apply {
            text = s
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.06f
            typeface = Fonts.ui(context, 600)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6f), 0, 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(CAPTION_DP)))
    }

    private fun icon(app: AppInfo, sizeDp: Float) = ImageView(context).apply {
        setImageBitmap(iconOf(app))
        layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
    }

    private fun ripple() = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
        GradientDrawable().apply { cornerRadius = dp(18f); setColor(0xFFFFFFFF.toInt()) })

    private fun grid(apps: List<AppInfo>) {
        var n = 0
        apps.chunked(GRID_COLS).forEach { chunk ->
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until GRID_COLS) {
                val app = chunk.getOrNull(i)
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dpi(8f), 0, 0)
                }
                if (app != null) {
                    cell.addView(icon(app, 52f))
                    cell.addView(TextView(context).apply {
                        text = app.label
                        setTextColor(0xF2FFFFFF.toInt())
                        setShadowLayer(dp(2f), 0f, dp(0.5f), 0x40000000)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Fonts.ui(context, 400)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dpi(4f), dpi(6f), dpi(4f), 0)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    cell.background = ripple()
                    cell.contentDescription = app.label
                    cell.setOnClickListener { pick(app) }
                    pop(cell, n++)
                }
                r.addView(cell, LinearLayout.LayoutParams(0, dpi(GRID_ROW_DP), 1f))
            }
            list.addView(r)
        }
    }

    private fun topHitRow(app: AppInfo): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply { cornerRadius = dp(20f); setColor(0x1AFFFFFF) }, null)
        setPadding(dpi(12f), 0, dpi(14f), 0)
        addView(icon(app, 52f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = app.label; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Fonts.ui(context, 600); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = subtitle(); setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Fonts.ui(context, 400); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(14f) })
        addView(ImageView(context).apply { setImageDrawable(EnterGlyph(0xCCFFFFFF.toInt())) }, LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
        contentDescription = app.label
        setOnClickListener { pick(app) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(TOP_HIT_DP - 4f)).apply { bottomMargin = dpi(4f) }
    }

    private fun row(app: AppInfo): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple()
        setPadding(dpi(12f), 0, dpi(12f), 0)
        addView(icon(app, 40f))
        addView(TextView(context).apply {
            text = app.label; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.ui(context, 500); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(16f) })
        contentDescription = app.label
        setOnClickListener { pick(app) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(ROW_DP))
    }

    // ---------------------------------------------------------------------------------------------
    // the glowing bar
    // ---------------------------------------------------------------------------------------------

    /**
     * One bar: a soft blurred halo and a crisp edge in turning Siri colours (Search or Ask's) while it's the one being
     * typed in, a thin grey edge otherwise. [setShape] gives the pill's current outline, so it can grow out of the
     * cutout; the view is [BAR_PAD_DP] larger than the bar on every side for the halo.
     */
    @SuppressLint("ViewConstructor")
    private inner class Bar(context: Context, private val slot: Int) : FrameLayout(context) {
        private val shape = RectF()
        private val path = Path()
        private var angle = 0f
        private var glow = 0f
        private var glowAnim: ValueAnimator? = null
        private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = SPIN_MS; repeatCount = ValueAnimator.INFINITE; interpolator = Easing.LINEAR
            addUpdateListener { angle = it.animatedValue as Float; if (glow > 0f) { halo.invalidate(); skin.invalidate() } }
        }
        private val sweepMatrix = Matrix()

        private fun sweep(): Shader = SweepGradient(shape.centerX(), shape.centerY(), GLOW, null).apply {
            sweepMatrix.setRotate(angle, shape.centerX(), shape.centerY()); setLocalMatrix(sweepMatrix)
        }

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(7f) }
        private val halo = object : View(context) {
            override fun onDraw(c: Canvas) {
                val g = glow * (1f - tileAlpha)
                if (g <= 0f) return
                haloPaint.shader = sweep(); haloPaint.alpha = (g * 230).toInt()
                c.drawPath(path, haloPaint)
            }
        }.apply { setRenderEffect(RenderEffect.createBlurEffect(dp(11f), dp(11f), Shader.TileMode.DECAL)) }

        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val tileBox = RectF()
        private val skin = object : View(context) {
            private val glassy = Frost(this)
            override fun onDraw(c: Canvas) {
                val bar = 1f - tileAlpha
                if (bar > 0f) glassy.draw(c, path, shape, bar)
                val g = glow * bar
                if (g > 0f) { edge.shader = sweep(); edge.alpha = (g * 255).toInt(); c.drawPath(path, edge) }
                // the widget's own tile, as the bar leaves it and lands back on it
                val origin = origin ?: return
                val tile = origin.tile
                // in its own proportions, never stretched: centred in the shape as it narrows into it or widens out of it
                val a = origin.aspect
                val hw = minOf(shape.width(), shape.height() * a) / 2f
                val hh = hw / a
                tileBox.set(shape.centerX() - hw, shape.centerY() - hh, shape.centerX() + hw, shape.centerY() + hh)
                if (tileAlpha > 0f) { tilePaint.alpha = (tileAlpha * 255).toInt(); c.drawBitmap(tile, null, tileBox, tilePaint) }
            }
            override fun onAttachedToWindow() { super.onAttachedToWindow(); glassy.attach() }
            override fun onDetachedFromWindow() { glassy.detach(); super.onDetachedFromWindow() }
        }

        /** How much the bar shows the widget's tile instead of itself (1 = all tile), at the ends of its flight. */
        var tileAlpha = 0f
            set(v) { if (field != v) { field = v; halo.invalidate(); skin.invalidate() } }
        private var corner = 0f

        /** The bar's contents, clipped to its current outline. */
        private val body = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val p = dp(BAR_PAD_DP)
                    val r = RectF(shape).apply { offset(-p, -p) }
                    outline.setRoundRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt(), corner)
                }
            }
        }
        private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val chipView = LinearLayout(context)
        private val chipIcon = ImageView(context)
        private val chipName = TextView(context)
        /** Multi Bubble: the apps added so far, as icons. */
        private val picksView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
        }
        private val ghost = TextView(context)
        val field = object : EditText(context) {
            // Backspace with nothing typed in the bottom bar goes back to the top app; in Multi Bubble it takes the last one out
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (split && slot == 1 && keyCode == KeyEvent.KEYCODE_DEL && text.isEmpty()) { unpickTop(); return true }
                if (multi && keyCode == KeyEvent.KEYCODE_DEL && text.isEmpty() && picks.isNotEmpty()) { unpick(picks.last()); return true }
                return super.onKeyDown(keyCode, event)
            }
        }
        private val enter = TextView(context)

        var active = false
            set(v) {
                if (field == v) return
                field = v
                glowAnim?.cancel()
                glowAnim = ValueAnimator.ofFloat(glow, if (v) 1f else 0f).apply {
                    duration = 200; addUpdateListener { glow = it.animatedValue as Float; halo.invalidate(); skin.invalidate() }; start()
                }
            }

        var contentAlpha: Float
            get() = row.alpha
            set(v) { if (row.alpha != v) { row.alpha = v; skin.invalidate() } }

        var chip: AppInfo? = null
            set(v) {
                field = v
                chipView.visibility = if (v == null) GONE else VISIBLE
                if (v != null) { chipIcon.setImageBitmap(iconOf(v)); chipName.text = v.label }
                this.field.hint = if (v != null) "" else hintText()
            }

        private fun hintText() = when {
            multi -> when { picks.isEmpty() -> "Add apps to bubble"; picks.size < MAX_BUBBLES -> "Add more"; else -> "" }
            !split -> "Open as a bubble"
            slot == 0 -> "App on top"
            else -> ""
        }

        /** Multi Bubble: shows [apps] as icon chips (a tap takes one out), [added] popping in. */
        fun setPicks(apps: List<AppInfo>, added: AppInfo? = null) {
            picksView.removeAllViews()
            apps.forEachIndexed { i, app ->
                val chip = ImageView(context).apply {
                    setImageBitmap(iconOf(app))
                    contentDescription = "Remove ${app.label}"
                    setOnClickListener { unpick(app) }
                }
                picksView.addView(chip, LinearLayout.LayoutParams(dpi(PICK_DP), dpi(PICK_DP)).apply { if (i > 0) leftMargin = dpi(6f) })
                if (app == added) {
                    chip.scaleX = 0.4f; chip.scaleY = 0.4f; chip.alpha = 0f
                    chip.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(POP_MS).setInterpolator(DECEL).start()
                }
            }
            picksView.visibility = if (apps.isEmpty()) GONE else VISIBLE
            field.hint = hintText()
        }

        init {
            clipChildren = false
            addView(halo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(skin, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val p = dpi(BAR_PAD_DP)
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(p, p, p, p) })
            body.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            row.addView(ImageView(context).apply { setImageDrawable(when { split -> SplitGlyph(0xBFFFFFFF.toInt()); multi -> MultiBubbleGlyph(0xBFFFFFFF.toInt()); else -> BubbleGlyph(0xBFFFFFFF.toInt()) }) },
                LinearLayout.LayoutParams(dpi(22f), dpi(22f)).apply { leftMargin = dpi(18f) })
            if (split) row.addView(TextView(context).apply {
                text = if (slot == 0) "Top" else "Bottom"
                setTextColor(0xB3FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Fonts.ui(context, 600)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(12f) })

            chipView.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(17f); setColor(0x24FFFFFF) }
                setPadding(dpi(4f), 0, dpi(12f), 0)
                addView(chipIcon, LinearLayout.LayoutParams(dpi(26f), dpi(26f)))
                addView(chipName.apply {
                    setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Fonts.ui(context, 500); isSingleLine = true
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(8f) })
                visibility = GONE
            }
            row.addView(chipView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(34f)).apply { leftMargin = dpi(12f) })
            row.addView(picksView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = dpi(12f) })

            // the grey completion sits under the field, laid out exactly like it, its typed part see-through
            val box = FrameLayout(context)
            for (t in listOf(ghost, field)) {
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                t.typeface = Fonts.ui(context, 400)
                t.isSingleLine = true
                t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                t.setPadding(0, 0, 0, 0)
                t.background = null
                box.addView(t, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            ghost.setTextColor(0x80FFFFFF.toInt())
            ghost.ellipsize = null
            field.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x99FFFFFF.toInt())
                hint = hintText()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = (if (split && slot == 0) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_GO) or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                isCursorVisible = false
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { if (isOpen && focus == slot) render() }
                })
                // Enter from a hardware keyboard comes as a down and an up: act on the down only
                setOnEditorActionListener { _, action, ev ->
                    if (action == EditorInfo.IME_NULL && ev?.keyCode != KeyEvent.KEYCODE_ENTER) false else {
                        if (action != EditorInfo.IME_NULL || ev?.action == KeyEvent.ACTION_DOWN) enter()
                        true
                    }
                }
                setOnTouchListener { _, ev -> ev.actionMasked == MotionEvent.ACTION_DOWN && !tapped() }
            }
            row.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dpi(12f) })

            enter.apply {
                text = if (split) "split" else "bubble"
                setTextColor(0xD9FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Fonts.ui(context, 600)
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x29FFFFFF) }
                setPadding(dpi(10f), 0, dpi(12f), 0)
                compoundDrawablePadding = dpi(5f)
                setCompoundDrawablesRelative(EnterGlyph(0xD9FFFFFF.toInt()).apply { setBounds(0, 0, dpi(15f), dpi(15f)) }, null, null, null)
                visibility = GONE
                setOnClickListener { enter() }
            }
            row.addView(enter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(28f)).apply { leftMargin = dpi(8f); rightMargin = dpi(14f) })
            // a tap anywhere on a bar counts as a tap on its field
            body.setOnClickListener { if (tapped()) field.requestFocus() }
        }

        /**
         * A tap on this bar, before its field takes focus (false: it may not). In Split the top bar goes back to
         * choosing (its chip is cleared), and the bottom one only takes over once there's a top app.
         */
        fun tapped(): Boolean {
            if (!split || slot == focus) return true
            if (slot == 0) { unpickTop(); return true }
            if (topPick == null) return false
            setFocus(1); render()
            return true
        }

        fun setShape(r: RectF, radius: Float = r.height() / 2f) {
            shape.set(r)
            corner = radius
            path.reset()
            path.addRoundRect(shape, radius, radius, Path.Direction.CW)
            halo.invalidate(); skin.invalidate(); body.invalidateOutline()
        }

        val shapeAspect get() = if (shape.height() > 0f) shape.width() / shape.height() else 1f

        /** The button at the end of the bar, saying what Enter does ([label]); null hides it. Done has no ↵. */
        fun showEnter(label: String?) {
            enter.visibility = if (label == null) GONE else VISIBLE
            if (label == null || enter.text.toString() == label) return
            enter.text = label
            enter.setCompoundDrawablesRelative(if (label == "Done") null else EnterGlyph(0xD9FFFFFF.toInt()).apply { setBounds(0, 0, dpi(15f), dpi(15f)) }, null, null, null)
            enter.setPadding(dpi(if (label == "Done") 14f else 10f), 0, dpi(if (label == "Done") 14f else 12f), 0)
        }

        fun setGhost(typed: String, rest: String) {
            if (rest.isEmpty()) { ghost.text = null; return }
            ghost.text = SpannableString(typed + rest).apply {
                setSpan(ForegroundColorSpan(0), 0, typed.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); spin.start() }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); spin.cancel(); glowAnim?.cancel() }
    }

    /**
     * Frosted glass, as Frosted icons: the blurred wallpaper behind [view] on screen (fixed to the window, so it's
     * redrawn whenever the view moves or scales), under [frost], with a soft white sheen at the top and a hairline rim.
     */
    private inner class Frost(private val view: View) {
        private val glassPaint = GlassPaint()
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = frost }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = RIM }
        private val toWindow = Matrix()
        private val drawnAt = Matrix()
        private var sheenTop = Float.NaN
        private var sheenHeight = Float.NaN
        private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
            toWindow.reset(); view.transformMatrixToGlobal(toWindow)
            if (toWindow != drawnAt) view.invalidate()
            true
        }

        fun attach() { view.viewTreeObserver.addOnPreDrawListener(moved); glass?.views?.add(view) }
        fun detach() { view.viewTreeObserver.removeOnPreDrawListener(moved); glass?.views?.remove(view) }

        fun draw(c: Canvas, path: Path, box: RectF, alpha: Float = 1f) {
            drawnAt.reset(); view.transformMatrixToGlobal(drawnAt)
            val a = (alpha * 255).roundToInt()
            if (glassPaint.aim(glass, drawnAt)) { glassPaint.paint.alpha = a; c.drawPath(path, glassPaint.paint) }
            tint.alpha = (android.graphics.Color.alpha(frost) * alpha).roundToInt()
            c.drawPath(path, tint)
            val h = minOf(box.height() * 0.5f, dp(48f))
            if (box.top != sheenTop || h != sheenHeight) {
                sheenTop = box.top; sheenHeight = h
                sheen.shader = android.graphics.LinearGradient(0f, box.top, 0f, box.top + h, SHEEN, 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            sheen.alpha = a
            c.drawPath(path, sheen)
            rim.alpha = (android.graphics.Color.alpha(RIM) * alpha).roundToInt()
            c.drawPath(path, rim)
        }
    }

    companion object {
        /** Frosted glass's sheen at the top and its rim. */
        private const val SHEEN = 0x2EFFFFFF
        private const val RIM = 0x3DFFFFFF
        private const val OPEN_MS = 380L
        /** Out of the widget and back into it. */
        private const val FLY_MS = 500L
        private const val FLY_BACK_MS = 420L
        /** Out of the 2×1 widget and back: how big the panel starts (and goes), and how small Home gets behind it. */
        private const val ZOOM_IN_MS = 420L
        private const val ZOOM_OUT_MS = 320L
        private const val ZOOM_FROM = 1.1f
        private const val ZOOM_TO = 1.06f
        private const val HOME_ZOOM = 0.9f
        /** The flight: a gentle start (it lifts off) and a long, soft settle; the width follows its own, a beat apart. */
        private val TRAVEL = android.view.animation.PathInterpolator(0.3f, 0f, 0f, 1f)
        private val STRETCH = android.view.animation.PathInterpolator(0.35f, 0f, 0f, 1f)
        private val DECEL = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val ACCEL = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        /** The widget icon's corner, as a share of its size (One UI's squircle, roughly). */
        const val ICON_CORNER = 0.36f
        private const val POP_MS = 280L
        private const val POP_STAGGER_MS = 15L
        private const val BLUR_OUT_MS = 450L
        /** How blurred, and how much smaller, the bars and card are as they go. */
        private const val BLUR_OUT_DP = 24f
        private const val BLUR_OUT_SCALE = 0.92f
        private const val SPIN_MS = 5000L
        private const val SIDE_DP = 16f
        private const val BAR_DP = 56f
        /** Room around a bar for its halo. */
        private const val BAR_PAD_DP = 18f
        private const val BAR_GAP_DP = 12f
        private const val CARD_PAD_DP = 12f
        private const val CAPTION_DP = 28f
        private const val GRID_COLS = 4
        private const val GRID_ROW_DP = 92f
        private const val TOP_HIT_DP = 80f
        private const val ROW_DP = 56f
        private const val MAX_SUGGESTED = 8
        private const val MAX_MORE = 3
        /** Multi Bubble: as many apps as the bubble stack shows (BubblesWidget.MAX), and their chips' size. */
        private const val MAX_BUBBLES = com.oneuiclone.home.widgets.BubblesWidget.MAX
        private const val PICK_DP = 30f
        /** An Enter this soon after one that added an app is the same press, delivered twice. */
        private const val DOUBLE_ENTER_MS = 250L
        /** Search or Ask's glow: orange, pink, violet, blue, cyan, round again. */
        private val GLOW = intArrayOf(0xFFFF9A3C.toInt(), 0xFFFF4F9A.toInt(), 0xFFB45CFF.toInt(), 0xFF4D8DFF.toInt(), 0xFF36D8FF.toInt(), 0xFFFF9A3C.toInt())
        private val MARKS = Regex("\\p{Mn}+")

        fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
    }
}

/** Stroked glyphs drawn on a 24-unit grid. */
internal abstract class Glyph(color: Int) : Drawable() {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    abstract fun Canvas.glyph()
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.glyph()
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A bubble with a small one beside it (the Bubble widget). */
internal class BubbleGlyph(color: Int) : Glyph(color) {
    override fun Canvas.glyph() { drawCircle(10.5f, 10.5f, 7.5f, paint); drawCircle(10.5f, 10.5f, 3.2f, paint); drawCircle(18.8f, 18.8f, 2.6f, paint) }
}

/** A bubble with a plus in it and a small one beside it (the Multi Bubble widget). */
internal class MultiBubbleGlyph(color: Int) : Glyph(color) {
    override fun Canvas.glyph() {
        drawCircle(10.5f, 10.5f, 7.5f, paint)
        drawLine(10.5f, 7.3f, 10.5f, 13.7f, paint); drawLine(7.3f, 10.5f, 13.7f, 10.5f, paint)
        drawCircle(18.8f, 18.8f, 2.6f, paint)
    }
}

/** Two windows, one above the other (the Split widget). */
internal class SplitGlyph(color: Int) : Glyph(color) {
    override fun Canvas.glyph() { drawRoundRect(4.5f, 2.5f, 19.5f, 11f, 2.6f, 2.6f, paint); drawRoundRect(4.5f, 13f, 19.5f, 21.5f, 2.6f, 2.6f, paint) }
}

/** Return ↵ (what Enter opens). */
internal class EnterGlyph(color: Int) : Glyph(color) {
    private val p = Path().apply { moveTo(20f, 5f); lineTo(20f, 12f); cubicTo(20f, 13.7f, 18.7f, 15f, 17f, 15f); lineTo(5f, 15f); moveTo(9f, 11f); lineTo(5f, 15f); lineTo(9f, 19f) }
    override fun Canvas.glyph() { drawPath(p, paint) }
}

