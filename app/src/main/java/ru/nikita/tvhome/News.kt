package ru.nikita.tvhome

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Новость из ленты. related — сколько других изданий пишут о том же. */
class NewsItem(val title: String, val desc: String, val src: String, val time: Long, val link: String) {
    var sources = 1
}

/** Что показать: новость дня (если есть), пять новостей по кругу и срочная. */
class NewsPick(val day: NewsItem?, val list: List<NewsItem>, val breaking: NewsItem?)

/**
 * Новости BBC Русской службы, DW и Meduza. Из России ленты открываются только через VPN,
 * поэтому последний ответ хранится и показывается без сети.
 */
class News(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("news", Context.MODE_PRIVATE)

    fun cached(): List<NewsItem> = runCatching { fromJson(prefs.getString("items", null) ?: "[]") }.getOrDefault(emptyList())
    fun fetchedAt() = prefs.getLong("at", 0L)

    fun fetch(done: (List<NewsItem>) -> Unit) = thread {
        val all = FEEDS.flatMap { (src, url) -> runCatching { load(src, url) }.getOrDefault(emptyList()) }
        if (all.isEmpty()) return@thread
        prefs.edit().putString("items", toJson(all)).putLong("at", System.currentTimeMillis()).apply()
        done(all)
    }

    private fun load(src: String, url: String): List<NewsItem> {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 10000
        con.inputStream.use { input ->
            val xp = Xml.newPullParser()
            xp.setInput(input, null)
            val out = mutableListOf<NewsItem>()
            var inItem = false; var tag = ""
            var title = ""; var desc = ""; var date = ""; var link = ""
            while (xp.next() != XmlPullParser.END_DOCUMENT && out.size < 25) {
                when (xp.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = xp.name
                        if (tag == "item") { inItem = true; title = ""; desc = ""; date = ""; link = "" }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) when (tag) {
                        "title" -> title += xp.text; "description" -> desc += xp.text
                        "pubDate" -> date += xp.text; "link" -> link += xp.text
                    }
                    XmlPullParser.END_TAG -> {
                        if (xp.name == "item") {
                            inItem = false
                            val t = parseDate(date.trim())
                            if (t > 0 && title.isNotBlank()) out += NewsItem(clean(title), clean(desc), src, t, link.trim())
                        }
                        tag = ""
                    }
                }
            }
            return out
        }
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun parseDate(s: String): Long {
        for (f in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss z")) {
            runCatching { return SimpleDateFormat(f, Locale.US).parse(s)!!.time }
        }
        return 0L
    }

    /** Новость дня, пять для карточки и срочная (не чаще трёх в час, живёт 30 минут). */
    fun pick(all: List<NewsItem>): NewsPick {
        val now = System.currentTimeMillis()
        val day24 = all.filter { now - it.time < 86_400_000 }.sortedByDescending { it.time }
        val stems = day24.associateWith { stemsOf(it.title) }
        // слишком частые слова (страны, «заявил») не склеивают разные события
        val freq = HashMap<String, Int>()
        stems.values.forEach { set -> set.forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        val common = freq.filterValues { it > maxOf(3, day24.size / 5) }.keys
        fun related(a: NewsItem, b: NewsItem) = (stems[a]!! - common).count { it in stems[b]!! } >= 3
        val groups = day24.associateWith { a -> day24.filter { b -> b !== a && b.src != a.src && related(a, b) } }
        day24.forEach { it.sources = 1 + groups[it]!!.map { r -> r.src }.toSet().size }

        val day = day24.filter { it.sources >= 2 }
            .maxWithOrNull(compareBy<NewsItem> { it.sources }.thenBy { groups[it]!!.size }.thenBy { it.time })
        val list = mutableListOf<NewsItem>()
        day?.let { list += it }
        val perSrc = HashMap<String, Int>()
        for (n in day24) {
            if (list.size == 5) break
            if (n === day || (perSrc[n.src] ?: 0) >= 2 || list.any { related(it, n) }) continue
            list += n; perSrc[n.src] = (perSrc[n.src] ?: 0) + 1
        }

        val seen = prefs.getStringSet("brkSeen", emptySet())!!
        val shown = prefs.getString("brkTimes", "")!!.split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 3_600_000 }
        val urgent = Regex("(?i)срочно|молния|breaking")
        val breaking = day24.firstOrNull { n ->
            now - n.time < 30 * 60_000 && n.link !in seen &&
                (urgent.containsMatchIn(n.title) || (n.sources >= 3 && groups[n]!!.all { now - it.time < 30 * 60_000 }))
        }?.takeIf { shown.size < 3 || it.link == prefs.getString("brkNow", "") }
        if (breaking != null && breaking.link != prefs.getString("brkNow", "")) {
            prefs.edit().putString("brkNow", breaking.link).putString("brkTimes", (shown + now).joinToString(",")).apply()
        }
        return NewsPick(day, list, breaking)
    }

    /** Срочная прочитана — больше не держим её. */
    fun ack(n: NewsItem) = prefs.edit().putStringSet("brkSeen", (prefs.getStringSet("brkSeen", emptySet())!! + n.link).toList().takeLast(50).toSet()).apply()

    private fun stemsOf(t: String) = Regex("[а-яёa-z]{5,}").findAll(t.lowercase()).map { it.value.take(5) }.toSet()

    private fun toJson(l: List<NewsItem>) = JSONArray().apply {
        l.forEach { put(JSONObject().put("t", it.title).put("d", it.desc).put("s", it.src).put("w", it.time).put("l", it.link)) }
    }.toString()

    private fun fromJson(s: String) = JSONArray(s).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).run { NewsItem(getString("t"), getString("d"), getString("s"), getLong("w"), getString("l")) } }
    }

    companion object {
        val FEEDS = listOf(
            "BBC" to "https://feeds.bbci.co.uk/russian/rss.xml",
            "DW" to "https://rss.dw.com/xml/rss-ru-all",
            "Meduza" to "https://meduza.io/rss/all",
        )

        fun ago(t: Long): String {
            val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
            return if (m < 1) "только что" else if (m < 60) "$m мин назад" else SimpleDateFormat("HH:mm", Locale("ru")).format(t)
        }

        // Настройки: смена (0 по кругу, 1 один круг, 2 только новость дня), срочные (0 поверх, 1 на главном, 2 выкл), скрыть
        fun mode(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("newsMode", 0)
        fun urgentMode(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getInt("newsUrgent", 0)
        fun hidden(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("newsHidden", false)
        fun set(ctx: Context, key: String, v: Int) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt(key, v).apply()
        fun setHidden(ctx: Context, v: Boolean) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("newsHidden", v).apply()

        fun night() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 23 || it < 8 }
    }
}

/** Плашка срочной новости поверх фильма: сверху по центру, не забирает пульт, 12 секунд. */
object BreakingBanner {
    private var view: LinearLayout? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hideRun = Runnable { hide() }

    fun show(ctx: Context, n: NewsItem) = ui.post {
        hide()
        val app = ctx.applicationContext
        val S = app.resources.displayMetrics.widthPixels / 1920f
        fun p(v: Int) = (v * S).roundToInt()
        fun tv(size: Int, color: Int, face: android.graphics.Typeface) = TextView(app).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, p(size).toFloat()); setTextColor(color); typeface = face; includeFontPadding = false
        }
        if (Art.inter400 === android.graphics.Typeface.DEFAULT) Art.loadFonts(app)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p(30), p(24), p(30), p(22))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 18, 24, 31)); cornerRadius = p(26).toFloat(); setStroke(maxOf(1, p(2)), Color.argb(190, 235, 87, 87))
            }
        }
        val top = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Brk.label(app, p(17), p(12), p(6)))
        top.addView(tv(20, Color.rgb(240, 164, 164), Art.inter500).apply { text = "${n.src} · ${News.ago(n.time)}" },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(16) })
        root.addView(top)
        root.addView(tv(32, Color.rgb(238, 242, 246), Art.inter600).apply { text = n.title; maxLines = 3 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = p(12) })
        root.addView(tv(20, Color.rgb(154, 166, 178), Art.inter400).apply { text = "Подробнее — кнопкой «Домой». Исчезнет через 12 секунд" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(12) })
        val lp = WindowManager.LayoutParams(
            p(1120), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = p(48); windowAnimations = android.R.style.Animation_Toast }
        try { app.getSystemService(WindowManager::class.java).addView(root, lp); view = root } catch (_: Exception) {}
        ui.postDelayed(hideRun, 12_000)
    }

    fun hide() {
        ui.removeCallbacks(hideRun)
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }
}

/** Красная метка «СРОЧНО». */
object Brk {
    fun label(ctx: Context, size: Int, padH: Int, padV: Int) = TextView(ctx).apply {
        text = "СРОЧНО"; setTextColor(Color.WHITE); typeface = Art.inter600; includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat()); letterSpacing = .08f
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply { setColor(Color.rgb(235, 87, 87)); cornerRadius = 999f }
    }
}
