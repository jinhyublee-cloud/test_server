package com.mydaylog.app.insight

import com.mydaylog.app.data.Classify
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun ldt(ts: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone)
fun epoch(d: LocalDateTime) = d.atZone(zone).toInstant().toEpochMilli()

// ================= 약속 (카톡·문자) =================
data class Plan(
    val at: LocalDateTime, val hasTime: Boolean, val place: String?, val who: String,
    val title: String, val quote: String, val src: String, val msgTs: Long, val pkg: String?,
    /** false = 대화에서 날짜를 찾지 못함 → 사용자가 날짜를 골라야 함 (대화 날짜로 대신하지 않음) */
    val dateKnown: Boolean = true,
    val agreed: Boolean = false,
    val cancelled: Boolean = false,
    /** 같은 대화 흐름(방+시작 시각) 식별용 */
    val thread: String = "",
) {
    val plainKey get() = if (dateKnown) "plan|${at.toLocalDate()}|$who" else "plan|undated|$thread"

    fun toJson(): String = JSONObject().put("at", at.toString()).put("hasTime", hasTime).put("place", place ?: "")
        .put("who", who).put("title", title).put("quote", quote).put("src", src).put("msgTs", msgTs).put("pkg", pkg ?: "")
        .put("dateKnown", dateKnown).put("agreed", agreed).put("cancelled", cancelled).put("thread", thread).toString()

    companion object {
        fun fromJson(s: String): Plan = JSONObject(s).let {
            Plan(
                LocalDateTime.parse(it.getString("at")), it.getBoolean("hasTime"), it.optString("place").ifBlank { null },
                it.getString("who"), it.getString("title"), it.getString("quote"), it.getString("src"),
                it.getLong("msgTs"), it.optString("pkg").ifBlank { null },
                it.optBoolean("dateKnown", true), it.optBoolean("agreed"), it.optBoolean("cancelled"), it.optString("thread"),
            )
        }
    }
}

object PlanFinder {
    /** 2.3: 행사·공지 단어 */
    val eventNoun = Regex("""시무식|종무식|송년회|신년회|회식|워크숍|워크샵|세미나|교육|설명회|간담회|총회|행사|면접|발표회|오리엔테이션|창립기념|기념식|결혼식|돌잔치|장례식|집들이|생일\s?파티|모임|정모|수업|강의|시험|진료|검진|공연|콘서트|상견례|체육대회|야유회|동창회""")
    val cue = Regex("""보자|봐요|봬요|뵙|뵐|봐!|봐~|보는\s?걸로|볼래|볼까|만나|만날|모이|모여|약속|미팅|회의|점심|저녁|식사|밥\s?먹|한잔|한 잔|술\s?마|번개|예약|방문|가자|갈래|갈까|데리러|픽업|참석|진행|개최|""" + eventNoun.pattern)
    private val eventTitle = Regex("""(?:([가-힣A-Za-z]{2,8})\s)?(""" + eventNoun.pattern + """)""")
    private val notEventPrefix = setOf("오늘", "금일", "내일", "명일", "이번", "다음", "공지", "안내", "그럼", "전사", "전체", "올해", "이번주", "다음주")
    private val labelPlace = Regex("""(?:장소|위치|어디)\s*[:：]\s*([^\n]{2,30})""")
    private val place = Regex("""([가-힣A-Za-z0-9]+?(?:역|점|동|관|센터|카페|병원|회의실|빌딩|타워)?(?:\s?\d+번\s?출구|\s?\d+층)?(?:\s[가-힣A-Za-z0-9]+(?:역|점|카페|병원))?)\s?(?:앞|앞에서|에서|로 와|으로 와)""")
    private val notPlace = setOf("오늘", "내일", "모레", "거기", "여기", "저기", "집", "회사", "나", "너", "우리", "같이", "그때", "그럼", "저녁", "점심", "아침", "주말")
    private val meetTitle = Regex("""([가-힣A-Za-z0-9]{2,10})\s?(미팅|회의)""")
    private val notMeetWord = setOf("그럼", "그러면", "내일", "오늘", "다음", "이번", "주간", "오전", "오후")

