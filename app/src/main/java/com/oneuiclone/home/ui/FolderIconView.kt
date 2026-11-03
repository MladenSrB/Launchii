package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory

/**
 * One UI folder icon (FolderIconSupplier): squircle container (#99FCFCFF default, 1.7 % inset, faint dark
 * rim) with a 3×3 preview grid — child size 0.21·icon, gap 0.04·icon, centred.
 */
open class FolderIconView(context: Context, dp: DeviceProfile) : IconView(context, dp) {

    companion object {
        const val DEFAULT_BG = 0x99FCFCFF.toInt()          // folder_icon_default_background_color
        /** FolderStyle.IconColorId: white, blue, green, orange, yellow (close_folder_color1..5). */
        val PALETTE = intArrayOf(0x99FCFCFF.toInt(), 0x99AACFEE.toInt(), 0x9999DCC8.toInt(), 0x99EFC898.toInt(), 0x99F3E1AB.toInt())
        const val GRID = 3
        const val SIZE_RATIO = 0.21f
        const val GAP_RATIO = 0.04f
    }

    var previews: List<Bitmap> = emptyList()
        set(v) { field = v; invalidate() }
    var bgColor = DEFAULT_BG
        set(v) { field = v; fillPaint.color = v; invalidate() }
    /** 0..1 — used when the folder is opening (preview hidden while icons fly). */
    var previewAlpha = 1f
        set(v) { field = v; invalidate() }
    var containerAlpha = 1f
        set(v) { field = v; invalidate() }
    /** Preview slots left empty while the apps for them fly in (dropped into the folder). */
    var hiddenPreviews: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    /**
     * Style › Blur: the blurred wallpaper behind the icon fills the container, under its colour at [fillOpacity].
     * It's fixed to the window, so the icon redraws whenever it moves on screen.
     */
    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; invalidate()
        }
    var fillOpacity = 1f
        set(v) { field = v; invalidate() }
    private val glassPaint = GlassPaint()
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val glassAt = Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        if (glass != null) {
            toWindow.reset(); transformMatrixToGlobal(toWindow)
            if (toWindow != drawnAt) invalidate()
        }
        true
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

    protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DEFAULT_BG }
    protected val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x24000000
    }
    protected val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    protected val shape = Path()
    private var shapeSize = -1
    private val dst = RectF()

    protected fun ensureShape() {
        if (shapeSize == iconSize) return
        shapeSize = iconSize
        val inset = iconSize * 3f / 180f
        val inner = iconSize - inset * 2
        shape.reset()
        IconFactory.maskPath(100f).transform(Matrix().apply {
            setScale(inner / 100f * 1.02f, inner / 100f * 1.02f)
            postTranslate(inset - inner * 0.01f, inset - inner * 0.01f)
        }, shape)
        rimPaint.strokeWidth = iconSize * 2.5f / 180f
    }

    /**
     * The squircle container (rim, glass, colour) on a canvas already moved to the icon's [left], [top], grown by
     * [folderBgScale] while an app hovers over the folder.
     */
    protected fun drawContainer(canvas: Canvas, left: Int, top: Int) {
        canvas.save()
        canvas.scale(folderBgScale, folderBgScale, iconSize / 2f, iconSize / 2f)
        rimPaint.alpha = (0x24 * containerAlpha).toInt()
        canvas.drawPath(shape, rimPaint)
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        glassAt.set(drawnAt); glassAt.preTranslate(left.toFloat(), top.toFloat())
        if (glassPaint.aim(glass, glassAt)) {
            glassPaint.paint.alpha = (255 * containerAlpha).toInt()
            canvas.drawPath(shape, glassPaint.paint)
        }
        fillPaint.alpha = (android.graphics.Color.alpha(bgColor) * containerAlpha * fillOpacity).toInt()
        canvas.drawPath(shape, fillPaint)
        canvas.restore()
    }

    /** Position of preview child [rank] relative to icon top-left. */
    open fun childRect(rank: Int, out: RectF) {
        val f = iconSize.toFloat()
        val gap = GAP_RATIO * f
        val size = SIZE_RATIO * f
        val start = (f - (GRID - 1) * gap - GRID * size) / 2f
        val step = gap + size
        val x = start + (rank % GRID) * step
        val y = start + (rank / GRID) * step
        out.set(x, y, x + size, y + size)
    }

    override fun drawIcon(canvas: Canvas, left: Int, top: Int) {
        ensureShape()
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        if (containerAlpha > 0f) drawContainer(canvas, left, top)
        if (previewAlpha > 0f) {
            bmpPaint.alpha = (255 * previewAlpha).toInt()
            previews.take(GRID * GRID).forEachIndexed { i, b ->
                if (i in hiddenPreviews) return@forEachIndexed
                childRect(i, dst)
                shaped.draw(canvas, b, dst, bmpPaint)
            }
        }
        canvas.restore()
    }

    fun iconRectOnScreen(out: Rect) = iconBoundsOnScreen(out)

    /** The folder icon as drawn, for the surface an app closes into. */
    fun renderIcon(): Bitmap {
        val b = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        drawIcon(Canvas(b), 0, 0)
        return b
    }
}
