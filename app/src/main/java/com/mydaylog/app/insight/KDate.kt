package com.mydaylog.app.insight

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** 문장 속 한국어 날짜·시간 표현 찾기 (폰 안 규칙) */
data class When(val date: LocalDate?, val time: LocalTime?, val dateText: String? = null)

enum class DateKind { ABSOLUTE, WEEKDAY, WEEKDAY_BARE, WEEKEND, RELATIVE, D_MINUS, DAY_ONLY, WEEK, MONTH_END }

data class DateHit(val date: LocalDate, val text: String, val kind: DateKind)

object KDate {
    private val full = Regex("""(20\d{2})\s?[.\-/년]\s?(\d{1,2})\s?[.\-/월]\s?(\d{1,2})\s?일?""")
    private val monthDay = Regex("""(?<!\d)(\d{1,2})\s?월\s?(\d{1,2})\s?일""")
    private val slash = Regex("""(?<![\d/.])(\d{1,2})/(\d{1,2})(?![\d/])""")
    private val dayOnly = Regex("""(?<![\d월/.\-])(\d{1,2})\s?일(?!\s?(동안|간|째|치|차|부터\s?\d))""")
    /** 2.3: "11.30(월)", "~11.27", "12.1까지" (소수점과 헷갈리지 않게 뒤에 오는 말을 확인) */
    private val dot = Regex("""(?<![\d.])(\d{1,2})\.\s?(\d{1,2})(?![\d.])(?=\s?\(|\s?까지|\s?일|\s?[월화수목금토일]요일|\s?~|\s?-|\s?부터|\s?$|\s?\n)""")
    private val rel = Regex("""(오늘|금일|내일|낼|명일|익일|모레|글피)""")
    private val week = Regex("""(이번\s?주|이번주|금주|다음\s?주|담주|차주)\s?(중|내|안|까지|중으로|내로)""")
    private val monthEnd = Regex("""(이번\s?달|이달|다음\s?달|담달)?\s?(월말|말일|달\s?말|연말)""")
    private val weekday = Regex("""(이번\s?주|이번주|다음\s?주|담주|다다음\s?주)?\s?(월|화|수|목|금|토|일)요일""")
    private val weekend = Regex("""(이번\s?주|다음\s?주|담주)?\s?주말""")
    private val dMinus = Regex("""D\s?-\s?(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val hm = Regex("""(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])""")
    private val korTime = Regex("""(오전|오후|아침|점심|저녁|밤|새벽|낮)?\s?(\d{1,2})\s?시(?!간|\s?방향)\s?(반|(\d{1,2})\s?분)?""")
    /** "7시 말고 8시", "8시로 바꿔" → 마지막 시간을 씀 */
    private val change = Regex("""말고|대신|변경|바꿔|바꾸|미뤄|미루|당겨|늦춰|로 하자|으로 하자|로 해요|으로 해요""")

    private val dows = mapOf(
        "월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY, "목" to DayOfWeek.THURSDAY,
        "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY,
    )

    private fun safe(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun isChange(text: String) = change.containsMatchIn(text)

    fun findDateHit(text: String, base: LocalDate): DateHit? {
        full.find(text)?.let { m ->
            safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return DateHit(it, m.value, DateKind.ABSOLUTE) }
        }
        fun yearFor(month: Int, day: Int): LocalDate? {
            val d = safe(base.year, month, day) ?: return null
            return if (d.isBefore(base.minusDays(30))) safe(base.year + 1, month, day) else d
        }
        monthDay.find(text)?.let { m -> yearFor(m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { return DateHit(it, m.value, DateKind.ABSOLUTE) } }
        slash.find(text)?.let { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
            if (mo in 1..12 && d in 1..31) yearFor(mo, d)?.let { return DateHit(it, m.value, DateKind.ABSOLUTE) }
        }
        dot.find(text)?.let { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
            if (mo in 1..12 && d in 1..31) yearFor(mo, d)?.let { return DateHit(it, m.value, DateKind.ABSOLUTE) }
        }
        val thisWeekMon = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // 여러 요일이 나오면 바꾸는 말("토요일 말고 일요일")에서는 마지막 것을 씀
        val wds = weekday.findAll(text).toList()
        (if (isChange(text)) wds.lastOrNull() else wds.firstOrNull())?.let { m ->
            val dow = dows.getValue(m.groupValues[2])
            val mod = m.groupValues[1].replace(" ", "")
            val (date, kind) = when {
                mod.startsWith("다다음") -> thisWeekMon.plusWeeks(2).with(TemporalAdjusters.nextOrSame(dow)) to DateKind.WEEKDAY
                mod.startsWith("다음") || mod == "담주" -> thisWeekMon.plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow)) to DateKind.WEEKDAY
                mod.startsWith("이번") -> thisWeekMon.with(TemporalAdjusters.nextOrSame(dow)) to DateKind.WEEKDAY
                else -> base.with(TemporalAdjusters.nextOrSame(dow)) to DateKind.WEEKDAY_BARE
            }
            return DateHit(date, m.value, kind)
        }
        weekend.find(text)?.let { m ->
            val mod = m.groupValues[1].replace(" ", "")
            val sat = if (mod.startsWith("다음") || mod == "담주") thisWeekMon.plusWeeks(1).with(DayOfWeek.SATURDAY)
            else if (base.dayOfWeek == DayOfWeek.SUNDAY) base else base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            return DateHit(sat, m.value, DateKind.WEEKEND)
        }
        rel.find(text)?.let { m ->
            val d = when (m.value) {
                "오늘", "금일" -> base; "내일", "낼", "명일", "익일" -> base.plusDays(1); "모레" -> base.plusDays(2); else -> base.plusDays(3)
            }
            return DateHit(d, m.value, DateKind.RELATIVE)
        }
        // 주 단위 기한: 그 주 금요일
        week.find(text)?.let { m ->
            val mod = m.groupValues[1].replace(" ", "")
            val w = if (mod.startsWith("다음") || mod == "담주" || mod == "차주") thisWeekMon.plusWeeks(1) else thisWeekMon
            return DateHit(w.with(DayOfWeek.FRIDAY), m.value, DateKind.WEEK)
        }
        // 월말·연말
        monthEnd.find(text)?.let { m ->
            val d = when {
                m.groupValues[2] == "연말" -> LocalDate.of(base.year, 12, 31)
                m.groupValues[1].replace(" ", "").let { it.startsWith("다음") || it == "담달" } -> base.plusMonths(1).let { it.withDayOfMonth(it.lengthOfMonth()) }
                else -> base.withDayOfMonth(base.lengthOfMonth())
            }
            return DateHit(d, m.value, DateKind.MONTH_END)
        }
        dMinus.find(text)?.let { m -> return DateHit(base.plusDays(m.groupValues[1].toLong()), m.value, DateKind.D_MINUS) }
        dayOnly.find(text)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) {
                val cand = safe(base.year, base.monthValue, d)
                val date = if (cand == null || cand.isBefore(base)) base.plusMonths(1).let { safe(it.year, it.monthValue, d) } else cand
                if (date != null) return DateHit(date, m.value, DateKind.DAY_ONLY)
            }
        }
        return null
    }

    fun findDate(text: String, base: LocalDate): Pair<LocalDate, String>? = findDateHit(text, base)?.let { it.date to it.text }

    /** explicit = 오전/오후·저녁 같은 말이나 14:30처럼 시각이 분명함 */
    data class TimeHit(val time: LocalTime, val explicit: Boolean)

    private fun parseKor(m: MatchResult): LocalTime? {
        var h = m.groupValues[2].toInt()
        if (h !in 0..24) return null
        val min = when {
            m.groupValues[3] == "반" -> 30
            m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt().coerceIn(0, 59)
            else -> 0
        }
        when (m.groupValues[1]) {
            "오후", "저녁", "밤" -> if (h < 12) h += 12
            "점심", "낮" -> if (h < 6) h += 12
            "오전", "아침", "새벽" -> if (h == 12) h = 0
            else -> if (h in 1..7) h += 12
        }
        if (h == 24) h = 0
        return LocalTime.of(h, min)
    }

    fun findTimeHits(text: String): List<TimeHit> {
        val hits = ArrayList<Pair<Int, TimeHit>>()
        hm.findAll(text).forEach { hits += it.range.first to TimeHit(LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()), true) }
        korTime.findAll(text).forEach { m -> parseKor(m)?.let { hits += m.range.first to TimeHit(it, m.groupValues[1].isNotEmpty()) } }
        return hits.sortedBy { it.first }.map { it.second }
    }

    fun findTimes(text: String): List<LocalTime> = findTimeHits(text).map { it.time }

    /**
     * 시간 하나 고르기: 바꾸는 말이 있으면 마지막, 아니면 처음.
     * "7시 말고 8시"처럼 바뀐 시간에 오전/오후가 없으면 앞 시간의 오후를 이어받음
     */
    fun findTimeHit(text: String, prev: LocalTime? = null): TimeHit? {
        val all = findTimeHits(text)
        if (all.isEmpty()) return null
        if (!isChange(text)) return all.first()
        val last = all.last()
        val ref = prev ?: all.dropLast(1).lastOrNull()?.time
        return pmCarry(last, ref)
    }

    /** 오전/오후가 없는 8~11시는 앞서 오후 시간이 나왔으면 오후로 */
    fun pmCarry(h: TimeHit, ref: LocalTime?): TimeHit =
        if (!h.explicit && h.time.hour in 8..11 && ref != null && ref.hour >= 12) TimeHit(h.time.plusHours(12), false) else h

    fun findTime(text: String): LocalTime? = findTimeHit(text)?.time

    fun find(text: String, base: LocalDateTime): When {
        val d = findDateHit(text, base.toLocalDate())
        return When(d?.date, findTime(text), d?.text)
    }
}
