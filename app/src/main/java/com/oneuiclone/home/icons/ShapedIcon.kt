package com.oneuiclone.home.icons

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.util.WeakHashMap

/**
 * Draws icon bitmaps filled into the icon shape instead of as whole squares (0.173). On the user's Pixel 10 Pro XL
 * (PowerVR GPU, Android 17) the GPU drew the see-through corners of some icons pure white, though their bytes were
 * clear and every copy (fresh, hardware, F16, alpha-1, mipmapped, unfiltered, force dark off) did the same; only a
 * software layer or a fill through the shape (Icon check's 10 and 11) came out clean. A fill never draws the corners.
 * One per view: it keeps a shader per bitmap and the shape's path per size.
 */
class ShapedIcon {
    private val shaders = WeakHashMap<Bitmap, BitmapShader>()
    private val matrix = Matrix()
    private var path: Path? = null
    private var pathSize = -1f
    private var pathShape = ""

    /** [b] into [dst] (a square) with [paint]'s alpha and filters, cut to the current icon shape. */
    fun draw(canvas: Canvas, b: Bitmap, dst: RectF, paint: Paint) {
        val s = dst.width()
        if (s <= 0f || b.isRecycled) return
        if (s != pathSize || pathShape != IconShape.current) {
            path = IconFactory.maskPath(s); pathSize = s; pathShape = IconShape.current
        }
        val shader = shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        matrix.setScale(s / b.width, s / b.height)
        shader.setLocalMatrix(matrix)
        val aa = paint.isAntiAlias
        paint.shader = shader
        paint.isAntiAlias = true
        canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.drawPath(path!!, paint)
        canvas.restore()
        paint.shader = null
        paint.isAntiAlias = aa
    }

    private val tmp = RectF()

    fun draw(canvas: Canvas, b: Bitmap, dst: android.graphics.Rect, paint: Paint) {
        tmp.set(dst); draw(canvas, b, tmp, paint)
    }
}
