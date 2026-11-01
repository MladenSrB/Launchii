package com.oneuiclone.home.icons

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.oneuiclone.home.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Re-implementation of One UI 8.5's icon-tray pipeline (spec §16):
 * layers → emboss highlight (SRC_OVER) → emboss shadow (MULTIPLY) → night layer → icon shape mask (the squircle by default).
 */
class IconFactory(private val context: Context) {

    companion object {
        /** framework-res config_icon_mask on S26 (One UI 8.5). */
        const val ONE_UI_MASK =
            "M99,50C99,6.935,77.063,1,50,1C22.935,1,1,6.935,1,50c0,43.063,21.935,49,49,49C77.063,99,99,93.063,99,50z"
        private const val TRAY_COLOR = 0xFFFEFEFE.toInt()          // ICON_BG_COLOR (-65794)
        private const val NIGHT_LAYER = 0x19000000                  // sLayerColorForNight
        private const val CONTAIN_SCALE = 0.72f                     // DEFAULT_THEME_APPICON_SCALE
        private const val ANALYSIS_SIZE = 192
        /** Dark icons' plate: near-black, a touch lighter at the top (One UI 8.5 / iOS 18 dark icons). */
        private const val DARK_TOP = 0xFF2E2E31.toInt()
        private const val DARK_BOTTOM = 0xFF141416.toInt()
        /** The dark plate's overall colour (the Zoom In card starts from it). */
        const val DARK_PLATE = 0xFF212123.toInt()
        /** Apps whose monochrome layer themes badly: Themed and Frosted recolour their full icon (createTonal) instead. */
        val FORCE_TONAL = setOf("com.t3tools.t3code")
        /** Chrome, drawn as One UI shows it ([createChrome]) rather than from its own icon, whose layout varies by version. */
        private val CHROME = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
        /** The logo's width as a share of the plate (One UI's Chrome: ≈ 74 %). */
        private const val CHROME_LOGO_FRACTION = 0.74f
        /** Icon theme › Dot ([homeIcon]'s look): the glyph as an even grid of dots, black on paper or white on black. */
        const val LOOK_DOT = "dot"
        /** Icon theme › Prism: the glyph in a gradient of the app's own colour, on a white or graphite plate. */
        const val LOOK_PRISM = "prism"
        /** Icon theme › Stencil: the glyph cut through a black plate, showing the wallpaper behind the icon frosted. */
        const val LOOK_STENCIL = "stencil"
        private const val DOT_PLATE_LIGHT = 0xFFF3F2ED.toInt()
        private const val DOT_PLATE_DARK = 0xFF141414.toInt()
        private const val DOT_INK_LIGHT = 0xFF151515.toInt()
        private const val DOT_INK_DARK = 0xFFF2F2F2.toInt()
        /** Dots per side, and the share of the plate the grid spans (the glyph is fitted to it). */
        private const val DOT_GRID = 13
        private const val DOT_SPAN = 0.62f
        /** A cell gets a dot when the glyph covers this much of it; the dot's radius as a share of the cell. */
        private const val DOT_MIN = 0.35f
        private const val DOT_RADIUS = 0.38f
        private const val PRISM_PLATE_LIGHT = 0xFFFFFFFF.toInt()
        private const val PRISM_PLATE_DARK = 0xFF1C1D22.toInt()
        /** Stencil's plate, dark in light mode too (the user's choice): black, a touch lighter at the top; its overall colour for the Zoom In card. */
        private const val STENCIL_TOP = 0xFF2A2A30.toInt()
        private const val STENCIL_BOTTOM = 0xFF0B0B0E.toInt()
        private const val STENCIL_PLATE = 0xFF19191D.toInt()
        /** The frost on Stencil's cut-out (white, this opaque), over the blurred wallpaper drawn under the icon. */
        private const val STENCIL_FROST = 0.18f
        /** Prism's gradient for apps with no colour of their own (by chroma): blue into violet. */
        private const val PRISM_GREY_FROM = 0xFF3D8BFF.toInt()
        private const val PRISM_GREY_TO = 0xFF9B5CFF.toInt()

        /** Icons › Light or dark ([homeIcon]'s lookMode), for the styles that have both: follow dark mode, or one of them always. */
        const val MODE_AUTO = "auto"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"

        /** Whether a Dot or Prism icon is drawn in its dark version: [mode], or with dark mode ([systemNight]) for Auto. */
        fun lookNight(mode: String, systemNight: Boolean): Boolean = when (mode) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> systemNight
        }

        /** A Dot, Prism or Stencil icon's plate colour (the Zoom In card starts from it). */
        fun lookPlate(look: String, night: Boolean): Int = when (look) {
            LOOK_DOT -> if (night) DOT_PLATE_DARK else DOT_PLATE_LIGHT
            LOOK_STENCIL -> STENCIL_PLATE
            else -> if (night) PRISM_PLATE_DARK else PRISM_PLATE_LIGHT
        }

        private val ONE_DOT = arrayOf(
            intArrayOf(22, 22), intArrayOf(96, 2), intArrayOf(169, 22), intArrayOf(189, 96),
            intArrayOf(169, 169), intArrayOf(96, 189), intArrayOf(22, 169), intArrayOf(2, 96),
        )