    fun titleOf(text: String): String? {
        eventTitle.find(text)?.let { m ->
            val w = m.groupValues[1]
            val noun = m.groupValues[2].replace(" ", "")
            return if (w.isEmpty() || w in notEventPrefix || w.endsWith("은") || w.endsWith("는")) noun else "$w $noun"
        }
        meetTitle.find(text)?.let { m ->
            val w = m.groupValues[1]
            return if (w in notMeetWord || KDate.findTime(w) != null || w.endsWith("시") || w.endsWith("요일")) m.groupValues[2] else "$w ${m.groupValues[2]}"
        }
        return when {
            text.contains("저녁") -> "저녁 식사"
            text.contains("점심") -> "점심 식사"
            text.contains("한잔") || text.contains("한 잔") || text.contains("술") -> "술 약속"
            text.contains("예약") -> "예약"
            text.contains("미팅") || text.contains("회의") -> "회의"
            else -> null
        }
    }

    fun placeOf(text: String): String? {
        labelPlace.find(text)?.let { return it.groupValues[1].trim().trimEnd('.', ',') }
        val m = place.find(text) ?: Regex("""([가-힣A-Za-z0-9]+(?:강당|회의실|홀|연회장|교육장))(?:으로|로)""").find(text) ?: return null
        val p = m.groupValues[1].trim()
        if (p.length < 2 || p in notPlace || KDate.findTime(p) != null || p.endsWith("요일") || p.endsWith("시")) return null
        return p
    }

    /** 문자(예약 확정 안내 등): 한 통 안에 날짜가 분명히 있을 때만 */
    fun find(e: Entry): Plan? {
        if (e.source != Source.SMS) return null
        if (Classify.isAd(e)) return null
        val text = e.text
        if (!cue.containsMatchIn(text)) return null
        if (ChatPlanner.past.containsMatchIn(text)) return null
        val msg = ldt(e.ts)
        val hit = KDate.findDateHit(text, msg.toLocalDate()) ?: return null
        val time = KDate.findTime(text)
        val at = hit.date.atTime(time ?: LocalTime.of(12, 0))
        if (at.isBefore(msg.minusHours(1)) || at.isAfter(msg.plusDays(120))) return null
        return Plan(at, time != null, placeOf(text), e.title, titleOf(text) ?: "약속", "${e.title}: ${text.replace('\n', ' ').take(80)}",
            e.source.label, e.ts, e.pkg, thread = "${e.title}|${e.ts}")
    }
}

/**
 * 카톡 약속을 "대화 흐름" 단위로 찾기 (2.2)
 * - 같은 방에서 4시간 넘게 끊기지 않은 메시지들을 하나의 대화로 묶음
 * - 날짜·시간·장소·약속 단어가 서로 다른 메시지에 있어도 합쳐서 하나의 약속으로
 * - 날짜를 못 찾으면 대화한 날로 대신하지 않고 "날짜 확인 필요"로 둠
 *   (단, "오늘·이따·퇴근하고"처럼 그날을 뜻하는 말이 있으면 그날)
 * - "7시 말고 8시", "토요일 말고 일요일"처럼 바꾸면 마지막 것을 씀
 * - 과거 이야기("어제 만났던")는 무시, "취소·못 갈 것 같아"는 표시
 */
object ChatPlanner {
    private const val THREAD_GAP = 4 * 3_600_000L
    val past = Regex("""어제|그제|그저께|엊그제|지난\s?주|지난번|저번에|아까|했었|만났던|먹었던|봤었|다녀왔|갔었|였었""")
    private val agree = Regex("""좋아|좋아요|좋습니다|좋네요|콜|ㅇㅋ|오케이|(?i:ok)|그래|그러자|그럽시다|알겠|넵|네네|가능|확정|그때 봐|그때 뵙""")
    private val cancel = Regex("""취소|못\s?갈|못\s?가|안\s?될\s?것|어려울\s?것|다음에\s?보|다음으로\s?미루""")
    private val sameDay = Regex("""오늘|이따|있다가|좀\s?있다|조금\s?있다|퇴근하고|퇴근\s?후|끝나고|지금""")

