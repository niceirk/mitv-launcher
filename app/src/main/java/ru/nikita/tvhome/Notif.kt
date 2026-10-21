package ru.nikita.tvhome

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Переписка из уведомления Telegram: кто, что, сколько новых и как ответить. */
class Chat(val key: String, val pkg: String) {
    var name = ""
    var text = ""
    var time = 0L
    var count = 0
    var reply: Notification.Action? = null
    var open: PendingIntent? = null
    var avatar: android.graphics.drawable.Drawable? = null
    /** Последние сообщения переписки: отправитель (в группе) и текст. */
    var history: List<Pair<String?, String>> = emptyList()
    /** 0 — личный (люди и боты), 1 — группа, 2 — канал */
    var kind = 0
}

/** Переписки живут, пока висят их уведомления: прочитали на телефоне — пропали и здесь. */
object Chats {
    val TG = setOf("org.telegram.messenger", "org.telegram.messenger.web", "cassian.telegram.ooa.pro", "org.thunderdog.challegram")
    val byName = LinkedHashMap<String, Chat>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    fun changed() = ui.post { listeners.toList().forEach { it() } }

    fun sorted() = byName.values.sortedByDescending { it.time }
    fun unread() = byName.values.sumOf { it.count }
    fun find(name: String?) = name?.let { byName[it] }

    fun showText(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("tgText", false)
    fun setShowText(ctx: Context, v: Boolean) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("tgText", v).apply()

    /** Что показать вместо текста, если текст скрыт. */
    fun preview(ctx: Context, c: Chat) = if (showText(ctx)) c.text else when (c.count) {
        0 -> "Прочитано"; 1 -> "Новое сообщение"; in 2..4 -> "${c.count} новых сообщения"; else -> "${c.count} новых сообщений"
    }

    /** Отправить ответ через кнопку «Ответить» самого уведомления. */
    fun send(ctx: Context, c: Chat, text: String): Boolean {
        val a = c.reply ?: return false
        val inputs = a.remoteInputs ?: return false
        val data = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        android.app.RemoteInput.addResultsToIntent(inputs, fill, data)
        return try { a.actionIntent.send(ctx, 0, fill); c.count = 0; changed(); true } catch (e: Exception) { false }
    }
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach { take(it, quiet = true) }
        Chats.changed()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { take(sbn, quiet = false) }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in Chats.TG) return
        val gone = Chats.byName.values.filter { it.key == sbn.key }
        gone.forEach { Chats.byName.remove(it.name) }
        if (gone.isNotEmpty()) Chats.changed()
    }

    private fun take(sbn: StatusBarNotification, quiet: Boolean) {
        if (sbn.packageName !in Chats.TG) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val name = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim() ?: return
        // последнее сообщение из переписки; в группе — с именем отправителя
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { it as? Bundle }.orEmpty()
        val last = msgs.lastOrNull()
        val body = last?.getCharSequence("text")?.toString() ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val sender = last?.getCharSequence("sender")?.toString()
        val text = if (sender != null && sender != name && ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) "$sender: $body" else body
        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

        val prev = Chats.byName[name]
        // два клиента на одном аккаунте присылают одно и то же — оставляем тот, где можно ответить
        if (prev != null && prev.pkg != sbn.packageName && prev.reply != null && reply == null && System.currentTimeMillis() - prev.time < 10_000) return
        val c = Chat(sbn.key, sbn.packageName).apply {
            this.name = name; this.text = text; time = sbn.postTime
            count = maxOf(n.number, msgs.size, 1); this.reply = reply ?: prev?.reply; open = n.contentIntent
            // аватар: крупная картинка уведомления — у Telegram это фото чата
            history = msgs.takeLast(5).mapNotNull { b ->
                val t = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                b.getCharSequence("sender")?.toString() to t
            }.ifEmpty { listOf(null to body) }
            kind = when {
                ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) -> 1
                (reply ?: prev?.reply) == null -> 2 // в канал ответить нельзя — так его и узнаём
                else -> 0
            }
            avatar = runCatching { n.getLargeIcon()?.loadDrawable(this@NotifService) }.getOrNull() ?: prev?.avatar
        }
        Chats.byName.remove(name); Chats.byName[name] = c
        Chats.changed()
        if (!quiet && prev?.text != text && !Home.replying) Banner.show(this, c) // стопка видна везде, кроме экрана ответа
    }

}

/** Лаунчер на экране — плашка не нужна, всё видно на главном экране. */
object Home {
    @Volatile var visible = false
    /** Открыт экран ответа — стопка поверх него перехватила бы пульт. */
    @Volatile var replying = false
}

