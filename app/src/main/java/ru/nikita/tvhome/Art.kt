package ru.nikita.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.random.Random

/** Фон верхней полки: мягкие световые пятна оттенка приложения и зерно, как в макете. */
object Art {
    // последние шесть фонов: на каждое приложение 0,6 МБ, без предела они копились бы весь день
    private val cache = object : LinkedHashMap<String, Bitmap>(8, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }

    fun shelf(key: String, color: Int): Bitmap = cache.getOrPut(key) {
        val w = 960; val h = 320
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        val h0 = hsv[0]; val h1 = (hsv[0] + 22) % 360
        val sat = if (hsv[1] < .15f) .15f else .75f // серым приложениям — серый фон
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.HSVToColor(floatArrayOf(h0, sat * .4f, .09f)))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        for ((x, y, r, hue, l) in listOf(
            Blob(700f, 90f, 380f, h0, .58f), Blob(880f, 260f, 300f, h1, .46f),
            Blob(420f, 40f, 260f, h1, .30f), Blob(560f, 300f, 220f, h0, .40f),
        )) {
            val col = Color.HSVToColor(140, floatArrayOf(hue, sat, l + .25f))
            p.shader = RadialGradient(x, y, r, col, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        // зерно
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rnd = Random(key.hashCode())
        for (i in px.indices) {
            val n = rnd.nextInt(-7, 8); val v = px[i]
            px[i] = Color.rgb(
                (Color.red(v) + n).coerceIn(0, 255), (Color.green(v) + n).coerceIn(0, 255), (Color.blue(v) + n).coerceIn(0, 255),
            )
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        // вдвое меньше памяти; зерно скрывает ступеньки градиента
        bmp.copy(Bitmap.Config.RGB_565, false).also { bmp.recycle() }
    }

    private data class Blob(val x: Float, val y: Float, val r: Float, val hue: Float, val l: Float)

    /** Размытие маленького снимка: несколько проходов усреднения по строкам и столбцам. */
    fun blur(b: Bitmap, passes: Int) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, step: Int, lineStep: Int) {
            for (l in 0 until lines) for (i in 0 until len) {
                var r = 0; var g = 0; var bl = 0; var n = 0
                for (k in -2..2) {
                    val j = i + k
                    if (j < 0 || j >= len) continue
                    val v = src[l * lineStep + j * step]
                    r += (v shr 16) and 255; g += (v shr 8) and 255; bl += v and 255; n++
                }
                dst[l * lineStep + i * step] = Color.rgb(r / n, g / n, bl / n)
            }
        }
        repeat(passes) { pass(px, tmp, w, h, 1, w); pass(tmp, px, h, w, w, 1) }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    var inter400: Typeface = Typeface.DEFAULT
    var inter500: Typeface = Typeface.DEFAULT
    var inter600: Typeface = Typeface.DEFAULT_BOLD

    fun loadFonts(ctx: Context) {
        inter400 = ctx.resources.getFont(R.font.inter_400)
        inter500 = ctx.resources.getFont(R.font.inter_500)
        inter600 = ctx.resources.getFont(R.font.inter_600)
    }
}

/** Залитый значок из набора «Полка» (GlyphDefs) в сетке 24, перекрашивается фокусом. */
class IconView(ctx: Context, private val kind: String) : View(ctx) {
    var color = Color.WHITE
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        Glyphs.draw(c, kind, width.toFloat(), color)
    }
}

/** Разбор разметки значков один раз и отрисовка: формы, вырезы слоем с CLEAR, детали поверх. */
object Glyphs {
    private class Prim(val path: Path, val fill: Boolean, val stroke: Float)
    private class Glyph(val f: List<Prim>, val c: List<Prim>, val o: List<Prim>)