    private class State(val first: Entry) {
        var date: DateHit? = null
        var dateMsg: LocalDateTime? = null
        var time: LocalTime? = null
        var timeMsg: LocalDateTime? = null
        var place: String? = null
        var title: String? = null
        var cue = false
        var agreed = false
        var cancelled = false
        var sameDay = false
        var last: Entry = first
        val lines = ArrayList<String>()
        fun complete() = date != null && cue
    }

    private fun speaker(e: Entry) = if (e.isGroupChat || e.title == "나") e.title else e.title

    fun plans(msgs: List<Entry>): List<Plan> {
        val out = ArrayList<Plan>()
        msgs.filter { it.source == Source.KAKAO && !Classify.isAd(it) }
            // 1:1 방에서 내가 보낸 메시지는 어느 방인지 알 수 없어 제외
            .filterNot { !it.isGroupChat && it.title == "나" }
            .groupBy { it.convKey }
            .forEach { (_, room) ->
                val sorted = room.sortedBy { it.ts }
                var thread = ArrayList<Entry>()
                sorted.forEach { m ->
                    if (thread.isNotEmpty() && m.ts - thread.last().ts > THREAD_GAP) { out += scan(thread); thread = ArrayList() }
                    thread += m
                }
                if (thread.isNotEmpty()) out += scan(thread)
            }
        return out
    }

    private fun scan(thread: List<Entry>): List<Plan> {
        val out = ArrayList<Plan>()
        var st: State? = null
        for (m in thread) {
            val text = m.text
            val msg = ldt(m.ts)
            val isPast = past.containsMatchIn(text)
            val hit = if (isPast) null else KDate.findDateHit(text, msg.toLocalDate())?.takeUnless { h ->
                // "~까지"는 약속이 아니라 마감, "다음 주 중·월말"은 날짜가 불분명 → 마감 쪽에서 처리
                h.kind == DateKind.WEEK || h.kind == DateKind.MONTH_END || h.kind == DateKind.D_MINUS ||
                    Regex(Regex.escape(h.text) + """\s?(\([^)]{1,4}\))?\s?까지""").containsMatchIn(text)
            }
            val time = if (isPast) null else KDate.findTimeHit(text, st?.time)?.let { th ->
                // 저녁·술 약속인데 오전/오후 없이 8~11시면 저녁으로
                val evening = text.contains("저녁") || text.contains("술") || st?.title?.let { it.contains("저녁") || it.contains("술") } == true
                KDate.pmCarry(th, st?.time ?: if (evening) java.time.LocalTime.NOON else null).time
            }
            val placeHere = if (isPast) null else PlanFinder.placeOf(text)
            val cueHere = !isPast && PlanFinder.cue.containsMatchIn(text)
            val agreeHere = agree.containsMatchIn(text)
            val cancelHere = cancel.containsMatchIn(text)
            val relevant = hit != null || time != null || cueHere || placeHere != null

            // 이미 날짜가 정해진 약속이 있는데, 바꾸자는 말 없이 다른 날짜가 나오면 새 약속
            val cur = st
            if (hit != null && cur != null && cur.complete() && cur.date!!.date != hit.date && !KDate.isChange(text)) {
                emit(cur)?.let { out += it }
                st = null
            }
            if (!relevant && st == null) continue
            if (!relevant && !agreeHere && !cancelHere) continue
            val s = st ?: State(m).also { st = it }
            if (hit != null) { s.date = hit; s.dateMsg = msg }
            if (time != null) { s.time = time; s.timeMsg = msg }
            if (placeHere != null) s.place = placeHere
            if (cueHere) {
                s.cue = true
                PlanFinder.titleOf(text)?.let { t -> if (s.title == null || t.contains(" ")) s.title = t }
            }
            if (agreeHere && s.lines.isNotEmpty()) s.agreed = true
            if (cancelHere && s.lines.isNotEmpty()) s.cancelled = true
            if (!isPast && sameDay.containsMatchIn(text)) s.sameDay = true
            s.last = m
            s.lines += "${speaker(m)}: ${text.replace('\n', ' ').take(60)}"
            if (s.lines.size > 4) s.lines.removeAt(0)
        }
        st?.let { s -> emit(s)?.let { out += it } }
        return out
    }

