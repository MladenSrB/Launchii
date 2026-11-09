package com.oneuiclone.home.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.motion.Springs
import kotlin.math.min

/**
 * One UI page edit (HomeScreen.Edit, PageEditStateInterpolator, spec §14):
 *  - pages scale to min(76 % W, 69 % H) with their top at 13.8 % H, 4.7 % W page spacing
 *  - enter spring 225 / 0.67, exit spring 360 / 0.99
 *  - dock alpha out 150 ms linear, panel translation 200 ms SINE_OUT_70, indicator 300 ms SINE_OUT_70
 *  - background: captured blur + 25 % dim
 */
class EditModeController(
    private val context: Context,
    private val dpProvider: () -> DeviceProfile,
    private val workspace: () -> PagedView,
    private val hotseat: () -> View,
    private val indicator: () -> View,
    private val homeLayer: FrameLayout,
    private val background: BackgroundController,
    private val onAddPage: () -> Unit,
    private val buttons: List<Pair<String, () -> Unit>>,
    private val onDeletePage: (Int) -> Unit = {},
    /** Home screen search button: hidden with the dock, the page dots re-centre. */
    /** The Home screen's Search pill (and the Update pill beside it), hidden like the dock while editing. */
    private val homeButtons: () -> List<View> = { emptyList() },
) {
    var isActive = false; private set
    private var spring: SpringAnimation? = null
    private var progressAnim: ValueAnimator? = null
    private var panel: LinearLayout? = null
    private var plusPage: PlusPage? = null
    private var headers: PageHeaders? = null
    private val scaleHolder = FloatValueHolder(1f)

    private val dp get() = dpProvider()
    private val targetScale get() = min(0.76f, 0.69f)                  // page_edit_scale_x/y ratios
    private val pivotY get() = dp.heightPx * 0.138f / (1f - targetScale)  // page_edit_top_padding 13.8 %

    fun enter() {
        if (isActive) return
        isActive = true
        keepBackground = false
        val ws = workspace()
        ws.pivotX = dp.widthPx / 2f
        ws.pivotY = pivotY
        ws.pageSpacing = (dp.widthPx * 0.047f / targetScale).toInt()       // page_spacing_in_page_edit
        ws.pageLocked = { it !is PlusPage }
        // "+" page
        plusPage = PlusPage(context, dp).also { p ->
            p.setOnClickListener { onAddPage(); exit() }
            ws.addView(p)
        }
        for (i in 0 until ws.childCount) {
            val page = ws.getChildAt(i)
            if (page !is PlusPage) page.background = pageCard()
        }
        ensurePanel()
        animateScale(targetScale, Springs.PAGE_EDIT_ENTER_STIFFNESS, Springs.PAGE_EDIT_ENTER_DAMPING)
        animateProgress(1f)
    }

    private var keepBackground = false

    fun exit(page: Int = -1, keepBackground: Boolean = false) {
        if (!isActive) return
        isActive = false
        this.keepBackground = keepBackground
        val ws = workspace()
        ws.pageLocked = { false }
        if (page >= 0) ws.snapToPage(page.coerceAtMost(ws.pageCount - 2))
        animateScale(1f, Springs.PAGE_EDIT_EXIT_STIFFNESS, Springs.PAGE_EDIT_EXIT_DAMPING) {
            plusPage?.let { ws.removeView(it) }; plusPage = null
            ws.pageSpacing = 0
            for (i in 0 until ws.childCount) ws.getChildAt(i).background = null
            if (ws.currentPage >= ws.pageCount) ws.setCurrentPageImmediately(ws.pageCount - 1)
        }
        animateProgress(0f)
    }

    /** Leaves edit mode at once, for when Home is hidden behind a closing app. */
    fun exitNow() {
        if (!isActive) return
        exit()
        progressAnim?.end()
        spring?.skipToEnd()
    }

    private fun pageCard(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp.dp(26f) / targetScale
        setColor(0x1FFFFFFF)
        setStroke((dp.dp(1f) / targetScale).toInt(), 0x33FFFFFF)
        alpha = 0
    }

    private fun animateScale(to: Float, stiffness: Float, damping: Float, end: (() -> Unit)? = null) {
        spring?.cancel()
        val ws = workspace()
        scaleHolder.value = ws.scaleX
        ws.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        spring = SpringAnimation(scaleHolder).apply {
            spring = SpringForce(to).setStiffness(stiffness).setDampingRatio(damping)
            setMinimumVisibleChange(0.002f)
            addUpdateListener { _, v, _ -> ws.scaleX = v; ws.scaleY = v; headers?.invalidate() }
            addEndListener { _, _, _, _ -> ws.setLayerType(View.LAYER_TYPE_NONE, null); end?.invoke() }
            start()
        }
    }

    /** Linear 0..1 timeline (500 ms) from which the sub-animations are cut (PAGE_EDIT_STATE_CHANGE_DURATION_MS). */
    private fun animateProgress(to: Float) {
        progressAnim?.cancel()
        val ws = workspace()
        val from = background.currentProgress.takeIf { background.current == BackgroundController.Kind.EDIT } ?: 0f
        progressAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 500
            interpolator = Easing.LINEAR
            addUpdateListener {
                val p = it.animatedValue as Float
                val ms = (if (to > from) p else 1f - p) * 500f
                val entering = to > from
                if (!keepBackground) background.set(BackgroundController.Kind.EDIT, p)
                // HideHotseatAlpha: 150 ms linear
                val chrome = if (entering) 1f - (ms / 150f).coerceIn(0f, 1f) else (ms / 150f).coerceIn(0f, 1f)
                hotseat().alpha = chrome
                // faded out is not enough: the dock sits under the panel's buttons and would still open apps
                hotseat().visibility = if (chrome > 0f) View.VISIBLE else View.INVISIBLE
                val buttons = homeButtons()
                for (b in buttons) {
                    b.alpha = chrome
                    b.visibility = if (chrome > 0f) View.VISIBLE else View.INVISIBLE
                }
                if (buttons.isNotEmpty()) (indicator() as? PageIndicator)?.leadingShown = Easing.SINE_OUT_70.getInterpolation(chrome)
                // cards: EnterCellLayoutAlpha 200 ms / Exit 100 ms
                val cardA = if (entering) (ms / 200f).coerceIn(0f, 1f) else 1f - (ms / 100f).coerceIn(0f, 1f)
                for (i in 0 until ws.childCount) (ws.getChildAt(i).background as? GradientDrawable)?.alpha = (255 * cardA).toInt()
                plusPage?.alpha = cardA
                headers?.alpha = cardA
                // panel: PageEditPanelTranslation 200 ms SINE_OUT_70
                panel?.let { pn ->
                    val t = Easing.SINE_OUT_70.getInterpolation((ms / 200f).coerceIn(0f, 1f))
                    val shown = if (entering) t else 1f - t
                    pn.translationY = pn.height * (1f - shown)
                    pn.alpha = shown
                    pn.visibility = if (shown > 0f) View.VISIBLE else View.INVISIBLE
                }
                // indicator: moves under the scaled page (IndicatorTranslation 300 ms SINE_OUT_70)
                val it2 = Easing.SINE_OUT_70.getInterpolation((ms / 300f).coerceIn(0f, 1f))
                val shownI = if (entering) it2 else 1f - it2
                val scaledBottom = dp.heightPx * 0.138f + dp.heightPx * targetScale
                indicator().translationY = (scaledBottom + dp.heightPx * 0.012f - (dp.indicatorRect.top)) * shownI
            }
            start()
        }
    }

    private fun ensurePanel() {
        if (panel != null) return
        val dp = dp
        val h = (dp.heightPx * 0.12f).toInt()                               // page_edit_panel_height_ratio
        val p = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((dp.widthPx * 0.04f).toInt(), (dp.heightPx * 0.021f).toInt(), (dp.widthPx * 0.04f).toInt(), 0)
            visibility = View.INVISIBLE
            isClickable = true                                              // taps between buttons stop here
        }
        for ((label, action) in buttons) {
            val b = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { action() }
            }
            val iconSize = (dp.heightPx * 0.034f).toInt()                  // page_edit_button_icon_size
            b.addView(EditGlyph(context, label), LinearLayout.LayoutParams(iconSize, iconSize))
            b.addView(TextView(context).apply {
                text = label
                setTextColor(0xFFFAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dp.dp(12f))
                typeface = Fonts.ui(context, 400)
                gravity = Gravity.CENTER
                maxLines = 2
                setShadowLayer(dp.dp(2f), 0f, 0f, 0x59000000)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (dp.heightPx * 0.011f).toInt()                  // page_edit_button_icon_spacing
            })
            p.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        homeLayer.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = dp.heightPx - h - dp.insets.bottom
        })
        panel = p
        val hd = PageHeaders(context, dp, workspace) { onDeletePage(it) }
        hd.alpha = 0f
        homeLayer.addView(hd, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (dp.heightPx * 0.138f).toInt()))
        headers = hd
    }

    /** Workspace scrolled while editing: the delete buttons ride along with their pages. */
    fun onWorkspaceScroll() { if (isActive || headers?.alpha != 0f) headers?.invalidate() }

    /** Re-apply page cards / "+" page after the workspace was rebound while editing. */
    fun refreshPages() {
        if (!isActive) return
        val ws = workspace()
        for (i in 0 until ws.childCount) {
            val page = ws.getChildAt(i)
            if (page !is PlusPage) page.background = pageCard().apply { alpha = 255 }
        }
        val plus = plusPage
        if (plus == null || plus.parent !== ws) {
            plusPage = PlusPage(context, dp).also { p -> p.setOnClickListener { onAddPage(); exit() }; ws.addView(p) }
        }
        headers?.invalidate()
    }

    fun reset() {
        panel?.let { homeLayer.removeView(it) }
        panel = null
        headers?.let { homeLayer.removeView(it) }
        headers = null
        isActive = false
        workspace().pageLocked = { false }
        hotseat().visibility = View.VISIBLE
    }

    /**
     * One UI page edit: a delete (bin) button centred above each page card. Drawn for every page at its
     * on-screen position (pages are scaled and scrolled), hidden when only one page is left.
     */
    private class PageHeaders(
        context: Context,
        private val dp: DeviceProfile,
        private val workspace: () -> PagedView,
        private val onDelete: (Int) -> Unit,
    ) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFAFAFA.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            setShadowLayer(dp.dp(2f), 0f, 0f, 0x59000000)
        }
        private val size = dp.dp(24f)
        private val hit = dp.dp(28f)
        private var pressed = -1

        private val realPages get() = workspace().let { ws -> (0 until ws.childCount).count { ws.getChildAt(it) !is PlusPage } }

        private fun centerX(i: Int): Float {
            val ws = workspace()
            val step = ws.width + ws.pageSpacing
            return ws.width / 2f + (i * step - ws.scrollX) * ws.scaleX
        }

        private val cy get() = height - dp.dp(30f)

        override fun onDraw(canvas: Canvas) {
            val n = realPages
            if (n <= 1) return
            p.strokeWidth = size * 0.08f
            for (i in 0 until n) {
                val cx = centerX(i)
                if (cx < -size || cx > width + size) continue
                drawBin(canvas, cx, cy)
            }
        }

        private fun drawBin(c: Canvas, cx: Float, cy: Float) {
            val r = size / 2f
            val w = r * 0.7f
            c.drawLine(cx - r, cy - r * 0.55f, cx + r, cy - r * 0.55f, p)
            c.drawLine(cx - r * 0.3f, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.9f, p)
            c.drawRoundRect(cx - w, cy - r * 0.55f, cx + w, cy + r, size * 0.08f, size * 0.08f, p)
            c.drawLine(cx - w * 0.35f, cy - r * 0.15f, cx - w * 0.35f, cy + r * 0.6f, p)
            c.drawLine(cx + w * 0.35f, cy - r * 0.15f, cx + w * 0.35f, cy + r * 0.6f, p)
        }

        private fun hitPage(x: Float, y: Float): Int {
            val n = realPages
            if (n <= 1 || alpha < 0.5f || kotlin.math.abs(y - cy) > hit) return -1
            return (0 until n).firstOrNull { kotlin.math.abs(x - centerX(it)) < hit } ?: -1
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { pressed = hitPage(e.x, e.y); return pressed >= 0 }
                android.view.MotionEvent.ACTION_UP -> {
                    if (pressed >= 0 && hitPage(e.x, e.y) == pressed) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        onDelete(pressed)
                    }
                    pressed = -1
                }
                android.view.MotionEvent.ACTION_CANCEL -> pressed = -1
            }
            return true
        }
    }

    /** "+" page (plus_page_button_size_sw_ratio 22.2 %). */
    private class PlusPage(context: Context, private val dp: DeviceProfile) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAFAFA.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val card = GradientDrawable().apply { cornerRadius = dp.dp(26f) / 0.69f; setColor(0x14FFFFFF); setStroke((dp.dp(1f) / 0.69f).toInt(), 0x33FFFFFF) }
        init { isClickable = true }
        override fun onDraw(canvas: Canvas) {
            card.setBounds(0, 0, width, height); card.draw(canvas)
            val s = minOf(dp.widthPx, dp.heightPx) * 0.222f / 2f
            p.strokeWidth = s * 0.08f
            val cx = width / 2f; val cy = height / 2f
            canvas.drawLine(cx - s / 2, cy, cx + s / 2, cy, p)
            canvas.drawLine(cx, cy - s / 2, cx, cy + s / 2, p)
        }
    }

    /** Simple glyphs for the bottom panel buttons. */
    private class EditGlyph(context: Context, private val label: String) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFAFAFA.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            setShadowLayer(4f, 0f, 0f, 0x40000000)
        }
        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            p.strokeWidth = s * 0.07f
            val c = s / 2f
            when {
                label.startsWith("Wallpaper") -> {
                    canvas.drawRoundRect(s * 0.15f, s * 0.15f, s * 0.85f, s * 0.85f, s * 0.12f, s * 0.12f, p)
                    canvas.drawLine(s * 0.22f, s * 0.72f, s * 0.45f, s * 0.48f, p); canvas.drawLine(s * 0.45f, s * 0.48f, s * 0.78f, s * 0.75f, p)
                }
                label.startsWith("Icons") -> {
                    // icon shapes: a triangle over a circle and a rounded square
                    canvas.drawPath(android.graphics.Path().apply {
                        moveTo(c, s * 0.14f); lineTo(s * 0.71f, s * 0.46f); lineTo(s * 0.29f, s * 0.46f); close()
                    }, p)
                    canvas.drawCircle(s * 0.3f, s * 0.7f, s * 0.15f, p)
                    canvas.drawRoundRect(s * 0.56f, s * 0.55f, s * 0.86f, s * 0.85f, s * 0.06f, s * 0.06f, p)
                }
                label.startsWith("Widgets") -> {
                    val g = s * 0.06f
                    canvas.drawRoundRect(s * 0.15f, s * 0.15f, c - g, c - g, s * 0.06f, s * 0.06f, p)
                    canvas.drawRoundRect(c + g, s * 0.15f, s * 0.85f, c - g, s * 0.06f, s * 0.06f, p)
                    canvas.drawRoundRect(s * 0.15f, c + g, s * 0.85f, s * 0.85f, s * 0.06f, s * 0.06f, p)
                }
                else -> {
                    canvas.drawCircle(c, c, s * 0.16f, p)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        canvas.drawLine(c + (s * 0.27f * Math.cos(a)).toFloat(), c + (s * 0.27f * Math.sin(a)).toFloat(),
                            c + (s * 0.37f * Math.cos(a)).toFloat(), c + (s * 0.37f * Math.sin(a)).toFloat(), p)
                    }
                }
            }
        }
    }
}
