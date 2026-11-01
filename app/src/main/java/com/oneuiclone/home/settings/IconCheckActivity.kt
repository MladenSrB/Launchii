package com.oneuiclone.home.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oneuiclone.home.Launcher
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.ui.Fonts

/**
 * Temporary (0.168): Icons › Icon check, for the white corners some icons get on the user's phone only. For each of
 * Home's icons: the icon on a checkerboard drawn by the GPU and in software, whether its corner was clear when it was
 * made (IconFactory.DIAG) and is now, and a software redraw's corner. Icons with a solid corner anywhere come first.
 */
class IconCheckActivity : OneUiPage() {

    private var built = false

    override fun build() {
        if (built) return
        built = true
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        appBar("Icon check")
        val apps = Launcher.live?.get()?.repo?.apps.orEmpty()
        val rows = apps.map { app ->
            val b = app.icon
            val now = IconFactory.corner(b)
            // the icon drawn again in software onto a clear bitmap: a solid corner here means the pixels are in the bitmap
            val redraw = if (b.isRecycled) "recycled" else Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).let {
                Canvas(it).drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)); IconFactory.corner(it).also { _ -> it.recycle() }
            }
            val made = IconFactory.DIAG["${app.key.component.packageName}/${b.width}"] ?: "not recorded"
            val bad = now != "a0" || redraw != "a0" || !made.contains("corner a0")
            Triple(app, bad, "now: corner $now · redraw $redraw · ${b.config} alpha ${b.hasAlpha()} mutable ${b.isMutable} gen ${b.generationId}\n$made")
        }.sortedByDescending { it.second }
        val badCount = rows.count { it.second }
        card {
            current?.addView(text("$badCount of ${rows.size} icons have a solid corner in the bitmap.\n" +
                "Each row: the icon drawn by the GPU (left) and in software (right), on a checkerboard. " +
                "If an icon shows white corners here but its numbers say a0, the white is added when it's drawn.", 14f, this.text))
        }
        for ((app, bad, info) in rows) card {
            val c = current ?: return@card
            c.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            for (software in listOf(false, true)) top.addView(FrameLayout(this).apply {
                background = Checker(dp(6f))
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                addView(ImageView(this@IconCheckActivity).apply {
                    setImageBitmap(app.icon)
                    if (software) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }, FrameLayout.LayoutParams(dp(48f), dp(48f)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10f) })
            top.addView(text((if (bad) "⚠ " else "") + app.label, 16f, if (bad) 0xFFFF5C5C.toInt() else this.text))
            c.addView(top)
            c.addView(text("${app.key.component.packageName}\n$info", 11.5f, sub).apply { setTextIsSelectable(true) })
        }
        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(page)
    }

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Fonts.ui(this@IconCheckActivity, 400)
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
    }

    /** A grey and white checkerboard of [cell] px squares, so see-through corners show as see-through. */
    private class Checker(private val cell: Int) : android.graphics.drawable.Drawable() {
        private val p = Paint().apply { color = 0xFF6E6E6E.toInt() }
        override fun draw(canvas: Canvas) {
            canvas.drawColor(0xFFA8A8A8.toInt())
            val b = bounds
            var y = b.top
            while (y < b.bottom) {
                var x = b.left + if ((y - b.top) / cell % 2 == 0) 0 else cell
                while (x < b.right) { canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), p); x += cell * 2 }
                y += cell
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
}
