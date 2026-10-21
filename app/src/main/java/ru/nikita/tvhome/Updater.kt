package ru.nikita.tvhome

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Обновление по кнопке: последний релиз на GitHub (тег v<versionCode>, файл .apk),
 * скачивание прямо в установщик Android; подтверждение — системное окно, один OK.
 */
object Updater {
    const val REPO = "niceirk/mitv-launcher"
    private const val DONE = "ru.nikita.tvhome.UPDATE_DONE"
    private val ui = Handler(Looper.getMainLooper())

    class Release(val code: Int, val name: String, val url: String)

    fun current(ctx: Context): Pair<Int, String> = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
        @Suppress("DEPRECATION")
        (if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode) to (it.versionName ?: "")
    }

    /** Последний релиз или null, если сети нет. */
    fun check(done: (Release?) -> Unit) = thread {
        val r = runCatching {
            val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 10000
            con.setRequestProperty("Accept", "application/vnd.github+json")
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name").endsWith(".apk") }
            Release(j.getString("tag_name").removePrefix("v").toInt(), j.optString("name", ""), apk.getString("browser_download_url"))
        }.getOrNull()
        ui.post { done(r) }
    }

    fun install(ctx: Context, r: Release, say: (String) -> Unit) = thread {
        try {
            ui.post { say("Скачиваю версию ${r.name}…") }
            val pi = ctx.packageManager.packageInstaller
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            pi.openSession(id).use { s ->
                val con = URL(r.url).openConnection() as HttpURLConnection
                con.connectTimeout = 10000; con.readTimeout = 30000
                con.inputStream.use { input -> s.openWrite("launcher.apk", 0, -1).use { out -> input.copyTo(out); s.fsync(out) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val back = PendingIntent.getBroadcast(ctx, 0, Intent(DONE).setClass(ctx, Done::class.java), flags)
                s.commit(back.intentSender)
            }
        } catch (e: Exception) {
            ui.post { say("Не удалось скачать обновление. Проверьте интернет и VPN") }
        }
    }

    /** Ответ установщика: попросить подтверждение у человека или сообщить об ошибке. */
    class Done : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> Toast.makeText(ctx, "Обновление не установилось: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
