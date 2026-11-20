package com.mydaylog.app.insight

import android.content.Context
import com.mydaylog.app.Prefs
import com.mydaylog.app.data.Db
import com.mydaylog.app.data.Repo
import com.mydaylog.app.data.Source
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class PlanView(val key: String, val status: String, val plan: Plan, val inCalendar: Boolean) {
    /** 확인이 필요한 약속 (아직 캘린더에 없고 사용자가 정리하지 않음) */
    val pending get() = (status == "new" || status == "edited") && !inCalendar
}

data class DeadlineView(val key: String, val status: String, val dl: Deadline) {
    val dDay: Long get() = ChronoUnit.DAYS.between(LocalDate.now(), dl.due)
    val open get() = status == "new" || status == "edited"
}

/** 챙길 것(약속·마감·정기 결제·메일)을 폰 안에서만 분석하고 암호화해 저장 */
object Insights {
    private const val DAY = 86_400_000L
    const val DELETED = "deleted"

    /** 새로 들어온 알림 분석. 카톡 약속은 대화 흐름을 보려고 이틀 전부터 함께 읽음 */
    fun refresh(ctx: Context) {
        val db = Db.get(ctx)
        val now = System.currentTimeMillis()
        // 2.2: 대화 흐름 기반 엔진으로 바뀜 → 손대지 않은 예전 약속은 지우고 35일치를 다시 분석
        // 2.3: 결제·마감·행사 규칙이 바뀜 → 손대지 않은 약속은 지우고 35일치 다시 분석
        if (Prefs.planEngine(ctx) < 3) {
            db.deleteInsightsByStatus("plan", "new")
            Prefs.setLastScan(ctx, 0L)
            Prefs.setPlanEngine(ctx, 3)
        }
        val since = maxOf(Prefs.lastScan(ctx) - 2 * DAY, now - 35 * DAY)
        val notis = db.notisSince(since)
        val entries = notis.map { Repo.entryOf(it) }

        // 약속: 카톡은 대화 단위, 문자는 날짜가 분명한 한 통 단위
        val plans = ChatPlanner.plans(entries) + entries.mapNotNull { PlanFinder.find(it) }
        val emitted = HashSet<String>()
        plans.forEach { p ->
            val due = if (p.dateKnown) epoch(p.at) else p.msgTs + 14 * DAY
            db.upsertInsight(p.plainKey, "plan", p.toJson(), due)
            emitted += db.keyOf(p.plainKey)
        }
        // 같은 대화에서 예전에 잡았던(아직 손대지 않은) 약속은 최신 결과로 대체
        val threads = plans.map { it.thread }.toSet()
        db.insights("plan").filter { it.status == "new" && it.key !in emitted }.forEach { r ->
            val p = runCatching { Plan.fromJson(r.data) }.getOrNull()
            if (p != null && p.thread in threads) db.deleteInsight(r.key)
        }

        notis.forEachIndexed { i, n ->
            val e = entries[i]
            DeadlineFinder.find(e)?.let { db.upsertInsight(it.plainKey, "dl", it.toJson(), epoch(it.due.atTime(23, 59))) }
            RecurringFinder.paymentOf(e)?.let { db.addPayment("pay|${n.id}|${it.scheduled}", it.ts, it.toJson()) }
        }
        Prefs.setLastScan(ctx, now)
    }

    // ---------- 약속 ----------
    fun plans(ctx: Context, includeDeleted: Boolean = false): List<PlanView> {
        val now = System.currentTimeMillis()
        val rows = Db.get(ctx).insights("plan", now - 3 * 3_600_000L)
            .filter { it.status != "dismissed" && (includeDeleted || it.status != DELETED) }
        return rows.mapNotNull { r ->
            val p = runCatching { Plan.fromJson(r.data) }.getOrNull() ?: return@mapNotNull null
            PlanView(r.key, r.status, p, p.dateKnown && inCalendar(ctx, p))
        }
    }

    private fun inCalendar(ctx: Context, p: Plan): Boolean {
        val events = Repo.calendarDay(ctx, p.at.toLocalDate())
        if (!p.hasTime) return events.any { it.title.contains(p.who) }
        val t = epoch(p.at)
        return events.any { !it.allDay && kotlin.math.abs(it.ts - t) <= 90 * 60_000L }
    }

    /** 사용자가 고친 약속: 이후 대화 분석이 덮어쓰지 않음 */
    fun updatePlan(ctx: Context, key: String, p: Plan) {
        val db = Db.get(ctx)
        db.updateInsightData(key, p.copy(dateKnown = true).toJson(), epoch(p.at))
        db.setInsightStatus(key, "edited")
    }

