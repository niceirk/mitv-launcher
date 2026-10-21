package ru.nikita.tvhome

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Погода Open-Meteo (без ключа). Последний ответ хранится, чтобы виджет не был пустым без сети. */
class Weather(private val ctx: Context) {
    data class Now(
        val temp: Int, val feels: Int, val code: Int, val max: Int, val min: Int,
        val hours: List<Pair<String, Int>>,
    )

    private val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    // город выбирается в быстрых настройках; часовой пояс — города
    private val url get() = CITIES[city(ctx)]!!.let { (lat, lon) ->
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&hourly=temperature_2m&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=2"
    }

    fun cached(): Now? = prefs.getString("json", null)?.let { runCatching { parse(it) }.getOrNull() }

    fun fetch(done: (Now) -> Unit) = thread {
        runCatching {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 8000
            val body = con.inputStream.bufferedReader().readText()
            val now = parse(body)
            prefs.edit().putString("json", body).apply()
            done(now)
        }
    }

    private fun parse(s: String): Now {
        val j = JSONObject(s)
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val h = j.getJSONObject("hourly")
        val times = h.getJSONArray("time"); val temps = h.getJSONArray("temperature_2m")
        val hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hours = (1..12).mapNotNull { k ->
            val i = hourNow + k
            if (i < times.length()) times.getString(i).takeLast(5) to temps.getDouble(i).roundToInt() else null
        }
        return Now(
            cur.getDouble("temperature_2m").roundToInt(), cur.getDouble("apparent_temperature").roundToInt(),
            cur.getInt("weather_code"),
            daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(), hours,
        )
    }

    companion object {
        val CITIES = linkedMapOf(
            "Москва" to (55.75 to 37.62), "Санкт-Петербург" to (59.94 to 30.31), "Новосибирск" to (55.03 to 82.92),
            "Екатеринбург" to (56.84 to 60.61), "Казань" to (55.79 to 49.12), "Нижний Новгород" to (56.33 to 44.00),
            "Челябинск" to (55.16 to 61.40), "Самара" to (53.20 to 50.15), "Ростов-на-Дону" to (47.23 to 39.72),
            "Уфа" to (54.73 to 55.96), "Краснодар" to (45.04 to 38.98), "Воронеж" to (51.67 to 39.18),
            "Пермь" to (58.01 to 56.23), "Волгоград" to (48.71 to 44.51), "Калининград" to (54.71 to 20.51),
            "Минск" to (53.90 to 27.56),
        )
        fun city(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("city", null)
            ?.takeIf { it in CITIES } ?: "Москва"
        fun setCity(ctx: Context, c: String) {
            ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("city", c).apply()
            ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().remove("json").apply()
        }

        fun text(code: Int) = when (code) {
            0 -> "Ясно"; 1 -> "Почти ясно"; 2 -> "Переменная облачность"; 3 -> "Облачно"
            45, 48 -> "Туман"; in 51..57 -> "Морось"; in 61..67 -> "Дождь"; in 71..77 -> "Снег"
            in 80..82 -> "Ливень"; 85, 86 -> "Снегопад"; in 95..99 -> "Гроза"; else -> "—"
        }
        fun icon(code: Int) = when (code) { 0, 1 -> "sun"; in 51..67, in 80..82, in 95..99 -> "rain"; else -> "cloud" }
        fun deg(t: Int) = if (t > 0) "+$t°" else "$t°"
    }
}
