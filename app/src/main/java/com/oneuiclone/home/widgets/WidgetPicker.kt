package com.oneuiclone.home.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.MagnifierView
import java.text.Collator
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * One UI-style widget list: full-screen glass sheet, apps grouped alphabetically, each with a
 * horizontal strip of preview cards labelled with their cell size. The search field filters by app or
 * widget name: an app match keeps all its widgets, otherwise only the matching widgets stay.
 *
 * A recycled list, so it stays smooth however many widgets are installed: only the cards on screen exist,
 * and previews are decoded (at the size they show) as their cards scroll in, then cached.
 */
@SuppressLint("ViewConstructor")
class WidgetPicker(
    context: Context,
    private val dp: DeviceProfile,
    private val manager: WidgetManager,
    private val onPick: (AppWidgetProviderInfo) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val sheet = LinearLayout(context)
    private val list = RecyclerView(context)
    private val main = Handler(Looper.getMainLooper())
    /** Labels and icons for the whole list. */
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "widget-picker").apply { priority = Thread.MIN_PRIORITY } }
    /**
     * Preview decoding: a few threads at background (not lowest) priority so they keep up while the list is
     * flung, newest request first so the cards on screen come before the ones already scrolled past.
     */
    private val decoder = java.util.concurrent.ThreadPoolExecutor(DECODERS, DECODERS, 1, java.util.concurrent.TimeUnit.SECONDS,
        object : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable) = offerFirst(e)
        }) { r ->
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }.apply { name = "widget-previews" }
    }
    @Volatile private var closed = false
    private val search = EditText(context)
    private val clear = TextView(context)
    private val empty = TextView(context)
    private var query = ""
    private var loaded = false

    /** Labels and icons, resolved off the main thread; previews come later, per card ([preview]). */
    private class Entry(val info: AppWidgetProviderInfo, val label: String, val key: String, val match: String)
    private class Group(val label: String, val match: String, val icon: Drawable?, val entries: List<Entry>)

    private var groups: List<Group> = emptyList()
    private var filtered: List<Group> = emptyList()

    /** Previews at display size, by bytes (an eighth of the heap at most). */
    private val previews = object : LruCache<String, Drawable>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Drawable) = (value as? BitmapDrawable)?.bitmap?.allocationByteCount ?: 1
    }
    /** The image each preview goes to: the view last bound to it, on screen or held aside by the list. */
    private val targets = HashMap<String, ImageView>()
    /** Previews of cards still bound; a fling's worth of cards gone by is skipped instead of decoded. */
    private val wanted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        isClickable = true
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(0, dp.insets.top + dp.dpi(24f), 0, 0)

        sheet.addView(TextView(context).apply {
            text = "Widgets"
            setTextColor(0xFFFAFAFA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Fonts.ui(context, 600)
            setPadding(dp.dpi(24f), dp.dpi(8f), dp.dpi(24f), dp.dpi(16f))
        })
        sheet.addView(searchField(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp.dpi(48f)).apply {
            leftMargin = dp.dpi(14f); rightMargin = dp.dpi(14f); bottomMargin = dp.dpi(14f)
        })

        val body = FrameLayout(context)
        list.apply {
            // a screen of cards beyond the visible ones is bound too, so their previews are decoding before they show
            layoutManager = object : LinearLayoutManager(context) {
                override fun calculateExtraLayoutSpace(state: RecyclerView.State, extra: IntArray) {
                    extra[0] = height / 2; extra[1] = height
                }
            }
            setItemViewCacheSize(6)
            adapter = Cards()
            itemAnimator = null
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp.insets.bottom + dp.dpi(24f))
            // dragging the list puts the keyboard away so more of it shows
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_DRAGGING && search.hasFocus()) hideKeyboard()
                }
            })
        }
        body.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        empty.apply {
            text = "No widgets found"
            setTextColor(0x99FAFAFA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.ui(context, 400)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp.dpi(40f), 0, 0)
            visibility = GONE
        }
        body.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheet.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // the window doesn't resize for the keyboard: keep the end of the list above it
        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsets.Type.ime()).bottom, dp.insets.bottom)
            list.setPadding(0, 0, 0, bottom + dp.dpi(24f))
            insets
        }
        load()
    }

    private fun searchField(): View {
        val box = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp.dp(24f); setColor(0x26FFFFFF) }
        }
        box.addView(MagnifierView(context, 0xB3FAFAFA.toInt()), LayoutParams(dp.dpi(20f), dp.dpi(20f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp.dpi(16f)
        })
        search.apply {
            background = null
            hint = "Search widgets"
            setHintTextColor(0x99FAFAFA.toInt())
            setTextColor(0xFFFAFAFA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.ui(context, 400)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp.dpi(16f + 20f + 10f), 0, dp.dpi(48f), 0)
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = normalize(s?.toString().orEmpty().trim())
                    clear.animate().alpha(if (s.isNullOrEmpty()) 0f else 1f).setDuration(150).start()
                    clear.isClickable = !s.isNullOrEmpty()
                    applyFilter()
                    list.scrollToPosition(0)
                }
            })
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
        }
        box.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clear.apply {
            text = "✕"
            setTextColor(0xCCFAFAFA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            alpha = 0f
            isClickable = false
            contentDescription = "Clear"
            setOnClickListener { search.text = null }
        }
        box.addView(clear, LayoutParams(dp.dpi(48f), LayoutParams.MATCH_PARENT, Gravity.END))
        return box
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    /** An app match keeps all its widgets; otherwise only the widgets whose name matches. */
    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        filtered = if (query.isEmpty()) groups else groups.mapNotNull { g ->
            if (g.match.contains(query)) g
            else g.entries.filter { it.match.contains(query) }.takeIf { it.isNotEmpty() }?.let { Group(g.label, g.match, g.icon, it) }
        }
        list.adapter?.notifyDataSetChanged()
        empty.visibility = if (loaded && query.isNotEmpty() && filtered.isEmpty()) VISIBLE else GONE
    }

    private fun hideKeyboard() {
        search.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    /** Labels and icons for every widget, sorted by app, handed to the list in one go. */
    private fun load() {
        val pm = context.packageManager
        loader.execute {
            val collator = Collator.getInstance()
            val result = manager.providers().groupBy { it.provider.packageName }
                .map { (pkg, infos) ->
                    val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                    val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                    val entries = infos.map { info ->
                        val l = info.loadLabel(pm).toString()
                        Entry(info, l, info.provider.flattenToString() + "#" + info.profile.hashCode(), normalize(l))
                    }
                    Group(label, normalize(label), icon, entries)
                }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
            main.post {
                if (closed) return@post
                groups = result
                loaded = true
                applyFilter()
            }
        }
    }

    /** Sets [view]'s preview: from the cache, else decoded at its box size off the main thread, then faded in. */
    private fun preview(e: Entry, view: ImageView) {
        (view.tag as? String)?.let { old -> if (targets[old] === view) { targets.remove(old); wanted.remove(old) } }
        view.tag = e.key
        view.animate().cancel()
        previews.get(e.key)?.let { view.setImageDrawable(it); view.alpha = 1f; return }
        view.setImageDrawable(null)
        targets[e.key] = view
        if (!wanted.add(e.key)) return                    // already on its way
        val (w, h) = previewBox(e.info)
        val pad = 2 * dp.dpi(PREVIEW_PAD_DP)
        try {
            decoder.execute {
                // scrolled past before its turn: let it go (binding it again asks again)
                if (e.key !in wanted) return@execute
                val d = try {
                    (e.info.loadPreviewImage(context, 0) ?: e.info.loadIcon(context, 0))?.let { fitted(it, w - pad, h - pad) }
                } catch (_: Exception) { null }
                main.post {
                    wanted.remove(e.key)
                    if (closed || d == null) return@post
                    previews.put(e.key, d)
                    val v = targets.remove(e.key)?.takeIf { it.tag == e.key } ?: return@post
                    v.setImageDrawable(d)
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(150).start()
                }
            }
        } catch (_: Exception) {}                         // closed
    }

    /** A preview's box: 120dp tall, as wide as its cell span's shape. */
    private fun previewBox(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val (sx, sy) = manager.spanFor(info, dp)
        val h = dp.dpi(120f)
        return (h * (sx.toFloat() / sy).coerceIn(0.6f, 2.2f)).toInt() to h
    }

    /**
     * [d] drawn once at the size it shows (fitted into [w]×[h]). Previews come at full resolution (often 1000+ px);
     * scaling those on every frame is what made the list stutter.
     */
    private fun fitted(d: Drawable, w: Int, h: Int): Drawable {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = minOf(w.toFloat() / iw, h.toFloat() / ih, 1f)
        val bw = (iw * scale).toInt().coerceAtLeast(1)
        val bh = (ih * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val bounds = Rect(d.bounds)
        d.setBounds(0, 0, bw, bh)
        d.draw(Canvas(bitmap))
        d.bounds = bounds
        return BitmapDrawable(context.resources, bitmap)
    }

    // ---------------------------------------------------------------------------------------------
    // cards
    // ---------------------------------------------------------------------------------------------

    private class Holder(val card: LinearLayout, val icon: ImageView, val title: TextView, val count: TextView,
                         val strip: LinearLayout, val scroller: HorizontalScrollView) : RecyclerView.ViewHolder(card)

    private inner class Cards : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = filtered.size

        override fun onViewRecycled(h: Holder) {
            for (i in 0 until h.strip.childCount) {
                val image = (h.strip.getChildAt(i) as ViewGroup).getChildAt(0)
                (image.tag as? String)?.let { k -> if (targets[k] === image) { targets.remove(k); wanted.remove(k) } }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp.dp(26f); setColor(0x1FFFFFFF) }
                setPadding(dp.dpi(18f), dp.dpi(14f), dp.dpi(18f), dp.dpi(16f))
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp.dpi(14f); rightMargin = dp.dpi(14f); bottomMargin = dp.dpi(10f)
                }
            }
            val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = ImageView(context)
            header.addView(icon, LinearLayout.LayoutParams(dp.dpi(28f), dp.dpi(28f)))
            val title = TextView(context).apply {
                setTextColor(0xFFFAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Fonts.ui(context, 600)
            }
            header.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp.dpi(12f) })
            val count = TextView(context).apply {
                setTextColor(0x99FAFAFA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Fonts.ui(context, 400)
            }
            header.addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp.dpi(8f) })
            card.addView(header)
            val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
            card.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp.dpi(12f) })
            return Holder(card, icon, title, count, strip, scroller)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val g = filtered[position]
            h.icon.setImageDrawable(g.icon)
            h.title.text = g.label
            h.count.text = "${g.entries.size}"
            h.scroller.scrollTo(0, 0)
            // reuse the strip's cells; only a group with more widgets than any before it adds some
            while (h.strip.childCount < g.entries.size) h.strip.addView(cell(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp.dpi(14f) })
            for (i in 0 until h.strip.childCount) {
                val c = h.strip.getChildAt(i) as LinearLayout
                val e = g.entries.getOrNull(i)
                c.visibility = if (e == null) GONE else VISIBLE
                if (e != null) bindCell(c, e)
            }
        }
    }

    private fun cell() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp.dp(16f); setColor(0x14FFFFFF) }
            val pad = dp.dpi(PREVIEW_PAD_DP)
            setPadding(pad, pad, pad, pad)
        }, LinearLayout.LayoutParams(0, 0))
        addView(TextView(context).apply {
            setTextColor(0xCCFAFAFA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Fonts.ui(context, 400)
            gravity = Gravity.CENTER
            maxLines = 3
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp.dpi(8f) })
    }

    private fun bindCell(c: LinearLayout, e: Entry) {
        val (sx, sy) = manager.spanFor(e.info, dp)
        val (w, h) = previewBox(e.info)
        val image = c.getChildAt(0) as ImageView
        val label = c.getChildAt(1) as TextView
        image.layoutParams = image.layoutParams.apply { width = w; height = h }
        label.layoutParams = label.layoutParams.apply { width = w }
        label.text = "${e.label}\n$sx × $sy"
        c.setOnClickListener { onPick(e.info) }
        preview(e, image)
    }

    fun show() {
        sheet.alpha = 0f
        sheet.translationY = dp.dp(60f)
        sheet.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(Easing.BASIC).start()
    }

    fun dismiss() {
        hideKeyboard()
        closed = true
        loader.shutdownNow()
        decoder.shutdownNow()
        sheet.animate().alpha(0f).translationY(dp.dp(60f)).setDuration(250).setInterpolator(Easing.SINE_IN_OUT_33)
            .withEndAction { (parent as? android.view.ViewGroup)?.removeView(this); onClose() }.start()
    }

    private companion object {
        const val PREVIEW_PAD_DP = 8f
        const val DECODERS = 3
        val MARKS = Regex("\\p{Mn}+")
    }
}
