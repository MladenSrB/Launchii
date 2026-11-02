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
 * Temporary (0.168, 0.170): Icons › Icon check, for the white corners some icons get on the user's phone only. For each
 * of Home's icons, on a checkerboard: the icon drawn by the GPU, in software, by the GPU from a fresh copy (new pixels,
 * so no texture the GPU kept) and from a hardware copy (uploaded once from the bytes as they are now). Then its corner
 * pixel, the raw bytes there and its loose pixels (IconFactory.loose), and how it was made (IconFactory.DIAG). Icons
 * with a solid corner or loose pixels come first.
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
            val (loose, raw) = IconFactory.loose(b)
            val bad = now != "a0" || redraw != "a0" || !made.contains("corner a0") || loose > 0
            Triple(app, bad, "now: corner $now · raw $raw · loose $loose · redraw $redraw · ${b.config} alpha ${b.hasAlpha()} mutable ${b.isMutable} gen ${b.generationId}\n$made")
        }.sortedByDescending { it.second }
        val badCount = rows.count { it.second }
        card {
            current?.addView(text("$badCount of ${rows.size} icons have a solid corner or loose pixels in the bitmap.\n" +
                "Each row, on a checkerboard: 1 the icon drawn by the GPU, 2 in software, 3 the GPU from a fresh copy, " +
                "4 the GPU from a hardware copy. Screenshot any row where 1 shows white corners: which of 2–4 are clean " +
                "tells where the white comes from.", 14f, this.text))
        }
        for ((app, bad, info) in rows) card {
            val c = current ?: return@card
            c.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            c.addView(text((if (bad) "⚠ " else "") + app.label, 16f, if (bad) 0xFFFF5C5C.toInt() else this.text))
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val b = app.icon
            val fresh = if (b.isRecycled) null else b.copy(Bitmap.Config.ARGB_8888, false)
            val hardware = if (b.isRecycled) null else runCatching { b.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
            for ((i, shown) in listOf(b, b, fresh, hardware).withIndex()) top.addView(FrameLayout(this).apply {
                background = Checker(dp(6f))
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                addView(ImageView(this@IconCheckActivity).apply {
                    if (shown != null) setImageBitmap(shown)
                    if (i == 1) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }, FrameLayout.LayoutParams(dp(48f), dp(48f)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10f) })
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
