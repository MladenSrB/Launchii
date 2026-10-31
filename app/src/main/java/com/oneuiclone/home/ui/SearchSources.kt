package com.oneuiclone.home.ui

import android.Manifest
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.model.AppUsage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Off-main-thread sources for search, alive as long as the launcher: what to suggest before anything is typed
 * (app usage scores and contacts) and the contacts matching a query (READ_CONTACTS).
 *
 * Suggestions are a [Snapshot] worked out in the background and only replaced while search isn't on screen
 * ([refresh] from onStop, at most every [REFRESH_MS]); each search takes the snapshot as it opens, so nothing
 * reshuffles in front of the user. The snapshot is saved, so a restarted launcher shows the same thing.
 */
class SearchSources(
    private val context: Context,
    private val usage: AppUsage,
    private val apps: () -> List<AppInfo>,
) {
    class Contact(val id: Long, val uri: Uri, val name: String, val photo: Bitmap?)

    class Snapshot(val scores: Map<AppKey, Double>, val contacts: List<Contact>)

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-sources").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val opened = context.getSharedPreferences("search_contacts", Context.MODE_PRIVATE)
    private val saved = context.getSharedPreferences("search_suggestions", Context.MODE_PRIVATE)
    private var refreshing = false

    /** What a search opening now shows. Main thread. */
    var snapshot = Snapshot(emptyMap(), emptyList()); private set

    val contactsAllowed get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    init {
        worker.execute {
            val s = restore()
            main.post { if (snapshot.scores.isEmpty() && snapshot.contacts.isEmpty()) snapshot = s }
        }
    }

    /**
     * Works the suggestions out again when the last time was over [REFRESH_MS] ago ([force]: now), then [done].
     * Call it while search is closed; an open search keeps the snapshot it opened with either way.
     */
    fun refresh(force: Boolean = false, done: (() -> Unit)? = null) {
        if (refreshing || (!force && System.currentTimeMillis() - saved.getLong(KEY_AT, 0L) < REFRESH_MS)) return
        val list = apps()
        if (list.isEmpty()) return                                  // not loaded yet: try again next time
        refreshing = true
        worker.execute {
            val s = Snapshot(usage.scores(list), if (contactsAllowed) suggestedContacts(MAX_SUGGESTED) else emptyList())
            save(s)
            main.post { snapshot = s; refreshing = false; done?.invoke() }
        }
    }

    /** Contacts whose name matches [q] (the provider folds accents itself). */
    fun query(q: String, done: (List<Contact>) -> Unit) {
        worker.execute {
            val ct = if (contactsAllowed) matching(q) else emptyList()
            main.post { done(ct) }
        }
    }

    /** A contact opened from search moves up the suggestions (from the next refresh on). */
    fun contactOpened(c: Contact) {
        val k = c.uri.toString()
        opened.edit().putInt(k, opened.getInt(k, 0) + 1).apply()
    }

    private fun save(s: Snapshot) {
        val scores = JSONObject()
        for ((k, v) in s.scores) if (v > 0.0) scores.put(k.flatten(), v)
        saved.edit()
            .putString(KEY_SCORES, scores.toString())
            .putString(KEY_CONTACTS, JSONArray(s.contacts.map { it.uri.toString() }).toString())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun restore(): Snapshot = try {
        val scores = HashMap<AppKey, Double>()
        saved.getString(KEY_SCORES, null)?.let { JSONObject(it) }?.let { o ->
            for (k in o.keys()) AppKey.parse(k)?.let { scores[it] = o.getDouble(k) }
        }
        val uris = saved.getString(KEY_CONTACTS, null)?.let { JSONArray(it) }
        val contacts = if (uris == null || !contactsAllowed) emptyList()
            else (0 until uris.length()).flatMap { read(Uri.parse(uris.getString(it)), null, null, 1) }
        Snapshot(scores, contacts)
    } catch (_: Exception) {
        Snapshot(emptyMap(), emptyList())
    }

    private val cols = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)

    /** Names starting with [q] before ones where a later word does ("m": Mom before Jake Morales). */
    private fun matching(q: String): List<Contact> =
        read(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q)), null,
            "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC", 50)
            .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }.take(MAX_MATCHES)

    /**
     * Most likely to be wanted first: contacts opened from search before, starred ones, then people from recent
     * conversations (messaging apps' conversation shortcuts), then whoever was contacted or edited most recently.
     */
    private fun suggestedContacts(max: Int): List<Contact> {
        val out = LinkedHashMap<Long, Contact>()
        fun add(list: List<Contact>) { for (c in list) if (out.size < max) out.putIfAbsent(c.id, c) }
        opened.all.entries.sortedByDescending { it.value as? Int ?: 0 }.forEach { (uri, _) ->
            if (out.size < max) add(read(Uri.parse(uri), null, null, 1))
        }
        if (out.size < max) add(read(ContactsContract.Contacts.CONTENT_URI, "${ContactsContract.Contacts.STARRED}=1",
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC", max))
        for (name in conversationNames()) {
            if (out.size >= max) break
            add(read(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name)), null, null, 3)
                .filter { it.name.equals(name, ignoreCase = true) }.take(1))
        }
        if (out.size < max) add(read(ContactsContract.Contacts.CONTENT_URI, "${ContactsContract.Contacts.HAS_PHONE_NUMBER}=1",
            "${ContactsContract.Contacts.LAST_TIME_CONTACTED} DESC, ${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC",
            max + out.size))
        return out.values.toList()
    }

    /** Titles of conversation shortcuts (one per chat), most recently active first. */
    private fun conversationNames(): List<String> = try {
        if (!launcherApps.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED)
            launcherApps.profiles.flatMap { launcherApps.getShortcuts(q, it) ?: emptyList() }
                .filter { it.categories?.contains(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION) == true }
                .sortedByDescending { it.lastChangedTimestamp }
                .mapNotNull { it.shortLabel?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .distinct()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun read(uri: Uri, selection: String?, order: String?, limit: Int): List<Contact> = try {
        context.contentResolver.query(uri, cols, selection, null, order)?.use { c ->
            val out = ArrayList<Contact>()
            while (c.moveToNext() && out.size < limit) contact(c)?.let { out += it }
            out
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun contact(c: Cursor): Contact? {
        val name = c.getString(2) ?: return null
        val photo = c.getString(3)?.let { p ->
            try { context.contentResolver.openInputStream(Uri.parse(p))?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
        return Contact(c.getLong(0), ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)), name, photo)
    }

    companion object {
        /** Suggested contacts before anything is typed (three rows of two). */
        const val MAX_SUGGESTED = 6
        /** Matching contacts while typing (two rows of two). */
        const val MAX_MATCHES = 4
        private const val REFRESH_MS = 6 * 60 * 60 * 1000L
        private const val KEY_SCORES = "scores"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_AT = "at"
    }
}
