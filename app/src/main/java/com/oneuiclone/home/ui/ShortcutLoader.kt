package com.oneuiclone.home.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.SystemClock
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Deep shortcuts for the quick option popup, loaded off the main thread. Querying LauncherApps and rendering
 * the shortcut icons took 35 ms (warm) to 360 ms (first use) on the main thread at long-press time; now
 * the load starts on touch-down, so it's normally finished by the time the long-press fires.
 */
class ShortcutLoader(private val context: Context, private val iconSizePx: Int) {

    private class Entry(val night: Boolean, val time: Long, val result: Future<List<Pair<ShortcutInfo, Bitmap>>>)

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    /** Own factory: the app repository renders icons on its own thread with a shared one. */
    private val factory by lazy { IconFactory(context) }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shortcuts").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val cache = HashMap<AppKey, Entry>()

    init {
        worker.execute { factory }                 // decode the emboss assets now, not on the first long-press
    }

    private fun night() = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Starts (or refreshes, after [MAX_AGE_MS]) the load for [app]. Call from touch-down. */
    fun prefetch(app: AppInfo) {
        val night = night()
        val now = SystemClock.uptimeMillis()
        val e = cache[app.key]
        if (e != null && e.night == night && (!e.result.isDone || now - e.time < MAX_AGE_MS)) return
        cache[app.key] = Entry(night, now, worker.submit<List<Pair<ShortcutInfo, Bitmap>>> { load(app, night) })
    }

    /** Shortcuts for [app]; waits briefly for an in-flight load and gives up rather than stall the UI. */
    fun get(app: AppInfo): List<Pair<ShortcutInfo, Bitmap>> {
        prefetch(app)
        return try { cache[app.key]?.result?.get(WAIT_MS, TimeUnit.MILLISECONDS) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun clear() = cache.clear()

    fun destroy() = worker.shutdownNow()

    private fun load(app: AppInfo, night: Boolean): List<Pair<ShortcutInfo, Bitmap>> = try {
        if (!launcherApps.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.key.component.packageName)
                .setActivity(app.key.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            (launcherApps.getShortcuts(q, app.user) ?: emptyList())
                .sortedWith(compareBy({ if (it.isDynamic) 1 else 0 }, { it.rank }))
                .take(4)
                .mapNotNull { sc ->
                    val d = launcherApps.getShortcutIconDrawable(sc, context.resources.displayMetrics.densityDpi) ?: return@mapNotNull null
                    sc to factory.create(d, iconSizePx, night)
                }
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val MAX_AGE_MS = 30_000L
        private const val WAIT_MS = 120L
    }
}