    // ---------- 마감 ----------
    fun deadlines(ctx: Context, includePast: Boolean = true, includeDeleted: Boolean = false): List<DeadlineView> {
        val from = System.currentTimeMillis() - (if (includePast) 14 else 0) * DAY
        return Db.get(ctx).insights("dl", from)
            .filter { it.status != "dismissed" && (includeDeleted || it.status != DELETED) }
            .mapNotNull { r -> runCatching { DeadlineView(r.key, r.status, Deadline.fromJson(r.data)) }.getOrNull() }
    }

    fun updateDeadline(ctx: Context, key: String, d: Deadline) {
        val db = Db.get(ctx)
        db.updateInsightData(key, d.toJson(), epoch(d.due.atTime(23, 59)))
        db.setInsightStatus(key, "edited")
    }

    fun setStatus(ctx: Context, key: String, status: String) = Db.get(ctx).setInsightStatus(key, status)

    // ---------- 정기 결제 ----------
    data class RecurringEdit(val name: String, val amount: Long, val day: Int)

    fun recurring(ctx: Context): List<Recurring> {
        val db = Db.get(ctx)
        val pays = db.paymentsSince(System.currentTimeMillis() - 400 * DAY).mapNotNull {
            runCatching { Payment.fromRow(it.ts, it.data) }.getOrNull()
        }
        val today = LocalDate.now()
        val found = RecurringFinder.detect(pays, today) { db.decision(it) }
        // 사용자가 고친 이름·금액·결제일 반영
        return found.map { r ->
            val row = db.decisionRow(r.key) ?: return@map r
            val o = runCatching { JSONObject(row.second) }.getOrNull() ?: return@map r
            if (!o.has("name")) return@map r
            val day = o.getInt("day")
            val ym = YearMonth.from(if (r.paidThisMonth) today.plusMonths(1) else today)
            r.copy(merchant = o.getString("name"), amount = o.getLong("amount"), day = day,
                next = ym.atDay(minOf(day, ym.lengthOfMonth())), confirmed = true)
        }.sortedBy { it.day }
    }

    fun editRecurring(ctx: Context, r: Recurring, e: RecurringEdit) = Db.get(ctx).setDecision(
        r.key, "rc", "confirmed",
        JSONObject().put("name", e.name).put("amount", e.amount).put("day", e.day).toString(),
    )

    fun decide(ctx: Context, plainKey: String, confirmed: Boolean) =
        Db.get(ctx).setDecision(plainKey, "rc", if (confirmed) "confirmed" else "dismissed")

    /** 정기 결제 아님으로 지운 항목 (되돌리기용) */
    fun dismissedRecurring(ctx: Context): List<Recurring> {
        val db = Db.get(ctx)
        val pays = db.paymentsSince(System.currentTimeMillis() - 400 * DAY).mapNotNull {
            runCatching { Payment.fromRow(it.ts, it.data) }.getOrNull()
        }
        val dismissed = HashSet<String>()
        val all = RecurringFinder.detect(pays, LocalDate.now()) { k ->
            val d = db.decision(k)
            if (d == "dismissed") { dismissed += k; null } else d
        }
        return all.filter { it.key in dismissed }
    }

    fun restoreRecurring(ctx: Context, plainKey: String) = Db.get(ctx).let { it.deleteInsight(it.keyOf(plainKey)) }

    /** 이번 달 카드·결제 내역 (예정 안내 제외, 최신순) */
    fun paymentsThisMonth(ctx: Context): List<Payment> {
        val start = epoch(LocalDate.now().withDayOfMonth(1).atStartOfDay())
        val f = com.mydaylog.app.data.MoneyExclude.filter(ctx)
        return Db.get(ctx).paymentsSince(start).mapNotNull { r ->
            runCatching { Payment.fromRow(r.ts, r.data).let { p -> p.copy(id = r.hash, excluded = r.hash in f.ids || f.ruled(p.merchant, false)) } }.getOrNull()
        }.filter { !it.scheduled }.sortedByDescending { it.ts }
    }

    // ---------- 메일 ----------
    fun mails(ctx: Context, days: Int = 7): List<MailItem> {
        val since = System.currentTimeMillis() - days * DAY
        val mails = Db.get(ctx).notisSince(since).map { Repo.entryOf(it) }.filter { it.source == Source.MAIL }
        return MailDigest.digest(mails)
    }

    /** 알림용: 오늘 챙길 것 개수 (내용 없이) */
    fun dueCounts(ctx: Context): Pair<Int, Int> {
        val today = LocalDate.now()
        val dl = deadlines(ctx, includePast = false).count { it.open && it.dDay in setOf(0L, 1L, 3L) }
        val rc = recurring(ctx).count { it.confirmed && !it.paidThisMonth && ChronoUnit.DAYS.between(today, it.next) in 0..3 }
        return dl to rc
    }
}