    private fun emit(s: State): Plan? {
        if (!s.cue) return null
        if (s.date == null && s.time == null) return null
        val lastMsg = ldt(s.last.ts)
        var dateKnown = true
        var date: LocalDate
        val hit = s.date
        if (hit != null) {
            date = hit.date
            // 같은 요일에 "토요일"이라고 했는데 그 시각이 이미 지났으면 다음 주
            val dm = s.dateMsg!!
            if (hit.kind == DateKind.WEEKDAY_BARE && date == dm.toLocalDate()) {
                val t = s.time
                if (t == null || !t.isAfter(dm.toLocalTime())) date = date.plusWeeks(1)
            }
        } else if (s.sameDay) {
            date = s.timeMsg!!.toLocalDate()
            if (s.time != null && s.time!!.isBefore(s.timeMsg!!.toLocalTime().minusHours(1))) date = date.plusDays(1)
        } else {
            dateKnown = false
            date = lastMsg.toLocalDate() // 임시 값 (화면에는 "날짜 확인 필요")
        }
        val at = date.atTime(s.time ?: LocalTime.of(12, 0))
        // 시간이 없으면 날짜만 비교 (오늘 행사 공지가 오후에 와도 놓치지 않게)
        if (dateKnown && s.time == null && date.isBefore(lastMsg.toLocalDate())) return null
        if (dateKnown && s.time != null && at.isBefore(lastMsg.minusHours(1))) return null
        if (dateKnown && at.isAfter(lastMsg.plusDays(120))) return null
        val first = s.first
        val who = if (first.isGroupChat) first.conv!! else first.convKey
        return Plan(
            at = at, hasTime = s.time != null, place = s.place, who = who,
            title = s.title ?: "약속", quote = s.lines.joinToString("\n"), src = "카톡",
            msgTs = s.last.ts, pkg = first.pkg, dateKnown = dateKnown,
            agreed = s.agreed, cancelled = s.cancelled, thread = "$who|${first.ts}",
        )
    }
}

// ================= 마감 (쿠폰·기한) =================
data class Deadline(
    val due: LocalDate, val title: String, val srcLabel: String, val quote: String, val msgTs: Long, val pkg: String?,
    /** 캘린더로 보냈는지 */
    val cal: Boolean = false,
) {
    val plainKey get() = "dl|$due|${title.replace(" ", "")}"
    fun toJson(): String = JSONObject().put("due", due.toString()).put("title", title).put("src", srcLabel)
        .put("quote", quote).put("msgTs", msgTs).put("pkg", pkg ?: "").put("cal", cal).toString()

    companion object {
        fun fromJson(s: String) = JSONObject(s).let {
            Deadline(LocalDate.parse(it.getString("due")), it.getString("title"), it.getString("src"), it.getString("quote"),
                it.getLong("msgTs"), it.optString("pkg").ifBlank { null }, it.optBoolean("cal"))
        }
    }
}

object DeadlineFinder {
    private val cue = Regex("""까지|유효\s?기간|만료|마감|기한|D\s?-\s?\d+|종료\s?예정""", RegexOption.IGNORE_CASE)
    private val adStrict = Regex("""\(광고\)|\[광고\]|수신\s?거부|무료\s?거부""")
    private val coupon = Regex("""([가-힣A-Za-z0-9][가-힣A-Za-z0-9 ]{0,18}?)\s?(쿠폰|교환권|관람권|기프티콘|상품권|이용권|모바일\s?상품권)""")

