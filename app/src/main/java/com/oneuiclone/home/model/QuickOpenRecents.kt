package com.oneuiclone.home.model

import android.content.Context

/**
 * The apps last used in a bubble and in split screen, for the Bubble and Split panels' grid, which shows them newest
 * first ahead of the most used. Every app bubble is Launchii's own (AppBubbles posts it), so each one shown is recorded
 * ([bubbled]); so are splits opened from the widgets ([split]). With usage access, splits made anywhere else (Recents,
 * One UI's edge panel) are found in the system's activity events ([scan], AppUsage.splitPairs). By package: that's all
 * the system's events give.
 */
class QuickOpenRecents(private val context: Context) {

    private val prefs = context.getSharedPreferences("quick_open_recents", Context.MODE_PRIVATE)

    /** Newest use per package ([apps]), and per pair of packages split together ([pairs], "a|b", a < b). */
    class Snapshot(val apps: Map<String, Long>, val pairs: Map<String, Long> = emptyMap()) {
        /** The apps split with [pkg], each with when that pair was last used. */
        fun partners(pkg: String): Map<String, Long> {
            val out = HashMap<String, Long>()
            for ((k, t) in pairs) {
                val a = k.substringBefore('|'); val b = k.substringAfter('|')
                when (pkg) { a -> out[b] = t; b -> out[a] = t }
            }
            return out
        }

        companion object { val EMPTY = Snapshot(emptyMap()) }
    }

    fun bubbled(app: AppInfo) = put(BUBBLE, app.key.component.packageName)

    /** Packages that bubble their own conversations (Messages, chat apps), seen by the badge listener. */
    fun bubblesItself(pkg: String) {
        if (pkg == context.packageName || prefs.contains(SELF + pkg)) return
        prefs.edit().putLong(SELF + pkg, System.currentTimeMillis()).apply()
    }

    fun split(top: AppInfo, bottom: AppInfo) {
        val a = top.key.component.packageName; val b = bottom.key.component.packageName
        val now = System.currentTimeMillis()
        prefs.edit().putLong(SPLIT + a, now).putLong(SPLIT + b, now).putLong(PAIR + pairKey(a, b), now).apply()
        trim()
    }

    fun bubbles() = Snapshot(read(BUBBLE))

    fun splits() = Snapshot(read(SPLIT), read(PAIR))

    /**
     * Adds the splits the system saw since the last scan (at most every [SCAN_MS]; each reads only what's new). Reads the system's usage events, so
     * call it off the main thread. Pairs with an app in a bubble are left out: a bubble over an app has both in front,
     * just like a split.
     */
    fun scan(usage: AppUsage) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(SCANNED, 0L)
        if (now - last < SCAN_MS) return
        val bubbly = read(BUBBLE).keys + read(SELF).keys
        val found = usage.splitPairs(since = maxOf(last, now - KEEP_MS), ignore = bubbly) ?: return
        val e = prefs.edit().putLong(SCANNED, now)
        val apps = read(SPLIT); val pairs = read(PAIR)
        for ((pair, t) in found) {
            val (a, b) = pair
            if (t > (apps[a] ?: 0L)) e.putLong(SPLIT + a, t)
            if (t > (apps[b] ?: 0L)) e.putLong(SPLIT + b, t)
            val k = pairKey(a, b)
            if (t > (pairs[k] ?: 0L)) e.putLong(PAIR + k, t)
        }
        e.apply()
        trim()
    }

    private fun put(kind: String, pkg: String) {
        prefs.edit().putLong(kind + pkg, System.currentTimeMillis()).apply()
        trim()
    }

    private fun read(kind: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((k, v) in prefs.all) if (k.startsWith(kind) && v is Long) out[k.removePrefix(kind)] = v
        return out
    }

    /** Forgets uses older than [KEEP_MS]. */
    private fun trim() {
        val old = System.currentTimeMillis() - KEEP_MS
        val gone = prefs.all.filter { (k, v) -> k != SCANNED && !k.startsWith(SELF) && v is Long && v < old }.keys
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove(it) } }.apply()
    }

    private fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    private companion object {
        const val BUBBLE = "b:"
        const val SPLIT = "s:"
        const val PAIR = "p:"
        const val SELF = "self:"
        const val SCANNED = "scanned"
        const val KEEP_MS = 30 * 86_400_000L
        const val SCAN_MS = 30_000L
    }
}
