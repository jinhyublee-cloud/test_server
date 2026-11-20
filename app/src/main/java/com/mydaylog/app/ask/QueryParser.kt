package com.mydaylog.app.ask

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Topic(val label: String) {
    PAY("결제"), DEPOSIT("입금"), DEADLINE("마감"), CALL("통화"), MAIL("메일"), SMS("문자"),
    PLAN("일정"), KAKAO("카톡"), ALL("기록"),
}

/** 질문을 기간·주제·검색어로 나눈 결과 */
data class AskQuery(
    val text: String,
    val from: LocalDate,
    val to: LocalDate,
    val label: String,
    val topic: Topic,
    val keywords: List<String>,
    /** 질문에 기간이 있었는지 (없으면 기본 기간) */
    val dateSaid: Boolean,
    val wantWhere: Boolean,
    val wantTotal: Boolean,
) {
    val singleDay get() = from == to
    val days get() = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
}

/**
 * 한국어 질문 해석 (규칙 기반, 폰 안에서만 동작)
 * 예: "어제 어디서 결제했어?" → 어제 · 결제
 *     "지난주 금요일 김민수랑 통화했나?" → 11/14 · 통화 · [김민수]
 *     "스타벅스 언제 갔지?" → 최근 90일 · 기록 · [스타벅스]
 */
object QueryParser {
    const val SEARCH_DAYS = 90L

    private val wd = mapOf('월' to DayOfWeek.MONDAY, '화' to DayOfWeek.TUESDAY, '수' to DayOfWeek.WEDNESDAY,
        '목' to DayOfWeek.THURSDAY, '금' to DayOfWeek.FRIDAY, '토' to DayOfWeek.SATURDAY, '일' to DayOfWeek.SUNDAY)

    private val topicRes = listOf(
        Topic.DEPOSIT to Regex("""입금|받은\s?돈|돈\s?받|송금\s?받|월급|급여"""),
        Topic.PAY to Regex("""결제|샀|산\s?거|구매|썼|쓴\s?(돈|곳|데|거)|많이\s?쓴|지출|카드|얼마|돈|계산했|소비|승인|사용\s?(금액|내역|액)|씀씀이|가계부"""),
        Topic.DEADLINE to Regex("""마감|기한|쿠폰|만료|유효\s?기간|기프티콘|D-\d"""),
        Topic.CALL to Regex("""전화|통화|부재중"""),
        Topic.MAIL to Regex("""메일"""),
        Topic.SMS to Regex("""문자"""),
        Topic.PLAN to Regex("""일정|약속|스케줄|미팅|회의|만나|만났|행사|모임|회식|예약"""),
        Topic.KAKAO to Regex("""카톡|카카오톡|단톡|톡방|대화방"""),
    )

    /** 검색어가 될 수 없는 일반 단어 (이름·가게가 아닌 말) */
    private val generic = setOf(
        "이력", "내역", "내용", "목록", "리스트", "현황", "상황", "정보", "기록", "자료", "데이터", "결과", "통계", "분석",
        "총합", "합계", "총액", "총계", "금액", "액수", "가격", "비용", "요금", "건수", "횟수", "개수", "숫자", "전체", "전부",
        "가장", "제일", "많이", "많은", "적게", "적은", "비싼", "싼", "큰", "작은", "높은", "낮은", "최고", "최저", "순위", "순서",
        "처음", "마지막", "최근", "최신", "주로", "자주", "보통", "대충", "대략", "간단히", "자세히", "상세히", "한눈에",
        "확인", "조회", "검색", "정리", "요약", "보고", "알림", "메시지", "소식", "연락", "일과", "하루", "일상", "활동",
        "중요한", "중요", "급한", "새로운", "새", "다른", "모든", "각각", "별로", "단위", "기준", "시간", "날짜", "시각",
        "장소", "곳", "데", "가게", "매장", "상점", "카드사", "은행", "사용", "이용", "사용한", "이용한", "사용처",
        "들어온", "나간", "받은", "보낸", "온", "간", "있던", "있었던", "했던", "봤던", "쓴", "산", "한", "된",
        "뭐야", "뭔지", "뭐였지", "뭐였어", "뭐니", "얼만지", "얼마야", "얼마지", "어땠어", "어때", "어떻게", "왜",
        "해", "줘", "부탁", "부탁해", "궁금", "궁금해", "알고", "싶어", "싶은데", "보고싶어", "확인해줘", "통", "명", "곳은",
    )

