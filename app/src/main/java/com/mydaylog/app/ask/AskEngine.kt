package com.mydaylog.app.ask

import android.provider.CallLog
import com.mydaylog.app.data.Classify
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.Deadline
import com.mydaylog.app.insight.Plan
import com.mydaylog.app.insight.epoch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 답변 근거 한 줄. entry가 있으면 눌러서 원래 앱·대화방을 엶 */
data class Ev(val ts: Long, val source: Source, val title: String, val body: String, val entry: Entry?, val allDay: Boolean = false)

/**
 * 코드가 계산한 답 (숫자·금액·건수는 여기서만 만듦)
 * - headline: 기본 답변 (AI가 없어도 이 문장으로 답함)
 * - context: 기기 AI에 넘길 근거 (폰 밖으로 나가지 않음)
 */
data class Facts(val query: AskQuery, val headline: String, val details: List<String>, val evidence: List<Ev>, val context: String) {
    val found get() = evidence.isNotEmpty()
}

object AskEngine {
    const val MAX_EVIDENCE = 40
    private const val MAX_CONTEXT = 2400

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun date(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    private fun hm(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    private fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${"월화수목금토일"[d.dayOfWeek.value - 1]})"
    fun won(v: Long) = "%,d원".format(v)
    private fun norm(s: String) = s.lowercase().replace(Regex("""\s+"""), "")

    private fun allText(e: Entry) = listOf(e.title, e.text, e.conv.orEmpty(), e.app, e.location.orEmpty()).joinToString(" ")

    /** 검색어: 모두 들어간 기록 우선, 없으면 하나라도 들어간 기록 */
    private fun <T> byKeywords(list: List<T>, kws: List<String>, text: (T) -> String): List<T> {
        if (kws.isEmpty()) return list
        val n = kws.map(::norm)
        val all = list.filter { x -> val t = norm(text(x)); n.all { t.contains(it) } }
        if (all.isNotEmpty() || n.size == 1) return all
        return list.filter { x -> val t = norm(text(x)); n.any { t.contains(it) } }
    }

    fun answer(
        q0: AskQuery, entries: List<Entry>, plans: List<Plan>, deadlines: List<Deadline>, today: LocalDate,
        excluded: (Entry) -> Boolean = { false },
    ): Facts {
        // 검색어 중 기록 어디에도 없는 말(내역·총합 같은 일반 단어, 오타)은 조건에서 뺌
        val pool = entries.filter { date(it.ts) in q0.from..q0.to && !Classify.isAd(it) }.map { norm(allText(it)) } +
            plans.map { norm(it.title + it.who + it.place.orEmpty() + it.quote) } +
            deadlines.map { norm(it.title + it.srcLabel + it.quote) }
        val valid = q0.keywords.filter { k -> norm(k).let { n -> pool.any { it.contains(n) } } }
        val dropped = q0.keywords - valid.toSet()
        val q = if (dropped.isEmpty()) q0 else QueryParser.withKeywords(q0, valid, today)
        val f0 = answerFor(q, entries, plans, deadlines, today, excluded)
        val f = if (dropped.isEmpty()) f0 else {
            val words = dropped.joinToString(" ") { "‘$it’" }
            val note = if (valid.isEmpty()) "$words 이(가) 들어간 기록은 없어서 ${q.label} ${q.topic.label} 전체로 찾았어요."
            else "$words 은(는) 기록에 없는 말이라 빼고 찾았어요."
            f0.copy(details = f0.details + note)
        }
        return f.copy(context = contextOf(f))
    }

    private fun answerFor(q: AskQuery, entries: List<Entry>, plans: List<Plan>, deadlines: List<Deadline>, today: LocalDate, excluded: (Entry) -> Boolean): Facts {
        val inRange = entries.filter { date(it.ts) in q.from..q.to && !Classify.isAd(it) }
        val hits = byKeywords(inRange, q.keywords, ::allText)
        val kw = if (q.keywords.isEmpty()) "" else "‘${q.keywords.joinToString(" ")}’ "
        val period = q.label

        val f = when (q.topic) {
            Topic.PAY -> pay(q, hits, period, kw, deposit = false, excluded)
            Topic.DEPOSIT -> pay(q, hits, period, kw, deposit = true, excluded)
            Topic.CALL -> calls(q, hits, period, kw)
            Topic.PLAN -> planAnswer(q, hits, byKeywords(plans, q.keywords) { it.title + " " + it.who + " " + it.place.orEmpty() + " " + it.quote },
                byKeywords(deadlines, q.keywords) { it.title + " " + it.srcLabel }, period, kw)
            Topic.DEADLINE -> deadlineAnswer(q, byKeywords(deadlines, q.keywords) { it.title + " " + it.srcLabel + " " + it.quote }, period, kw, today)
            Topic.KAKAO -> messages(q, hits, Source.KAKAO, period, kw)
            Topic.SMS -> messages(q, hits, Source.SMS, period, kw)
            Topic.MAIL -> messages(q, hits, Source.MAIL, period, kw)
            Topic.ALL -> overview(q, hits, period, kw, excluded)
        }
        return f
    }

    // ---------- 결제·입금 ----------
    private fun pay(q: AskQuery, hits: List<Entry>, period: String, kw: String, deposit: Boolean, excluded: (Entry) -> Boolean): Facts {
        val all = hits.filter { if (deposit) Classify.isDeposit(it) else Classify.isPayment(it) }
        val out = all.filter(excluded)
        val list = all - out.toSet()
        val what = if (deposit) "입금" else "결제"
        val outNote = if (out.isEmpty()) null
        else "합계에서 빼기로 한 $what ${out.size}건(${won(out.sumOf { Classify.amount(it.text) ?: 0L })})은 계산에서 뺐어요."
        if (list.isEmpty()) return Facts(q, "$period ${kw}$what 기록이 없어요.", listOfNotNull(outNote), emptyList(), "")
        val total = list.sumOf { Classify.amount(it.text) ?: 0L }
        val ev = order(q, list).map { e ->
            val card = Classify.cardOf(e)
            Ev(e.ts, e.source, "${Classify.moneyLabel(e)} ${won(Classify.amount(e.text) ?: 0)}", listOfNotNull(card, e.app.takeIf { card == null }).joinToString(""), e)
        }
        val places = list.map { Classify.moneyLabel(it) }.distinct()
        val head = buildString {
            append("$period ${kw}$what ${list.size}건, 총 ${won(total)}이에요.")
            if (!deposit && places.size in 1..4) append(" (${places.joinToString(", ")})")
        }
        val details = buildList {
            if (!deposit) {
                val byCard = list.groupBy { Classify.cardOf(it) ?: "기타" }.mapValues { (_, v) -> v.sumOf { Classify.amount(it.text) ?: 0L } }
                if (byCard.size > 1) add("카드별: " + byCard.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${won(it.value)}" })
                val byPlace = list.groupBy { Classify.moneyLabel(it) }.mapValues { (_, v) -> v.sumOf { Classify.amount(it.text) ?: 0L } }
                if (byPlace.size > 1) add("많이 쓴 곳: " + byPlace.entries.sortedByDescending { it.value }.take(3).joinToString(" · ") { "${it.key} ${won(it.value)}" })
                list.maxByOrNull { Classify.amount(it.text) ?: 0L }?.takeIf { list.size > 1 }?.let {
                    add("가장 큰 결제: ${Classify.moneyLabel(it)} ${won(Classify.amount(it.text) ?: 0)} (${md(date(it.ts))} ${hm(it.ts)})")
                }
                if (places.size > 4) add("결제한 곳: " + places.take(6).joinToString(", ") + if (places.size > 6) " 외 ${places.size - 6}곳" else "")
            }
            outNote?.let { add(it) }
        }
        return Facts(q, head, details, ev, "")
    }

    // ---------- 통화 ----------
    private fun calls(q: AskQuery, hits: List<Entry>, period: String, kw: String): Facts {
        val list = hits.filter { it.source == Source.CALL }
        if (list.isEmpty()) return Facts(q, "$period ${kw}통화 기록이 없어요.", emptyList(), emptyList(), "")
        val inc = list.count { it.callType == CallLog.Calls.INCOMING_TYPE }
        val out = list.count { it.callType == CallLog.Calls.OUTGOING_TYPE }
        val miss = list.count { it.isMissed }
        val parts = listOfNotNull("수신 $inc".takeIf { inc > 0 }, "발신 $out".takeIf { out > 0 }, "부재중 $miss".takeIf { miss > 0 })
        val head = "$period ${kw}통화 ${list.size}건이에요" + (if (parts.isNotEmpty()) " (${parts.joinToString(" · ")})." else ".")
        val details = buildList {
            val talk = list.sumOf { it.durationSec }
            if (talk > 0) add("통화한 시간 합계: ${durText(talk)}")
            val people = list.groupingBy { it.title }.eachCount().entries.sortedByDescending { it.value }
            if (people.size > 1) add("많이 통화한 사람: " + people.take(3).joinToString(", ") { "${it.key} ${it.value}건" })
        }
        val ev = order(q, list).map { Ev(it.ts, Source.CALL, it.title, it.text, it) }
        return Facts(q, head, details, ev, "")
    }

    private fun durText(sec: Long) = when {
        sec >= 3600 -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
        sec >= 60 -> "${sec / 60}분 ${sec % 60}초"
        else -> "${sec}초"
    }

    // ---------- 일정 ----------
    private fun planAnswer(q: AskQuery, hits: List<Entry>, plans: List<Plan>, dls: List<Deadline>, period: String, kw: String): Facts {
        val cal = hits.filter { it.source == Source.CAL }
        val ps = plans.filter { it.dateKnown && !it.cancelled && it.at.toLocalDate() in q.from..q.to }
            // 캘린더에 이미 있는 약속은 중복 제외
            .filter { p -> cal.none { c -> date(c.ts) == p.at.toLocalDate() && (c.title.contains(p.title) || p.hasTime && !c.allDay && kotlin.math.abs(c.ts - epoch(p.at)) <= 90 * 60_000L) } }
        val ds = dls.filter { it.due in q.from..q.to }
        val n = cal.size + ps.size
        if (n == 0 && ds.isEmpty()) return Facts(q, "$period ${kw}일정이 없어요.", emptyList(), emptyList(), "")
        val ev = buildList {
            cal.forEach { add(Ev(it.ts, Source.CAL, it.title, it.location ?: it.app, it, it.allDay)) }
            ps.forEach { p ->
                val src = if (p.src == "문자") Source.SMS else Source.KAKAO
                val room = Entry("plan", p.msgTs, src, p.who, p.title, p.who, p.pkg, if (src == Source.SMS) "문자" else "카카오톡")
                add(Ev(epoch(p.at), src, "${p.title} (${p.src}에서 찾은 약속)", listOfNotNull(p.place, p.who).joinToString(" · "), room, !p.hasTime))
            }
            ds.forEach { d -> add(Ev(epoch(d.due.atTime(23, 59)), Source.APP, "마감: ${d.title}", d.srcLabel, null, true)) }
        }.sortedBy { it.ts }
        val head = buildString {
            append("$period ${kw}일정 ${n}개")
            if (ps.isNotEmpty()) append("(캘린더 ${cal.size} · 대화에서 찾은 약속 ${ps.size})")
            if (ds.isNotEmpty()) append(", 마감 ${ds.size}개")
            append("가 있어요.")
        }
        val details = buildList {
            ev.firstOrNull { it.ts >= System.currentTimeMillis() && !it.title.startsWith("마감") }?.let {
                add("가장 가까운 일정: ${md(date(it.ts))} ${if (it.allDay) "종일" else hm(it.ts)} ${it.title}")
            }
        }
        return Facts(q, head, details, ev, "")
    }

    // ---------- 마감 ----------
    private fun deadlineAnswer(q: AskQuery, dls: List<Deadline>, period: String, kw: String, today: LocalDate): Facts {
        val list = dls.filter { it.due in q.from..q.to }.sortedBy { it.due }
        if (list.isEmpty()) return Facts(q, "$period ${kw}마감 기록이 없어요.", emptyList(), emptyList(), "")
        val near = list.first()
        val dd = java.time.temporal.ChronoUnit.DAYS.between(today, near.due)
        val head = "$period ${kw}마감 ${list.size}개, 가장 가까운 건 ${md(near.due)} ${near.title}" +
            (if (dd >= 0) " (D-$dd)이에요." else "이에요.")
        val ev = list.map { d ->
            val parts = d.srcLabel.split(" · ", limit = 2)
            val src = if (parts[0] == "문자") Source.SMS else if (parts[0] == "카톡") Source.KAKAO else Source.APP
            val entry = if (parts.size == 2 && src != Source.APP) Entry("dl", d.msgTs, src, parts[1], d.title, parts[1], d.pkg, parts[0]) else null
            Ev(epoch(d.due.atTime(23, 59)), src, "${d.title}까지", d.srcLabel, entry, true)
        }
        return Facts(q, head, emptyList(), ev, "")
    }

    // ---------- 카톡·문자·메일 ----------
    private fun messages(q: AskQuery, hits: List<Entry>, src: Source, period: String, kw: String): Facts {
        val list = hits.filter { it.source == src }
        val name = src.label
        if (list.isEmpty()) return Facts(q, "$period ${kw}${name} 기록이 없어요.", emptyList(), emptyList(), "")
        val groups = list.groupingBy { if (src == Source.KAKAO) it.convKey else it.title }.eachCount()
            .entries.sortedByDescending { it.value }
        val unit = if (src == Source.KAKAO) "대화방" else "보낸 곳"
        val head = "$period ${kw}${name} ${list.size}개, $unit ${groups.size}곳이에요."
        val details = listOf("많은 순: " + groups.take(4).joinToString(", ") { "${it.key} ${it.value}" })
        val ev = order(q, list).map { e ->
            Ev(e.ts, e.source, if (e.isGroupChat) e.conv!! else e.title, if (e.isGroupChat) "${e.title}: ${e.text}" else e.text, e)
        }
        return Facts(q, head, details, ev, "")
    }

    // ---------- 전체 ----------
    private fun overview(q: AskQuery, hits: List<Entry>, period: String, kw: String, excluded: (Entry) -> Boolean): Facts {
        if (hits.isEmpty()) {
            return Facts(q, if (kw.isNotEmpty()) "$period ${kw}이(가) 나온 기록이 없어요." else "$period 기록이 없어요.", emptyList(), emptyList(), "")
        }
        val counts = Source.entries.mapNotNull { s -> hits.count { it.source == s }.takeIf { it > 0 }?.let { "${s.label} $it" } }
        val head = if (kw.isNotEmpty()) {
            val last = hits.maxBy { it.ts }
            "$period ${kw}이(가) 나온 기록 ${hits.size}건이에요. 가장 최근은 ${md(date(last.ts))} ${hm(last.ts)} ${srcName(last)}예요."
        } else "$period 기록 ${hits.size}건이에요 (${counts.joinToString(" · ")})."
        val details = buildList {
            if (kw.isNotEmpty()) add("종류: " + counts.joinToString(" · "))
            val pays = hits.filter { Classify.isPayment(it) && !excluded(it) }
            if (pays.isNotEmpty()) add("결제 ${pays.size}건, 총 ${won(pays.sumOf { Classify.amount(it.text) ?: 0L })}")
            val miss = hits.count { it.isMissed }
            if (miss > 0) add("부재중 전화 ${miss}건")
            val cal = hits.filter { it.source == Source.CAL }
            if (cal.isNotEmpty()) add("일정: " + cal.take(4).joinToString(", ") { (if (it.allDay) "" else hm(it.ts) + " ") + it.title })
        }
        val ev = order(q, hits).map { e ->
            Ev(e.ts, e.source, if (e.isGroupChat) e.conv!! else e.title, if (e.isGroupChat) "${e.title}: ${e.text}" else e.text, e, e.allDay)
        }
        return Facts(q, head, details, ev, "")
    }

    private fun srcName(e: Entry) = when (e.source) {
        Source.APP -> "${e.app} 알림"
        Source.KAKAO -> "카톡(${e.convKey})"
        else -> "${e.source.label}(${e.title})"
    }

    /** 하루 질문은 시간순, 여러 날은 최신순 */
    private fun order(q: AskQuery, list: List<Entry>): List<Entry> =
        (if (q.singleDay) list.sortedBy { it.ts } else list.sortedByDescending { it.ts }).take(MAX_EVIDENCE)

    // ---------- 기기 AI에 넘길 근거 ----------
    private fun contextOf(f: Facts): String = buildString {
        append("[계산 결과]\n").append(f.headline).append('\n')
        f.details.forEach { append(it).append('\n') }
        if (f.evidence.isNotEmpty()) {
            append("[기록]\n")
            for (e in f.evidence) {
                val line = "${md(date(e.ts))} ${if (e.allDay) "종일" else hm(e.ts)} ${e.source.label} · ${e.title}: ${e.body.replace('\n', ' ').take(70)}\n"
                if (length + line.length > MAX_CONTEXT) break
                append(line)
            }
        }
    }

    // ---------- AI 답변 검사 ----------
    private val num = Regex("""\d[\d,]*""")

    /**
     * 기기 AI 문장이 근거에 없는 숫자를 만들면 버리고 기본 답변을 씀.
     * (금액·건수·시간이 기록과 다르게 나오는 것 방지)
     */
    fun aiAnswerOk(ai: String?, f: Facts): Boolean {
        val t = ai?.trim().orEmpty()
        if (t.length < 4 || t.length > 700) return false
        val known = num.findAll(f.context + " " + f.query.text).map { it.value.replace(",", "").trimStart('0') }.toSet()
        val bad = num.findAll(t).map { it.value.replace(",", "").trimStart('0') }.filter { it.isNotEmpty() }.any { it !in known }
        if (bad) return false
        if (f.found && Regex("""찾지\s?못|모르겠""").containsMatchIn(t)) return false
        return true
    }

    fun prompt(f: Facts): String = """
너는 사용자 휴대폰 안의 기록 비서야. 아래 [계산 결과]와 [기록]만 근거로 [질문]에 한국어 존댓말로 2~4문장 답해.
규칙:
- 금액·건수·날짜·시간은 [계산 결과]와 [기록]에 적힌 숫자만 그대로 써. 새로 더하거나 계산하지 마.
- 기록에 없는 내용은 추측하지 말고 "기록에서 찾지 못했어요"라고 해.
- 목록을 전부 나열하지 말고 질문에 필요한 것만 말해.

[질문] ${f.query.text}
[기간] ${f.query.label}
${f.context}
""".trim()
}