    private val cache = HashMap<String, Glyph?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun draw(c: Canvas, kind: String, size: Float, color: Int) {
        val g = cache.getOrPut(kind) { GlyphDefs.all[kind]?.let { Glyph(parse(it.f, 2.52f), parse(it.c, 1.5f), parse(it.o, 2.52f)) } } ?: return
        c.save()
        c.scale(size / 24f, size / 24f)
        c.translate(.588f, .588f); c.scale(.951f, .951f) // рисунок ужат, чтобы мягкая обводка не вышла за поле
        paint.color = color
        val layer = if (g.c.isNotEmpty()) c.saveLayer(-2f, -2f, 26f, 26f, null) else -1
        g.f.forEach { put(c, it, paint) }
        g.c.forEach { put(c, it, clear) }
        if (layer >= 0) c.restoreToCount(layer)
        g.o.forEach { put(c, it, paint) }
        c.restore()
    }

    private fun put(c: Canvas, p: Prim, paint: Paint) {
        paint.style = when {
            p.fill && p.stroke > 0f -> Paint.Style.FILL_AND_STROKE
            p.fill -> Paint.Style.FILL
            else -> Paint.Style.STROKE
        }
        paint.strokeWidth = p.stroke
        c.drawPath(p.path, paint)
    }

    private val tag = Regex("""<(circle|rect|path)\s([^>]*?)/>""")
    private val attr = Regex("""([a-z-]+)="([^"]*)"""")

    private fun parse(src: String?, stroke: Float): List<Prim> = src?.let { s ->
        tag.findAll(s).map { m ->
            val a = attr.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            fun n(k: String) = a[k]?.toFloatOrNull() ?: 0f
            val path = Path()
            when (m.groupValues[1]) {
                "circle" -> path.addCircle(n("cx"), n("cy"), n("r"), Path.Direction.CW)
                "rect" -> path.addRoundRect(n("x"), n("y"), n("x") + n("width"), n("y") + n("height"), n("rx"), n("rx"), Path.Direction.CW)
                else -> svgPath(a["d"] ?: "", path)
            }
            var scale = 1f
            a["transform"]?.let { t ->
                val mx = android.graphics.Matrix()
                Regex("""(rotate|translate|scale)\(([^)]*)\)""").findAll(t).forEach { op ->
                    val v = op.groupValues[2].trim().split(Regex("[ ,]+")).map { it.toFloat() }
                    when (op.groupValues[1]) {
                        "rotate" -> mx.preRotate(v[0], v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                        "translate" -> mx.preTranslate(v[0], v.getOrElse(1) { 0f })
                        "scale" -> { mx.preScale(v[0], v.getOrElse(1) { v[0] }); scale *= v[0] }
                    }
                }
                path.transform(mx)
            }
            val sw = when { a["stroke"] == "none" -> 0f; a["stroke-width"] != null -> n("stroke-width"); else -> stroke }
            Prim(path, a["fill"] != "none", sw * scale)
        }.toList()
    } ?: emptyList()

    /** Подмножество команд SVG-пути: M L H V C S Q A Z и их относительные варианты. */
    private fun svgPath(d: String, p: Path) {
        val tok = Regex("""[MmLlHhVvCcSsQqAaZz]|-?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f; var cx2 = 0f; var cy2 = 0f
        fun f() = tok[i++].toFloat()
        while (i < tok.size) {
            if (tok[i][0].isLetter()) cmd = tok[i++][0]
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { x = ox + f(); y = oy + f(); p.moveTo(x, y); sx = x; sy = y; cmd = if (rel) 'l' else 'L' }
                'L' -> { x = ox + f(); y = oy + f(); p.lineTo(x, y) }
                'H' -> { x = ox + f(); p.lineTo(x, y) }
                'V' -> { y = oy + f(); p.lineTo(x, y) }
                'C' -> {
                    val x1 = ox + f(); val y1 = oy + f(); cx2 = ox + f(); cy2 = oy + f(); x = ox + f(); y = oy + f()
                    p.cubicTo(x1, y1, cx2, cy2, x, y); continue
                }
                'S' -> {
                    val x1 = 2 * x - cx2; val y1 = 2 * y - cy2; cx2 = ox + f(); cy2 = oy + f(); x = ox + f(); y = oy + f()
                    p.cubicTo(x1, y1, cx2, cy2, x, y); continue
                }
                'Q' -> { val x1 = ox + f(); val y1 = oy + f(); x = ox + f(); y = oy + f(); p.quadTo(x1, y1, x, y) }
                'A' -> {
                    val r0 = f(); f(); f(); val large = f() != 0f; val sweep = f() != 0f
                    val nx = ox + f(); val ny = oy + f()
                    arc(p, x, y, r0, large, sweep, nx, ny); x = nx; y = ny
                }
                'Z' -> { p.close(); x = sx; y = sy }
            }
            cx2 = x; cy2 = y
        }
    }

    /** Дуга SVG (rx = ry, без поворота) через центр окружности. */
    private fun arc(p: Path, x0: Float, y0: Float, r0: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        var r = r0.toDouble()
        val lam = (dx * dx + dy * dy) / (r * r)
        if (lam > 1) r *= Math.sqrt(lam)
        val sign = if (large == sweep) -1.0 else 1.0
        val k = sign * Math.sqrt(maxOf(0.0, (r * r - dx * dx - dy * dy) / (dx * dx + dy * dy)))
        val cxp = k * dy; val cyp = -k * dx
        val cx = cxp + (x0 + x) / 2.0; val cy = cyp + (y0 + y) / 2.0
        val t1 = Math.atan2((dy - cyp) / r, (dx - cxp) / r)
        var dt = Math.atan2((-dy - cyp) / r, (-dx - cxp) / r) - t1
        if (!sweep && dt > 0) dt -= 2 * Math.PI
        if (sweep && dt < 0) dt += 2 * Math.PI
        p.arcTo(android.graphics.RectF((cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat()),
            Math.toDegrees(t1).toFloat(), Math.toDegrees(dt).toFloat(), false)
    }
}

/**
 * Плитка без баннера. Современный значок (фон и рисунок отдельно) растягиваем: фон — на всю плитку,
 * рисунок — крупно по центру. Старый квадратный значок — крупный, со скруглением, на фоне его цвета.
 */
class IconTile(private val icon: Drawable, private val color: Int, private val label: String = "") : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds
        val adaptive = icon as? android.graphics.drawable.AdaptiveIconDrawable
        if (adaptive?.background != null && adaptive.foreground != null) {
            // фон значка без масштаба искажает рисунок — тянем по ширине и обрезаем
            val fg = adaptive.foreground!!; val back = adaptive.background!!
            back.setBounds(b.left - b.width() / 4, b.top - b.width() / 2 + b.height() / 2 - b.width() / 4,
                b.right + b.width() / 4, b.top + b.height() / 2 + b.width() / 2 + b.width() / 4)
            c.save(); c.clipRect(b); back.draw(c); c.restore()
            val s = (b.height() * 1.25f).toInt() // у рисунка большие поля по краям
            fg.setBounds(b.centerX() - s / 2, b.centerY() - s / 2, b.centerX() + s / 2, b.centerY() + s / 2)
            c.save(); c.clipRect(b); fg.draw(c); c.restore()
            return
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        val top = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * .8f, (hsv[2] * .55f).coerceAtLeast(.18f)))
        val bot = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], (hsv[2] * .85f).coerceAtLeast(.28f)))
        bg.shader = android.graphics.LinearGradient(0f, 0f, b.width().toFloat(), b.height().toFloat(), top, bot, Shader.TileMode.CLAMP)
        c.drawRect(b, bg)
        val s = (b.height() * .62f).toInt()
        val l = b.centerX() - s / 2; val t = b.centerY() - s / 2
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp); icon.setBounds(0, 0, s, s); icon.draw(bc)
        // скругление квадратных значков
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        c.save(); c.translate(l.toFloat(), t.toFloat()); c.drawRoundRect(0f, 0f, s.toFloat(), s.toFloat(), s * .22f, s * .22f, p); c.restore()
        bmp.recycle()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