    private val stop = setOf(
        "뭐", "뭘", "무엇", "무슨", "언제", "어디", "어디서", "어디에서", "얼마", "얼마나", "몇", "몇번", "몇건", "몇개",
        "나", "내", "내가", "제가", "저", "좀", "그날", "날", "기록", "건", "번", "개", "총", "합계", "합", "전체", "모두", "전부", "다",
        "누구", "누구랑", "누구와", "누구한테", "정리", "요약", "내용", "관련", "얘기", "대화", "알림", "목록", "리스트",
        "어떤", "있는", "있던", "했던", "온", "받은", "보낸", "거", "것", "중", "때", "쯤", "정도", "혹시", "그", "이", "저기",
        "오전", "오후", "아침", "점심", "저녁", "밤", "새벽", "하루", "동안", "사이", "부터", "까지", "최근", "요즘",
        "보여줘", "알려줘", "찾아줘", "말해줘", "해줘", "줘", "주세요", "있어", "있나", "있었어", "있었나", "있니", "없어",
        "했어", "했나", "했니", "했지", "했는지", "했을까", "했던가", "했어요", "갔어", "갔지", "왔어", "왔나",
    )
    private val josa = Regex("""(에서는|에서도|에서|에게서|에게|한테서|한테|께서|이랑|랑|하고|으로는|으로|로|에는|에도|에|의|을|를|은|는|이|가|도|만|과|와|께|보다|처럼|이야|야)$""")
    private val verbish = Regex("""(했|었|았|였|됐|봤|갔|왔|샀|썼|줘|있|없|알려|보여|찾아|해요|하니|할까|인가|이야|인지|나요|던가|까요|는지)[가-힣]{0,3}$""")
    private val dateWord = Regex("""^(오늘|금일|어제|그제|그저께|엊그제|내일|모레|지난|저번|이번|다음|이번주|지난주|저번주|다음주|이번달|지난달|저번달|다음달|올해|작년|주말|평일|[월화수목금토일]요일|주|달|월|일|전|후)$""")

    fun parse(q: String, today: LocalDate): AskQuery {
        val text = q.trim()
        val topic = topicRes.firstOrNull { it.second.containsMatchIn(text) }?.first ?: Topic.ALL
        val future = topic == Topic.PLAN || topic == Topic.DEADLINE
        val range = dateRange(text, today, future)
        val keywords = keywords(text)
        val (from, to, label) = range ?: when {
            keywords.isNotEmpty() ->
                Triple(today.minusDays(SEARCH_DAYS - 1), today, "최근 ${SEARCH_DAYS}일")
            topic == Topic.PLAN -> Triple(today, today.plusDays(6), "오늘부터 일주일")
            topic == Topic.DEADLINE -> Triple(today, today.plusDays(30), "앞으로 30일")
            else -> Triple(today, today, "오늘")
        }
        return AskQuery(
            text, from, to, label, topic, keywords, range != null,
            wantWhere = Regex("""어디""").containsMatchIn(text),
            wantTotal = Regex("""총|합계|얼마|전부|모두|다\s?합""").containsMatchIn(text),
        )
    }

