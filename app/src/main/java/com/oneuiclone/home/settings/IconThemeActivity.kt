package com.oneuiclone.home.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Icons (Home screen edit mode, Settings): the icon style and the rows that go with it, then icon size and app labels.
 * Rows come and go as the style changes; the dock strip stays fixed at the top and follows every change, the
 * sliders included.
 */
class IconThemeActivity : OneUiPage() {

    companion object {
        // 0 Default: the apps' own icons. 1 Themed: your colour palette. 2 Frosted: themed, with a see-through
        // plate. 3 Dark: every app on a dark plate, in its own colours. 4 Minimal: themed icons' white glyph alone.
        // 5 Dot: the themed glyph as a dot grid. 6 Prism: the themed glyph in a gradient of the app's colour, on white or graphite.
        // 7 Stencil: the themed glyph cut out of a black plate, the wallpaper showing through frosted.
        val STYLES = listOf("Default", "Themed", "Frosted", "Dark", "Minimal", "Dot", "Prism", "Stencil")
        private val SUMMARIES = listOf(
            "Each app's own icon",
            "Your colour palette on app icons",
            "Theme icons on see-through glass over your wallpaper",
            "Dark backgrounds with each app's colours, for any app",
            "Just the white symbols, with no icon shape behind them",
            "Symbols drawn in dots, black on paper or white on black",
            "Symbols in a gradient of each app's colour, on white or graphite",
            "Symbols cut out of black, showing your wallpaper frosted",
        )
        private val THEMED = setOf(1, 2, 4, 5, 6, 7)
        /** The styles with a light and a dark version (Dot, Prism), which get Light or dark. */
        private val LIGHT_AND_DARK = setOf(5, 6)
        private val MODES = listOf(
            com.oneuiclone.home.icons.IconFactory.MODE_AUTO to "Auto",
            com.oneuiclone.home.icons.IconFactory.MODE_LIGHT to "Light",
            com.oneuiclone.home.icons.IconFactory.MODE_DARK to "Dark",
        )
        private val MODE_SUMMARIES = listOf(
            "Light or dark with your phone's dark mode",
            "Light icons, in dark mode too",
            "Dark icons, in light mode too",
        )

        fun style(prefs: Prefs) = when {
            prefs.darkIcons -> 3; !prefs.themedIcons -> 0
            prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_DOT -> 5
            prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_PRISM -> 6
            prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL -> 7
            prefs.minimalIcons -> 4; prefs.glassIcons -> 2; else -> 1
        }
    }

    private lateinit var preview: StylePreview
    private lateinit var dockStrip: PreviewCard
    private var built = false
    /** Slider positions while dragged, previewed before they're saved. */
    private var liveTransparency: Int? = null
    private var liveScale: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.destroy()
    }

    override fun build() {
        if (!built) { built = true; layout() }
        preview.load { refreshDock() }
    }

    private fun stripWidth() = resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(14f) * 2

    private fun refreshDock() = preview.render(StylePreview.Look.of(prefs, iconScale = liveScale ?: prefs.iconScale,
        transparency = liveTransparency ?: prefs.glassIconTransparency), stripWidth() / 3, stripWidth()) { _, dock -> dockStrip.bitmap = dock }

    private fun layout() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // fixed: the app bar and the dock
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), 0) }
        appBar("Icons")
        card {
            dockStrip = PreviewCard(this@IconThemeActivity, dp(20f).toFloat(), bg).apply { contentDescription = "Your dock with these icons" }
            current?.addView(FrameLayout(this@IconThemeActivity).apply {
                setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
                addView(dockStrip, FrameLayout.LayoutParams(stripWidth(), preview.dockHeight(stripWidth())))
            })
        }
        page.addView(list)

        // the controls, scrolling only if larger text makes them taller than the screen
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }
        card {
            val style = style(prefs)
            lateinit var themedRows: List<View>
            lateinit var modeRows: List<View>
            lateinit var anyRows: List<View>
            lateinit var frostedRows: List<View>
            fun showFor(s: Int) {
                themedRows.forEach { it.visibility = if (s in THEMED) View.VISIBLE else View.GONE }
                modeRows.forEach { it.visibility = if (s in LIGHT_AND_DARK) View.VISIBLE else View.GONE }
                anyRows.forEach { it.visibility = if (s > 0) View.VISIBLE else View.GONE }
                frostedRows.forEach { it.visibility = if (s == 2) View.VISIBLE else View.GONE }
            }
            dropdown("Icon style", STYLES, style, SUMMARIES) { s ->
                prefs.themedIcons = s in THEMED
                prefs.glassIcons = s == 2
                prefs.darkIcons = s == 3
                prefs.minimalIcons = s == 4
                prefs.iconLook = when (s) {
                    5 -> com.oneuiclone.home.icons.IconFactory.LOOK_DOT
                    6 -> com.oneuiclone.home.icons.IconFactory.LOOK_PRISM
                    7 -> com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL
                    else -> ""
                }
                showFor(s); refreshDock()
            }
            modeRows = added {
                val mode = MODES.indexOfFirst { it.first == prefs.iconMode }.coerceAtLeast(0)
                dropdown("Light or dark", MODES.map { it.second }, mode, MODE_SUMMARIES) { m ->
                    prefs.iconMode = MODES[m].first; refreshDock()
                }
            }
            themedRows = added {
                toggle("Theme all apps", "Recolour apps that don't support theme icons so every icon matches.", prefs.themeAllApps) {
                    prefs.themeAllApps = it; refreshDock()
                }
            }
            anyRows = added {
                toggle("Apply to Apps screen and search", "Use these icons on the Apps screen and in search too, not just on Home.", prefs.themeAppsScreen) {
                    prefs.themeAppsScreen = it
                }
            }
            frostedRows = added {
                slider("Icon transparency", prefs.glassIconTransparency, 0, 100, onLive = { liveTransparency = it; refreshDock() }) {
                    prefs.glassIconTransparency = it; liveTransparency = null; refreshDock()
                }
            }
            showFor(style)
            // rows come and go smoothly as the style changes
            current?.layoutTransition = android.animation.LayoutTransition()
        }
        card {
            slider("Icon size", prefs.iconScale, 85, 115, onLive = { liveScale = it; refreshDock() }) {
                prefs.iconScale = it; liveScale = null; refreshDock()
            }
        }
        card {
            header("Show app labels")
            toggle("Home screen", null, prefs.homeLabels) { prefs.homeLabels = it }
            toggle("Apps screen", null, prefs.appsLabels) { prefs.appsLabels = it }
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
}