/**
 * Стопка уведомлений внизу справа: до трёх свежих переписок, живёт 30 с после последнего сообщения
 * и держит фокус пульта — ↑/↓ выбор, OK — ответить голосом, «Назад» и любая другая кнопка — скрыть.
 */
object Banner {
    private var view: FrameLayout? = null
    private val stack = mutableListOf<Chat>()
    private var sel = 0
    private val ui = Handler(Looper.getMainLooper())
    private val hideRun = Runnable { hideNow() }
    private var appCtx: Context? = null

    fun show(ctx: Context, c: Chat) = ui.post {
        appCtx = ctx.applicationContext
        stack.removeAll { it.name == c.name }
        stack.add(0, c)
        while (stack.size > 3) stack.removeAt(stack.size - 1)
        sel = 0
        if (view == null) attach(ctx) else paint(ctx)
        ui.removeCallbacks(hideRun); ui.postDelayed(hideRun, 30_000)
    }

    fun hide(@Suppress("UNUSED_PARAMETER") ctx: Context) = ui.post { hideNow() }

    private fun hideNow() {
        ui.removeCallbacks(hideRun)
        val v = view ?: return
        view = null; stack.clear()
        runCatching { appCtx?.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    private fun attach(ctx: Context) {
        val S = ctx.resources.displayMetrics.widthPixels / 1920f
        fun p(v: Int) = (v * S).roundToInt()
        if (Art.inter400 === android.graphics.Typeface.DEFAULT) Art.loadFonts(ctx)
        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.action != KeyEvent.ACTION_DOWN) return true
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (sel > 0) sel--; paint(ctx); restart() }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (sel < stack.size - 1) sel++; paint(ctx); restart() }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                        val c = stack.getOrNull(sel)
                        hideNow()
                        if (c != null) ReplyActivity.start(ctx, c.name, view = true)
                    }
                    else -> hideNow()
                }
                return true
            }
        }
        root.isFocusable = true; root.isFocusableInTouchMode = true
        val lp = WindowManager.LayoutParams(
            p(500), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = p(72); y = p(64); windowAnimations = android.R.style.Animation_Toast }
        view = root
        paint(ctx)
        try { ctx.getSystemService(WindowManager::class.java).addView(root, lp); root.requestFocus() } catch (e: Exception) { view = null }
    }

    private fun restart() { ui.removeCallbacks(hideRun); ui.postDelayed(hideRun, 30_000) }

    private fun paint(ctx: Context) {
        val root = view ?: return
        val S = ctx.resources.displayMetrics.widthPixels / 1920f
        fun p(v: Int) = (v * S).roundToInt()
        fun tv(size: Int, color: Int, face: android.graphics.Typeface) = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, p(size).toFloat()); setTextColor(color); typeface = face
            includeFontPadding = false; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val ink = Color.rgb(238, 242, 246); val soft = Color.rgb(154, 166, 178); val dark = Color.rgb(15, 20, 26)
        root.removeAllViews()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        stack.forEachIndexed { i, c ->
            val on = i == sel
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p(22), p(18), p(24), p(18))
                background = GradientDrawable().apply {
                    setColor(if (on) Color.argb(245, 238, 242, 246) else Color.argb(225, 18, 24, 31)); cornerRadius = p(24).toFloat()
                    setStroke(maxOf(1, p(1)), Color.argb(36, 236, 242, 248))
                }
            }
            val ava: View = c.avatar?.let { d ->
                android.widget.ImageView(ctx).apply {
                    setImageDrawable(d); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
                }
            } ?: tv(24, Color.WHITE, Art.inter600).apply {
                text = c.name.trim().take(1).uppercase(); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(42, 143, 216)) }
            }
            card.addView(ava, LinearLayout.LayoutParams(p(56), p(56)).apply { rightMargin = p(16) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(tv(26, if (on) dark else ink, Art.inter600).apply { text = c.name })
            texts.addView(tv(23, if (on) Color.rgb(69, 80, 92) else soft, Art.inter400).apply { text = Chats.preview(ctx, c) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(5) })
            card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (on) {
                fun glyph(kind: String, filled: Boolean) = FrameLayout(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (filled) setColor(dark) else { setColor(Color.TRANSPARENT); setStroke(maxOf(1, p(2)), Color.argb(90, 15, 20, 26)) }
                    }
                    addView(IconView(ctx, kind).apply { color = if (filled) ink else dark }, FrameLayout.LayoutParams(p(22), p(22), Gravity.CENTER))
                }
                card.addView(glyph("open", true), LinearLayout.LayoutParams(p(44), p(44)).apply { leftMargin = p(12) })  // OK — открыть
                card.addView(glyph("close", false), LinearLayout.LayoutParams(p(44), p(44)).apply { leftMargin = p(8) }) // Назад — скрыть
            }
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p(10) })
        }
        root.addView(col)
    }
}
