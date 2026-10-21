package ru.nikita.tvhome

/**
 * Знаки препинания для распознанной речи — распознавание на телевизоре отдаёт текст без них.
 * Простые правила: заглавная в начале, запятая после обращения и перед союзами, «?» для вопросов, иначе точка.
 * Слова с ошибками так не исправить — только расстановка знаков.
 */
object Punct {
    private val lead = setOf("привет", "здравствуй", "здравствуйте", "слушай", "смотри", "кстати", "короче",
        "ну", "да", "нет", "ок", "окей", "хорошо", "спасибо", "понял", "поняла", "ладно", "извини", "блин")
    private val ask = setOf("как", "что", "где", "когда", "почему", "зачем", "кто", "сколько", "куда", "откуда",
        "какой", "какая", "какое", "какие", "чей", "можно", "можешь", "сможешь", "будешь", "есть")
    private val before = listOf("потому что", "так как", "чтобы", "что", "но", "а", "если", "когда", "который",
        "которая", "которое", "которые", "где", "поэтому", "хотя", "зато")

    fun fix(raw: String): String {
        var words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) return raw
        if (words.last().last() in ".!?") return capFirst(raw.trim())
        // распознавание ставит заглавные внутри фразы — оставляем их только у имён, которых не знаем
        words = words.mapIndexed { i, w -> if (i > 0 && w.lowercase() in lead + ask + before) w.lowercase() else w }.toMutableList()
        val low = words.map { it.lowercase() }
        var question = low.drop(if (low[0] in lead && low.size > 1) 1 else 0).firstOrNull() in ask || "ли" in low
        val out = StringBuilder()
        for (i in words.indices) {
            val w = words[i]; val lw = low[i]
            if (i > 0) {
                val two = if (i + 1 < low.size) "$lw ${low[i + 1]}" else ""
                val needComma = (i == 1 && low[0] in lead) ||
                    (lw in before && !(lw == "что" && low[i - 1] == "потому") && !(lw == "как" )) || two in before
                if (needComma && !out.endsWith(",")) out.append(',')
                out.append(' ')
            }
            out.append(w)
        }
        if (low.first() in ask) question = true
        return capFirst(out.toString()) + if (question) "?" else "."
    }

    private fun capFirst(s: String) = s.replaceFirstChar { it.uppercaseChar() }
}
