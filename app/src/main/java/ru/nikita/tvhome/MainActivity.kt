package ru.nikita.tvhome

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // Вёрстка в координатах макета 1920×1080, пересчитанных под реальный экран
    private val S by lazy { resources.displayMetrics.widthPixels / 1920f }
    private fun p(v: Int) = (v * S).roundToInt()

    private val INK = Color.rgb(238, 242, 246)
    private val INK_SOFT = Color.rgb(154, 166, 178)
    private val ON_FOCUS = Color.rgb(15, 20, 26)
    private val EDGE = Color.argb(36, 236, 242, 248)
    private val PANEL = Color.argb(240, 18, 24, 31)
    private val WARN = Color.rgb(240, 164, 164)
    private val OK = Color.rgb(95, 211, 138)
    // Кнопки сверху в покое — почти прозрачные, чтобы не спорить с полкой; в фокусе — белые
    private val GLYPH = Color.argb(224, 238, 242, 246)
    private val CHIP_REST = .62f // кнопки сверху в покое

    // Сервисы, которые обычно не открываются через VPN
    private val vpnBad = setOf(
        "ru.kinopoisk.tv", "ru.more.play", "ru.mts.mtstv", "ru.amediateka", "com.amdteka", "ru.ivi.client",
        "gpm.tnt_premier", "ru.start.androidmobile", "ru.rt.video.app.tv",
    )
    private val VPN_APP = "com.v2raytun.android"

    private lateinit var store: AppStore
    private lateinit var weather: Weather
    private lateinit var news: News
    private var pick: NewsPick? = null
    private var newsIdx = 0
    private var newsRounds = 0
    private var bannerFor = ""
    private val ui = Handler(Looper.getMainLooper())

    private val rows = listOf(mutableListOf<App>(), mutableListOf<App>())
    private val rowW = intArrayOf(300, 220)
    private val rowH = intArrayOf(180, 132)
    private val visible = intArrayOf(4, 6)
    private val offset = intArrayOf(0, 0)
    private val lastCol = intArrayOf(1, 0)
    private var focusRow = 0
    private var focusCol = 1
    private var moving: App? = null
    private var vpnOn = false

    private lateinit var root: FrameLayout
    private lateinit var shelfA: ImageView
    private lateinit var shelfB: ImageView
    private var shelfKey = ""
    private lateinit var kindTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var facts: LinearLayout
    private lateinit var shelfText: LinearLayout
    private lateinit var wx: LinearLayout
    private lateinit var newsCard: LinearLayout
    private lateinit var newsHead: LinearLayout
    private lateinit var newsBody: FrameLayout
    private lateinit var rowsBox: FrameLayout
    private val scrolls = arrayOfNulls<HorizontalScrollView>(2)
    private val tracks = arrayOfNulls<LinearLayout>(2)
    private lateinit var topbar: LinearLayout
    private lateinit var vpnChip: LinearLayout
    private lateinit var capsule: LinearLayout
    private lateinit var vpnIcon: IconView
    private lateinit var tgSep: View
    private lateinit var clock: TextView
    private lateinit var day: TextView
    private lateinit var tip: TextView
    private lateinit var timerText: TextView
    private lateinit var gearChip: View
    private lateinit var tgChip: LinearLayout
    private lateinit var tgText: TextView
    private val tgBadges = mutableListOf<TextView>()
    private val onChats: () -> Unit = { showChats() }
    private var overlay: FrameLayout? = null
    private var toastView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Art.loadFonts(this)
        store = AppStore(this)
        weather = Weather(this)
        news = News(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 17, 23)); clipChildren = false }
        setContentView(root)
        buildShelf(); buildTopbar(); buildWeather(); buildNews(); buildRows()
        tip = text(22, Art.inter500, INK_SOFT).apply { text = "Удерживайте OK, чтобы переместить или скрыть приложение" }
        root.addView(tip, lp(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = p(96); bottomMargin = p(44) })
        if (getPreferences(MODE_PRIVATE).getBoolean("tipDone", false)) tip.visibility = View.GONE
        reloadApps()
        Chats.listen(onChats)
        watchVpn()
        tickClock()
        weather.cached()?.let { showWeather(it) }
        refreshWeather()
        showNews(news.cached())
        refreshNews()
        checkUpdate()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver)
        Chats.unlisten(onChats)
        ui.removeCallbacksAndMessages(null)
    }

    override fun onPause() { super.onPause(); Home.visible = false; ui.removeCallbacks(vpnPoll); ui.removeCallbacks(newsTick) }

    override fun onResume() {
        super.onResume()
        Home.visible = true
        ui.removeCallbacks(vpnPoll); ui.post(vpnPoll)
        Sleep.restore(this)
        BreakingBanner.hide()
        // таймер новостей мог остановиться, если система выгружала лаунчер, — догоняем при возврате
        if (System.currentTimeMillis() - news.fetchedAt() > 10 * 60_000) news.fetch { all -> ui.post { showNews(all) } }
        // приложения перечитываем при установке и удалении (pkgReceiver), а время использования — не чаще раза в 10 минут
        if (SystemClock.elapsedRealtime() - loadedAt > 10 * 60_000) reloadApps(keep = true)
        scheduleNews()
    }

    // Кнопка «Домой», когда лаунчер уже открыт: закрыть всё и встать в начало
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // проверка выключения экрана с компьютера: am start ... --ez sleep_test true
        if (intent?.getBooleanExtra("sleep_test", false) == true) { Sleep.sleepNow(this); return }
        // проверка стопки уведомлений с компьютера: две последние переписки
        if (intent?.getBooleanExtra("banner_test", false) == true) {
            Chats.sorted().take(2).reversed().forEach { Banner.show(this, it) }; return
        }
        // проверка экрана ответа с компьютера: готовый текст вместо микрофона, без отправки
        intent?.getStringExtra("reply_test")?.let { fake ->
            Chats.sorted().firstOrNull()?.let { c ->
                startActivity(Intent(this, ReplyActivity::class.java).putExtra("chat", c.name).putExtra("fake", fake)); return
            }
        }
        closeOverlay(); endMove(save = true)
        focusTile(0, 0)
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { ui.post { reloadApps(keep = true) } }
    }

    // ---------- Общие помощники вёрстки ----------

    private fun lp(w: Int, h: Int, g: Int = Gravity.TOP or Gravity.START) =
        FrameLayout.LayoutParams(if (w > 0) p(w) else w, if (h > 0) p(h) else h, g)

    private fun text(size: Int, face: Typeface, color: Int = INK) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, p(size).toFloat())
        typeface = face; setTextColor(color); includeFontPadding = false
        isSingleLine = true
    }

    private fun pill(fill: Int, stroke: Int = EDGE, radius: Int = 999) = GradientDrawable().apply {
        setColor(fill); cornerRadius = p(radius).toFloat(); setStroke(maxOf(1, p(1)), stroke)
    }

    private fun rounded(v: View, r: Int) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, p(r).toFloat())
        }
        v.clipToOutline = true
    }

    // ---------- Верхняя полка ----------

    private fun buildShelf() {
        shelfA = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        shelfB = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        root.addView(shelfA, lp(-1, 640)); root.addView(shelfB, lp(-1, 640))
        // затемнение к низу и влево, чтобы текст и ряд читались
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(184, 12, 17, 23), Color.rgb(12, 17, 23)))
        }, lp(-1, 640))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(190, 12, 17, 23), Color.TRANSPARENT))
        }, lp(1060, 640))

        shelfText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kindTv = text(26, Art.inter500, INK_SOFT)
        titleTv = text(96, Art.inter600).apply { letterSpacing = -0.03f }
        facts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shelfText.addView(kindTv)
        shelfText.addView(titleTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(14) })
        shelfText.addView(facts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(28) })
        root.addView(shelfText, lp(1000, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = p(96); bottomMargin = p(580) })
    }

    private fun showShelf(a: App) {
        if (shelfKey != a.pkg) {
            shelfKey = a.pkg
            val bmp = Art.shelf(a.pkg, a.color)
            val (front, back) = if (shelfA.alpha > .5f) shelfA to shelfB else shelfB to shelfA
            back.setImageBitmap(bmp)
            back.animate().alpha(1f).setDuration(500).start()
            front.animate().alpha(0f).setDuration(500).start()
        }
        kindTv.text = a.kind
        titleTv.text = a.label
        facts.removeAllViews()
        val list = AppStore.facts(a).map { it to false }.toMutableList()
        // с предупреждением — одна подпись рядом, иначе ряд подписей заезжает под карточку новостей
        if (vpnOn && hasVpnApp() && a.pkg in vpnBad) { list.add(0, "Через VPN может не открыться" to true); while (list.size > 2) list.removeAt(list.size - 1) }
        for ((f, warn) in list) {
            val chip = text(22, Art.inter500, if (warn) WARN else INK).apply {
                this.text = f
                setPadding(p(18), p(10), p(18), p(10))
                background = pill(Color.argb(20, 236, 242, 248), if (warn) Color.argb(90, 240, 164, 164) else EDGE)
            }
            facts.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = p(14) })
        }
    }

    // ---------- Панель сверху ----------

    private fun buildTopbar() {
        // кнопки без подложки: в покое полупрозрачные значки, в фокусе — белый круг
        topbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false
            clipToPadding = false // увеличенный в фокусе круг не обрезается отступом
            setPadding(p(6), p(6), p(6), p(6))
        }
        val search = iconChip("search") { openSearch() }
        // VPN — щит: зелёный — включён; нажатие переключает, удержание открывает v2rayTun
        vpnIcon = IconView(this, "shield")
        vpnChip = iconChip(null) { toggleVpn() }.apply {
            isLongClickable = true
            setOnLongClickListener { openApp(VPN_APP); true }
            addView(vpnIcon, LinearLayout.LayoutParams(p(32), p(32)))
        }
        if (!hasVpnApp()) vpnChip.visibility = View.GONE // у кого нет v2rayTun — кнопки нет
        val inputChip = iconChip("screen") { openInputs() }
        timerText = text(25, Art.inter500).apply { visibility = View.GONE }
        val timerChip = chip { cycleSleep() }.apply {
            isLongClickable = true
            setOnLongClickListener { pickSleep(); true }
            addView(IconView(this@MainActivity, "timer"), LinearLayout.LayoutParams(p(32), p(32)))
            addView(timerText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(12) })
            minimumWidth = p(68); setPadding(p(18), 0, p(18), 0); gravity = Gravity.CENTER
        }
        val gear = iconChip("gear") { openSettings() }
        gearChip = gear
        tgText = text(25, Art.inter500)
        tgChip = chip { openMessages() }.apply {
            addView(IconView(this@MainActivity, "plane"), LinearLayout.LayoutParams(p(30), p(30)).apply { rightMargin = p(10) })
            addView(tgText); visibility = View.GONE
            setPadding(p(18), 0, p(22), 0)
        }
        tgSep = View(this).apply { setBackgroundColor(EDGE); visibility = View.GONE }
        capsule.addView(tgChip, LinearLayout.LayoutParams(-2, p(68)))
        capsule.addView(tgSep, LinearLayout.LayoutParams(maxOf(1, p(1)), p(34)).apply { leftMargin = p(8); rightMargin = p(8) })
        for (v in listOf(search, vpnChip, inputChip, timerChip, gear)) capsule.addView(v, LinearLayout.LayoutParams(-2, p(68)).apply { leftMargin = p(2); rightMargin = p(2) })
        topbar.addView(capsule)
        root.addView(topbar, lp(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = p(38); rightMargin = p(96) })
        // часы — слева, на одной линии с полкой и рядами; чуть тише названия приложения
        day = text(22, Art.inter500, INK_SOFT)
        clock = text(40, Art.inter600).apply { letterSpacing = -0.02f }
        val whenBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        whenBox.addView(clock)
        whenBox.addView(day, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(16); bottomMargin = p(4) })
        root.addView(whenBox, lp(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = p(54); leftMargin = p(96) })
        paintVpn()
    }

    private fun chip(onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(p(26), 0, p(26), 0)
        alpha = CHIP_REST
        isFocusable = true; isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> styleChip(v as LinearLayout, has) }
    }

    private fun iconChip(kind: String?, onClick: () -> Unit) = chip(onClick).apply {
        setPadding(0, 0, 0, 0); gravity = Gravity.CENTER
        minimumWidth = p(68)
        kind?.let { addView(IconView(this@MainActivity, it), LinearLayout.LayoutParams(p(32), p(32))) }
    }

    private fun styleChip(v: LinearLayout, focused: Boolean) {
        v.background = if (focused) pill(INK, INK) else null
        for (i in 0 until v.childCount) when (val ch = v.getChildAt(i)) {
            is TextView -> ch.setTextColor(if (focused) ON_FOCUS else INK)
            is IconView -> ch.color = if (focused) ON_FOCUS else INK
        }
        if (v === vpnChip) paintVpn()
        v.alpha = if (focused) 1f else CHIP_REST
        v.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f).setDuration(180).start()
        if (focused) { liftRows(false) }
    }

    /** Щит VPN: включён — зелёный (на белой кнопке темнее), выключен — как остальные значки. */
    private fun paintVpn() {
        val f = vpnChip.isFocused
        vpnIcon.color = when {
            vpnOn && f -> Color.rgb(31, 138, 76)
            vpnOn -> OK
            f -> ON_FOCUS
            else -> INK
        }
    }

    private fun tickClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale("ru")).format(now)
        day.text = SimpleDateFormat("EE, d MMMM", Locale("ru")).format(now).replace(".", "")
        showSleep()
        ui.postDelayed({ tickClock() }, 10_000)
    }

    /** VPN включён, если поднят туннель tun* — так видно сразу, без задержек системы. */
    private fun tunnelUp(): Boolean {
        val names = runCatching { java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.map { it.name } }
            .getOrElse { listOf("ошибка: ${it.message}") }
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val vpnNet = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val on = names.any { it.startsWith("tun") } || vpnNet
        if (on != vpnOn) android.util.Log.d("tvhome", "vpn: интерфейсы=$names сеть_vpn=$vpnNet итог=$on")
        return on
    }

    // Оповещения о VPN приходят не всегда (если VPN не для всех приложений) — пока лаунчер виден, проверяем сами
    private val vpnPoll = object : Runnable { override fun run() { refreshVpn(); ui.postDelayed(this, 3000) } }

    private fun refreshVpn() {
        val on = tunnelUp()
        if (on == vpnOn) return
        vpnOn = on; paintVpn()
        currentApp()?.let { showShelf(it) }
    }

    // VPN может действовать не на все приложения (раздельный туннель), поэтому смотрим все сети, а не только свою
    private fun watchVpn() {
        val cm = getSystemService(ConnectivityManager::class.java)
        fun update(@Suppress("UNUSED_PARAMETER") gone: Network? = null) {
            val on = tunnelUp()
            ui.post {
                if (on == vpnOn) return@post
                vpnOn = on
                paintVpn()
                currentApp()?.let { showShelf(it) }
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { update(); ui.postDelayed({ update() }, 1000) }
            // пропавшая сеть ещё секунду видна в списке — не считаем её и перепроверяем
            override fun onLost(n: Network) { update(gone = n); ui.postDelayed({ update() }, 1000) }
        }
        cm.registerNetworkCallback(android.net.NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), cb)
        update()
    }

    // ---------- Погода ----------

    private lateinit var wxIcon: IconView
    private lateinit var wxTemp: TextView
    private lateinit var wxLine1: TextView
    private lateinit var wxLine2: TextView
    private lateinit var wxHours: LinearLayout

    private fun buildWeather() {
        wx = LinearLayout(this).apply {
            isFocusable = true; isClickable = true
            setOnClickListener { openWeather() }
            setOnFocusChangeListener { v, has -> focusWidget(v, has, pill(Color.argb(107, 12, 17, 23), EDGE, 28)) }
            orientation = LinearLayout.VERTICAL
            setPadding(p(32), p(28), p(32), p(28))
            background = pill(Color.argb(107, 12, 17, 23), EDGE, 28)
        }
        val now = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wxIcon = IconView(this, "cloud")
        wxTemp = text(72, Art.inter600).apply { letterSpacing = -0.04f; text = "—" }
        val desc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wxCity = text(24, Art.inter600).apply { text = Weather.city(this@MainActivity) }
        desc.addView(wxCity)
        // карточка узкая (460): описание переносится, а не обрезается
        wxLine1 = text(21, Art.inter400, INK_SOFT).apply { isSingleLine = false; maxLines = 2 }
        wxLine2 = text(21, Art.inter400, INK_SOFT).apply { isSingleLine = false; maxLines = 2 }
        desc.addView(wxLine1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(6) })
        desc.addView(wxLine2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(4) })
        now.addView(wxIcon, LinearLayout.LayoutParams(p(64), p(64)).apply { rightMargin = p(20) })
        now.addView(wxTemp, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = p(20) })
        now.addView(desc)
        wxHours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wx.addView(now)
        wx.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        wx.addView(View(this).apply { setBackgroundColor(EDGE) }, LinearLayout.LayoutParams(-1, maxOf(1, p(1))).apply { topMargin = p(24) })
        wx.addView(wxHours, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p(18) })
        root.addView(wx, lp(460, 300, Gravity.BOTTOM or Gravity.END).apply { rightMargin = p(96); bottomMargin = p(580) })
    }

    private fun showWeather(w: Weather.Now) {
        wxTemp.text = Weather.deg(w.temp)
        wxLine1.text = "${Weather.text(w.code)}, ощущается ${Weather.deg(w.feels)}"
        wxLine2.text = "Днём ${Weather.deg(w.max)}, ночью ${Weather.deg(w.min)}"
        val parent = wxIcon.parent as ViewGroup
        val idx = parent.indexOfChild(wxIcon); val params = wxIcon.layoutParams
        parent.removeView(wxIcon)
        wxIcon = IconView(this, Weather.icon(w.code)); parent.addView(wxIcon, idx, params)
        wxHours.removeAllViews()
        lastWx = w
        for ((t, deg) in w.hours.take(5)) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(text(18, Art.inter400, INK_SOFT).apply { text = t })
            col.addView(text(24, Art.inter600).apply { text = Weather.deg(deg) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(6) })
            wxHours.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun refreshWeather() {
        weather.fetch { w -> ui.post { showWeather(w) } }
        ui.postDelayed({ refreshWeather() }, 30 * 60_000L)
    }

    // ---------- Ряды приложений ----------

    /** Свой ряд: без автопрокрутки, прокручиваем сами, как в макете. */
    private inner class Row : HorizontalScrollView(this@MainActivity) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?) = 0
    }

    private fun buildRows() {
        rowsBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(rowsBox, lp(-1, -1).apply { topMargin = p(560) - p(40) })
        val tops = intArrayOf(0, 330)
        for (ri in 0..1) {
            val sc = Row().apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = false; clipToPadding = false; isFocusable = false
            }
            val tr = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
                setPadding(p(96), p(40), p(96), p(20))
            }
            sc.addView(tr)
            rowsBox.addView(sc, lp(-1, rowH[ri] + 130).apply { topMargin = p(tops[ri]) })
            scrolls[ri] = sc; tracks[ri] = tr
        }
        rowsBox.addView(text(24, Art.inter500, INK_SOFT).apply { text = "Ещё" },
            lp(-2, -2).apply { leftMargin = p(96); topMargin = p(330 + 40 - 46) })
    }

    private var loadedAt = 0L

    private fun reloadApps(keep: Boolean = false) {
        loadedAt = SystemClock.elapsedRealtime()
        val focusedPkg = if (keep) currentApp()?.pkg else null
        val all = store.load()
        val hidden = store.hidden
        val byPkg = all.associateBy { it.pkg }
        var mainOrder = store.main
        // новые приложения из списка по умолчанию не лезут в главный ряд сами; просто убираем исчезнувшие
        mainOrder = mainOrder.filter { it in byPkg }.toMutableList()
        val next0 = mainOrder.filter { it !in hidden }.mapNotNull { byPkg[it] }
        val next1 = all.filter { it.pkg !in mainOrder && it.pkg !in hidden }
            .sortedWith(compareByDescending<App> { it.minutesMonth }.thenBy { it.label })
        if (keep && next0.map { it.pkg } == rows[0].map { it.pkg } && next1.map { it.pkg } == rows[1].map { it.pkg }) {
            // ряды те же — плитки не пересоздаём, только освежаем время использования для полки
            for (r in rows) for (a in r) byPkg[a.pkg]?.let { a.minutesMonth = it.minutesMonth; a.lastUsed = it.lastUsed }
            currentApp()?.let { showShelf(it) }
            return
        }
        rows[0].clear(); rows[0].addAll(next0)
        rows[1].clear(); rows[1].addAll(next1)
        renderRows()
        val target = focusedPkg?.let { pkg -> (0..1).firstNotNullOfOrNull { r -> rows[r].indexOfFirst { it.pkg == pkg }.takeIf { it >= 0 }?.let { r to it } } }
        if (target != null) focusTile(target.first, target.second)
        else if (currentFocus == null || !keep) focusTile(0, minOf(1, rows[0].size - 1).coerceAtLeast(0))
    }

    private fun renderRows() {
        tgBadges.clear()
        for (ri in 0..1) {
            val tr = tracks[ri]!!
            tr.removeAllViews()
            rows[ri].forEachIndexed { ci, a -> tr.addView(tile(a, ri, ci)) }
        }
        showChats()
    }

    private fun tile(a: App, ri: Int, ci: Int): View {
        val w = rowW[ri]; val h = rowH[ri]; val radius = if (ri == 0) 26 else 22
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            isFocusable = true; isClickable = true; isLongClickable = true
            tag = intArrayOf(ri, ci)
        }
        val card = FrameLayout(this).apply { elevation = p(8).toFloat() }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (a.banner != null) setImageDrawable(a.banner) else background = IconTile(a.icon, a.color)
        }
        card.addView(img, FrameLayout.LayoutParams(-1, -1))
        rounded(card, radius)
        if (a.pkg in Chats.TG) {
            val badge = text(24, Art.inter600, ON_FOCUS).apply {
                gravity = Gravity.CENTER; setPadding(p(12), 0, p(12), 0); minWidth = p(44)
                background = pill(INK, INK); visibility = View.GONE
            }
            card.addView(badge, FrameLayout.LayoutParams(-2, p(44), Gravity.TOP or Gravity.END).apply { topMargin = p(10); rightMargin = p(10) })
            tgBadges += badge
        }
        val name = text(if (ri == 0) 26 else 22, Art.inter500).apply { text = a.label; alpha = 0f }
        box.addView(card, LinearLayout.LayoutParams(p(w), p(h)))
        box.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(30) })
        box.setOnFocusChangeListener { _, has ->
            val k = if (moving === a) 1.18f else if (has) 1.14f else 1f
            card.animate().scaleX(k).scaleY(k).translationY(if (has) -p(6).toFloat() else 0f)
                .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            card.elevation = p(if (has) 24 else 8).toFloat()
            name.animate().alpha(if (has && moving == null) 1f else 0f).setDuration(200).start()
            if (has) onTileFocus(ri, ci)
        }
        box.setOnClickListener { if (moving != null) endMove(save = true) else launch(a) }
        box.setOnLongClickListener { openTileMenu(a, ri); true }
        if (moving === a) card.foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = p(radius).toFloat(); setStroke(p(4), INK)
        }
        return box.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = p(40) } }
    }

    private fun onTileFocus(ri: Int, ci: Int) {
        focusRow = ri; focusCol = ci
        val step = rowW[ri] + 40
        if (ci - offset[ri] > visible[ri]) offset[ri] = ci - visible[ri]
        if (ci < offset[ri]) offset[ri] = ci
        scrolls[ri]!!.smoothScrollTo(p(offset[ri] * step), 0)
        liftRows(ri == 1)
        rows[ri].getOrNull(ci)?.let { showShelf(it) }
    }

    private fun liftRows(low: Boolean) {
        rowsBox.animate().translationY(if (low) -p(250).toFloat() else 0f).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
        shelfText.animate().alpha(if (low) 0f else 1f).setDuration(250).start()
        wx.animate().alpha(if (low) 0f else 1f).setDuration(250).start()
        if (::newsCard.isInitialized) newsCard.animate().alpha(if (low) 0f else 1f).setDuration(250).start()
    }

    private fun currentApp(): App? = rows.getOrNull(focusRow)?.getOrNull(focusCol)

    private fun focusTile(ri: Int, ci: Int) {
        val tr = tracks[ri] ?: return
        val r = if (rows[ri].isEmpty()) 1 - ri else ri
        val t = tracks[r]!!
        if (t.childCount == 0) return
        t.getChildAt(ci.coerceIn(0, t.childCount - 1)).requestFocus()
        if (tr !== t) return
    }

    private fun bump(dir: Int) {
        val v = tracks[focusRow]?.getChildAt(focusCol) ?: return
        v.animate().translationX(p(12 * dir).toFloat()).setDuration(90).withEndAction {
            v.animate().translationX(0f).setDuration(160).start()
        }.start()
    }

    // ---------- Пульт ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (overlay != null) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { closeOverlay(); return true }
            // по строкам панели ходим сами: строки новостей меняют высоту, и обычный поиск фокуса уводит его под панель
            val cur = currentFocus
            val i = panelRows.indexOf(cur)
            if (i >= 0) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { panelRows.getOrNull(i + 1)?.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { panelRows.getOrNull(i - 1)?.requestFocus() ?: return super.dispatchKeyEvent(e); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
            if (cur == null || !inside(cur, overlay!!)) { panelRows.firstOrNull()?.requestFocus(); return true }
            return super.dispatchKeyEvent(e)
        }
        val f = currentFocus
        val pos = f?.tag as? IntArray
        val m = moving
        if (m != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveBy(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveBy(1); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { endMove(save = true); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            offset[0] = 0; offset[1] = 0
            scrolls.forEach { it?.smoothScrollTo(0, 0) }
            focusTile(0, 0); return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_MENU && pos != null) { currentApp()?.let { openTileMenu(it, pos[0]) }; return true }
        if (pos != null) {
            val (ri, ci) = pos[0] to pos[1]
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ci < rows[ri].size - 1) focusTile(ri, ci + 1) else bump(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ci > 0) focusTile(ri, ci - 1) else bump(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ri == 0 && rows[1].isNotEmpty()) { lastCol[0] = ci; focusTile(1, minOf(lastCol[1], rows[1].size - 1)) }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    lastCol[ri] = ci
                    if (ri == 1 && rows[0].isNotEmpty()) focusTile(0, minOf(lastCol[0], rows[0].size - 1))
                    else (nearest(widgets(), centerX(f)) ?: gearChip).requestFocus()
                    return true
                }
            }
        } else if (f === newsCard || f === wx) {
            // виджеты — свой ряд между плитками и кнопками
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusTile(0, minOf(lastCol[0], rows[0].size - 1)); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { (nearest(chips(), centerX(f)) ?: gearChip).requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (f === wx && newsCard.visibility == View.VISIBLE) newsCard.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (f === newsCard) wx.requestFocus(); return true }
            }
        } else if (f != null && f.parent === capsule &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && chips().firstOrNull() === f || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && chips().lastOrNull() === f)) {
            return true // края капсулы: фокус не уходит на плитки
        } else if (f != null && f.parent === capsule && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            (nearest(widgets(), centerX(f)) ?: return super.dispatchKeyEvent(e)).requestFocus(); return true
        } else if (f != null && f.parent === capsule && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusTile(0, minOf(lastCol[0], rows[0].size - 1)); return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- Запуск ----------

    private fun launch(a: App) {
        if (vpnOn && a.pkg in vpnBad) {
            showPanel("${a.label} может не открыться", "Этот сервис обычно не работает через VPN", listOf(
                Item("Выключить VPN и открыть") { toggleVpn { start(a.intent) } },
                Item("Открыть через VPN") { start(a.intent) },
                Item("Отмена", quiet = true) {},
            ), side = false, warn = true)
            return
        }
        start(a.intent)
    }

    /** Переключить VPN командой виджета v2rayTun — без открытия приложения. */
    private fun toggleVpn(then: (() -> Unit)? = null) {
        val i = Intent("com.v2raytun.android.action.widget.click")
            .setClassName(VPN_APP, "$VPN_APP.receiver.WidgetProvider1x1")
        val wasOn = tunnelUp()
        try { sendBroadcast(i) } catch (e: Exception) { toast("Не удалось переключить VPN"); return }
        toast(if (wasOn) "VPN выключается" else "VPN включается")
        ui.postDelayed({ refreshVpn() }, 1500)
        ui.postDelayed({ refreshVpn(); toast(if (tunnelUp()) "VPN включён" else "VPN выключен") }, 4000)
        then?.let { ui.postDelayed(it, 3000) }
    }

    private fun openApp(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) toast("Приложение не найдено") else start(i)
    }

    private fun start(i: Intent) {
        try { startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Не удалось открыть") }
    }

    private fun openSearch() {
        for (i in listOf(Intent("android.search.action.GLOBAL_SEARCH"), Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_SEARCH))) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        toast("Поиск недоступен на этом телевизоре")
    }

    // ---------- Меню приложения и перемещение ----------

    private fun openTileMenu(a: App, ri: Int) {
        if (tip.visibility == View.VISIBLE) { tip.visibility = View.GONE; getPreferences(MODE_PRIVATE).edit().putBoolean("tipDone", true).apply() }
        val items = mutableListOf(Item("Открыть", glyph = "play") { launch(a) })
        if (ri == 0) {
            items += Item("Переместить", glyph = "move") { startMove(a) }
            items += Item("Убрать из главного ряда", glyph = "rowdown") { store.main = store.main.apply { remove(a.pkg) }; reloadApps(keep = true); toast("${a.label} теперь в ряду «Ещё»") }
        } else {
            items += Item("Добавить в главный ряд", glyph = "rowup") { store.main = store.main.apply { remove(a.pkg); add(a.pkg) }; reloadApps(keep = true); toast("${a.label} в главном ряду") }
        }
        items += Item("Скрыть", glyph = "eyeoff") { store.hidden = store.hidden.apply { add(a.pkg) }; reloadApps(keep = true); toast("${a.label} скрыт. Вернуть — в настройках, «Скрытые приложения»") }
        items += Item("О приложении", glyph = "info") {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
        }
        showMenu(a.label, null, items)
    }

    private fun startMove(a: App) {
        moving = a
        renderRows()
        focusTile(0, rows[0].indexOf(a))
        toast("Стрелками влево и вправо, OK — готово")
    }

    private fun moveBy(d: Int) {
        val a = moving ?: return
        val i = rows[0].indexOf(a); val to = i + d
        if (to < 0 || to >= rows[0].size) { bump(d); return }
        rows[0].removeAt(i); rows[0].add(to, a)
        renderRows(); focusTile(0, to)
    }

    private fun endMove(save: Boolean) {
        val a = moving ?: return
        moving = null
        if (save) {
            // в сохранённом порядке остаются и скрытые — ставим видимые в новом порядке
            val hidden = store.main.filter { p -> rows[0].none { it.pkg == p } }
            store.main = (rows[0].map { it.pkg } + hidden).toMutableList()
            toast("Порядок сохранён")
        }
        renderRows(); focusTile(0, rows[0].indexOf(a).coerceAtLeast(0))
    }

    // ---------- Панели: меню, настройки, входы ----------

    class Item(val label: String, val value: (() -> String)? = null, val head: Boolean = false, val keepOpen: Boolean = false,
               val icon: android.graphics.drawable.Drawable? = null, val glyph: String? = null, val quiet: Boolean = false,
               val sub: String? = null, val meta: String? = null, val badge: String? = null, val act: () -> Unit = {})

    @Suppress("FunctionName")
    private fun Item(label: String, act: () -> Unit): Item = MainActivity.Item(label, null, false, false, null, null, false, null, null, null, act)

    private fun showMenu(title: String, lead: String?, items: List<Item>) = showPanel(title, lead, items, side = false)

    private fun showPanel(
        title: String, lead: String?, items: List<Item>, side: Boolean,
        tabs: List<String>? = null, tab: Int = 0, onTab: ((Int) -> Unit)? = null, focusTab: Boolean = false,
        warn: Boolean = false, focusAt: Int = -1,
    ) {
        closeOverlay()
        val shot = frostShot()
        val ov = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(184, 5, 8, 12)); isClickable = true
            setOnClickListener { closeOverlay() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(if (side) 48 else 16), p(if (side) 64 else 16), p(if (side) 48 else 16), p(if (side) 64 else 16))
            isClickable = true
        }
        panel.background = if (side) null else Frost(shot, panel, p(26).toFloat())
        val titleTv = text(if (side) 38 else 28, Art.inter600).apply { text = title; isSingleLine = false }
        val titleLp = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(if (side) 0 else 18); topMargin = p(if (side) 0 else 12); bottomMargin = p(6) }
        if (warn) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(IconView(this, "warn").apply { color = WARN }, LinearLayout.LayoutParams(p(34), p(34)).apply { rightMargin = p(14) })
            row.addView(titleTv)
            panel.addView(row, titleLp)
        } else panel.addView(titleTv, titleLp)
        lead?.let {
            panel.addView(text(if (side) 22 else 20, Art.inter400, INK_SOFT).apply { text = it; isSingleLine = false },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(if (side) 0 else 18); bottomMargin = p(16) })
        }
        var first: View? = null
        var tabFocus: View? = null
        val rowViews = mutableListOf<View>()
        panelRows = rowViews
        if (tabs != null) {
            // вкладки: стрелки влево-вправо, OK открывает вкладку
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tabs.forEachIndexed { i, label ->
                val t = text(23, Art.inter500).apply {
                    text = label; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                    setPadding(p(22), 0, p(22), 0)
                }
                fun paint(f: Boolean) {
                    t.background = when {
                        f -> pill(INK, INK)
                        i == tab -> pill(Color.argb(40, 236, 242, 248), Color.argb(60, 236, 242, 248))
                        else -> null
                    }
                    t.setTextColor(if (f) ON_FOCUS else if (i == tab) INK else INK_SOFT)
                }
                paint(false)
                t.setOnFocusChangeListener { _, f -> paint(f) }
                t.setOnClickListener { onTab?.invoke(i) }
                bar.addView(t, LinearLayout.LayoutParams(-2, p(56)).apply { rightMargin = p(8) })
                if (i == tab) tabFocus = t
            }
            panel.addView(bar, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = p(18) })
        }
        for (it in items) {
            if (it.head) {
                panel.addView(text(20, Art.inter600, INK_SOFT).apply { text = it.label },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(24); topMargin = p(22); bottomMargin = p(8) })
                continue
            }
            if (it.meta != null) {
                val nr = newsRow(it)
                panel.addView(nr, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p(6) })
                rowViews += nr
                if (first == null) first = nr
                continue
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p(24), 0, p(24), 0); isFocusable = true; isClickable = true
            }
            it.icon?.let { d ->
                row.addView(ImageView(this).apply {
                    setImageDrawable(d); scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
                    outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
                }, LinearLayout.LayoutParams(p(48), p(48)).apply { rightMargin = p(18) })
            }
            val glyph = it.glyph?.let { k -> IconView(this, k).apply { color = GLYPH } }
            glyph?.let { g -> row.addView(g, LinearLayout.LayoutParams(p(30), p(30)).apply { rightMargin = p(18) }) }
            val rest = if (it.quiet) INK_SOFT else INK
            val l = text(25, Art.inter500, rest).apply { text = it.label }
            val v = text(23, Art.inter400, INK_SOFT).apply { text = it.value?.invoke() ?: ""; gravity = Gravity.END }
            row.addView(l, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p(20) })
            row.setOnFocusChangeListener { _, has ->
                row.background = if (has) pill(INK, INK, 16) else null
                l.setTextColor(if (has) ON_FOCUS else rest); v.setTextColor(if (has) Color.rgb(69, 80, 92) else INK_SOFT)
                glyph?.color = if (has) ON_FOCUS else GLYPH
            }
            row.setOnClickListener { _ ->
                if (!it.keepOpen) closeOverlay()
                it.act()
                if (it.keepOpen) v.text = it.value?.invoke() ?: ""
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, p(68)).apply { bottomMargin = p(6) })
            rowViews += row
            if (first == null) first = row
        }
        if (side) {
            val sc = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false; isFillViewport = true; isClickable = true
                addView(panel)
            }
            sc.background = Frost(shot, sc, 0f)
            ov.addView(sc, FrameLayout.LayoutParams(p(660), -1, Gravity.END))
        } else {
            ov.addView(panel, FrameLayout.LayoutParams(p(560), -2, Gravity.CENTER))
        }
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))
        overlay = ov
        if (focusAt >= 0 && focusAt < rowViews.size) rowViews[focusAt].requestFocus()
        else if (focusTab || first == null) (tabFocus ?: first)?.requestFocus() else first?.requestFocus()
    }

    private var panelRows: List<View> = emptyList()

    private fun inside(v: View, parent: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }

    private fun closeOverlay() {
        val ov = overlay ?: return
        root.removeView(ov); overlay = null
        focusTile(focusRow, focusCol)
    }

    private fun openSettings() {
        val am = getSystemService(ActivityManager::class.java)
        fun mem(): String {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return "${mi.availMem / 1_048_576} МБ из ${"%.1f".format(Locale("ru"), mi.totalMem / 1_073_741_824.0)} ГБ"
        }
        fun disk(): String {
            val st = StatFs(Environment.getDataDirectory().path)
            return "свободно ${"%.1f".format(Locale("ru"), st.availableBytes / 1e9)} из ${"%.0f".format(st.totalBytes / 1e9)} ГБ"
        }
        val days = SystemClock.elapsedRealtime() / 86_400_000
        val hidden = store.hidden
        showPanel("Быстрые настройки", null, listOf(
            Item("Телевизор", head = true),
            Item("Работает без перезагрузки", { if (days == 0L) "меньше суток" else "$days дн." }, glyph = "uptime"),
            Item("Свободно памяти", { mem() }, keepOpen = true, glyph = "memory"),
            Item("Освободить память", null, keepOpen = true, glyph = "clean") { freeMemory() },
            Item("Место", { disk() }, glyph = "storage"),
            Item("Главный экран", head = true),
            Item("Текст сообщений Telegram", { if (Chats.showText(this)) "показывать" else "скрывать" }, keepOpen = true, glyph = "chat") {
                Chats.setShowText(this, !Chats.showText(this))
            },
            Item("Скрытые приложения", { if (hidden.isEmpty()) "нет" else "${hidden.size}" }, glyph = "eyeoff") { openHidden() },
            Item("Новости", { newsSummary() }, glyph = "news") { openNewsSettings() },
            Item("Город погоды", { Weather.city(this) }, glyph = "sun") { openCities() },
            Item("Сеть", head = true),
        ) + (if (hasVpnApp()) listOf(Item("VPN", { if (vpnOn) "включён" else "выключен" }, glyph = "shield") { toggleVpn() }) else emptyList()) + listOf(
            Item("Wi-Fi и интернет", glyph = "wifi") { start(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            Item("Лаунчер", head = true),
            Item("Разрешения", { perms().let { l -> val n = l.count { !it.second() }; if (n == 0) "все есть" else "нет $n из ${l.size}" } }, glyph = "shield") { openPerms() },
            Item("Обновление", { updateText() }, glyph = "update") { update() },
            Item("Все настройки телевизора", glyph = "gear") { start(Intent(Settings.ACTION_SETTINGS)) },
        ), side = true)
    }

    private fun freeMemory() {
        val am = getSystemService(ActivityManager::class.java)
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        for (ai in packageManager.getInstalledApplications(0)) {
            if (ai.packageName == packageName || ai.packageName == VPN_APP) continue
            am.killBackgroundProcesses(ai.packageName)
        }
        ui.postDelayed({
            val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            val freed = (after - before) / 1_048_576
            toast(if (freed > 20) "Освобождено $freed МБ" else "Памяти уже достаточно")
        }, 800)
    }

    private fun openHidden() {
        val all = store.load().associateBy { it.pkg }
        val hidden = store.hidden.mapNotNull { all[it] }
        if (hidden.isEmpty()) { toast("Скрытых приложений нет"); return }
        showPanel("Скрытые приложения", "OK возвращает приложение на главный экран", hidden.map { a ->
            Item(a.label) { store.hidden = store.hidden.apply { remove(a.pkg) }; reloadApps(keep = true); toast("${a.label} снова на главном экране") }
        }, side = true)
    }

    private fun openInputs() {
        val tim = getSystemService(TvInputManager::class.java)
        val inputs = tim?.tvInputList.orEmpty().filter { it.isPassthroughInput }
        if (inputs.isEmpty()) { toast("Входы не найдены"); return }
        showPanel("Вход", "Откуда показывать картинку", inputs.map { info ->
            Item(info.loadLabel(this).toString()) {
                start(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id)))
            }
        }, side = true)
    }

    // ---------- Город, разрешения, обновление ----------

    private fun hasVpnApp() = runCatching { packageManager.getPackageInfo(VPN_APP, 0) }.isSuccess

    private fun openCities() {
        val cur = Weather.city(this)
        showPanel("Город погоды", null, Weather.CITIES.keys.map { c ->
            Item(c, { if (c == cur) "✓" else "" }) {
                Weather.setCity(this, c); wxCity.text = c; lastWx = null
                weather.fetch { w -> ui.post { showWeather(w) } }
                toast("Погода: $c")
            }
        }, side = true, focusAt = Weather.CITIES.keys.indexOf(cur))
    }

    /** Разрешение: название, есть ли оно и куда вести, если на телевизоре есть такой экран. */
    private fun perms(): List<Triple<String, () -> Boolean, String?>> = listOf(
        Triple("Время в приложениях", {
            val ops = getSystemService(android.app.AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
        }, Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Triple("Уведомления Telegram", {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        }, "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        Triple("Поверх приложений", { Settings.canDrawOverlays(this) }, Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Triple("Установка обновлений", { packageManager.canRequestPackageInstalls() }, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Triple("Таймер сна", { Sleep.canSleep(this) }, null),
        Triple("Главный экран по умолчанию", {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == packageName
        }, Settings.ACTION_HOME_SETTINGS),
    )

    private fun openPerms() {
        showPanel("Разрешения", "OK открывает экран телевизора, где включается разрешение. Где такого экрана нет — нужен компьютер, см. «Первая настройка» на GitHub",
            perms().map { (name, ok, action) ->
                Item(name, { if (ok()) "есть" else "нет" }) {
                    if (ok()) return@Item
                    val i = action?.let { a -> Intent(a).apply { if (a != Settings.ACTION_USAGE_ACCESS_SETTINGS && a != Settings.ACTION_HOME_SETTINGS && !a.contains("LISTENER")) data = Uri.parse("package:$packageName") } }
                    if (i == null || runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure)
                        toast("На этом телевизоре — только с компьютера: github.com/${Updater.REPO}")
                }
            }, side = true)
    }

    private var latest: Updater.Release? = null

    private fun updateText(): String {
        val (code, name) = Updater.current(this)
        return latest?.takeIf { it.code > code }?.let { "есть ${it.name}" } ?: "версия $name"
    }

    private fun checkUpdate() {
        Updater.check { r ->
            latest = r
            if (r != null && r.code > Updater.current(this).first) toast("Есть обновление лаунчера ${r.name}: быстрые настройки → «Обновление»")
        }
        ui.postDelayed({ checkUpdate() }, 24 * 3_600_000L)
    }

    private fun update() {
        val (code, name) = Updater.current(this)
        val r = latest
        if (r != null && r.code > code) {
            if (!packageManager.canRequestPackageInstalls()) { toast("Сначала разрешите установку: «Разрешения» → «Установка обновлений»"); return }
            Updater.install(this, r) { toast(it) }
        } else {
            toast("Проверяю обновления…")
            Updater.check { n ->
                latest = n
                when {
                    n == null -> toast("Не удалось проверить: нет связи с GitHub")
                    n.code > code -> toast("Есть версия ${n.name}. Нажмите «Обновление» ещё раз")
                    else -> toast("Установлена последняя версия $name")
                }
            }
        }
    }

    // ---------- Виджеты: новости и погода ----------

    private var lastWx: Weather.Now? = null
    private lateinit var wxCity: TextView

    private fun widgets() = listOf<View>(newsCard, wx).filter { it.visibility == View.VISIBLE }
    private fun chips() = (0 until capsule.childCount).map { capsule.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it.isFocusable }
    private fun centerX(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[0] + v.width / 2

    /** Ближайший по горизонтали: тот, под или над которым стоит фокус, иначе крайний с нужной стороны. */
    private fun nearest(views: List<View>, x: Int) = views.minByOrNull { v ->
        val l = IntArray(2).also { v.getLocationOnScreen(it) }[0]
        if (x in l..l + v.width) 0 else minOf(Math.abs(x - l), Math.abs(x - l - v.width))
    }

    /** Фокус на виджете: белая карточка, тёмный текст и значки, как у кнопок сверху. */
    private fun focusWidget(v: View, has: Boolean, rest: android.graphics.drawable.Drawable) {
        v.background = if (has) pill(Color.argb(245, 238, 242, 246), INK, 28) else rest
        fun walk(x: View) {
            when (x) {
                is TextView -> {
                    if (x.tag == null) x.tag = x.currentTextColor
                    val c = x.tag as Int
                    x.setTextColor(if (!has) c else if (c == INK_SOFT) Color.rgb(69, 80, 92) else ON_FOCUS)
                }
                is IconView -> x.color = if (has) ON_FOCUS else INK
                is ViewGroup -> for (i in 0 until x.childCount) walk(x.getChildAt(i))
                else -> if (x.height <= p(2)) x.setBackgroundColor(if (has) Color.argb(40, 15, 20, 26) else EDGE)
            }
        }
        walk(v)
        v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(180).start()
        if (has) liftRows(false)
    }

    private fun openWeather() {
        val w = lastWx ?: run { toast("Погода появится, когда будет связь"); return }
        showPanel("Погода: ${Weather.city(this)}", "${Weather.text(w.code)}, ощущается ${Weather.deg(w.feels)}. Днём ${Weather.deg(w.max)}, ночью ${Weather.deg(w.min)}",
            listOf(Item("По часам", head = true)) + w.hours.map { (t, deg) -> Item(t, { Weather.deg(deg) }, keepOpen = true) }, side = true)
    }

    // ---------- Новости ----------

    private fun buildNews() {
        newsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(30), p(26), p(30), p(26))
            isFocusable = true; isClickable = true
            setOnClickListener { openNews() }
            setOnFocusChangeListener { _, has ->
                animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(180).start()
                paintNews()
                if (has) liftRows(false)
            }
        }
        newsHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        newsBody = FrameLayout(this)
        newsCard.addView(newsHead, LinearLayout.LayoutParams(-1, p(32)))
        newsCard.addView(newsBody, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = p(16) })
        root.addView(newsCard, lp(460, 300, Gravity.BOTTOM or Gravity.END).apply { rightMargin = p(576); bottomMargin = p(580) })
        newsCard.visibility = if (News.hidden(this)) View.GONE else View.VISIBLE
    }

    private fun refreshNews() {
        news.fetch { all -> ui.post { showNews(all) } }
        ui.postDelayed({ refreshNews() }, 10 * 60_000L)
    }

    private fun showNews(all: List<NewsItem>) {
        pick = if (all.isEmpty()) null else news.pick(all)
        newsIdx = 0; newsRounds = 0
        paintNews(); scheduleNews()
        val b = pick?.breaking ?: return
        if (b.link != bannerFor && !Home.visible && News.urgentMode(this) == 0 && !News.night()) {
            bannerFor = b.link; BreakingBanner.show(this, b)
        }
    }

    /** Карточка: срочная держится сама; иначе новость дня первой и остальные по кругу. */
    private fun paintNews() {
        if (!::newsCard.isInitialized) return
        val f = newsCard.isFocused
        val pk = pick
        val brk = pk?.breaking
        newsCard.background = when {
            f -> pill(Color.argb(245, 238, 242, 246), INK, 28)
            brk != null -> pill(Color.argb(140, 40, 14, 18), Color.argb(190, 235, 87, 87), 28)
            else -> pill(Color.argb(107, 12, 17, 23), EDGE, 28)
        }
        val ink = if (f) ON_FOCUS else INK
        val soft = if (f) Color.rgb(69, 80, 92) else INK_SOFT
        newsHead.removeAllViews()
        if (brk != null) {
            newsHead.addView(Brk.label(this, p(17), p(12), p(6)))
            newsHead.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            newsHead.addView(text(18, Art.inter400, soft).apply { text = News.ago(brk.time) })
        } else {
            newsHead.addView(text(24, Art.inter600, ink).apply { text = "Новости" })
            newsHead.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            pk?.list?.indices?.forEach { i ->
                newsHead.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == newsIdx) ink else if (f) Color.argb(50, 15, 20, 26) else Color.argb(64, 236, 242, 248))
                    }
                }, LinearLayout.LayoutParams(p(8), p(8)).apply { leftMargin = p(8) })
            }
        }
        newsBody.removeAllViews()
        val item = brk ?: pk?.list?.getOrNull(newsIdx)
        val v = if (item == null) text(22, Art.inter500, soft).apply {
            isSingleLine = false; text = "Новости загрузятся, когда будет связь. Ленты открываются через VPN"
        } else newsView(item, brk == null && item === pk?.day, ink, soft, f)
        newsBody.addView(v, FrameLayout.LayoutParams(-1, -1))
        newsHold = 0L
        v.findViewWithTag<FrameLayout>("clip")?.let { credits(it) }
    }

    private var newsHold = 0L

    /** Титры: через 2,5 с заголовок едет вверх до последней строки, новость держится, пока её не дочитать. */
    private fun credits(clip: FrameLayout) = clip.post {
        val t = clip.getChildAt(0) as? TextView ?: return@post
        // контейнер урезает высоту заголовка под себя — полную берём из разметки строк
        val full = (t.layout?.height ?: return@post) + t.paddingTop + t.paddingBottom
        val over = full - clip.height
        if (over <= 0) return@post
        t.layoutParams = t.layoutParams.apply { height = full }
        val ms = (over * 1000L / p(28)).coerceAtLeast(1500L)
        t.animate().translationY(-over.toFloat()).setStartDelay(2500).setDuration(ms)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
        newsHold = 2500 + ms + 3000
        scheduleNews()
    }

    private fun newsView(n: NewsItem, day: Boolean, ink: Int, soft: Int, f: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (day) addView(text(16, Art.inter600, ink).apply {
            text = "ГЛАВНОЕ СЕГОДНЯ"; letterSpacing = .06f; setPadding(p(10), p(4), p(10), p(4))
            background = pill(Color.TRANSPARENT, if (f) Color.argb(128, 15, 20, 26) else Color.argb(140, 236, 242, 248))
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = p(10) })
        // длинный заголовок не обрезается, а прокручивается вверх титрами
        val clip = FrameLayout(this@MainActivity).apply { tag = "clip" }
        clip.addView(text(if (day) 28 else 26, Art.inter600, ink).apply {
            text = n.title; isSingleLine = false; setLineSpacing(0f, 1.12f)
        }, FrameLayout.LayoutParams(-1, -2))
        addView(clip, LinearLayout.LayoutParams(-1, 0, 1f))
        val meta = buildString {
            append(n.src).append(" · ").append(SimpleDateFormat("HH:mm", Locale("ru")).format(Date(n.time)))
            if (day && n.sources >= 2) append(" · пишут ${n.sources} издания")
        }
        addView(text(18, Art.inter400, soft).apply { text = meta }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(10) })
    }

    private val newsTick = Runnable { nextNews() }

    private fun scheduleNews() {
        ui.removeCallbacks(newsTick)
        val pk = pick ?: return
        ui.postDelayed(newsTick, maxOf(newsHold, if (pk.list.getOrNull(newsIdx) === pk.day) 16_000L else 8_000L))
    }

    private fun nextNews() {
        val pk = pick ?: return
        if (!Home.visible || News.hidden(this)) return
        if (newsCard.isFocused || overlay != null || pk.breaking != null || pk.list.size < 2) { scheduleNews(); return }
        val mode = News.mode(this)
        if (mode == 2) return // только новость дня
        val next = (newsIdx + 1) % pk.list.size
        if (mode == 1 && next == 0 && ++newsRounds >= 1) { newsIdx = 0; paintNews(); return } // один круг и остановиться
        val old = newsBody.getChildAt(0) ?: return
        old.animate().alpha(0f).translationY(-p(14).toFloat()).setDuration(300).withEndAction {
            newsIdx = next; paintNews()
            newsBody.getChildAt(0)?.let { it.alpha = 0f; it.translationY = p(14).toFloat(); it.animate().alpha(1f).translationY(0f).setDuration(300).start() }
            scheduleNews()
        }.start()
    }

    private fun openNews() {
        val pk = pick ?: run { toast("Новостей пока нет: ленты открываются через VPN"); return }
        val fmt = SimpleDateFormat("HH:mm", Locale("ru"))
        val list = listOfNotNull(pk.breaking) + pk.list.filter { it !== pk.breaking }
        val items = list.map { n ->
            Item(n.title, keepOpen = true, sub = n.desc, meta = "${n.src} · ${fmt.format(Date(n.time))}",
                badge = if (n === pk.breaking) "brk" else if (n === pk.day) "day" else null)
        }
        showPanel("Новости", "BBC, DW и Meduza, обновлено в ${fmt.format(Date(news.fetchedAt()))}", items, side = true)
        pk.breaking?.let { news.ack(it); pick = NewsPick(pk.day, pk.list, null); newsIdx = 0; paintNews() }
    }

    /** Строка новости в панели: заголовок, у выбранной — описание из ленты, внизу издание и время. */
    private fun newsRow(it: Item) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(p(24), p(14), p(24), p(14))
        isFocusable = true; isClickable = true
        if (it.badge == "brk") addView(Brk.label(this@MainActivity, p(15), p(9), p(3)), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = p(8) })
        val l = text(23, Art.inter500).apply {
            text = it.label; isSingleLine = false; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f)
        }
        val d = text(20, Art.inter400, Color.rgb(69, 80, 92)).apply {
            text = it.sub; isSingleLine = false; maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f); visibility = View.GONE
        }
        val m = text(18, Art.inter400, INK_SOFT).apply { text = (if (it.badge == "day") "Главное сегодня · " else "") + it.meta }
        addView(l)
        addView(d, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p(6) })
        addView(m, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(6) })
        setOnFocusChangeListener { _, has ->
            background = if (has) pill(INK, INK, 16) else null
            l.setTextColor(if (has) ON_FOCUS else INK); l.maxLines = if (has) 4 else 2
            d.visibility = if (has && !it.sub.isNullOrBlank()) View.VISIBLE else View.GONE
            m.setTextColor(if (has) Color.rgb(69, 80, 92) else INK_SOFT)
        }
    }

    private fun newsSummary() = if (News.hidden(this)) "скрыты" else when (News.mode(this)) {
        0 -> "по кругу"; 1 -> "один круг"; else -> "новость дня"
    }

    private fun openNewsSettings(focus: Int = 0) {
        val m = News.mode(this); val u = News.urgentMode(this); val h = News.hidden(this)
        fun ch(b: Boolean): () -> String = { if (b) "✓" else "" }
        fun mode(v: Int, at: Int) { News.set(this, "newsMode", v); newsIdx = 0; newsRounds = 0; paintNews(); scheduleNews(); openNewsSettings(at) }
        fun urgent(v: Int, at: Int) { News.set(this, "newsUrgent", v); openNewsSettings(at) }
        showPanel("Новости", "Что и как показывать на главном экране", listOf(
            Item("Смена новостей", head = true),
            Item("По кругу", ch(m == 0)) { mode(0, 0) },
            Item("Один круг и остановиться", ch(m == 1)) { mode(1, 1) },
            Item("Не листать, только новость дня", ch(m == 2)) { mode(2, 2) },
            Item("Срочные новости", head = true),
            Item("Поверх приложений", ch(u == 0)) { urgent(0, 3) },
            Item("Только на главном экране", ch(u == 1)) { urgent(1, 4) },
            Item("Выключены", ch(u == 2)) { urgent(2, 5) },
            Item("Карточка", head = true),
            Item("Скрыть новости с главного экрана", ch(h)) {
                News.setHidden(this, !h); newsCard.visibility = if (!h) View.GONE else View.VISIBLE; openNewsSettings(6)
            },
        ), side = true, focusAt = focus)
    }

    /** Снимок экрана, уменьшенный и размытый один раз при открытии панели: матовая подложка без живого размытия. */
    private fun frostShot(): Bitmap? = runCatching {
        val k = 12
        val b = Bitmap.createBitmap(maxOf(1, root.width / k), maxOf(1, root.height / k), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.scale(1f / k, 1f / k); root.draw(c)
        Art.blur(b, 3); b
    }.getOrNull()

    /** Фон панели: размытый снимок того, что под ней, затемнение и тонкая светлая рамка. */
    private inner class Frost(private val shot: Bitmap?, private val host: View, private val radius: Float) : android.graphics.drawable.Drawable() {
        private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(31, 236, 242, 248) }
        private val clip = Path()
        private val loc = IntArray(2)
        override fun draw(c: Canvas) {
            val r = RectF(bounds)
            c.save()
            clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW); c.clipPath(clip)
            shot?.let {
                host.getLocationOnScreen(loc)
                c.save(); c.translate(-loc[0].toFloat(), -loc[1].toFloat())
                c.drawBitmap(it, null, Rect(0, 0, root.width, root.height), bp)
                c.restore()
            }
            fill.color = Color.argb(184, 5, 8, 12); c.drawRect(r, fill)
            fill.color = Color.argb(158, 30, 38, 48); c.drawRect(r, fill)
            c.restore()
            edge.strokeWidth = maxOf(1f, p(1).toFloat())
            c.drawRoundRect(r, radius, radius, edge)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    // ---------- Telegram ----------

    private fun showChats() {
        if (!::tgChip.isInitialized) return
        val n = Chats.unread()
        tgBadges.forEach { it.text = "$n"; it.visibility = if (n > 0) View.VISIBLE else View.GONE }
        val latest = Chats.sorted().firstOrNull { it.count > 0 }
        tgChip.visibility = if (latest == null) View.GONE else View.VISIBLE
        tgSep.visibility = tgChip.visibility
        tgText.text = "$n"
    }

    private var msgTab = 0 // по умолчанию «Личные»
    private val tabKinds = listOf(0, 1, 2, -1) // -1 — все

    private fun openMessages(focusTab: Boolean = false) {
        val all = Chats.sorted()
        if (all.isEmpty()) { toast("Новых сообщений нет"); return }
        val fmt = SimpleDateFormat("HH:mm", Locale("ru"))
        fun of(k: Int) = if (k < 0) all else all.filter { it.kind == k }
        val labels = listOf("Личные", "Группы", "Каналы", "Все").mapIndexed { i, l ->
            val n = of(tabKinds[i]).sumOf { it.count }
            if (n > 0) "$l  $n" else l
        }
        val chats = of(tabKinds[msgTab])
        val items = if (chats.isEmpty()) listOf(Item("Здесь пока пусто", head = true))
        else chats.map { c ->
            Item(c.name, { if (c.count > 0) "${c.count} нов." else fmt.format(Date(c.time)) }, icon = c.avatar ?: letterAvatar(c.name)) { chatMenu(c) }
        }
        showPanel("Сообщения", "Telegram, пока телевизор включён", items, side = true,
            tabs = labels, tab = msgTab, onTab = { msgTab = it; openMessages(focusTab = true) }, focusTab = focusTab)
    }

    /** Кружок с первой буквой — если у чата нет фото. */
    private fun letterAvatar(name: String) = object : android.graphics.drawable.Drawable() {
        private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf((name.hashCode() and 0xffff) % 360f, .5f, .7f))
        }
        private val tx = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Art.inter600; textAlign = android.graphics.Paint.Align.CENTER
        }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, bg)
            tx.textSize = b.height() * .45f
            c.drawText(name.trim().take(1).uppercase(), b.exactCenterX(), b.exactCenterY() - (tx.descent() + tx.ascent()) / 2, tx)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun chatMenu(c: Chat) = ReplyActivity.start(this, c.name, view = true)

    // ---------- Таймер сна ----------

    private val sleepSteps = intArrayOf(0, 15, 30, 60, 90, 120)

    private fun cycleSleep() {
        if (!Sleep.canSleep(this)) { toast("Таймеру нужно разрешение: выдаётся один раз с компьютера"); return }
        val left = Sleep.endsAt(this).let { if (it == 0L) 0 else ((it - System.currentTimeMillis()) / 60_000).toInt() + 1 }
        // следующий шаг после текущего остатка; после двух часов — выключить
        val next = sleepSteps.firstOrNull { it > left } ?: 0
        Sleep.set(this, next)
        showSleep()
        toast(if (next == 0) "Таймер сна выключен" else "Телевизор выключится через ${minutesText(next)}")
    }

    /** Удержание на таймере: выбор интервала списком. */
    private fun pickSleep() {
        if (!Sleep.canSleep(this)) { toast("Таймеру нужно разрешение: выдаётся один раз с компьютера"); return }
        val left = Sleep.endsAt(this).let { if (it == 0L) 0 else ((it - System.currentTimeMillis()) / 60_000).toInt() + 1 }
        val choices = intArrayOf(15, 30, 45, 60, 90, 120, 180)
        val items = choices.map { m ->
            Item(minutesText(m)) { Sleep.set(this, m); showSleep(); toast("Телевизор выключится через ${minutesText(m)}") }
        }.toMutableList()
        items += Item("Выключить таймер", { if (left == 0) "сейчас выключен" else "осталось ${minutesText(left)}" }) {
            Sleep.set(this, 0); showSleep(); toast("Таймер сна выключен")
        }
        showPanel("Таймер сна", "Через сколько выключить телевизор", items, side = true)
    }

    private fun showSleep() {
        val at = Sleep.endsAt(this)
        if (at == 0L) { timerText.visibility = View.GONE; return }
        timerText.visibility = View.VISIBLE
        timerText.text = minutesText(((at - System.currentTimeMillis()) / 60_000).toInt() + 1)
    }

    private fun minutesText(m: Int) = when {
        m >= 60 && m % 60 == 0 -> "${m / 60} ч"
        m >= 60 -> "${m / 60} ч ${m % 60} мин"
        else -> "$m мин"
    }

    // ---------- Подсказка внизу ----------

    private fun toast(msg: String) {
        toastView?.let { root.removeView(it) }
        val t = text(26, Art.inter500).apply {
            text = msg; setPadding(p(30), p(18), p(30), p(18))
            background = pill(Color.argb(242, 20, 27, 35), EDGE, 18); alpha = 0f
        }
        root.addView(t, lp(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = p(60) })
        toastView = t
        t.animate().alpha(1f).setDuration(200).start()
        ui.postDelayed({ if (toastView === t) t.animate().alpha(0f).setDuration(250).withEndAction { root.removeView(t); if (toastView === t) toastView = null }.start() }, 2400)
    }
}
