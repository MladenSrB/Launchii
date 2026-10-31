package com.oneuiclone.home.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the launcher our widgets sit on draws its icons, so that in other launchers the icon-like widgets (Bubble,
 * Split, Bubbles, and the 2×1's pill) match the icons beside them instead of Launchii's. A widget is only told its
 * own size (the host's options); the rest is the default Home's way of laying out a cell:
 *
 * - Pixel Launcher and other Launcher3s (measured on Pixel Launcher, Android 16, 4×5): an icon box 84.4 % of the
 *   cell's width, its shape the system's icon mask shrunk as Launcher3's IconNormalizer does (91.7 % for a circle),
 *   a 14 sp label 8 % of the icon below it, the icon and label centred in the cell together, cells 16 dp apart.
 * - Samsung's One UI Home: as Launchii's DeviceProfile draws One UI, a full-size icon 66 % of the cell, 13 sp labels
 *   5.5 dp below it, cells touching.
 *
 * [iconPx] is the icon's box and [shapePx] the shape in it; [cellPx] a single cell's width.
 */
class HostLook private constructor(
    val iconPx: Int,
    val shapeScale: Float,
    val labelPx: Float,
    val gapPx: Int,
    val cellPx: Float,
    private val unitMask: Path,
) {
    val shapePx get() = iconPx * shapeScale

    /** The icon's shape in an [size]-px box, as the host draws an icon there. */
    fun mask(size: Int): Path {
        val k = size * shapeScale / MASK_SIZE
        val inset = size * (1f - shapeScale) / 2f
        return Path(unitMask).apply { transform(Matrix().apply { setScale(k, k); postTranslate(inset, inset) }) }
    }

    /** An app's icon drawn as the host draws it: the system's shape (adaptive icons), shrunk to [shapeScale]. */
    fun icon(d: Drawable, size: Int = iconPx): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val inset = (size * (1f - shapeScale) / 2f).roundToInt()
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(Canvas(out))
        return out
    }

    /** The label's size and gap on a widget laid out like an app icon (layout/quick_open_widget, layout/bubbles_widget). */
    fun applyIcon(views: RemoteViews, tile: Int, label: Int? = null) = views.apply {
        setViewLayoutWidth(tile, iconPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        setViewLayoutHeight(tile, iconPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        if (label == null) return@apply
        setTextViewTextSize(label, TypedValue.COMPLEX_UNIT_PX, labelPx)
        setViewLayoutMargin(label, RemoteViews.MARGIN_TOP, gapPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
    }

    companion object {
        private const val MASK_SIZE = 100f
        private const val SAMSUNG_HOME = "com.sec.android.app.launcher"

        /** Launcher3's IconNormalizer: the largest share of the box an icon may cover, by how round it is. */
        private const val CIRCLE_AREA_BY_RECT = Math.PI.toFloat() / 4f
        private const val MAX_CIRCLE_AREA_FACTOR = 380f / 576f
        private const val MAX_SQUARE_AREA_FACTOR = 375f / 576f

        /** Launcher3 (Pixel Launcher): the icon box as a share of the cell's width, and the label's gap as one of the icon. */
        private const val L3_ICON = 0.844f
        private const val L3_GAP = 0.081f
        private const val L3_LABEL_SP = 14f
        private const val L3_CELL_GAP_DP = 16f

        /** One UI (DeviceProfile): the icon as a share of the cell's width, at most this share of its height. */
        private const val ONE_UI_ICON = 0.66f
        private const val ONE_UI_ICON_MAX_H = 0.62f
        private const val ONE_UI_LABEL_SP = 13f
        private const val ONE_UI_GAP_DP = 5.5f

        /** The cell we size for until the host has said how big the widget is: Pixel Launcher's 4×5 on a 411 dp phone. */
        private val DEFAULT_CELL = SizeF(78f, 104f)

        /** How [context]'s launcher lays out a [spanX]-cell-wide widget of [size] (dp). */
        fun of(context: Context, size: SizeF, spanX: Int = 1): HostLook {
            val m = context.resources.displayMetrics
            val samsung = defaultHome(context).let { it == SAMSUNG_HOME || it == context.packageName }
            val cellGap = if (samsung) 0f else L3_CELL_GAP_DP * m.density
            val cellW = (size.width * m.density - cellGap * (spanX - 1)) / spanX
            val cellH = size.height * m.density
            val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (samsung) ONE_UI_LABEL_SP else L3_LABEL_SP, m)
            val mask = systemMask()
            // the icon and its label must fit the cell's height (a label line is ~1.4× its text size)
            var icon: Int; val gap: Int
            if (samsung) {
                icon = minOf(cellW * ONE_UI_ICON, cellH * ONE_UI_ICON_MAX_H).roundToInt()
                gap = (ONE_UI_GAP_DP * m.density).roundToInt()
            } else {
                icon = (cellW * L3_ICON).roundToInt()
                gap = (icon * L3_GAP).roundToInt()
                icon = minOf(icon, (cellH - gap - labelPx * 1.4f).roundToInt())
            }
            return HostLook(icon.coerceAtLeast(1), if (samsung) 1f else normalizedScale(mask), labelPx, gap, cellW, mask)
        }

        /**
         * The sizes (dp) widget [appWidgetId] is shown at: one per orientation from Android 12 hosts, else its
         * classic portrait size, else the default cell spanning [spanX] cells.
         */
        fun sizes(context: Context, appWidgetId: Int, spanX: Int = 1, options: Bundle? = null): List<SizeF> {
            val o = options ?: AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                ?.filter { it.width > 0f && it.height > 0f }?.distinct()?.takeIf { it.isNotEmpty() }?.let { return it.take(16) }
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w > 0 && h > 0) return listOf(SizeF(w.toFloat(), h.toFloat()))
            return listOf(SizeF(DEFAULT_CELL.width * spanX + L3_CELL_GAP_DP * (spanX - 1), DEFAULT_CELL.height))
        }

        /** [make]'s views for each of [sizes], for the host to pick the one that fits (one alone needs no map). */
        fun responsive(sizes: List<SizeF>, make: (SizeF) -> RemoteViews): RemoteViews =
            if (sizes.size == 1) make(sizes[0]) else RemoteViews(sizes.associateWith(make))

        private fun defaultHome(context: Context): String? = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

        /** The system's icon shape (config_icon_mask) in a [MASK_SIZE] box: a circle on Pixels, the squircle on Samsungs. */
        private fun systemMask(): Path {
            val d = AdaptiveIconDrawable(ColorDrawable(0), ColorDrawable(0))
            d.setBounds(0, 0, MASK_SIZE.toInt(), MASK_SIZE.toInt())
            return Path(d.iconMask)
        }

        /** IconNormalizer.getScale for a full-bleed shape: rounder shapes may cover a little more of the box. */
        private fun normalizedScale(mask: Path): Float {
            // measured on a finer grid than the mask's own 100 units
            val box = 400
            val fine = Path(mask).apply { transform(Matrix().apply { setScale(box / MASK_SIZE, box / MASK_SIZE) }) }
            val region = Region().apply { setPath(fine, Region(0, 0, box, box)) }
            var area = 0f
            val r = Rect(); val it = RegionIterator(region)
            while (it.next(r)) area += r.width() * r.height()
            val byRect = area / (box * box)
            if (byRect <= 0f) return 1f
            val required = if (byRect < CIRCLE_AREA_BY_RECT) MAX_CIRCLE_AREA_FACTOR
                else MAX_SQUARE_AREA_FACTOR + (MAX_CIRCLE_AREA_FACTOR - MAX_SQUARE_AREA_FACTOR) / (1f - CIRCLE_AREA_BY_RECT) * (1f - byRect)
            return if (byRect > required) sqrt(required / byRect) else 1f
        }
    }
}
