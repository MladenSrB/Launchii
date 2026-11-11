package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.model.WorkspaceModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One UI widget resize frame (GridResizableFrame: reset spring 200 / 0.7). Edge handles resize the span,
 * snapping to the cell grid on release; overlaps and the provider's minimum size are respected.
 */
@SuppressLint("ViewConstructor")
class WidgetResizeFrame(
    context: Context,
    private val dp: DeviceProfile,
    private val model: WorkspaceModel,
    private val item: HomeItem.Widget,
    private val minSpanX: Int,
    private val minSpanY: Int,
    private val onCommit: () -> Unit,
    private val onDismiss: () -> Unit,
) : View(context) {

    private val rect = RectF(cellRect(item.x, item.y, item.sx, item.sy))
    private var dismissing = false
    private var handle = -1                   // 0 left, 1 top, 2 right, 3 bottom
    private var lastX = 0f
    private var lastY = 0f
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp.dp(2f); color = 0xFFFAFAFA.toInt()
        setShadowLayer(dp.dp(2f), 0f, 0f, 0x40000000)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAFAFA.toInt(); setShadowLayer(dp.dp(2f), 0f, 0f, 0x40000000) }
    private val knobR = dp.dp(7f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadow layers on strokes
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()
    }

    private fun cellRect(x: Int, y: Int, sx: Int, sy: Int): Rect {
        val g = dp.workspaceGrid
        return Rect(g.left + x * dp.cellWidth, g.top + y * dp.cellHeight,
            g.left + (x + sx) * dp.cellWidth, g.top + (y + sy) * dp.cellHeight)
    }

    /** The side handles sit on the screen edges: keep the back gesture from swallowing them. */
    private fun excludeHandlesFromGestures() {
        val h = dp.dp(28f)
        systemGestureExclusionRects = listOf(
            Rect((rect.left - h).toInt(), (rect.centerY() - h).toInt(), (rect.left + h).toInt(), (rect.centerY() + h).toInt()),
            Rect((rect.right - h).toInt(), (rect.centerY() - h).toInt(), (rect.right + h).toInt(), (rect.centerY() + h).toInt()),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (handle < 0) excludeHandlesFromGestures()
        val r = dp.dp(26f)
        canvas.drawRoundRect(rect, r, r, stroke)
        canvas.drawCircle(rect.left, rect.centerY(), knobR, knob)
        canvas.drawCircle(rect.centerX(), rect.top, knobR, knob)
        canvas.drawCircle(rect.right, rect.centerY(), knobR, knob)
        canvas.drawCircle(rect.centerX(), rect.bottom, knobR, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dismissing) return false
        val hit = dp.dp(28f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = when {
                    abs(ev.x - rect.left) < hit && abs(ev.y - rect.centerY()) < rect.height() / 2 -> 0
                    abs(ev.y - rect.top) < hit && abs(ev.x - rect.centerX()) < rect.width() / 2 -> 1
                    abs(ev.x - rect.right) < hit && abs(ev.y - rect.centerY()) < rect.height() / 2 -> 2
                    abs(ev.y - rect.bottom) < hit && abs(ev.x - rect.centerX()) < rect.width() / 2 -> 3
                    else -> -1
                }
                lastX = ev.x; lastY = ev.y
                if (handle < 0) { dismiss(); return false }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (handle >= 0) {
                val dx = ev.x - lastX; val dy = ev.y - lastY
                lastX = ev.x; lastY = ev.y
                when (handle) {
                    0 -> rect.left = (rect.left + dx).coerceAtMost(rect.right - dp.cellWidth * 0.8f)
                    1 -> rect.top = (rect.top + dy).coerceAtMost(rect.bottom - dp.cellHeight * 0.8f)
                    2 -> rect.right = (rect.right + dx).coerceAtLeast(rect.left + dp.cellWidth * 0.8f)
                    3 -> rect.bottom = (rect.bottom + dy).coerceAtLeast(rect.top + dp.cellHeight * 0.8f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (handle >= 0) {
                snap(); handle = -1
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                handle = -1
                animateTo(RectF(cellRect(item.x, item.y, item.sx, item.sy)))
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun snap() {
        val g = dp.workspaceGrid
        var x0 = ((rect.left - g.left) / dp.cellWidth).roundToInt().coerceIn(0, dp.homeCols - 1)
        var y0 = ((rect.top - g.top) / dp.cellHeight).roundToInt().coerceIn(0, dp.homeRows - 1)
        var x1 = ((rect.right - g.left) / dp.cellWidth).roundToInt().coerceIn(x0 + 1, dp.homeCols)
        var y1 = ((rect.bottom - g.top) / dp.cellHeight).roundToInt().coerceIn(y0 + 1, dp.homeRows)
        if (x1 - x0 < minSpanX) { if (handle == 0) x0 = x1 - minSpanX else x1 = x0 + minSpanX }
        if (y1 - y0 < minSpanY) { if (handle == 1) y0 = y1 - minSpanY else y1 = y0 + minSpanY }
        val ok = x0 >= 0 && y0 >= 0 && x1 <= dp.homeCols && y1 <= dp.homeRows &&
            !model.isOccupied(item.screen, x0, y0, x1 - x0, y1 - y0, item)
        if (ok) {
            item.x = x0; item.y = y0; item.sx = x1 - x0; item.sy = y1 - y0
            model.save()
            onCommit()
        }
        animateTo(RectF(cellRect(item.x, item.y, item.sx, item.sy)))
    }

    /** Spring the frame to its snapped bounds (RESET_ANIMATION 200 / 0.7). */
    private fun animateTo(target: RectF) {
        val start = RectF(rect)
        val h = FloatValueHolder(0f)
        SpringAnimation(h).apply {
            spring = SpringForce(1f).setStiffness(200f).setDampingRatio(0.7f)
            setMinimumVisibleChange(0.002f)
            addUpdateListener { _, v, _ ->
                rect.set(start.left + (target.left - start.left) * v, start.top + (target.top - start.top) * v,
                    start.right + (target.right - start.right) * v, start.bottom + (target.bottom - start.bottom) * v)
                invalidate()
            }
            start()
        }
    }

    fun dismiss() {
        if (dismissing) return
        dismissing = true
        handle = -1
        parent?.requestDisallowInterceptTouchEvent(false)
        animate().alpha(0f).setDuration(150).withEndAction { (parent as? android.view.ViewGroup)?.removeView(this); onDismiss() }.start()
    }
}
