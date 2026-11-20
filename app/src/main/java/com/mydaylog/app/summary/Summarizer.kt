package com.mydaylog.app.summary

import android.content.Context
import com.mydaylog.app.data.Classify
import com.mydaylog.app.data.Dash
import com.mydaylog.app.data.Db
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Journal
import com.mydaylog.app.data.Repo
import com.mydaylog.app.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 하루 요약을 폰 안에서만 만듭니다. (네트워크 사용 없음)
 * 캘린더·통화·알림 기록을 규칙으로 정리해 문장과 목록으로 씁니다.
 */
object Summarizer {

    fun timeOf(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    private fun hourOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).hour

    fun won(v: Long) = "%,d원".format(v)

    suspend fun generate(ctx: Context, day: String): Result<Journal> = withContext(Dispatchers.IO) {
        runCatching {
            val d = LocalDate.parse(day)
            val dash = Dash.build(ctx, d)
            val next = Repo.calendarDay(ctx, d.plusDays(1))
            val db = Db.get(ctx)
            db.saveJournal(day, compose(dash, next, d == LocalDate.now()), "자동")
            db.journal(day)!!
        }
    }

    // ---------- 한국어 조사 ----------
    private fun hasBatchim(word: String): Boolean {
        val base = if (word.endsWith(")") && word.contains("(")) word.substringBeforeLast("(") else word
        val c = base.trim().lastOrNull() ?: return false
        return when {
            c in '가'..'힣' -> (c - '가') % 28 != 0
            c.isDigit() -> c in "0136780"
            else -> false
        }
    }

    fun josa(word: String, withBatchim: String, without: String) = word + if (hasBatchim(word)) withBatchim else without

    // ---------- 요약 문서 만들기 ----------
    fun headline(d: Dash): String {
        val c = d.counts
        val parts = mutableListOf<String>()
        val cal = c[Source.CAL] ?: 0
        val call = c[Source.CALL] ?: 0
        val missed = d.entries.count { it.isMissed }
        val msg = (c[Source.SMS] ?: 0) + (c[Source.KAKAO] ?: 0)
        val mail = c[Source.MAIL] ?: 0
        if (cal > 0) parts += "일정 ${cal}개"
        if (call > 0) parts += if (missed > 0) "통화 ${call}건(부재중 $missed)" else "통화 ${call}건"
        if (msg > 0) parts += "메시지 ${msg}개"
        if (mail > 0) parts += "메일 ${mail}통"
        val s1 = when {
            parts.isNotEmpty() -> josa(parts.joinToString(", "), "이", "가") + " 있었어요."
            d.entries.isNotEmpty() -> "앱 알림 ${d.entries.size}개가 있었어요."
            else -> return "기록이 거의 없는 조용한 하루였어요."
        }
        val top = d.contacts.firstOrNull()
        val s2 = when {
            top != null && top.count >= 3 -> josa(top.name, "과", "와") + "의 연락이 가장 많았어요."
            d.spend > 0 -> "지출은 ${won(d.spend)}이에요."
            else -> null
        }
        return listOfNotNull(s1, s2).joinToString(" ")
    }

