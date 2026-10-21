package ru.nikita.tvhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Голосовой ответ: слушаем, показываем распознанный текст и отправляем только после OK.
 * Ответ уходит от имени пользователя, поэтому без подтверждения ничего не отправляется.
 */
class ReplyActivity : Activity() {
    private val S by lazy { resources.displayMetrics.widthPixels / 1920f }
    private fun p(v: Int) = (v * S).roundToInt()
    private val INK = Color.rgb(238, 242, 246)
    private val SOFT = Color.rgb(154, 166, 178)
    private val ON_FOCUS = Color.rgb(15, 20, 26)
    private val REC = Color.rgb(235, 87, 87)

    private var chat: Chat? = null
    private var sr: SpeechRecognizer? = null
    private var heardText = ""
    private lateinit var say: TextView
    private lateinit var heard: TextView
    private lateinit var mic: LevelView
    private lateinit var rec: TextView
    private lateinit var btns: LinearLayout
    private lateinit var listenBox: LinearLayout
    private lateinit var viewBox: LinearLayout
    private lateinit var msgsBox: LinearLayout
    private var shown = false
    private var sendBtn: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Art.loadFonts(this)
        chat = Chats.find(intent.getStringExtra("chat"))
        val c = chat ?: run { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(p(48), p(44), p(48), p(40))
            background = GradientDrawable().apply { setColor(Color.argb(242, 18, 24, 31)); cornerRadius = p(32).toFloat(); setStroke(maxOf(1, p(1)), Color.argb(36, 236, 242, 248)) }
        }
        // кому уходит ответ — крупно и с фото, чтобы нельзя было перепутать чат
        val who = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        c.avatar?.let { d ->
            who.addView(android.widget.ImageView(this).apply {
                setImageDrawable(d); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
            }, LinearLayout.LayoutParams(p(72), p(72)).apply { rightMargin = p(20) })
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumWidth = p(400) }
        names.addView(text(24, SOFT, Art.inter500).apply { text = "Ответ для" })
        names.addView(text(40, INK, Art.inter600).apply { text = c.name }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(6) })
        who.addView(names)
        card.addView(who)
        // просмотр: последние сообщения, текст скрыт до нажатия на глаз
        viewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        msgsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        viewBox.addView(msgsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p(26) })
        val vb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        viewBox.layoutParams = LinearLayout.LayoutParams(-1, -2)
        val vCap = caption()
        val replyBtn = roundButton("mic", "Ответить голосом", vCap) { listen() }
        if (c.reply != null) vb.addView(replyBtn)
        lateinit var eyeBtn: FrameLayout
        eyeBtn = roundButton("eye", "Показать текст", vCap) { shown = !shown; paintMessages(); setEye(eyeBtn, vCap) }
        vb.addView(eyeBtn)
        vb.addView(roundButton("open", "Открыть в Telegram", vCap) { runCatching { c.open?.send() }; finish() })
        viewBox.addView(vb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p(30) })
        viewBox.addView(vCap, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(16) })
        card.addView(viewBox, LinearLayout.LayoutParams(-1, -2))

        listenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
        mic = LevelView(this)
        listenBox.addView(mic, LinearLayout.LayoutParams(p(132), p(132)).apply { topMargin = p(30) })
        rec = text(24, REC, Art.inter600).apply { text = "● Идёт запись"; visibility = View.INVISIBLE }
        listenBox.addView(rec, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(16) })
        say = text(30, SOFT, Art.inter500).apply { text = "Говорите" }
        listenBox.addView(say, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(26) })
        heard = text(42, INK, Art.inter500).apply { isSingleLine = false; gravity = Gravity.CENTER; maxWidth = p(800) }
        listenBox.addView(heard, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(18) })
        // ряд кнопок во всю ширину карточки: иначе он сжимается до ширины подписи и обрезает кнопки
        btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lCap = caption()
        sendBtn = pillButton("send", "Отправить", lCap) { send() }
        row.addView(sendBtn, LinearLayout.LayoutParams(-2, p(92)).apply { rightMargin = p(14) })
        row.addView(roundButton("mic", "Надиктовать заново", lCap) { listen() }, LinearLayout.LayoutParams(p(92), p(92)).apply { leftMargin = p(10); rightMargin = p(10) })
        row.addView(roundButton("close", "Отмена", lCap) { finish() }, LinearLayout.LayoutParams(p(92), p(92)).apply { leftMargin = p(10) })
        btns.addView(row, LinearLayout.LayoutParams(-1, -2))
        btns.addView(lCap, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(16) })
        listenBox.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p(30) })
        card.addView(listenBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, FrameLayout.LayoutParams(p(900), -2, Gravity.CENTER))
        setContentView(root)
        shown = Chats.showText(this)
        if (intent.getBooleanExtra("view", false)) {
            listenBox.visibility = View.GONE; paintMessages(); setEye(eyeBtn, vCap)
            (if (c.reply != null) replyBtn else eyeBtn).requestFocus()
        } else listen()
    }

    override fun onResume() { super.onResume(); Home.replying = true }
    override fun onPause() { super.onPause(); Home.replying = false }
    override fun onDestroy() { super.onDestroy(); sr?.destroy() }

    private fun text(size: Int, color: Int, face: android.graphics.Typeface) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, p(size).toFloat()); setTextColor(color); typeface = face; includeFontPadding = false
    }

    /** Глаз: показать или скрыть текст сообщений. */
    private fun setEye(b: FrameLayout, cap: TextView) {
        val ic = b.getChildAt(0) as IconView
        b.removeViewAt(0); b.addView(IconView(this, if (shown) "eyeoff" else "eye").apply { color = ic.color }, 0, ic.layoutParams)
        b.tag = if (shown) "Скрыть текст" else "Показать текст"
        if (b.isFocused) cap.text = b.tag as String
    }

    /** Главная кнопка: значок и надпись. */
    private fun pillButton(icon: String, label: String, cap: TextView, act: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
        setPadding(p(30), 0, p(36), 0)
        val ic = IconView(this@ReplyActivity, icon).apply { color = INK }
        addView(ic, LinearLayout.LayoutParams(p(34), p(34)).apply { rightMargin = p(14) })
        val tx = text(28, INK, Art.inter600).apply { text = label }
        addView(tx)
        fun paint(f: Boolean) {
            background = GradientDrawable().apply { setColor(if (f) INK else Color.argb(28, 236, 242, 248)); cornerRadius = p(46).toFloat() }
            tx.setTextColor(if (f) ON_FOCUS else INK); ic.color = if (f) ON_FOCUS else INK
            if (f) cap.text = ""
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { act() }
    }

    private fun caption() = text(24, INK, Art.inter500).apply { gravity = Gravity.CENTER }

    /** Круглая кнопка-значок; подпись выделенной кнопки — одна строка под рядом. */
    private fun roundButton(icon: String, label: String, cap: TextView, act: () -> Unit) = FrameLayout(this).apply {
        isFocusable = true; isClickable = true; tag = label
        val ic = IconView(this@ReplyActivity, icon).apply { color = INK }
        addView(ic, FrameLayout.LayoutParams(p(38), p(38), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(p(92), p(92)).apply { leftMargin = p(10); rightMargin = p(10) }
        fun paint(f: Boolean) {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (f) INK else Color.argb(28, 236, 242, 248)) }
            (getChildAt(0) as? IconView)?.color = if (f) ON_FOCUS else INK
            if (f) cap.text = tag as String
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { act() }
    }

    private fun paintMessages() {
        val c = chat ?: return
        msgsBox.removeAllViews()
        for ((sender, t) in c.history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p(24), p(16), p(24), p(16)); isFocusable = true
                fun paint(f: Boolean) {
                    background = GradientDrawable().apply {
                        setColor(Color.argb(if (f) 46 else 20, 236, 242, 248)); cornerRadius = p(18).toFloat()
                        if (f) setStroke(p(2), INK)
                    }
                }
                paint(false); setOnFocusChangeListener { _, f -> paint(f) }
            }
            if (sender != null && sender != c.name) row.addView(text(22, SOFT, Art.inter600).apply { text = sender })
            row.addView(text(30, if (shown) INK else SOFT, if (shown) Art.inter400 else Art.inter500).apply {
                text = if (shown) t else "Текст скрыт"; isSingleLine = false; maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            msgsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p(10) })
        }
    }

    private fun listen() {
        viewBox.visibility = View.GONE; listenBox.visibility = View.VISIBLE
        intent.getStringExtra("fake")?.let { heardText = Punct.fix(it); heard.text = heardText; done("Отправить: ${chat?.name}?"); return }
        btns.visibility = View.GONE; heardText = ""; heard.text = ""
        say.text = "Подождите…"
        mic.recording = false; rec.visibility = View.INVISIBLE
        android.util.Log.d("tvhome", "речь: старт, доступно=${SpeechRecognizer.isRecognitionAvailable(this)}")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { done("Распознавание речи недоступно"); return }
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(b: Bundle?) { b.best()?.let { heard.text = it } }
                override fun onResults(b: Bundle?) {
                    android.util.Log.d("tvhome", "речь: результат ${b.best()?.length ?: 0} симв.")
                    val t = b.best()
                    if (t.isNullOrBlank()) done("Не расслышал. Можно надиктовать заново")
                    else { heardText = Punct.fix(t); heard.text = heardText; done("Отправить: ${chat?.name}?") }
                }
                override fun onError(error: Int) {
                    android.util.Log.d("tvhome", "речь: ошибка $error")
                    done(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет связи с распознаванием речи (ошибка $error)"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят другим приложением (ошибка $error)"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал. Можно надиктовать заново"
                        else -> "Распознавание не запустилось (ошибка $error)"
                    })
                }
                override fun onReadyForSpeech(p0: Bundle?) {
                    android.util.Log.d("tvhome", "речь: микрофон готов")
                    mic.recording = true; rec.visibility = View.VISIBLE
                    rec.startAnimation(AlphaAnimation(1f, .35f).apply { duration = 600; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE })
                    say.text = "Слушаю, говорите"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(db: Float) { mic.level = ((db + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() { stopRec(); say.text = "Распознаю…" }
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private fun Bundle?.best() = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun stopRec() { mic.recording = false; rec.clearAnimation(); rec.visibility = View.INVISIBLE }

    private fun done(msg: String) {
        stopRec()
        say.text = msg
        btns.visibility = View.VISIBLE
        val row = btns.getChildAt(0) as LinearLayout
        sendBtn?.visibility = if (heardText.isBlank()) View.GONE else View.VISIBLE
        val target = if (heardText.isBlank()) row.getChildAt(1) else sendBtn
        target?.post { target.requestFocus() }
    }

    private fun send() {
        val c = chat ?: return finish()
        val ok = heardText.isNotBlank() && Chats.send(this, c, heardText)
        android.widget.Toast.makeText(this, if (ok) "Отправлено: ${c.name}" else "Не удалось отправить", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Кружок микрофона: серый — ждём, красный со столбиками громкости — идёт запись. */
    private inner class LevelView(ctx: Context) : View(ctx) {
        var recording = false
            set(v) { field = v; if (!v) level = 0f; invalidate() }
        var level = 0f
            set(v) { field = field * .5f + v * .5f; invalidate() }
        private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val shape = floatArrayOf(.45f, .75f, 1f, .75f, .45f)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            bg.color = if (recording) REC else Color.rgb(69, 80, 92)
            c.drawCircle(w / 2, h / 2, w / 2, bg)
            val bw = w * .07f; val gap = w * .05f
            val x0 = w / 2 - (5 * bw + 4 * gap) / 2
            for (i in 0 until 5) {
                val k = if (recording) .18f + level * shape[i] * .82f else .18f
                val bh = h * .5f * k
                val x = x0 + i * (bw + gap)
                c.drawRoundRect(x, h / 2 - bh / 2, x + bw, h / 2 + bh / 2, bw / 2, bw / 2, bar)
            }
        }
    }

    companion object {
        fun start(ctx: Context, chatName: String, view: Boolean = false) = ctx.startActivity(
            Intent(ctx, ReplyActivity::class.java).putExtra("chat", chatName).putExtra("view", view)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
