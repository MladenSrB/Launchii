package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Blurred snapshot of what is behind a card, under a tint: frosted glass (quick options, pop-up folders). */
class BlurBackdropView(context: Context) : View(context) {
    private var bmp: Bitmap? = null
    var tint = 0
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)

    fun set(b: Bitmap, radius: Float) {
        bmp = b
        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, null, Rect(0, 0, width, height), p)
        canvas.drawColor(tint)
    }
}