    fun periodLines(items: List<Entry>): List<String> {
        val out = mutableListOf<Pair<Long, String>>()
        val useful = items.filterNot { Classify.isAd(it) }
        useful.filter { it.source == Source.CAL }.forEach {
            val t = if (it.allDay) "종일" else timeOf(it.ts)
            out += it.ts to "$t ${it.title}${it.location?.let { l -> " ($l)" } ?: ""}"
        }
        useful.filter { it.source == Source.CALL }.forEach {
            when {
                it.isMissed -> out += it.ts to "${timeOf(it.ts)} ${it.title} 전화를 놓쳤어요"
                it.durationSec >= 60 -> out += it.ts to "${timeOf(it.ts)} ${josa(it.title, "과", "와")} ${it.durationSec / 60}분 통화"
            }
        }
        useful.filter { it.source == Source.KAKAO && it.title != "나" }.groupBy { it.convKey }
            .entries.sortedByDescending { it.value.size }.take(3).forEach { (key, list) ->
                val f = list.first()
                val what = "${timeOf(f.ts)} " + if (f.isGroupChat) "$key 방 카톡 ${list.size}개" else "${key}에게서 카톡 ${list.size}개"
                out += f.ts to what
            }
        useful.filter {
            it.source == Source.SMS && !Classify.isServiceSender(it.title) &&
                !Classify.isPayment(it) && !Classify.isDeposit(it) && !Classify.isDelivery(it)
        }.take(2).forEach { out += it.ts to "${timeOf(it.ts)} ${it.title} 문자: ${it.text.replace('\n', ' ').take(24)}" }
        useful.filter { it.source == Source.MAIL }.take(3).forEach {
            out += it.ts to "${timeOf(it.ts)} ${it.title} 메일: ${it.text.lineSequence().firstOrNull().orEmpty().take(26)}"
        }
        useful.filter { Classify.isPayment(it) }.forEach {
            out += it.ts to "${timeOf(it.ts)} ${Classify.moneyLabel(it)} ${won(Classify.amount(it.text)!!)} 결제"
        }
        useful.filter { Classify.isDeposit(it) }.forEach {
            out += it.ts to "${timeOf(it.ts)} ${Classify.moneyLabel(it)} ${won(Classify.amount(it.text)!!)} 입금"
        }
        useful.filter { Classify.isDelivery(it) }.takeLast(2).forEach {
            out += it.ts to "${timeOf(it.ts)} ${it.app} 배송 ${if (Classify.isDelivered(it)) "완료" else "소식"}"
        }
        return out.sortedBy { it.first }.map { it.second }.take(7)
    }

    fun compose(d: Dash, nextDay: List<Entry>, isToday: Boolean): String {
        val sb = StringBuilder()
        sb.append("## 한 줄 요약\n").append(headline(d)).append('\n')
        for ((name, range) in listOf("오전" to 0..11, "오후" to 12..17, "저녁" to 18..23)) {
            val lines = periodLines(d.entries.filter { hourOf(it.ts) in range })
            if (lines.isNotEmpty()) {
                sb.append("\n## ").append(name).append('\n')
                lines.forEach { sb.append("- ").append(it).append('\n') }
            }
        }
        val todos = d.todos.filter { !it.done }
        if (todos.isNotEmpty()) {
            sb.append("\n## 챙겨야 할 일\n")
            todos.forEach { sb.append("- ").append(it.title).append('\n') }
        }
        if (nextDay.isNotEmpty()) {
            val label = if (isToday) "내일" else d.day.plusDays(1).let { "${it.monthValue}/${it.dayOfMonth}" }
            sb.append("\n## 내일 이후\n")
            nextDay.take(4).forEach {
                val t = if (it.allDay) "종일" else timeOf(it.ts)
                sb.append("- $label $t ${it.title}${it.location?.let { l -> " ($l)" } ?: ""}\n")
            }
        }
        return sb.toString().trimEnd()
    }
}

object JournalParse {
    fun sections(md: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var cur: String? = null
        val sb = StringBuilder()
        fun flush() { cur?.let { map[it] = sb.toString().trim() }; sb.clear() }
        md.lines().forEach { line ->
            if (line.startsWith("## ")) { flush(); cur = line.removePrefix("## ").trim() }
            else if (cur != null) sb.append(line).append('\n')
        }
        flush()
        return map
    }

    fun headline(md: String): String =
        sections(md)["한 줄 요약"]?.takeIf { it.isNotBlank() }
            ?: md.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }.orEmpty()

    fun bullets(body: String?): List<String> =
        body?.lines()?.map { it.trim() }?.filter { it.startsWith("- ") }?.map { it.drop(2).trim() } ?: emptyList()
}
