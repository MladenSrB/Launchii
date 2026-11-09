package com.oneuiclone.home.icons

import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Icons › Icon shape: the outline every icon, plate, folder and Launchii widget tile is cut to. [current] is kept
 * in step with the saved choice by Prefs, so anything drawing an icon shape ([path]) follows it without being told.
 * Each shape is drawn in a 100-unit box, from the top and clockwise (the Bubbles widget's ring runs along it).
 */
object IconShape {
    const val SQUIRCLE = "squircle"
    const val CIRCLE = "circle"
    const val SQUARE = "square"
    const val COOKIE = "cookie"
    const val HEXAGON = "hexagon"

    val ALL = listOf(SQUIRCLE, CIRCLE, SQUARE, COOKIE, HEXAGON)

    /** The saved shape (Prefs.iconShape). */
    @Volatile var current = SQUIRCLE

    val isSquircle get() = current == SQUIRCLE

    /** Icons › Keep folders and widgets squircle (Prefs.keepSquircle), kept in step by Prefs. */
    @Volatile var keepSquircle = false

    /** The shape of folders and Launchii's widget tiles: the icon shape, or the squircle when they keep it. */
    val container get() = if (keepSquircle) SQUIRCLE else current

    fun name(id: String) = when (id) {
        CIRCLE -> "Circle"
        SQUARE -> "Square"
        COOKIE -> "Cookie"
        HEXAGON -> "Hexagon"
        else -> "Squircle"
    }

    /** One UI 8.5's config_icon_mask (IconFactory.ONE_UI_MASK) redrawn to start at the top and run clockwise. */
    private const val SQUIRCLE_PATH = "M50,1C77.063,1,99,6.935,99,50C99,93.063,77.063,99,50,99C22.935,99,1,93.063,1,50C1,6.935,22.935,1,50,1z"
    /**
     * Square is the iPhone's icon shape (iOS 26): a rounded square with Apple's continuous corners, the corner
     * radius 7/27 of the side (as measured from iOS 26's icons; iOS 7–18 used about 22.4 %).
     */
    private const val SQUARE_CORNER = 98f * 7f / 27f
    /**
     * One continuous corner, in units of the radius: (back along the side coming in, along the side going out),
     * the points of UIBezierPath(roundedRect:cornerRadius:)'s corner, made symmetric about the diagonal. The curve
     * starts 1.52866 radii from the corner and its curvature builds up, so no kink shows where side meets corner.
     */
    private val IOS_CORNER = floatArrayOf(
        1.52866f, 0f,                                                           // line to here
        1.08849f, 0f, 0.86841f, 0f, 0.66993f, 0.06550f,                         // cubic
        0.63149f, 0.07491f,                                                     // line
        0.37282f, 0.16906f, 0.16906f, 0.37282f, 0.07491f, 0.63149f,             // cubic
        0.06550f, 0.66993f,                                                     // line
        0f, 0.86841f, 0f, 1.08849f, 0f, 1.52866f)                               // cubic
    /** Cookie: a superellipse this square, its sides pinched in by this much of its reach. */
    private const val COOKIE_N = 3.2
    private const val COOKIE_PINCH = 0.11
    /** Hexagon: point on top, corners rounded off this far along each side. */
    private const val HEX_RADIUS = 52.5f
    private const val HEX_ROUND = 0.26f

    private val unit = HashMap<String, Path>()

    /** [id]'s outline in a 100-unit box, made once. */
    private fun unitPath(id: String): Path = synchronized(unit) {
        unit.getOrPut(id) {
            when (id) {
                CIRCLE -> Path().apply {
                    val o = RectF(1f, 1f, 99f, 99f)
                    arcTo(o, -90f, 180f, true); arcTo(o, 90f, 180f); close()
                }
                SQUARE -> roundedSquare()
                COOKIE -> cookie()
                HEXAGON -> hexagon()
                else -> PathParser.createPathFromPathData(SQUIRCLE_PATH)
            }
        }
    }

    /** [id]'s outline (the current shape by default) in a [size] box. */
    fun path(size: Float, id: String = current): Path =
        Path(unitPath(id)).apply { transform(Matrix().apply { setScale(size / 100f, size / 100f) }) }

    /** [id]'s outline into [out], centred on ([cx], [cy]) and [size] across. */
    fun path(out: Path, cx: Float, cy: Float, size: Float, id: String = current) {
        out.reset()
        unitPath(id).transform(Matrix().apply { setScale(size / 100f, size / 100f); postTranslate(cx - size / 2f, cy - size / 2f) }, out)
    }

    /**
     * A rounded rectangle's corner (a share of the icon's size) standing in for [id]: exact for Circle; for Square
     * the plain rounded rectangle through the same corner point (its continuous corner's middle sits where a
     * circular one of the same radius does); [squircle] (the caller's own approximation) for the rest. A growing
     * [path] clip shapes them where it matters.
     */
    fun corner(squircle: Float, id: String = current) = when (id) {
        CIRCLE -> 0.5f
        SQUARE -> SQUARE_CORNER / 98f
        else -> squircle
    }

    /**
     * How much bigger than a card [w]×[h] the shape must be (across) to hold the card whole: the size at which a
     * shape growing out from an icon stops showing in the card's outline.
     */
    fun cover(w: Float, h: Float) = maxOf(w, h) * COVER

    /** The narrowest shape (Cookie, its pinched sides) holds a square 61 % of its size; a little to spare. */
    private const val COVER = 1.75f

    private fun roundedSquare() = Path().apply {
        val r = SQUARE_CORNER
        val k = IOS_CORNER
        moveTo(50f, 1f)
        // each corner: where it is, the way back along the side coming in, the way along the side going out
        for (c in listOf(
            floatArrayOf(99f, 1f, -1f, 0f, 0f, 1f), floatArrayOf(99f, 99f, 0f, -1f, -1f, 0f),
            floatArrayOf(1f, 99f, 1f, 0f, 0f, -1f), floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f))) {
            fun x(i: Int) = c[0] + (k[i] * c[2] + k[i + 1] * c[4]) * r
            fun y(i: Int) = c[1] + (k[i] * c[3] + k[i + 1] * c[5]) * r
            lineTo(x(0), y(0))
            cubicTo(x(2), y(2), x(4), y(4), x(6), y(6))
            lineTo(x(8), y(8))
            cubicTo(x(10), y(10), x(12), y(12), x(14), y(14))
            lineTo(x(16), y(16))
            cubicTo(x(18), y(18), x(20), y(20), x(22), y(22))
        }
        close()
    }

    private fun cookie() = Path().apply {
        val steps = 240
        for (i in 0..steps) {
            val t = -PI / 2 + i * 2 * PI / steps
            val se = (abs(cos(t)).pow(COOKIE_N) + abs(sin(t)).pow(COOKIE_N)).pow(-1 / COOKIE_N)
            val r = 49 * se * (1 - COOKIE_PINCH * (1 + cos(4 * t)) / 2)
            val x = (50 + r * cos(t)).toFloat(); val y = (50 + r * sin(t)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun hexagon() = Path().apply {
        val v = (0 until 6).map { i -> val a = -PI / 2 + i * PI / 3; PointF((50 + HEX_RADIUS * cos(a)).toFloat(), (50 + HEX_RADIUS * sin(a)).toFloat()) }
        fun toward(a: PointF, b: PointF, f: Float) = PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
        // each corner a quadratic from HEX_ROUND before the vertex to HEX_ROUND after it; the top one split at its peak
        val ins = v.indices.map { toward(v[it], v[(it + 5) % 6], HEX_ROUND) }
        val outs = v.indices.map { toward(v[it], v[(it + 1) % 6], HEX_ROUND) }
        val top = v[0]
        val peak = PointF((ins[0].x + 2 * top.x + outs[0].x) / 4f, (ins[0].y + 2 * top.y + outs[0].y) / 4f)
        moveTo(peak.x, peak.y)
        quadTo((top.x + outs[0].x) / 2f, (top.y + outs[0].y) / 2f, outs[0].x, outs[0].y)
        for (i in 1 until 6) {
            lineTo(ins[i].x, ins[i].y)
            quadTo(v[i].x, v[i].y, outs[i].x, outs[i].y)
        }
        lineTo(ins[0].x, ins[0].y)
        quadTo((ins[0].x + top.x) / 2f, (ins[0].y + top.y) / 2f, peak.x, peak.y)
        close()
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x2C323232; blendMode = BlendMode.MULTIPLY
    }

    /**
     * One UI's emboss (a light rim along the top, a faint dark one all round) drawn along the current shape, for
     * the shapes its squircle-shaped bitmaps don't fit. Measured from those bitmaps: the light rim reaches about
     * 4 % in at the top and fades out by the middle; the dark one about 2.5 % in. The mask cuts off the outer half.
     */
    fun drawEmboss(c: Canvas, size: Int) = synchronized(highlightPaint) {
        val s = size.toFloat()
        val p = path(s)
        highlightPaint.strokeWidth = s * 0.08f
        highlightPaint.maskFilter = BlurMaskFilter(s * 0.012f, BlurMaskFilter.Blur.NORMAL)
        highlightPaint.shader = LinearGradient(0f, 0f, 0f, s * 0.5f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(p, highlightPaint)
        shadowPaint.strokeWidth = s * 0.05f
        shadowPaint.maskFilter = BlurMaskFilter(s * 0.008f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(p, shadowPaint)
    }
}
