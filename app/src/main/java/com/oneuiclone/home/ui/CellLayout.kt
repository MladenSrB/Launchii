package com.oneuiclone.home.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Fixed grid of cells; children carry [LayoutParams] with cell coordinates. */
class CellLayout(context: Context, var cols: Int, var rows: Int) : ViewGroup(context) {

    class LayoutParams(var cellX: Int, var cellY: Int, var spanX: Int = 1, var spanY: Int = 1) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    var cellW = 0; private set
    var cellH = 0; private set

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun addCell(v: View, x: Int, y: Int, sx: Int = 1, sy: Int = 1) {
        addView(v, LayoutParams(x, y, sx, sy))
    }

    fun childAt(x: Int, y: Int): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as? LayoutParams ?: continue
            if (x >= lp.cellX && x < lp.cellX + lp.spanX && y >= lp.cellY && y < lp.cellY + lp.spanY) return c
        }
        return null
    }

    /** Cell under a point in this view's coordinates. */
    fun cellAt(px: Float, py: Float, out: IntArray) {
        out[0] = ((px - paddingLeft) / cellW.coerceAtLeast(1)).toInt().coerceIn(0, cols - 1)
        out[1] = ((py - paddingTop) / cellH.coerceAtLeast(1)).toInt().coerceIn(0, rows - 1)
    }

    fun cellLeft(x: Int) = paddingLeft + x * cellW
    fun cellTop(y: Int) = paddingTop + y * cellH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED && (layoutParams?.height ?: 0) > 0)
            layoutParams.height else MeasureSpec.getSize(heightMeasureSpec)
        cellW = (w - paddingLeft - paddingRight) / cols
        cellH = (h - paddingTop - paddingBottom) / rows
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LayoutParams
            c.measure(
                MeasureSpec.makeMeasureSpec(cellW * lp.spanX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellH * lp.spanY, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LayoutParams
            val x = cellLeft(lp.cellX)
            val y = cellTop(lp.cellY)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams() = LayoutParams(0, 0)
    override fun shouldDelayChildPressedState() = false
}