    /** 검색어를 바꾼 질문 (기간을 말하지 않았으면 기간도 그 검색어 기준 기본값으로) */
    fun withKeywords(q: AskQuery, kws: List<String>, today: LocalDate): AskQuery {
        if (q.dateSaid) return q.copy(keywords = kws)
        val (from, to, label) = when {
            kws.isNotEmpty() -> Triple(today.minusDays(SEARCH_DAYS - 1), today, "최근 ${SEARCH_DAYS}일")
            q.topic == Topic.PLAN -> Triple(today, today.plusDays(6), "오늘부터 일주일")
            q.topic == Topic.DEADLINE -> Triple(today, today.plusDays(30), "앞으로 30일")
            else -> Triple(today, today, "오늘")
        }
        return q.copy(from = from, to = to, label = label, keywords = kws)
    }

    // ---------------- 기간 ----------------
    private fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
    fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        today.plusDays(1) -> "내일"
        else -> "${d.monthValue}월 ${d.dayOfMonth}일(${"월화수목금토일"[d.dayOfWeek.value - 1]})"
    }

    private fun one(d: LocalDate, today: LocalDate) = Triple(d, d, dayLabel(d, today))
    private fun span(a: LocalDate, b: LocalDate, name: String) = Triple(a, b, "$name(${md(a)}~${md(b)})")

    private fun weekOf(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }

    fun dateRange(t: String, today: LocalDate, future: Boolean): Triple<LocalDate, LocalDate, String>? {
        val s = t.replace(" ", "")
        fun has(r: String) = Regex(r).containsMatchIn(s)

        // 1) 주 단위 (+ 요일)
        Regex("""(지난|저번|이번|다음|담)주(?:의)?(?:([월화수목금토일])요일)?""").find(s)?.let { m ->
            val shift = when (m.groupValues[1]) { "지난", "저번" -> -1L; "이번" -> 0L; else -> 1L }
            val (mon, sun) = weekOf(today.plusWeeks(shift))
            val w = m.groupValues[2]
            if (w.isNotEmpty()) {
                return one(mon.with(TemporalAdjusters.nextOrSame(wd[w[0]]!!)), today)
            }
            if (s.contains("주말")) return span(mon.plusDays(5), sun, m.groupValues[1] + "주 주말")
            val name = when (shift) { -1L -> "지난주"; 0L -> "이번 주"; else -> "다음 주" }
            return span(mon, sun, name)
        }
        // 2) 월 단위
        Regex("""(지난|저번|이번|다음|담)달""").find(s)?.let { m ->
            val shift = when (m.groupValues[1]) { "지난", "저번" -> -1L; "이번" -> 0L; else -> 1L }
            val first = today.withDayOfMonth(1).plusMonths(shift)
            val last = first.with(TemporalAdjusters.lastDayOfMonth())
            val name = when (shift) { -1L -> "지난달"; 0L -> "이번 달"; else -> "다음 달" }
            return span(first, if (shift == 0L && !future) today else last, name)
        }
        if (has("올해|올한해|금년")) return span(today.withDayOfYear(1), today, "올해")
        if (has("작년")) {
            val y = today.minusYears(1)
            return span(y.withDayOfYear(1), y.with(TemporalAdjusters.lastDayOfYear()), "작년")
        }
        // 3) 최근 N일/주/달
        Regex("""(?:최근|지난)(\d+|한|두|세|일|이|삼)?(일|주일|주|달|개월)""").find(s)?.let { m ->
            val n = when (val g = m.groupValues[1]) { "", "한", "일" -> 1L; "두", "이" -> 2L; "세", "삼" -> 3L; else -> g.toLong() }
            val days = when (m.groupValues[2]) { "일" -> n; "주일", "주" -> n * 7; else -> n * 30 }.coerceIn(1, 366)
            return span(today.minusDays(days - 1), today, "최근 ${m.groupValues[1]}${m.groupValues[2]}")
        }
        if (has("^최근|최근에|요즘")) return span(today.minusDays(6), today, "최근 일주일")
        // 4) N일 전 / N주 전
        Regex("""(\d+)일전""").find(s)?.let { return one(today.minusDays(it.groupValues[1].toLong()), today) }
        Regex("""(\d+|한|두|세)주전""").find(s)?.let { m ->
            val n = when (m.groupValues[1]) { "한" -> 1L; "두" -> 2L; "세" -> 3L; else -> m.groupValues[1].toLong() }
            val (a, b) = weekOf(today.minusWeeks(n)); return span(a, b, "${n}주 전")
        }
        // 5) 날짜 (11월 14일, 11/14, 11.14, 2026-11-14)
        Regex("""(20\d\d)[.\-/년](\d{1,2})[.\-/월](\d{1,2})""").find(s)?.let { m ->
            return runCatching { one(LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()), today) }.getOrNull()
        }
        Regex("""(\d{1,2})월(\d{1,2})일""").find(s)?.let { m -> return md(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today, future)?.let { one(it, today) } }
        Regex("""(?<![\d.])(\d{1,2})[/.](\d{1,2})(?![\d.])""").find(s)?.let { m -> return md(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today, future)?.let { one(it, today) } }
        Regex("""(\d{1,2})월(?!\d)""").find(s)?.let { m ->
            val mo = m.groupValues[1].toInt()
            if (mo in 1..12) {
                var first = LocalDate.of(today.year, mo, 1)
                if (!future && first.isAfter(today)) first = first.minusYears(1)
                val last = first.with(TemporalAdjusters.lastDayOfMonth())
                return span(first, if (last.isAfter(today) && !future) today else last, "${mo}월")
            }
        }
        // 6) 하루 단위 단어
        when {
            has("그저께|그제|엊그제") -> return one(today.minusDays(2), today)
            has("어제|어저께") -> return one(today.minusDays(1), today)
            has("모레") -> return one(today.plusDays(2), today)
            has("내일|낼") -> return one(today.plusDays(1), today)
            has("오늘|금일|지금까지") -> return one(today, today)
        }
        // 7) 요일만: 과거 질문이면 가장 가까운 지난 그 요일(오늘 포함), 일정이면 다가오는 요일
        Regex("""([월화수목금토일])요일""").find(s)?.let { m ->
            val dow = wd[m.groupValues[1][0]]!!
            val d = if (future) today.with(TemporalAdjusters.nextOrSame(dow)) else today.with(TemporalAdjusters.previousOrSame(dow))
            return one(d, today)
        }
        if (s.contains("주말")) {
            val sat = if (future) today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
            return span(sat, sat.plusDays(1), "주말")
        }
        return null
    }

    /** 연도 없는 날짜: 지난 일을 묻는데 미래 날짜면 작년으로 */
    private fun md(m: Int, d: Int, today: LocalDate, future: Boolean): LocalDate? {
        val x = runCatching { LocalDate.of(today.year, m, d) }.getOrNull() ?: return null
        return when {
            !future && x.isAfter(today) -> x.minusYears(1)
            future && x.isBefore(today.minusDays(60)) -> x.plusYears(1)
            else -> x
        }
    }

    // ---------------- 검색어 ----------------
    fun keywords(q: String): List<String> {
        val topicWords = topicRes.map { it.second }
        return q.split(Regex("""[\s,.?!~·'"“”‘’()\[\]]+"""))
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.any(Char::isDigit) }
            .map { w -> var x = w; repeat(2) { if (x.length > 2) x = x.replace(josa, "") }; x }
            .filter { it.length >= 2 }
            .filterNot { it in stop || it in generic || dateWord.matches(it) }
            .filterNot { w -> topicWords.any { it.containsMatchIn(w) } }
            .filterNot { verbish.containsMatchIn(it) }
            .filterNot { Regex("""^[월화수목금토일]요일""").containsMatchIn(it) || it.endsWith("주말") || it.endsWith("요일") }
            .filterNot { Regex("""^(지난|저번|이번|다음)""").containsMatchIn(it) }
            .distinct().toList()
    }
}
