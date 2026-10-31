package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.widgets.QuickOpenWidget
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 2×1 Bubble & Split widget on Home: one frosted pill a little taller than an icon ([HEIGHT]) and as wide as two
 * (its ends line up with the icons around it), centred in its row or on the icons' centre line ([centred]), Bubble on the left half and Split on the
 * right; each half opens its own panel ([onTap]), which Home shrinks and fades behind. No name: the glyphs say it.
 */
@SuppressLint("ViewConstructor")
class QuickOpenPairView(context: Context, dp: DeviceProfile, private val frost: Int) : IconView(context, dp) {

    var onTap: ((QuickOpenWidget.Kind) -> Unit)? = null
    /** Touch down (true) and up (false), for Home's press ripple. */
    var onPress: ((MotionEvent, Boolean) -> Unit)? = null

    /** Centred in its row, as other widgets are; otherwise on the icons' centre line, level with the icons beside it. */
    var centred = true
        set(v) { if (field != v) { field = v; layoutPill(); invalidate() } }

    /** The blurred wallpaper the pill shows, as Frosted icons' plates. */
    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; invalidate()
        }

    private val pill = RectF()
    private val pillPath = Path()
    private var tile: Bitmap? = null
    private val glassPaint = GlassPaint()
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        toWindow.reset(); transformMatrixToGlobal(toWindow)
        if (toWindow != drawnAt) invalidate()
        true
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPill()
    }

    private fun layoutPill() {
        if (width == 0) return
        val size = dp.iconSizePx
        // the container is two cells less its padding: each end sits where a cell's icon would
        val pad = (dp.cellWidth * 2 - width) / 2f
        val inset = ((dp.cellWidth - size) / 2f - pad).coerceAtLeast(0f)
        // a little taller than the icons
        val h = size * HEIGHT
        val top = if (centred) (height - h) / 2f else iconTop() + (size - h) / 2f
        pill.set(inset, top, width - inset, top + h)
        pillPath.reset()
        pillPath.addRoundRect(pill, pill.height() / 2f, pill.height() / 2f, Path.Direction.CW)
        val tw = pill.width().roundToInt(); val th = pill.height().roundToInt()
        if (tile?.width != tw || tile?.height != th) tile = QuickOpenWidget.pairTile(tw, th, frost)
    }

    override fun onDraw(canvas: Canvas) {
        if (pill.isEmpty) layoutPill()
        val t = tile ?: return
        // the glass in the pill's shape, then the pill itself (its see-through tint and glyphs)
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (glassPaint.aim(glass, drawnAt)) canvas.drawPath(pillPath, glassPaint.paint)
        canvas.drawBitmap(t, null, pill, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; onPress?.invoke(ev, true) }
            MotionEvent.ACTION_UP -> {
                onPress?.invoke(ev, false)
                if (abs(ev.x - downX) < slop && abs(ev.y - downY) < slop) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    onTap?.invoke(if (ev.x < pill.centerX()) QuickOpenWidget.Kind.BUBBLE else QuickOpenWidget.Kind.SPLIT)
                }
            }
            MotionEvent.ACTION_CANCEL -> onPress?.invoke(ev, false)
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass?.views?.add(this)
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        glass?.views?.remove(this)
        super.onDetachedFromWindow()
    }

    companion object {
        /** The pill's height, in icon sizes. */
        private const val HEIGHT = 1.1f
    }
}
