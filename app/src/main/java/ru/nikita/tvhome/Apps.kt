package ru.nikita.tvhome

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Установленное приложение, которое можно открыть с телевизора. */
class App(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable,
    val color: Int,
    val kind: String,
) {
    var minutesMonth = 0L
    var lastUsed = 0L
}

/** Список приложений, порядок главного ряда и скрытые — в SharedPreferences. */
class AppStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val pm = ctx.packageManager

    // Порядок главного ряда при первом запуске — как пользователь расставил в Projectivy
    private val defaultMain = listOf(
        "org.smarttube.stable", "com.google.android.youtube.tv", "mobi.zona", "ru.kinopoisk.tv",
        "com.vk.tv", "ru.more.play", "ru.mts.mtstv", "ru.amediateka",
    )
    // Системные пакеты, которые в рядах не нужны
    private val skip = setOf(
        ctx.packageName, "com.spocky.projengmenu", "com.xiaomi.mitv.handbook", "com.xiaomi.mitv.manualhelp",
        "com.google.android.tvlauncher", "com.mitv.tvhome.atv",
    )

    var main: MutableList<String>
        get() = prefs.getString("main", null)?.split(',')?.filter { it.isNotBlank() }?.toMutableList()
            ?: defaultMain.toMutableList()
        set(v) = prefs.edit().putString("main", v.joinToString(",")).apply()

    var hidden: MutableSet<String>
        get() = prefs.getStringSet("hidden", emptySet())!!.toMutableSet()
        set(v) = prefs.edit().putStringSet("hidden", v).apply()

    fun load(): List<App> {
        val found = LinkedHashMap<String, App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in found || pkg in skip) continue
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                val banner = (ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm))
                    ?.takeIf { opaque(it) } // прозрачный баннер даёт пустую плитку — лучше значок
                val icon = ri.loadIcon(pm)
                found[pkg] = App(
                    pkg, ri.loadLabel(pm).toString(), intent, banner, icon,
                    colorOf(pkg, banner ?: icon), kindOf(ri.activityInfo.applicationInfo),
                )
            }
        }
        fillUsage(found)
        return found.values.toList()
    }

    // Многие приложения не сообщают категорию — для известных задаём сами
    private val kinds = mapOf(
        "org.smarttube.stable" to "YouTube без рекламы", "com.google.android.youtube.tv" to "Видео",
        "com.google.android.youtube.tvmusic" to "Музыка", "mobi.zona" to "Фильмы и сериалы",
        "ru.kinopoisk.tv" to "Онлайн-кинотеатр", "ru.more.play" to "Онлайн-кинотеатр", "ru.ivi.client" to "Онлайн-кинотеатр",
        "ru.amediateka" to "Сериалы", "com.amdteka" to "Сериалы", "gpm.tnt_premier" to "Онлайн-кинотеатр",
        "ru.start.androidmobile" to "Онлайн-кинотеатр", "ru.rt.video.app.tv" to "Телевидение", "ru.mts.mtstv" to "Телевидение",
        "com.vk.tv" to "Видео", "com.stremio.one" to "Фильмы и сериалы", "com.v2raytun.android" to "VPN",
        "com.android.vending" to "Магазин приложений", "org.telegram.messenger.web" to "Мессенджер",
        "com.lonelycatgames.Xplore" to "Файлы", "com.alphainventor.filemanager" to "Файлы",
        "com.mediatek.wwtv.tvcenter" to "Эфирное телевидение", "com.android.tv.settings" to "Настройки",
    )

    // Цвет считается один раз на версию приложения
    private fun colorOf(pkg: String, d: Drawable): Int {
        val ver = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "c:$pkg:$ver"
        if (prefs.contains(key)) return prefs.getInt(key, 0)
        return dominantColor(d).also { prefs.edit().putInt(key, it).apply() }
    }

    private fun kindOf(ai: ApplicationInfo) = kinds[ai.packageName] ?: when (ai.category) {
        ApplicationInfo.CATEGORY_VIDEO -> "Видео"
        ApplicationInfo.CATEGORY_AUDIO -> "Музыка"
        ApplicationInfo.CATEGORY_GAME -> "Игра"
        ApplicationInfo.CATEGORY_IMAGE -> "Фото"
        ApplicationInfo.CATEGORY_SOCIAL -> "Общение"
        ApplicationInfo.CATEGORY_NEWS -> "Новости"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Инструменты"
        else -> "Приложение"
    }

    /** Время просмотра за 30 дней. Нужен доступ к статистике: выдаётся один раз через adb. */
    private fun fillUsage(apps: Map<String, App>) {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val end = System.currentTimeMillis()
        val stats = try { usm.queryAndAggregateUsageStats(end - 30L * 86_400_000, end) } catch (e: Exception) { return }
        for ((pkg, s) in stats) apps[pkg]?.let {
            it.minutesMonth = s.totalTimeInForeground / 60_000
            it.lastUsed = s.lastTimeUsed
        }
    }

    companion object {
        /** Средний насыщенный цвет картинки: из него строится фон полки. */
        fun dominantColor(d: Drawable): Int {
            val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 24, 24); d.draw(Canvas(bmp))
            var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
            val hsv = FloatArray(3)
            for (x in 0 until 24) for (y in 0 until 24) {
                val p = bmp.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                // белый и чёрный фон баннера почти не учитываем — цвет даёт логотип
                val k = 0.002 + Math.pow(hsv[1] * hsv[2].toDouble(), 2.0)
                r += Color.red(p) * k; g += Color.green(p) * k; b += Color.blue(p) * k; w += k
            }
            bmp.recycle()
            return if (w == 0.0) Color.rgb(60, 80, 100) else Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
        }

        /** Баннер, который хотя бы наполовину непрозрачный. */
        fun opaque(d: Drawable): Boolean {
            val bmp = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 32, 18); d.draw(Canvas(bmp))
            var a = 0L
            for (x in 0 until 32) for (y in 0 until 18) a += Color.alpha(bmp.getPixel(x, y))
            bmp.recycle()
            return a / (32 * 18) > 128
        }

        fun facts(a: App): List<String> {
            val out = mutableListOf<String>()
            val m = a.minutesMonth
            if (m > 0) out += when {
                m >= 60 -> "${(m + 30) / 60} ч за месяц"
                else -> "$m мин за месяц"
            }
            if (a.lastUsed > 0) out += "Последний раз ${whenText(a.lastUsed)}"
            if (out.isEmpty()) out += "В этом месяце не открывали"
            return out
        }

        private fun whenText(t: Long): String {
            val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
            val days = ((now.timeInMillis - t) / 86_400_000).toInt()
            return when {
                now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && days < 2 -> "сегодня"
                days < 2 -> "вчера"
                else -> SimpleDateFormat("d MMMM", Locale("ru")).format(c.time)
            }
        }
    }
}
