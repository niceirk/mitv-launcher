package ru.nikita.tvhome

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

/**
 * Таймер сна. Выключить телевизор напрямую обычному приложению нельзя, поэтому в конце таймера
 * включаем штатную заставку и на минуту сокращаем ожидание перед сном (sleep_timeout):
 * телевизор сам уходит в режим ожидания. Через минуту прежнее значение возвращается —
 * будильник без пробуждения сработает, как только телевизор включат.
 */
object Sleep {
    private const val WARN = "ru.nikita.tvhome.SLEEP_WARN"
    private const val OFF = "ru.nikita.tvhome.SLEEP_OFF"
    private const val RESTORE = "ru.nikita.tvhome.SLEEP_RESTORE"
    private const val KEY = "sleep_timeout"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun endsAt(ctx: Context) = prefs(ctx).getLong("sleepAt", 0L).takeIf { it > System.currentTimeMillis() } ?: 0L

    /** Право менять sleep_timeout выдаётся один раз с компьютера: pm grant … WRITE_SECURE_SETTINGS */
    fun canSleep(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun set(ctx: Context, minutes: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val warn = pi(ctx, WARN); val off = pi(ctx, OFF)
        am.cancel(warn); am.cancel(off)
        val at = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        prefs(ctx).edit().putLong("sleepAt", at).apply()
        if (at == 0L) return
        if (minutes > 1) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 60_000L, warn)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, off)
    }

    fun sleepNow(ctx: Context) {
        if (!canSleep(ctx)) return
        val cr = ctx.contentResolver
        if (!prefs(ctx).contains("sleepWas")) {
            prefs(ctx).edit().putLong("sleepWas", Settings.Secure.getLong(cr, KEY, 1_800_000L)).apply()
        }
        Settings.Secure.putLong(cr, KEY, 15_000L)
        try {
            ctx.startActivity(Intent("com.google.android.pano.action.SLEEP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ctx.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 60_000L, pi(ctx, RESTORE))
    }

    /** Вернуть прежнее ожидание перед сном, если оно было сокращено. */
    fun restore(ctx: Context) {
        val was = prefs(ctx).getLong("sleepWas", -1L)
        if (was < 0 || !canSleep(ctx)) return
        Settings.Secure.putLong(ctx.contentResolver, KEY, was)
        prefs(ctx).edit().remove("sleepWas").apply()
    }

    private fun pi(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(action).setClass(ctx, SleepReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class SleepReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.action) {
                WARN -> Toast.makeText(ctx, "Телевизор выключится через минуту", Toast.LENGTH_LONG).show()
                OFF -> { prefs(ctx).edit().putLong("sleepAt", 0L).apply(); sleepNow(ctx) }
                RESTORE -> restore(ctx)
            }
        }
    }
}
