package com.mydaylog.app.data

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 하루의 지출·수입 (합계에서 뺀 항목은 items에는 있고 합계에는 없음) */
data class DayMoney(val spend: Long, val income: Long, val items: List<MoneyPick>) {
    val excludedCount get() = items.count { it.excluded }
}

/** 달력용 날짜별 지출·수입 */
object MoneyDays {
    fun month(ctx: Context, ym: YearMonth): Map<LocalDate, DayMoney> {
        val today = LocalDate.now()
        val from = ym.atDay(1)
        if (from.isAfter(today)) return emptyMap()
        val to = minOf(ym.atEndOfMonth(), today)
        return group(MoneyExclude.picks(ctx, from, to))
    }

    /** 순수 계산부 (단위 테스트 대상) */
    fun group(picks: List<MoneyPick>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayMoney> =
        picks.groupBy { Instant.ofEpochMilli(it.entry.ts).atZone(zone).toLocalDate() }
            .mapValues { (_, list) ->
                val sorted = list.sortedBy { it.entry.ts }
                DayMoney(
                    sorted.filter { !it.deposit && !it.excluded }.sumOf { it.amount },
                    sorted.filter { it.deposit && !it.excluded }.sumOf { it.amount },
                    sorted,
                )
            }

    /** 달력 칸에 들어갈 짧은 금액: 4,500 / 1.7만 / 123만 */
    fun compact(v: Long): String = when {
        v < 10_000 -> "%,d".format(v)
        v < 1_000_000 -> {
            val x = Math.round(v / 1_000.0) / 10.0
            if (x % 1.0 == 0.0) "${x.toLong()}만" else "${x}만"
        }
        v < 100_000_000 -> "${Math.round(v / 10_000.0)}만"
        else -> "%.1f억".format(v / 100_000_000.0)
    }
}
