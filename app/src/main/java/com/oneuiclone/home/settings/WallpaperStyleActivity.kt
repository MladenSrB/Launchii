package com.oneuiclone.home.settings

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.oneuiclone.home.WallpaperActivity

/**
 * One UI's "Wallpaper and style" (Home screen edit mode › Wallpaper and style): lock screen and Home previews, then
 * Change wallpapers (a photo, positioned in [WallpaperActivity]) with Blur wallpaper under it, and Colour palette (the
 * system's Wallpaper & style app, which owns Material You colours). Icons are a page of their own ([IconThemeActivity],
 * opened from Home's edit mode and Settings). The Home preview is Home's real first page ([StylePreview]).
 */
class WallpaperStyleActivity : OneUiPage() {

    companion object {
        private const val REQUEST_PHOTO = 1
        /** Pixel's Wallpaper & style app; AOSP's picker is the fallback. */
        private val PICKERS = listOf("com.google.android.apps.wallpaper", "com.android.wallpaper")
        /** WallpaperPickerDelegate.LAUNCH_SOURCE_LAUNCHER: opens the picker's main screen (wallpaper and colours). */
        private const val EXTRA_LAUNCH_SOURCE = "com.android.wallpaper.LAUNCH_SOURCE"
        private const val LAUNCH_SOURCE_LAUNCHER = "app_launched_launcher"
    }

    private lateinit var preview: StylePreview
    private lateinit var lockCard: PreviewCard
    private lateinit var homeCard: PreviewCard
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = StylePreview(this)
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperChanged, android.os.Handler(mainLooper))
    }

    override fun onDestroy() {
        super.onDestroy()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperChanged)
        preview.destroy()
    }

    /** A wallpaper set (or blurred) while the page is open. */
    private val wallpaperChanged = WallpaperManager.OnColorsChangedListener { _, _ -> if (built) build() }

    /** Lays the page out once; every resume re-reads the wallpapers (one may have just been set). */
    override fun build() {
        if (!built) { built = true; layout() }
        preview.load { lockCard.bitmap = preview.renderLock(cardWidth()); refreshHome() }
    }

    private fun refreshHome() = preview.render(StylePreview.Look.of(prefs), cardWidth(), cardWidth()) { home, _ -> homeCard.bitmap = home }

    private fun cardWidth() = (resources.displayMetrics.widthPixels - dp(12f) * 2 - dp(20f) * 2 - dp(16f)) / 2
    private fun layout() {
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(24f)) }

        appBar("Wallpaper and style")

        // the two previews, lock screen first
        val ratio = preview.height / preview.width.toFloat()
        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        lockCard = PreviewCard(this, dp(18f).toFloat(), cardColor).apply { contentDescription = "Lock screen preview" }
        homeCard = PreviewCard(this, dp(18f).toFloat(), cardColor).apply { contentDescription = "Home screen preview" }
        val h = (cardWidth() * ratio).toInt()
        previews.addView(lockCard, LinearLayout.LayoutParams(0, h, 1f).apply { marginEnd = dp(8f) })
        previews.addView(homeCard, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = dp(8f) })
        for (c in listOf(lockCard, homeCard)) c.setOnClickListener { pickPhoto() }
        list.addView(previews, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(28f)
        })

        card {
            row("Change wallpapers", null, primary, null, { pickPhoto() }, titleWeight = 600)
            var strength: SeekBar? = null
            lateinit var blurRow: LinearLayout
            val blurSummary = "Soften the wallpaper behind your Home screen. Your lock screen stays as it is."
            fun applyBlur() {
                val summary = (blurRow.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
                summary.text = if (prefs.homeBlur) "Blurring your wallpaper…" else "Restoring your wallpaper…"
                com.oneuiclone.home.ui.WallpaperBlur.apply(this@WallpaperStyleActivity, prefs.homeBlurLevel) { r ->
                    summary.text = blurSummary
                    // the wallpaper change reloaded the previews before the blurred and sharp copies were recorded
                    if (r == com.oneuiclone.home.ui.WallpaperBlur.Result.DONE && !isDestroyed) build()
                    if (r == com.oneuiclone.home.ui.WallpaperBlur.Result.NO_ACCESS && prefs.homeBlur) {
                        prefs.homeBlur = false
                        (blurRow.getChildAt(1) as Switch).isChecked = false
                        Toast.makeText(this@WallpaperStyleActivity,
                            "Set your wallpaper with Change wallpapers or Set as wallpaper in your photos app to blur it. " +
                                "Live wallpapers can't be blurred.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            blurRow = toggle("Blur wallpaper", blurSummary, prefs.homeBlur) {
                if (prefs.homeBlur != it) { prefs.homeBlur = it; applyBlur() }
                strength?.isEnabled = it
            }
            strength = slider("Blur strength", prefs.homeBlurAmount, 10, 100) {
                prefs.homeBlurAmount = it
                if (prefs.homeBlur) applyBlur()
            }
            strength.isEnabled = prefs.homeBlur
        }
        card {
            row("Colour palette", "Choose a palette based on colours from your wallpaper.", sub,
                Swatch(this@WallpaperStyleActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(34f), dp(56f)).apply { marginStart = dp(16f) } },
                { openColours() }, titleWeight = 600)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(list); isVerticalScrollBarEnabled = false; clipToPadding = false }
        // the page scrolls under the status bar; this keeps it out from behind it
        val statusScrim = View(this).apply { setBackgroundColor(bg); translationZ = dp(8f).toFloat(); outlineProvider = null }
        val root = android.widget.FrameLayout(this)
        root.addView(scroll)
        root.addView(statusScrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            statusScrim.layoutParams.height = bars.top
            root.requestLayout()
            insets
        }
        setContentView(root)
    }

    private fun pickPhoto() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQUEST_PHOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        // positioned and set there, then back here, where the previews show it
        startActivity(Intent(this, WallpaperActivity::class.java).setData(uri)
            .putExtra(WallpaperActivity.EXTRA_STAY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Material You colours belong to the system's Wallpaper & style app; its main screen has the palettes. */
    private fun openColours() {
        val base = Intent(Intent.ACTION_SET_WALLPAPER).putExtra(EXTRA_LAUNCH_SOURCE, LAUNCH_SOURCE_LAUNCHER)
        for (pkg in PICKERS) {
            try { startActivity(Intent(base).setPackage(pkg)); return } catch (_: android.content.ActivityNotFoundException) {}
        }
        try {
            startActivity(Intent.createChooser(base, "Colour palette"))
        } catch (_: Exception) {
            Toast.makeText(this, "No Wallpaper & style app on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    /** The palette as One UI shows it: the accent over a light and a dark tone, in a rounded bar. */
    private class Swatch(context: Context) : View(context) {
        private val colors = intArrayOf(
            context.getColor(android.R.color.system_accent1_400),
            context.getColor(android.R.color.system_accent2_50),
            context.getColor(android.R.color.system_neutral1_800),
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        override fun onDraw(canvas: Canvas) {
            val r = width * 0.3f
            clip.reset(); clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val band = height / colors.size.toFloat()
            colors.forEachIndexed { i, c -> paint.color = c; canvas.drawRect(0f, i * band, width.toFloat(), (i + 1) * band, paint) }
            canvas.restore()
        }
    }
}
