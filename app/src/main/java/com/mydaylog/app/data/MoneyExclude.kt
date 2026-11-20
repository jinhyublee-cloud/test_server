package com.mydaylog.app.data

import android.content.Context
import com.mydaylog.app.security.SecLog
import org.json.JSONObject

/**
 * 합계에서 뺄 결제·입금 (사용자가 직접 지정)
 * - 기록 자체는 지우지 않고 합계에서만 뺌 → 언제든 되돌릴 수 있음
 * - 한 건만 빼기, 또는 "이 곳은 항상 빼기" (내 계좌끼리 이체, 회사 법인카드 등)
 * - 키는 HMAC, 내용은 암호화해 insights 표에 저장 (기존 보안 방식과 같음)
 */
data class MoneyFilter(val ids: Set<String>, val rules: Set<String>) {
    fun ruled(label: String, deposit: Boolean) = rule(label, deposit) in rules
    val isEmpty get() = ids.isEmpty() && rules.isEmpty()

    companion object {
        val NONE = MoneyFilter(emptySet(), emptySet())
        fun rule(label: String, deposit: Boolean) = (if (deposit) "d|" else "p|") + label.lowercase().replace(Regex("""\s+"""), "")
    }
}

/** 고르기 화면의 결제·입금 한 건 */
data class MoneyPick(
    val entry: Entry, val id: String, val label: String, val amount: Long, val deposit: Boolean, val card: String?,
    val byItem: Boolean, val byRule: Boolean,
) {
    val excluded get() = byItem || byRule
}

data class ExcludedItem(
    val id: String, val label: String, val amount: Long, val deposit: Boolean, val ts: Long,
    /** true면 "항상 빼기" 규칙 */
    val always: Boolean,
)

object MoneyExclude {
    private const val ITEM = "mx"
    private const val RULE = "mxr"
    private const val ON = "excluded"

    /** 결제·입금 한 건의 ID. 결제 탭(payments 표)의 키와 같아서 홈·결제 탭·물어보기가 같은 항목으로 봄 */
    fun idOf(ctx: Context, e: Entry): String = Db.get(ctx).keyOf("pay|${e.key.removePrefix("n")}|false")

    fun filter(ctx: Context): MoneyFilter {
        val db = Db.get(ctx)
        val ids = db.insights(ITEM).filter { it.status == ON }.mapNotNull { runCatching { JSONObject(it.data).getString("id") }.getOrNull() }
        val rules = db.insights(RULE).filter { it.status == ON }.mapNotNull { runCatching { JSONObject(it.data).getString("r") }.getOrNull() }
        return MoneyFilter(ids.toSet(), rules.toSet())
    }

    /** 이 기록을 합계에서 빼야 하는지 (결제·입금이 아니면 항상 false) */
    fun excludedFn(ctx: Context): (Entry) -> Boolean {
        val f = filter(ctx)
        if (f.isEmpty) return { false }
        val cache = HashMap<String, Boolean>()
        return { e ->
            cache.getOrPut(e.key) {
                val dep = Classify.isDeposit(e)
                (dep || Classify.isPayment(e)) && (f.ruled(Classify.moneyLabel(e), dep) || idOf(ctx, e) in f.ids)
            }
        }
    }

    fun exclude(ctx: Context, id: String, label: String, amount: Long, deposit: Boolean, ts: Long) {
        val json = JSONObject().put("id", id).put("l", label).put("a", amount).put("d", deposit).put("t", ts).toString()
        Db.get(ctx).setDecision("mx|$id", ITEM, ON, json)
        SecLog.add(ctx, "합계에서 빼기 · ${if (deposit) "입금" else "결제"} 1건")
    }

    fun exclude(ctx: Context, e: Entry) {
        val dep = Classify.isDeposit(e)
        exclude(ctx, idOf(ctx, e), Classify.moneyLabel(e), Classify.amount(e.text) ?: 0L, dep, e.ts)
    }

    fun excludeAlways(ctx: Context, label: String, deposit: Boolean) {
        val r = MoneyFilter.rule(label, deposit)
        val json = JSONObject().put("r", r).put("l", label).put("d", deposit).toString()
        Db.get(ctx).setDecision("mxr|$r", RULE, ON, json)
        SecLog.add(ctx, "합계에서 항상 빼기 규칙 추가")
    }

    fun include(ctx: Context, id: String) = Db.get(ctx).let { it.deleteInsight(it.keyOf("mx|$id")) }

    fun removeRule(ctx: Context, label: String, deposit: Boolean) =
        Db.get(ctx).let { it.deleteInsight(it.keyOf("mxr|${MoneyFilter.rule(label, deposit)}")) }

    /** 다시 넣기 (한 건 제외와 그 곳의 "항상 빼기" 규칙 모두 해제) */
    fun includeEverywhere(ctx: Context, id: String, label: String, deposit: Boolean) {
        include(ctx, id)
        removeRule(ctx, label, deposit)
    }

    /** 기간 안의 결제·입금 전체 (고르기 화면용, 최신순) */
    fun picks(ctx: Context, from: java.time.LocalDate, to: java.time.LocalDate): List<MoneyPick> {
        val f = filter(ctx)
        return Repo.entriesBetween(ctx, from, to)
            .filter { !Classify.isAd(it) && (Classify.isPayment(it) || Classify.isDeposit(it)) }
            .map { e ->
                val dep = Classify.isDeposit(e)
                val id = idOf(ctx, e)
                val label = Classify.moneyLabel(e)
                MoneyPick(e, id, label, Classify.amount(e.text) ?: 0L, dep, Classify.cardOf(e), id in f.ids, f.ruled(label, dep))
            }
            .sortedByDescending { it.entry.ts }
    }

    /**
     * 고르기 저장: keep = 합계에 넣을 항목 id
     * - 체크를 푼 항목 → 합계에서 빼기
     * - 다시 체크한 항목 → 한 건 제외 해제, "항상 빼기"로 빠졌던 곳이면 그 규칙도 해제
     */
    fun saveSelection(ctx: Context, picks: List<MoneyPick>, keep: Set<String>) {
        var out = 0; var back = 0
        picks.forEach { p ->
            val want = p.id in keep
            when {
                want && p.excluded -> { if (p.byItem) include(ctx, p.id); if (p.byRule) removeRule(ctx, p.label, p.deposit); back++ }
                !want && !p.excluded -> { exclude(ctx, p.id, p.label, p.amount, p.deposit, p.entry.ts); out++ }
            }
        }
        if (out + back > 0) SecLog.add(ctx, "합계 항목 고르기 · 빼기 $out · 다시 넣기 $back")
    }

    /** 결제 탭의 "합계에서 뺀 항목" 목록 */
    fun list(ctx: Context): List<ExcludedItem> {
        val db = Db.get(ctx)
        val items = db.insights(ITEM).filter { it.status == ON }.mapNotNull { r ->
            runCatching {
                val o = JSONObject(r.data)
                ExcludedItem(o.getString("id"), o.getString("l"), o.getLong("a"), o.getBoolean("d"), o.getLong("t"), false)
            }.getOrNull()
        }.sortedByDescending { it.ts }
        val rules = db.insights(RULE).filter { it.status == ON }.mapNotNull { r ->
            runCatching {
                val o = JSONObject(r.data)
                ExcludedItem(o.getString("r"), o.getString("l"), 0L, o.getBoolean("d"), r.updated, true)
            }.getOrNull()
        }
        return rules + items
    }
}
