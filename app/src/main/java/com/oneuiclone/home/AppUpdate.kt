package com.oneuiclone.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Self-update from the GitHub releases, as Skipii does. At most every 6 hours (and on a new version's first start)
 * the newest release's Launchii.apk is looked up; when it's newer than this build, Home shows an Update pill left
 * of the Search pill ([com.oneuiclone.home.ui.HomeSearchButton] with [Launcher.updatePill]). Tapping it downloads
 * the APK (checked against GitHub's size and sha256) and hands it to PackageInstaller. The first update asks the
 * user (Android may first ask to allow installs from Launchii); after that Launchii is its own installer and
 * Android 12+ updates it without a prompt, then a notification says it's done. Nothing newer = no pill. On Wi-Fi
 * the APK is fetched ahead, so the tap goes straight to installing. Logs "update check: …" / "update: …".
 */
object AppUpdate {
    private const val TAG = "Launchii"
    const val REPO = "MladenSrB/Launchii"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_ASSET = "Launchii.apk"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    /** A check that failed (offline, rate limit) tries again after this, not 6 hours later. */
    private const val RETRY_AFTER_MS = 60 * 60 * 1000L
    private const val KEY_NEXT_CHECK = "next_check"
    private const val KEY_RELEASE = "release"
    /** The version that made the last check: a different one (just installed by any means) checks at once. */
    private const val KEY_CHECKED_BY = "checked_by"
    /** The version an install was started for: the next start of that version posts "updated". */
    private const val KEY_INSTALLING = "installing"
    /** The install asked the user; Android's own dialog then offers Open, so no notification. */
    private const val KEY_PROMPTED = "prompted"
    private const val CHANNEL = "updates"
    internal const val ACTION_RESULT = "app.launchii.UPDATE_RESULT"

    data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?)

    enum class Phase { Ready, Downloading, Installing, Failed }

    /** The newer release, or null when this build is the newest (then nothing is shown). */
    var available: Release? = null
        private set
    var phase = Phase.Ready
        private set
    /** Download progress 0…1. */
    var progress = 0f
        private set
    /** Called on the main thread whenever [available], [phase] or [progress] changes. */
    var onChanged: (() -> Unit)? = null

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var thisVersion: String
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var checking = false
    /** The release whose APK is being fetched, and who's waiting for it (a tap during a Wi-Fi prefetch). */
    private var downloading: Release? = null
    private val waiting = mutableListOf<(File?) -> Unit>()
    /** Sent to "Install unknown apps"; the install carries on when Home comes back. */
    private var awaitingPermission = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Each update source keeps its own check schedule and cached release.
        prefs = app.getSharedPreferences("update_${REPO.replace('/', '_')}", Context.MODE_PRIVATE)
        thisVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        // Shown straight away from the last check; the next check corrects it.
        available = savedRelease()?.takeIf { isNewer(it.version) }
        // An installed update leaves its APK behind; keep only the one still to install.
        val keep = available?.let { apkFile(it).name }
        dir().listFiles()?.forEach { if (it.name != keep) it.delete() }
    }

    private fun changed() = onChanged?.invoke()

    /** Called whenever Home comes to the front; looks GitHub up at most every 6 hours, and on a new version's first start. */
    fun checkIfDue() {
        val newInstall = prefs.getString(KEY_CHECKED_BY, null) != thisVersion
        if (checking || (!newInstall && System.currentTimeMillis() < prefs.getLong(KEY_NEXT_CHECK, 0L))) return
        checking = true
        io.execute {
            val result = runCatching { fetchLatest() }
            main.post {
                checking = false
                val now = System.currentTimeMillis()
                result.onFailure {
                    Log.w(TAG, "update check failed (${it.message}); trying again in an hour")
                    prefs.edit().putLong(KEY_NEXT_CHECK, now + RETRY_AFTER_MS).putString(KEY_CHECKED_BY, thisVersion).apply()
                    return@post
                }
                val latest = result.getOrNull()
                prefs.edit().putLong(KEY_NEXT_CHECK, now + CHECK_EVERY_MS).putString(KEY_CHECKED_BY, thisVersion)
                    .apply { if (latest != null) putString(KEY_RELEASE, latest.toJson()) else remove(KEY_RELEASE) }
                    .apply()
                val newer = latest?.takeIf { isNewer(it.version) }
                Log.i(TAG, "update check: latest ${latest?.version ?: "none"}, this $thisVersion" +
                    if (newer != null) " → update available" else "")
                if (phase == Phase.Downloading || phase == Phase.Installing) return@post
                if (newer != available) { available = newer; phase = Phase.Ready; changed() }
                if (newer != null && !metered()) apkFor(newer) {}
            }
        }
    }

    /** The Update pill's tap. */
    fun install(context: Context) {
        val release = available ?: return
        if (phase == Phase.Downloading || phase == Phase.Installing) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        phase = Phase.Downloading
        changed()
        apkFor(release) { apk ->
            if (apk == null) { phase = Phase.Failed; changed(); return@apkFor }
            phase = Phase.Installing
            changed()
            io.execute {
                val started = runCatching { commit(apk, release) }
                main.post {
                    started.onFailure {
                        Log.w(TAG, "update: couldn't start the install", it)
                        phase = Phase.Failed
                        changed()
                    }
                }
            }
        }
    }

    /** Back from "Install unknown apps": install if it was allowed, else the pill waits again. */
    fun onResumed(context: Context) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (app.packageManager.canRequestPackageInstalls()) install(context)
    }

    /** PackageInstaller's answer to [commit] (via [UpdateReceiver]). */
    internal fun onInstallResult(context: Context, intent: Intent) {
        init(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) { phase = Phase.Failed; changed(); return }
                prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is replaced; onUpdated runs in the new one
            PackageInstaller.STATUS_FAILURE_ABORTED -> { // the user said no
                prefs.edit().remove(KEY_INSTALLING).remove(KEY_PROMPTED).apply()
                phase = Phase.Ready
                changed()
            }
            else -> {
                Log.w(TAG, "update: install failed ($status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)})")
                prefs.edit().remove(KEY_INSTALLING).remove(KEY_PROMPTED).apply()
                phase = Phase.Failed
                changed()
            }
        }
    }

    /** The new version's first start after [commit] installed it. */
    internal fun onUpdated(context: Context) {
        init(context)
        val version = prefs.getString(KEY_INSTALLING, null) ?: return
        val prompted = prefs.getBoolean(KEY_PROMPTED, false)
        prefs.edit().remove(KEY_INSTALLING).remove(KEY_PROMPTED).apply()
        Log.i(TAG, "update: now on $thisVersion (installed $version)")
        // A silent update closed the app with no word; Android's own dialog already offered Open.
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (prompted || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Launchii updated to $thisVersion")
            .setContentText("Tap to open")
            .setContentIntent(PendingIntent.getActivity(context, 0, home, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(CHANNEL.hashCode(), notification) }
            .onFailure { Log.w(TAG, "update: couldn't post the updated notification (${it.message})") }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Launchii/$thisVersion")
    }

    private fun fetchLatest(): Release? {
        val c = open(LATEST_URL).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return parseRelease(JSONObject(c.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            c.disconnect()
        }
    }

    /** The latest-release JSON → its Launchii.apk, or null when it has none. */
    internal fun parseRelease(json: JSONObject): Release? {
        val version = json.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_ASSET } ?: return null
        return Release(
            version = version,
            apkUrl = apk.optString("browser_download_url").ifBlank { return null },
            size = apk.optLong("size"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        )
    }

    /** "0.151" vs "0.150" → positive; missing parts count as 0. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun isNewer(version: String) = compareVersions(version, thisVersion) > 0

    private fun metered() = app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false

    /** The APK on disk (null on failure), on the main thread; downloads it once, however many ask meanwhile. */
    private fun apkFor(release: Release, then: (File?) -> Unit) {
        waiting += then
        if (downloading == release) return
        downloading = release
        io.execute {
            val apk = runCatching { downloadApk(release) }
                .onFailure { Log.w(TAG, "update: download of ${release.version} failed (${it.message})") }
                .getOrNull()
            main.post {
                if (downloading == release) downloading = null
                val calls = waiting.toList()
                waiting.clear()
                calls.forEach { it(apk) }
            }
        }
    }

    private fun setProgress(p: Float) = main.post { progress = p; if (phase == Phase.Downloading) changed() }

    private fun downloadApk(release: Release): File {
        val file = apkFile(release)
        if (file.length() == release.size && (release.sha256 == null || file.sha256() == release.sha256)) {
            setProgress(1f)
            return file
        }
        setProgress(0f)
        val part = File(dir(), file.name + ".part")
        val c = open(release.apkUrl)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val total = release.size.takeIf { it > 0 } ?: c.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var shown = -1
            part.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) (read * 100 / total).toInt() else 0
                        if (percent != shown) { shown = percent; setProgress(percent / 100f) }
                    }
                }
            }
            check(release.size <= 0 || read == release.size) { "got $read bytes, expected ${release.size}" }
            release.sha256?.let { check(digest.digest().hex() == it) { "sha256 mismatch" } }
        } finally {
            c.disconnect()
        }
        check(part.renameTo(file)) { "couldn't rename ${part.name}" }
        Log.i(TAG, "update: downloaded ${release.version} (${file.length()} bytes)")
        return file
    }

    private fun commit(apk: File, release: Release) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Once Launchii installed itself, Android 12+ lets it update without asking.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite(APK_ASSET, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            prefs.edit().putString(KEY_INSTALLING, release.version).putBoolean(KEY_PROMPTED, false).commit()
            val result = Intent(app, UpdateReceiver::class.java).setAction(ACTION_RESULT)
            // Mutable: PackageInstaller fills in the status extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            Log.i(TAG, "update: installing ${release.version}")
            session.commit(PendingIntent.getBroadcast(app, id, result, flags).intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private fun dir() = File(app.cacheDir, "update").apply { mkdirs() }
    private fun apkFile(release: Release) = File(dir(), "Launchii-${release.version}.apk")

    private fun savedRelease(): Release? = prefs.getString(KEY_RELEASE, null)?.let {
        runCatching {
            val o = JSONObject(it)
            Release(o.getString("version"), o.getString("url"), o.getLong("size"), o.optString("sha256").ifBlank { null })
        }.getOrNull()
    }

    private fun Release.toJson() =
        JSONObject().put("version", version).put("url", apkUrl).put("size", size).put("sha256", sha256 ?: "").toString()

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** PackageInstaller results, and the first start of a freshly installed version. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppUpdate.ACTION_RESULT -> AppUpdate.onInstallResult(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppUpdate.onUpdated(context)
        }
    }
}