        /** The icon shape (Icons › Icon shape, [IconShape.current]) in a [size] box. */
        fun maskPath(size: Float): Path = IconShape.path(size)
    }

    private val highlight: Bitmap = decode(R.drawable.oneui_emboss_highlight)
    private val shadow: Bitmap = decode(R.drawable.oneui_emboss_shadow)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC_OVER }
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.MULTIPLY }
    private val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NIGHT_LAYER
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    private fun decode(id: Int): Bitmap = BitmapFactory.decodeResource(
        context.resources, id, BitmapFactory.Options().apply { inScaled = false })

    fun isNight(ctx: Context = context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Home screen icon (themed per the settings) to the Apps screen icon (always the app's own colours). Shared by
     * AppRepository and the Wallpaper and style preview.
     */
    fun homeIcon(d: Drawable, size: Int, night: Boolean, themed: Boolean, themeAll: Boolean, plateAlpha: Float,
                 dark: Boolean = false, pkg: String? = null, minimal: Boolean = false, look: String = "",
                 lookMode: String = MODE_AUTO): Pair<Bitmap, Bitmap> {
        val chrome = pkg in CHROME
        val normal = if (chrome) createChrome(size, dark = false) else create(d, size, night)
        val forced = pkg in FORCE_TONAL
        // a real themed version only exists for adaptive icons with a monochrome layer
        val hasMono = (d as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome != null && !forced
        val styled = themed && look.isNotEmpty() && (hasMono || themeAll || forced)
        return when {
            dark && chrome -> createChrome(size, dark = true)
            dark && isGame(pkg) -> normal
            // one odd icon must not take Home down with it (icons load at start, so a throw here crash-loops)
            dark -> runCatching { createDark(d, size) }.onFailure { android.util.Log.w("IconFactory", "Dark icon failed: $pkg", it) }.getOrNull() ?: normal
            styled -> runCatching { createLook(d, size, lookNight(lookMode, night), look, hasMono) }.onFailure { android.util.Log.w("IconFactory", "$look icon failed: $pkg", it) }.getOrNull() ?: normal
            themed && minimal && hasMono -> createMinimal(d, size)
            themed && minimal && (themeAll || forced) -> createTonal(d, size, night, glyphOnly = true)
            themed && hasMono -> createThemed(d, size, night, plateAlpha)
            themed && (themeAll || forced) -> createTonal(d, size, night, plateAlpha)
            else -> normal
        } to normal
    }

    /** Games keep their own art under Dark icons: it's an illustration, not a logo to restyle. Dot, Prism and Stencil style them too (the user's choice). */
    @Suppress("DEPRECATION")
    private fun isGame(pkg: String?): Boolean {
        val info = pkg?.let { runCatching { context.packageManager.getApplicationInfo(it, 0) }.getOrNull() } ?: return false
        return info.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
            info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
    }

    /**
     * SemAppIconSolution.getColorThemeIcon(): accent background (system_accent1_400 / _500 at night) with the
     * monochrome layer tinted white (One UI goes black at night; the user wants white in both), then emboss + mask.
     * Falls back to the normal icon.
     * [plateAlpha] below 1 (glass icons): the background is that see-through, for the glass drawn under it.
     */
    fun createThemed(src: Drawable, size: Int, night: Boolean = isNight(), plateAlpha: Float = 1f): Bitmap {
        val mono = (src as? AdaptiveIconDrawable)?.monochrome ?: return create(src, size, night)
        val res = context.resources
        val accent = res.getColor(if (night) android.R.color.system_accent1_500 else android.R.color.system_accent1_400, null)
        val bg = (accent and 0x00FFFFFF) or ((plateAlpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
        val fg = Color.WHITE
        val glyph = mono.constantState?.newDrawable(res)?.mutate() ?: mono.mutate()
        glyph.setTint(fg)
        val themed = AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(bg), glyph)
        return create(themed, size, night = false, opaqueBase = plateAlpha >= 1f)
    }

    /** Minimal icon: [createThemed]'s white glyph on its own, no plate ([minimal]). Falls back to the normal icon. */
    fun createMinimal(src: Drawable, size: Int): Bitmap {
        val mono = (src as? AdaptiveIconDrawable)?.monochrome ?: return create(src, size)
        return minimal(monoGlyph(mono, size), size)
    }

    /** The monochrome layer in white, at the size it has on the themed plate, clipped to the mask. */
    private fun monoGlyph(mono: Drawable, size: Int): Bitmap {
        val glyph = (mono.constantState?.newDrawable(context.resources)?.mutate() ?: mono.mutate()).apply { setTint(Color.WHITE) }
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val inset = (size * 0.25f).roundToInt()
        glyph.bounds = Rect(-inset, -inset, size + inset, size + inset)
        glyph.draw(Canvas(layers))
        return clip(layers, size)
    }

    /**
     * Dot, Prism and Stencil icons ([LOOK_DOT], [LOOK_PRISM], [LOOK_STENCIL]): the themed glyph (the monochrome layer, or the tonal glyph for
     * apps without one) on a plate of the style's own: Dot and Prism follow light and dark mode, Stencil is dark in both.
     */
    fun createLook(src: Drawable, size: Int, night: Boolean, look: String, hasMono: Boolean): Bitmap {
        val mono = (src as? AdaptiveIconDrawable)?.monochrome
        val glyph = if (hasMono && mono != null) monoGlyph(mono, size) else createTonal(src, size, night, glyphOnly = true, bare = true)
        return when (look) {
            LOOK_DOT -> dots(glyph, size, night)
            LOOK_STENCIL -> stencil(glyph, size)
            else -> prism(glyph, size, night, brandColor(src))
        }
    }

    /**
     * The glyph redrawn as a [DOT_GRID]² grid of even dots, like the Nothing Phone's glyph font: fitted to [DOT_SPAN]
     * of the plate, each cell gets a dot when the glyph covers enough of it. Recycles [glyph].
     */
    private fun dots(glyph: Bitmap, size: Int, night: Boolean): Bitmap {
        val alpha = IntArray(size * size)
        glyph.getPixels(alpha, 0, size, 0, 0, size, size)
        val box = opaqueBounds(glyph)
        glyph.recycle()
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layers)
        c.drawColor(if (night) DOT_PLATE_DARK else DOT_PLATE_LIGHT)
        if (!box.isEmpty) {
            val span = size * DOT_SPAN
            val cell = span / DOT_GRID
            val o = (size - span) / 2f
            // plate point p shows glyph point box centre + (p - plate centre) / scale
            val scale = (span * 0.96f / max(box.width(), box.height())).coerceIn(0.8f, 1.6f)
            val mid = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) DOT_INK_DARK else DOT_INK_LIGHT }
            for (gy in 0 until DOT_GRID) for (gx in 0 until DOT_GRID) {
                val x0 = (box.exactCenterX() + (o + gx * cell - mid) / scale).toInt()
                val y0 = (box.exactCenterY() + (o + gy * cell - mid) / scale).toInt()
                val x1 = (box.exactCenterX() + (o + (gx + 1) * cell - mid) / scale).toInt()
                val y1 = (box.exactCenterY() + (o + (gy + 1) * cell - mid) / scale).toInt()
                var sum = 0f; var k = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (x in 0 until size && y in 0 until size) sum += Color.alpha(alpha[y * size + x]) / 255f
                    k++
                }
                if (k > 0 && sum / k >= DOT_MIN) c.drawCircle(o + (gx + 0.5f) * cell, o + (gy + 0.5f) * cell, cell * DOT_RADIUS, p)
            }
        }
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * The glyph cut through a black plate: the icon is see-through there but for a faint white frost, over the blurred
     * wallpaper Home draws under it (IconView.plateGlass, as for Frosted icons), so the symbol shows the wallpaper
     * frosted. Recycles [glyph].
     */
    private fun stencil(glyph: Bitmap, size: Int): Bitmap {
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layers)
        c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), STENCIL_TOP, STENCIL_BOTTOM, Shader.TileMode.CLAMP)
        })
        applyEmboss(c, size)
        c.drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        c.drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * STENCIL_FROST).roundToInt() })
        glyph.recycle()
        return clip(layers, size)
    }

    /**
     * The glyph in a gradient from [brand] (top left) into its neighbouring hue, over a soft glow of the same
     * gradient, on a white plate (graphite in dark mode). Recycles [glyph].
     */
    private fun prism(glyph: Bitmap, size: Int, night: Boolean, brand: Int?): Bitmap {
        val (from, to) = prismColors(brand, night)
        val gradient = Paint().apply {
            shader = android.graphics.LinearGradient(size * 0.2f, size * 0.2f, size * 0.8f, size * 0.8f, from, to, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        val offset = IntArray(2)
        val blur = glyph.extractAlpha(Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(size * 0.06f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }, offset)
        val glow = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(glow).apply {
            drawBitmap(blur, offset[0].toFloat(), offset[1] + size * 0.035f, Paint(Paint.FILTER_BITMAP_FLAG))
            drawPaint(gradient)
        }
        blur.recycle()
        Canvas(glyph).drawPaint(gradient)

        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layers)
        c.drawColor(if (night) PRISM_PLATE_DARK else PRISM_PLATE_LIGHT)
        c.drawBitmap(glow, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = if (night) 140 else 102 })
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        glow.recycle()
        glyph.recycle()
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /** Prism's gradient: [brand] made vivid, into the hue 42° on (yellows on white: back toward orange); blue into violet for apps with no colour. */
    private fun prismColors(brand: Int?, night: Boolean): Pair<Int, Int> {
        if (brand == null) return PRISM_GREY_FROM to PRISM_GREY_TO
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(brand, hsl)
        val s = max(hsl[1], 0.8f)
        // a yellow darkened to read on white goes olive, more so toward green: on white it runs toward orange instead
        val yellow = !night && hsl[0] in 35f..80f
        val h = if (yellow) hsl[0] - 8f else hsl[0]
        val next = if (yellow) (hsl[0] + 320f) % 360f else (hsl[0] + 42f) % 360f
        return readable(h, s, if (night) 0.62f else 0.55f, night) to readable(next, s, if (night) 0.65f else 0.58f, night)
    }

    /** That colour, darkened until it reads on the white plate (yellows) or lightened until it reads on graphite (blues). */
    private fun readable(h: Float, s: Float, l: Float, night: Boolean): Int {
        var lightness = l
        var c = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, lightness))
        while (if (night) Color.luminance(c) < 0.22f && lightness < 0.85f else Color.luminance(c) > 0.3f && lightness > 0.25f) {
            lightness += if (night) 0.02f else -0.02f
            c = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, lightness))
        }
        return c
    }

    /**
     * The app's own colour: the hue (in 15° steps) its coloured pixels weigh most in, by chroma, averaged; null for an
     * icon with next to no colour (judged by chroma, never HSL saturation, which is high for any tint near white).
     */
    private fun brandColor(src: Drawable): Int? {
        val s = 48
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (src is AdaptiveIconDrawable) drawAdaptive(c, src, s) else drawLegacy(c, src, s)
        val px = IntArray(s * s)
        b.getPixels(px, 0, s, 0, 0, s, s)
        b.recycle()
        val weight = FloatArray(24)
        val r = FloatArray(24); val g = FloatArray(24); val bl = FloatArray(24)
        val hsl = FloatArray(3)
        for (p in px) {
            val ch = chroma(p)
            if (ch < 0.2f || Color.alpha(p) < 128) continue
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            val k = (hsl[0] / 15f).toInt().coerceIn(0, 23)
            weight[k] += ch; r[k] += Color.red(p) * ch; g[k] += Color.green(p) * ch; bl[k] += Color.blue(p) * ch
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < s * s * 0.02f) return null
        return Color.rgb((r[best] / weight[best]).roundToInt(), (g[best] / weight[best]).roundToInt(), (bl[best] / weight[best]).roundToInt())
    }

    /**
     * A white glyph at the size it has on the themed plate (so Minimal icons line up with Themed and Frosted ones),
     * over a soft shadow so it still reads on a light wallpaper. Recycles [glyph].
     */
    private fun minimal(glyph: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val offset = IntArray(2)
        val shadow = glyph.extractAlpha(Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(size * 0.03f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }, offset)
        c.drawBitmap(shadow, offset[0].toFloat(), offset[1] + size * 0.012f, Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x47000000 })
        c.drawBitmap(glyph, 0f, 0f, bitmapPaint)
        shadow.recycle()
        glyph.recycle()
        return out
    }

    /**
     * Forced theme icon for apps without a monochrome layer ("tonal"): the icon's own background becomes the
     * theme plate, detail that stands out from it moves toward the glyph colour (white) and
     * detail on the other side of the background moves toward a deep accent. Flat logos come
     * out like a real monochrome glyph; illustrations keep their shading. [plateAlpha] as for [createThemed]: the
     * plate is see-through by that much, the glyph (and detail moving away from the plate) stays solid.
     * [glyphOnly]: just the white glyph, for Minimal icons ([minimal]); [bare] leaves out Minimal's shadow (Dot, Prism).
     */
    fun createTonal(src: Drawable, size: Int, night: Boolean = isNight(), plateAlpha: Float = 1f, glyphOnly: Boolean = false,
                    bare: Boolean = false): Bitmap {
        val res = context.resources
        val plate = res.getColor(if (night) android.R.color.system_accent1_500 else android.R.color.system_accent1_400, null)
        // white glyph in light and dark mode alike (as createThemed), so detail on the other side of the plate goes deep
        val glyph = Color.WHITE
        val away = res.getColor(android.R.color.system_accent1_700, null)

        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layers)
        val n = size * size
        val px = IntArray(n)
        val bg = IntArray(n)
        if (src is AdaptiveIconDrawable) {
            // per-pixel reference: the background layer on its own (handles gradient backgrounds)
            c.drawColor(Color.BLACK)
            val inset = (size * 0.25f).roundToInt()
            src.background?.let { it.bounds = Rect(-inset, -inset, size + inset, size + inset); it.draw(c) }
            layers.getPixels(bg, 0, size, 0, 0, size, size)
            drawAdaptive(c, src, size)
            layers.getPixels(px, 0, size, 0, 0, size, size)
        } else {
            drawLegacy(c, src, size)
            layers.getPixels(px, 0, size, 0, 0, size, size)
            bg.fill(ringMedian(px, size))
        }

        val lum = FloatArray(n)
        val lumBg = FloatArray(n)
        val dist = FloatArray(n)
        for (i in 0 until n) {
            lum[i] = luminance(px[i]); lumBg[i] = luminance(bg[i])
            val dr = Color.red(px[i]) - Color.red(bg[i])
            val dg = Color.green(px[i]) - Color.green(bg[i])
            val db = Color.blue(px[i]) - Color.blue(bg[i])
            dist[i] = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 255f
        }
        // polarity: content that is mostly darker than its background (logo on a white tray) should still
        // become the glyph, so flip which side of the background maps to it
        var sum = 0f
        for (i in 0 until n) if (dist[i] > 0.15f) sum += lum[i] - lumBg[i]
        val pol = if (sum < 0f) -1f else 1f
        // contrast stretch: the strongest glyph-side detail (95th percentile) always reaches the full glyph
        // colour, so low-contrast art (light grey on white) doesn't come out faint
        val hist = IntArray(256)
        var count = 0
        for (i in 0 until n) {
            val d = (lum[i] - lumBg[i]) * pol
            if (d > 0.02f) { hist[(d * 255).roundToInt().coerceIn(0, 255)]++; count++ }
        }
        var stretch = 1f
        if (count > 0) {
            var acc = 0; var b = 0
            while (b < 255 && acc + hist[b] < count * 0.95f) { acc += hist[b]; b++ }
            stretch = max(b / 255f, 0.08f)
        }

        for (i in 0 until n) {
            val lb = lumBg[i]
            val d = (lum[i] - lb) * pol
            val toward = max(minOf(if (pol > 0) 1f - lb else lb, stretch), 0.05f)
            val from = max(if (pol > 0) lb else 1f - lb, 0.05f)
            // saturated detail at the background's luminance (e.g. TikTok's cyan/red) still reads as glyph
            val chroma = (dist[i] - kotlin.math.abs(lum[i] - lb) * 1.2f).coerceIn(0f, 1f)
            val up = (Math.pow((d / toward).coerceIn(0f, 1f).toDouble(), 0.75).toFloat() + chroma * 0.6f).coerceAtMost(1f)
            val dn = Math.pow((-d / from).coerceIn(0f, 1f).toDouble(), 0.9).toFloat()
            // glyph only: faint detail (a tile a shade off its background) would show as a see-through square
            if (glyphOnly) { px[i] = Color.argb((255 * smooth(0.22f, 0.6f, up)).roundToInt(), 255, 255, 255); continue }
            val solid = max(up, dn)
            px[i] = Color.argb((255 * (plateAlpha + (1f - plateAlpha) * solid)).roundToInt().coerceIn(0, 255),
                tone(Color.red(plate), Color.red(glyph), Color.red(away), up, dn),
                tone(Color.green(plate), Color.green(glyph), Color.green(away), up, dn),
                tone(Color.blue(plate), Color.blue(glyph), Color.blue(away), up, dn),
            )
        }
        layers.setPixels(px, 0, size, 0, 0, size, size)
        if (glyphOnly) return clip(layers, size).let { if (bare) it else minimal(it, size) }
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * Dark icon, worked out for any app the way iOS 18 makes its dark icons (no per-app artwork): the icon's
     * foreground is lifted off its background onto a dark plate. A simple two-tone logo (a white glyph on a brand
     * colour, like Phone's handset on green) takes the brand colour onto the glyph. A colourful one keeps its colours,
     * with near-black detail turned light so it doesn't vanish. A foreground that carries its own light and dark
     * (Waze's white ghost with its black outline and face) is kept as drawn. When no foreground separates cleanly
     * (photos, busy art), null: the icon stays as it is.
     *
     * Foreground: an adaptive icon's foreground layer, less whatever in it matches the background layer under it
     * (Maps draws a white tile, with a drop shadow, into its foreground); a legacy (or one-layer) icon is split by
     * colour distance from the ring just inside the mask edge.
     */
    fun createDark(src: Drawable, size: Int): Bitmap? {
        val n = size * size
        val work = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(work)
        val fg = IntArray(n)
        var brand = Color.BLACK
        var split = false
        val inset = (size * 0.25f).roundToInt()
        val layerBounds = Rect(-inset, -inset, size + inset, size + inset)
        if (src is AdaptiveIconDrawable && src.foreground != null) {
            src.background?.let { it.bounds = layerBounds; it.draw(c) }
            val bg = IntArray(n)
            work.getPixels(bg, 0, size, 0, 0, size, size)
            brand = averageColor(bg) ?: Color.BLACK
            work.eraseColor(Color.TRANSPARENT)
            src.foreground.bounds = layerBounds
            src.foreground.draw(c)
            work.getPixels(fg, 0, size, 0, 0, size, size)
            keyOutBackground(fg, bg, size)
            split = coverage(fg, size) in 0.02f..0.85f
        }
        if (!split) {
            work.eraseColor(Color.TRANSPARENT)
            if (src is AdaptiveIconDrawable) drawAdaptive(c, src, size) else drawLegacy(c, src, size)
            val px = IntArray(n)
            work.getPixels(px, 0, size, 0, 0, size, size)
            brand = ringMedian(px, size)
            for (i in 0 until n) {
                val p = px[i]
                val d = colorDistance(p, brand)
                val a = smooth(0.09f, 0.22f, d) * Color.alpha(p) / 255f
                fg[i] = (p and 0x00FFFFFF) or ((a * 255).roundToInt() shl 24)
            }
            unblendEdges(fg, px, IntArray(n) { brand }, size)
            val cov = coverage(fg, size)
            if (cov < 0.02f || cov > 0.8f) { work.recycle(); return null }
        }

        // two-tone? most of the solid foreground is white-ish (a glyph made to sit on the brand colour). A foreground
        // with white and near-black parts both is a self-contained drawing, kept as it is
        val hsl = FloatArray(3)
        var solid = 0f
        var white = 0f
        var black = 0f
        for (p in fg) {
            val a = Color.alpha(p) / 255f
            if (a < 0.5f) continue
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            solid += a
            if (chroma(p) < 0.1f && hsl[2] > 0.72f) white += a
            if (hsl[2] < 0.2f) black += a
        }
        val drawn = solid > 0f && white / solid > 0.2f && black / solid > 0.06f
        val tint = if (!drawn && solid > 0f && white / solid > 0.6f) glyphTint(brand) else null

        for (i in 0 until n) {
            val p = fg[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            // by chroma, not HSL saturation: that's 1 for any tint at all near white or black (an edge of a white logo
            // over the brand colour, 252,251,255), which kept such pixels white in a dotted line around Life360's logo
            val neutral = (1f - chroma(p) / 0.2f).coerceIn(0f, 1f)
            var out = p or (0xFF shl 24)
            if (tint != null) {
                // white parts take the brand colour, shaded by how light they were; coloured parts stay
                val w = neutral * smooth(0.5f, 0.8f, hsl[2])
                val shaded = androidx.core.graphics.ColorUtils.blendARGB(Color.BLACK, tint, (0.55f + 0.45f * hsl[2]).coerceAtMost(1f))
                out = androidx.core.graphics.ColorUtils.blendARGB(out, shaded, w)
            }
            // near-black detail (a black logo on a white tray) turns light, or it would disappear on the plate
            val k = if (drawn) 0f else neutral * (1f - smooth(0.12f, 0.32f, hsl[2]))
            out = androidx.core.graphics.ColorUtils.blendARGB(out, 0xFFE6E6E8.toInt(), k)
            fg[i] = (out and 0x00FFFFFF) or (a shl 24)
        }
        work.setPixels(fg, 0, size, 0, 0, size, size)

        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layers)
        lc.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), DARK_TOP, DARK_BOTTOM, Shader.TileMode.CLAMP)
        })
        lc.drawBitmap(work, 0f, 0f, bitmapPaint)
        work.recycle()
        applyEmboss(lc, size)
        return clip(layers, size)
    }

    /**
     * Chrome as One UI shows it: the logo at [CHROME_LOGO_FRACTION] of a pure white plate with a thin grey ring
     * around it (kept white in dark mode, no night dim), or on the dark plate for Dark icons. Drawn here, not from
     * Chrome's icon: versions put the logo in different layers at different sizes (one overflows the mask), so
     * shrinking the real icon came out blank or as a square crop.
     */
    private fun createChrome(size: Int, dark: Boolean): Bitmap {
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layers)
        if (dark) c.drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), DARK_TOP, DARK_BOTTOM, Shader.TileMode.CLAMP)
        }) else c.drawColor(Color.WHITE)
        val mid = size / 2f
        val r = size * CHROME_LOGO_FRACTION / 2f
        drawChromeLogo(c, mid, mid, r)
        if (!dark) {
            val w = size * 0.014f
            c.drawCircle(mid, mid, r + w / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = w; color = 0x2E000000
            })
        }
        applyEmboss(c, size)
        return clip(layers, size)
    }

    /**
     * The Chrome logo, radius [r]: a blue centre in a white ring, and three segments (red top, yellow right, green
     * bottom left), each bounded by a line tangent to the ring, the line of the next one rotated 120°.
     */
    private fun drawChromeLogo(c: Canvas, cx: Float, cy: Float, r: Float) {
        val ring = r * 0.46f
        val blue = r * 0.37f
        // the red segment's lines end on the outer circle at alpha (right) and alpha + 240° (upper left)
        val alpha = Math.toDegrees(-kotlin.math.asin((ring / r).toDouble())).toFloat()
        val outer = RectF(cx - r, cy - r, cx + r, cy + r)
        val inner = RectF(cx - ring, cy - ring, cx + ring, cy + ring)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(cx, cy, r, p.apply { color = Color.WHITE })
        for ((i, col) in intArrayOf(0xFFEA4335.toInt(), 0xFFFBBC04.toInt(), 0xFF34A853.toInt()).withIndex()) {
            val rot = 120f * i
            val path = Path()
            path.arcTo(outer, alpha + 240f + rot, 120f, true)               // outer edge, clockwise to the line's end
            path.arcTo(inner, -90f + rot, -120f)                            // along the ring (lineTo its tangent point first)
            path.close()                                                    // the previous segment's line, back out
            c.drawPath(path, p.apply { color = col })
        }
        c.drawCircle(cx, cy, ring, p.apply { color = Color.WHITE })
        c.drawCircle(cx, cy, blue, p.apply { color = 0xFF4285F4.toInt() })
    }

    /**
     * Clear foreground pixels that match the opaque background under them (they read as background in the icon),
     * then what's left stranded around the cleared area: the edge and drop shadow of a tile drawn into the
     * foreground, which would otherwise outline it on the dark plate. Detail away from cleared areas is untouched.
     */
    private fun keyOutBackground(fg: IntArray, bg: IntArray, size: Int) {
        val n = size * size
        val src = fg.copyOf()
        val cleared = IntArray(n)
        for (i in 0 until n) {
            val a = Color.alpha(fg[i])
            if (a == 0 || Color.alpha(bg[i]) < 128) continue
            val k = smooth(0.06f, 0.16f, colorDistance(fg[i], bg[i]))
            if (k >= 1f) continue
            fg[i] = (fg[i] and 0x00FFFFFF) or ((a * k).roundToInt() shl 24)
            if (a > 128 && k < 0.5f) cleared[i] = 1
        }
        val edge = unblendEdges(fg, src, bg, size)
        val clearedSum = summedArea(cleared, size)
        if (clearedSum[n + 2 * size] == 0) return
        val solidSum = summedArea(IntArray(n) { if (Color.alpha(fg[it]) > 128) 1 else 0 }, size)
        val r = max(2, size / 48)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (Color.alpha(fg[i]) == 0) continue
            val x0 = max(0, x - r); val y0 = max(0, y - r)
            val x1 = minOf(size, x + r + 1); val y1 = minOf(size, y + r + 1)
            val area = ((x1 - x0) * (y1 - y0)).toFloat()
            if (boxSum(clearedSum, size, x0, y0, x1, y1) / area >= 0.15f &&
                boxSum(solidSum, size, x0, y0, x1, y1) / area < 0.3f) { fg[i] = 0; cleared[i] = 1 }
        }
        // a shadow's faint outer reach: translucent pixels joined to the cleared area go with it
        val queue = ArrayDeque<Int>()
        for (i in 0 until n) if (cleared[i] == 1) queue.add(i)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % size
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < size - 1) i + 1 else -1, i - size, i + size)) {
                if (j < 0 || j >= n || cleared[j] == 1 || edge[j]) continue
                val a = Color.alpha(fg[j])
                if (a == 0 || a > 128) continue
                fg[j] = 0; cleared[j] = 1; queue.add(j)
            }
        }
    }

    /**
     * Edge pixels drawn against the background are part logo colour, part background: a logo on a white square inside
     * the foreground layer (Skipii), a legacy icon on its tray, or a white logo whose see-through edge is mixed with
     * the brand colour drawn under it (Life360). Kept as they are, they ring the logo in a pale, dotted fringe on the
     * dark plate. Each pixel beside the background is judged as it shows over the background, and takes the colour of
     * the strongest solid logo pixel next to it instead, with the share of that colour it holds as its alpha. [src]:
     * the pixels as drawn, [fg]: the foreground being built. Returns the pixels it changed.
     */
    private fun unblendEdges(fg: IntArray, src: IntArray, bg: IntArray, size: Int): BooleanArray {
        val n = size * size
        // each pixel as it shows: over the background where that's solid
        val shown = IntArray(n) {
            val s = src[it]; val b = bg[it]
            if (Color.alpha(b) >= 128) androidx.core.graphics.ColorUtils.compositeColors(s, b or (0xFF shl 24)) else s
        }
        val d = FloatArray(n) {
            if (Color.alpha(bg[it]) >= 128 || Color.alpha(src[it]) > 128) colorDistance(shown[it], bg[it]) else -1f
        }
        val done = BooleanArray(n)
        val r = max(1, size / 96)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (d[i] < 0f || Color.alpha(src[i]) == 0) continue
            var nearBg = false
            var best = -1
            var bestD = d[i] + 0.05f
            for (yy in max(0, y - r)..minOf(size - 1, y + r)) for (xx in max(0, x - r)..minOf(size - 1, x + r)) {
                val j = yy * size + xx
                if (d[j] < 0f) continue
                if (d[j] < 0.06f) nearBg = true
                // only solid logo pixels give the colour (not a translucent shadow)
                if (d[j] > bestD && Color.alpha(src[j]) > 128) { bestD = d[j]; best = j }
            }
            if (!nearBg || best < 0) continue
            // p = b + t (f - b): how far along from the background to the logo colour; a pixel well off that line
            // is detail of its own, not a blend
            val p = shown[i]; val b = bg[i]; val f = shown[best]
            val fr = (Color.red(f) - Color.red(b)).toFloat()
            val fgr = (Color.green(f) - Color.green(b)).toFloat()
            val fb = (Color.blue(f) - Color.blue(b)).toFloat()
            val pr = (Color.red(p) - Color.red(b)).toFloat()
            val pg = (Color.green(p) - Color.green(b)).toFloat()
            val pb = (Color.blue(p) - Color.blue(b)).toFloat()
            // the logo pixel can be this pixel's background colour exactly (a background layer that varies): no line
            val len2 = fr * fr + fgr * fgr + fb * fb
            if (len2 < 1f) continue
            val t = ((pr * fr + pg * fgr + pb * fb) / len2).coerceIn(0f, 1f)
            val er = pr - t * fr; val eg = pg - t * fgr; val eb = pb - t * fb
            if (kotlin.math.sqrt(er * er + eg * eg + eb * eb) / 441.7f > 0.08f) continue
            fg[i] = (f and 0x00FFFFFF) or ((255 * t).roundToInt() shl 24)
            done[i] = true
        }
        return done
    }

    /** Summed-area table of [v] ([size]² values), (size + 1)² entries with a zero first row and column. */
    private fun summedArea(v: IntArray, size: Int): IntArray {
        val w = size + 1
        val s = IntArray(w * w)
        for (y in 0 until size) {
            var row = 0
            for (x in 0 until size) {
                row += v[y * size + x]
                s[(y + 1) * w + x + 1] = s[y * w + x + 1] + row
            }
        }
        return s
    }

    private fun boxSum(s: IntArray, size: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val w = size + 1
        return s[y1 * w + x1] - s[y0 * w + x1] - s[y1 * w + x0] + s[y0 * w + x0]
    }

    /** The brand colour as a glyph on the dark plate: kept vivid and lifted to read (white if it has no hue). */
    private fun glyphTint(brand: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(brand, hsl)
        if (chroma(brand) < 0.08f) return 0xFFF2F2F4.toInt()
        hsl[1] = hsl[1].coerceIn(0.45f, 0.9f)
        hsl[2] = hsl[2].coerceIn(0.58f, 0.72f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    /** Average colour of the opaque pixels, or null if there are none. */
    private fun averageColor(px: IntArray): Int? {
        var r = 0L; var g = 0L; var b = 0L; var k = 0L
        for (p in px) if (Color.alpha(p) > 128) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); k++ }
        return if (k == 0L) null else Color.rgb((r / k).toInt(), (g / k).toInt(), (b / k).toInt())
    }

    /** Share of the mask (its inscribed circle, near enough) that [px] covers. */
    private fun coverage(px: IntArray, size: Int): Float {
        val h = size / 2f
        val r2 = (size * 0.48f) * (size * 0.48f)
        var inside = 0; var covered = 0f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - h; val dy = y - h
            if (dx * dx + dy * dy > r2) continue
            inside++
            covered += Color.alpha(px[y * size + x]) / 255f
        }
        return if (inside == 0) 0f else covered / inside
    }

    /** How coloured [c] is, 0 (grey, white or black) to 1, whatever its lightness. */
    private fun chroma(c: Int): Float {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return (maxOf(r, g, b) - minOf(r, g, b)) / 255f
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441.7f
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun tone(plate: Int, glyph: Int, away: Int, up: Float, dn: Float): Int =
        (plate + (glyph - plate) * up + (away - plate) * dn).roundToInt().coerceIn(0, 255)

    private fun luminance(c: Int) = (Color.red(c) * 0.2126f + Color.green(c) * 0.7152f + Color.blue(c) * 0.0722f) / 255f

    /** Median colour of the ring just inside the mask edge (the icon's own background colour). */
    private fun ringMedian(px: IntArray, size: Int): Int {
        val r = ArrayList<Int>(); val g = ArrayList<Int>(); val b = ArrayList<Int>()
        val h = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val d = kotlin.math.hypot(x - h, y - h)
            if (d > size * 0.36f && d < size * 0.44f) {
                val p = px[y * size + x]; r += Color.red(p); g += Color.green(p); b += Color.blue(p)
            }
        }
        if (r.isEmpty()) return TRAY_COLOR
        r.sort(); g.sort(); b.sort()
        return Color.rgb(r[r.size / 2], g[g.size / 2], b[b.size / 2])
    }

    /**
     * Render [src] as a One UI 8.5 icon of [size] px. [opaqueBase] false keeps a see-through background layer
     * see-through (glass icons) instead of over black.
     */
    fun create(src: Drawable, size: Int, night: Boolean = isNight(), opaqueBase: Boolean = true): Bitmap {
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(layers)
        if (src is AdaptiveIconDrawable) {
            drawAdaptive(c, src, size, opaqueBase)
        } else {
            drawLegacy(c, src, size)
        }
        applyEmboss(c, size)
        if (night) c.drawPaint(nightPaint)
        return clip(layers, size)
    }

    private fun drawAdaptive(c: Canvas, d: AdaptiveIconDrawable, size: Int, opaqueBase: Boolean = true) {
        if (opaqueBase) c.drawColor(Color.BLACK)
        // AdaptiveIconDrawable layers are 1.5x the masked bounds (viewport 72/108, EXTRA_INSET 25 %).
        val inset = (size * 0.25f).roundToInt()
        val b = Rect(-inset, -inset, size + inset, size + inset)
        d.background?.let { it.bounds = b; it.draw(c) }
        d.foreground?.let { it.bounds = b; it.draw(c) }
    }

    /**
     * SemAppIconSolution.getAppIconAlphaRelativeScaleForIconTray(): sample 8 points on a 192 grid; if all
     * are opaque (alpha > 26) and the icon is square → crop (full bleed); otherwise contain at 0.72 on a
     * #FEFEFE tray.
     */
    private fun drawLegacy(c: Canvas, d: Drawable, size: Int) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: size
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: size
        val probe = Bitmap.createBitmap(ANALYSIS_SIZE, ANALYSIS_SIZE, Bitmap.Config.ARGB_8888)
        val scale = ANALYSIS_SIZE.toFloat() / max(iw, ih)
        val pw = (iw * scale).roundToInt()
        val ph = (ih * scale).roundToInt()
        val pl = (ANALYSIS_SIZE - pw) / 2
        val pt = (ANALYSIS_SIZE - ph) / 2
        d.setBounds(pl, pt, pl + pw, pt + ph)
        d.draw(Canvas(probe))

        // transparent margin (IconScale.alpha): trim to content bounds
        val content = opaqueBounds(probe)
        var filled = 0
        if (!content.isEmpty) {
            for (p in ONE_DOT) {
                val x = content.left + p[0] * content.width() / ANALYSIS_SIZE
                val y = content.top + p[1] * content.height() / ANALYSIS_SIZE
                if (Color.alpha(probe.getPixel(x.coerceIn(0, ANALYSIS_SIZE - 1), y.coerceIn(0, ANALYSIS_SIZE - 1))) > 26) filled++
            }
        }
        val square = kotlin.math.abs(content.width() - content.height()) <= 2
        val target = RectF()
        if (filled == 8 && square) {
            // CROP: content fills the whole icon
            target.set(0f, 0f, size.toFloat(), size.toFloat())
        } else {
            c.drawColor(TRAY_COLOR)
            val s = if (filled == 8) 0.68f else CONTAIN_SCALE
            val cw = content.width().coerceAtLeast(1)
            val ch = content.height().coerceAtLeast(1)
            val fit = size * s / max(cw, ch)
            val w = cw * fit
            val h = ch * fit
            target.set((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
        }
        c.drawBitmap(probe, content, target, bitmapPaint)
        probe.recycle()
    }

    private fun opaqueBounds(b: Bitmap): Rect {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 26) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > bt) bt = y
            }
        }
        return if (r < 0) Rect() else Rect(l, t, r + 1, bt + 1)
    }

    private fun applyEmboss(c: Canvas, size: Int) {
        // the bitmaps follow the squircle's edge; another shape gets the same rims drawn along its own
        if (!IconShape.isSquircle) { IconShape.drawEmboss(c, size); return }
        val dst = Rect(0, 0, size, size)
        c.drawBitmap(highlight, null, dst, highlightPaint)
        c.drawBitmap(shadow, null, dst, shadowPaint)
    }

    private fun clip(layers: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path = maskPath(size.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawPath(path, p)
        layers.recycle()
        return out
    }
}
