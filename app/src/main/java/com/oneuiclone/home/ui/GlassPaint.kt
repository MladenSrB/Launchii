package com.oneuiclone.home.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader

/**
 * A paint that fills with [WidgetGlass]: whatever it draws shows the blurred wallpaper that's behind it on screen.
 * Aim it before each draw with the canvas's mapping to the window.
 */
class GlassPaint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private val local = Matrix()

    /** Points [paint] at [glass] for a canvas mapped to the window by [toWindow]; false when there's nothing to draw. */
    fun aim(glass: WidgetGlass?, toWindow: Matrix): Boolean {
        val b = glass?.bitmap ?: return false
        if (!toWindow.invert(local)) return false
        local.preScale(glass.scale, glass.scale)                         // glass pixels → window → canvas
        if (bitmap !== b) {
            bitmap = b
            paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        paint.shader.setLocalMatrix(local)
        return true
    }
}

/**
 * Fills itself with [glass] (the blurred wallpaper behind it on screen) under [fill], as a closed folder's
 * container does with Style › Blur. The glass is fixed to the window, so it redraws whenever the view moves.
 */
class GlassView(context: android.content.Context, private val glass: WidgetGlass, private val fill: Int) : android.view.View(context) {
    private val glassPaint = GlassPaint()
    private val fillPaint = Paint().apply { color = fill }
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        toWindow.reset(); transformMatrixToGlobal(toWindow)
        if (toWindow != drawnAt) invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.views += this
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        glass.views -= this
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (glassPaint.aim(glass, drawnAt)) canvas.drawPaint(glassPaint.paint)
        canvas.drawPaint(fillPaint)
    }
}