    fun titleOf(e: Entry): String {
        coupon.find(e.text)?.let { return "${it.groupValues[1].trim()} ${it.groupValues[2]}".trim() }
        coupon.find(e.title)?.let { return "${it.groupValues[1].trim()} ${it.groupValues[2]}".trim() }
        if (e.source == Source.MAIL) return "${e.title} · ${e.text.lineSequence().first().take(24)}"
        val line = e.text.lineSequence().map { it.replace(Regex("""\[[^\]]*\]"""), "").trim() }.firstOrNull { it.length >= 4 } ?: e.title
        return line.take(28)
    }

    fun find(e: Entry): Deadline? {
        if (e.source == Source.CAL || e.source == Source.CALL) return null
        val all = e.title + " " + e.text
        if (adStrict.containsMatchIn(all)) return null
        if (!cue.containsMatchIn(all)) return null
        val msg = ldt(e.ts)
        val d = KDate.findDate(all, msg.toLocalDate())?.first ?: return null
        if (d.isBefore(msg.toLocalDate()) || d.isAfter(msg.toLocalDate().plusDays(400))) return null
        val src = if (e.source == Source.APP) e.app else if (e.isGroupChat) "${e.source.label} · ${e.conv}" else "${e.source.label} · ${e.title}"
        return Deadline(d, titleOf(e), src, all.replace('\n', ' ').take(100), e.ts, e.pkg)
    }
}

// ================= 메일 요약 =================
enum class MailCat(val label: String) { REPLY("답장 필요"), BILL("청구·결제"), NOTICE("알림"), NEWS("뉴스레터"), OTHER("기타") }

data class MailItem(val e: Entry, val sender: String, val subject: String, val bullets: List<String>, val cat: MailCat, val due: LocalDate?)

object MailDigest {
    private val news = Regex("""newsletter|뉴스레터|unsubscribe|수신\s?거부|weekly|광고|프로모션|이벤트|할인|특가|세일""", RegexOption.IGNORE_CASE)
    private val bill = Regex("""청구|요금|결제|납부|영수증|명세서|invoice|bill|payment|receipt""", RegexOption.IGNORE_CASE)
    private val notice = Regex("""로그인|보안|배송|주문|예약|인증|알림|안내|확인|비밀번호|password|sign.?in""", RegexOption.IGNORE_CASE)
    private val split = Regex("""(?<=[.!?。])\s+|\n+|(?<=다\.)""")

    fun categorize(subject: String, body: String, sender: String): MailCat {
        val all = "$subject $body"
        return when {
            news.containsMatchIn(all) -> MailCat.NEWS
            bill.containsMatchIn(all) -> MailCat.BILL
            Classify.requestRe.containsMatchIn(all) && !Classify.isServiceSender(sender) -> MailCat.REPLY
            notice.containsMatchIn(all) -> MailCat.NOTICE
            else -> MailCat.OTHER
        }
    }

    fun digest(mails: List<Entry>): List<MailItem> = mails.filter { it.source == Source.MAIL }
        .sortedByDescending { it.ts }.map { e ->
            val lines = e.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val subject = lines.firstOrNull() ?: "(제목 없음)"
            val body = lines.drop(1).joinToString(" ")
            val bullets = body.split(split).map { it.trim().trimEnd('.') }.filter { it.length >= 4 }
                .take(2).map { if (it.length > 42) it.take(41) + "…" else it }.toMutableList()
            val cat = categorize(subject, body, e.title)
            if (cat == MailCat.BILL) Classify.amount(subject + " " + body)?.let { a ->
                if (bullets.none { it.contains("원") }) bullets.add(0, "금액 %,d원".format(a))
            }
            val due = DeadlineFinder.find(e)?.due
            MailItem(e, e.title, subject.take(60), bullets.take(2), cat, due)
        }
}

// ================= 정기 결제 =================
data class Payment(
    val ts: Long, val merchant: String, val amount: Long, val scheduled: Boolean = false, val card: String? = null,
    /** 저장 키 (합계에서 빼기용, 저장되지 않음) */
    val id: String = "",
    val excluded: Boolean = false,
) {
    fun toJson(): String = JSONObject().put("m", merchant).put("a", amount).put("s", scheduled).put("c", card ?: "").toString()

    companion object {
        fun fromRow(ts: Long, json: String): Payment = JSONObject(json).let {
            Payment(ts, it.getString("m"), it.getLong("a"), it.optBoolean("s"), it.optString("c").ifBlank { null })
        }
    }
}

