package com.oneuiclone.home.device

import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Adaptive window layout derived from One UI Home fractions (spec §1.1 / §15). All fractions are of the full
 * window size, exactly as PhoneCellLayoutInfo uses them.
 */
class DeviceProfile(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val insets: Rect,
    val homeCols: Int = 4,
    val homeRows: Int = 6,
    val appsCols: Int = 4,
    appsRows: Int = 6,
    val iconScale: Float = 1f,
) {
    fun dp(v: Float) = v * density
    fun dpi(v: Float) = (v * density).roundToInt()

    // Use the current window, not smallestScreenWidthDp: a foldable's cover and inner
    // displays need different geometry without rewriting the saved workspace coordinates.
    val isExpanded = (widthPx - insets.left - insets.right) / density >= 600f
    // Keep the configured drawer columns (four by default) on both cover and inner displays.
    private val homeWidth = minOf(widthPx - insets.left - insets.right, dpi(840f))
    private val homeLeft = insets.left + (widthPx - insets.left - insets.right - homeWidth) / 2

    // ---- workspace (home) ----
    val pageTopPadding = maxOf(insets.top, (heightPx * 0.057f).roundToInt())          // page_top_padding_height_ratio
    /**
     * page_bottom_padding_height_ratio, plus the navigation bar inset: Samsung's ratios are of the area above
     * its own gesture hint, so on a Pixel the dock would otherwise sit right on the gesture handle.
     */
    val pageBottomPadding = (heightPx * 0.007f).roundToInt() + insets.bottom
    val indicatorAreaHeight = (heightPx * 0.041f).roundToInt()     // indicator_area_height_ratio
    val hotseatHeight = (heightPx * 0.124f).roundToInt()           // hotseat_bar_height_ratio
    val pageSidePadding = homeLeft + (homeWidth * 0.038f).roundToInt() // centred on wide windows

    val workspaceGrid: Rect = Rect(
        pageSidePadding,
        pageTopPadding,
        homeLeft + homeWidth - (homeWidth * 0.038f).roundToInt(),
        heightPx - pageBottomPadding - hotseatHeight - indicatorAreaHeight,
    )
    val cellWidth = workspaceGrid.width() / homeCols
    val cellHeight = workspaceGrid.height() / homeRows

    val indicatorRect = Rect(0, workspaceGrid.bottom, widthPx, workspaceGrid.bottom + indicatorAreaHeight)
    private val dockWidth = minOf(widthPx - insets.left - insets.right, dpi(520f))
    private val dockLeft = insets.left + (widthPx - insets.left - insets.right - dockWidth) / 2
    val hotseatRect = Rect(
        dockLeft + (dockWidth * 0.036f).roundToInt(),                             // hotseat_page_side_padding_width_ratio
        indicatorRect.bottom,
        dockLeft + dockWidth - (dockWidth * 0.036f).roundToInt(),
        indicatorRect.bottom + hotseatHeight,
    )

    /** 66 % of the cell width (= 15.25 % of the screen at 4 columns, matching One UI screenshots). */
    val iconSizePx = (cellWidth * 0.66f * iconScale).roundToInt().coerceAtMost(dpi(72f * iconScale)).coerceAtMost((cellHeight * 0.62f).roundToInt()).let { if (it % 2 == 1) it + 1 else it }
    val labelTextSizePx = dp(13f)
    val iconLabelGapPx = dp(5.5f)

    // ---- page indicator (page_indicator_dot_size_sw_ratio = slot width) ----
    val indicatorSlotPx = (minOf(widthPx, heightPx) * 0.047f)
    val indicatorDotPx = dp(6f)

    // ---- apps screen ----
    val appsSidePadding = maxOf(insets.left, insets.right) + (widthPx * 0.038f).roundToInt()          // apps_page_side_padding_width_ratio
    val searchBarWidth = minOf(dpi(244f), (widthPx - insets.left - insets.right - dpi(92f)).coerceAtLeast(1))                                  // search_bar_width_entry_point_0_to_599
    val searchBarHeight = dpi(52f)                                  // search_bar_wrapper_height
    val searchBarRadius = dp(32f)                                   // search_bar_bg_radius
    val searchBarBottomMargin = (heightPx * 0.022f).roundToInt() + insets.bottom
    val searchBarTop = heightPx - searchBarBottomMargin - searchBarHeight
    val appsIndicatorHeight = (heightPx * 0.044f).roundToInt()     // apps_page_indicator_height_ratio
    val appsGrid = Rect(
        appsSidePadding,
        maxOf(insets.top + dpi(8f), (heightPx * 0.088f).roundToInt()),
        widthPx - appsSidePadding,
        searchBarTop - appsIndicatorHeight - (heightPx * 0.012f).roundToInt(),
    )
    // Keep drawer rows usable in landscape and short multi-window bounds.
    val appsRows = minOf(appsRows, (appsGrid.height() / dp(104f)).toInt().coerceAtLeast(1))
    val appsIndicatorRect = Rect(0, appsGrid.bottom, widthPx, appsGrid.bottom + appsIndicatorHeight)

    // ---- transitions ----
    /** swipe_vertical_transition_y */
    val swipeTransitionY = dp(86f)

    /** Home→Apps progress = drag / 800 px on S26 (FHD+, density 2.8125) ⇒ 284.4 dp. */
    val swipeProgressDistance = dp(284.4f)

    /** One UI Home works in raw px/ms on a 2.8125 density panel; scale its thresholds to this screen. */
    val samsungPxScale = density / 2.8125f

    val cornerRadius = dp(26f)

    /** Inset of a widget inside its cells on every side (WidgetContainer's padding). */
    val widgetPaddingPx = (cellWidth * 0.04f).toInt()

    /** Preserve the widget's span aspect ratio on the inner display instead of stretching it wide. */
    fun widgetContentSize(spanX: Int, spanY: Int): Pair<Int, Int> {
        val edge = minOf(cellWidth, cellHeight)
        val w = spanX * if (isExpanded) edge else cellWidth
        val h = spanY * if (isExpanded) edge else cellHeight
        return (w - 2 * widgetPaddingPx).coerceAtLeast(1) to (h - 2 * widgetPaddingPx).coerceAtLeast(1)
    }
}
