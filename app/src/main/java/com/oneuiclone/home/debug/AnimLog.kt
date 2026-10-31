package com.oneuiclone.home.debug

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Timestamped launcher events around app opens and closes, kept in a file (pull it with
 * `adb shell run-as`) so quick-switch timing can be diagnosed. Each line has the wall-clock time and the gap
 * since the previous event. Writes go to a background thread (a main-thread write once stalled a close).
 */
object AnimLog {
    private const val FILE = "anim-log.txt"
    private const val MAX_BYTES = 128 * 1024

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "anim-log").apply { priority = Thread.MIN_PRIORITY } }
    /** Only used on [io]. */
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var last = 0L

    /** Debug builds only; a release build deletes any log an older build left behind and never writes one. */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) file = f
        else io.execute { f.delete() }
    }

    fun log(event: String) {
        val f = file ?: return
        val now = SystemClock.uptimeMillis()
        val wall = System.currentTimeMillis()
        val gap = if (last == 0L) 0L else now - last
        last = now
        io.execute {
            try {
                if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2).substringAfter('\n'))
                f.appendText("${clock.format(Date(wall))} +${gap}ms $event\n")
            } catch (_: Exception) {
            }
        }
    }
}