data class Recurring(
    val key: String, val merchant: String, val amount: Long, val day: Int, val months: Int,
    val confirmed: Boolean, val paidThisMonth: Boolean, val next: LocalDate,
)

object RecurringFinder {
    private val keyword = Regex("""정기\s?결제|자동\s?이체|정기\s?구독|구독료|멤버십|월\s?이용료|정기\s?출금|납부\s?예정|출금\s?예정|결제\s?예정""")
    private val scheduledRe = Regex("""자동\s?이체|납부\s?예정|출금\s?예정|결제\s?예정|이체\s?예정""")

    fun norm(m: String) = m.lowercase().replace(Regex("""[\s\d()\[\]*.,·_-]"""), "")

    /** 알림 한 건을 결제 내역으로 (결제·자동이체 예정 안내) */
    fun paymentOf(e: Entry): Payment? {
        if (Classify.isPayment(e)) return Payment(e.ts, Classify.moneyLabel(e), Classify.amount(e.text)!!, card = Classify.cardOf(e))
        val all = e.title + " " + e.text
        if ((e.source == Source.SMS || e.source == Source.MAIL || e.source == Source.APP || e.source == Source.KAKAO) && scheduledRe.containsMatchIn(all)) {
            val amt = Classify.amount(all) ?: return null
            val msg = ldt(e.ts)
            val due = KDate.findDate(all, msg.toLocalDate())?.first ?: return null
            return Payment(epoch(due.atTime(9, 0)), Classify.moneyLabel(e), amt, scheduled = true)
        }
        return null
    }

    fun isKeyword(e: Entry) = keyword.containsMatchIn(e.title + " " + e.text)

    fun detect(payments: List<Payment>, today: LocalDate, decision: (String) -> String?): List<Recurring> {
        val out = ArrayList<Recurring>()
        val thisMonth = YearMonth.from(today)
        payments.groupBy { norm(it.merchant) }.forEach { (nm, list) ->
            if (nm.length < 2) return@forEach
            val key = "rc|$nm"
            val dec = decision(key)
            if (dec == "dismissed") return@forEach
            val byMonth = list.groupBy { YearMonth.from(ldt(it.ts)) }.mapValues { (_, v) -> v.maxBy { it.ts } }
            val months = byMonth.keys.sorted()
            val last = byMonth.getValue(months.last())
            // 최근 달부터 거꾸로, 금액 ±10%·날짜 ±3일로 이어지는 달 수
            var run = 1
            var i = months.size - 1
            while (i > 0) {
                val a = byMonth.getValue(months[i]); val b = byMonth.getValue(months[i - 1])
                val consecutive = months[i - 1].plusMonths(1) == months[i]
                val sameAmt = kotlin.math.abs(a.amount - b.amount) <= a.amount / 10
                val sameDay = kotlin.math.abs(ldt(a.ts).dayOfMonth - ldt(b.ts).dayOfMonth) <= 3
                if (consecutive && sameAmt && sameDay) { run++; i-- } else break
            }
            val recentEnough = months.last() >= thisMonth.minusMonths(1)
            val scheduledHint = list.any { it.scheduled }
            val isRecurring = dec == "confirmed" || (recentEnough && (run >= 2 || scheduledHint))
            if (!isRecurring) return@forEach
            val day = ldt(last.ts).dayOfMonth
            val paid = byMonth[thisMonth]?.let { !it.scheduled || ldt(it.ts).toLocalDate() <= today } == true
            val nextMonth = if (paid) thisMonth.plusMonths(1) else thisMonth
            val next = nextMonth.atDay(minOf(day, nextMonth.lengthOfMonth()))
            out += Recurring(key, last.merchant, last.amount, day, run, dec == "confirmed" || run >= 3 || scheduledHint, paid, next)
        }
        return out.sortedBy { it.day }
    }
}
