package com.mydaylog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydaylog.app.Prefs
import com.mydaylog.app.data.DayMoney
import com.mydaylog.app.data.MoneyDays
import com.mydaylog.app.data.MoneyExclude
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * 날짜별 지출·수입 달력
 * - 칸마다 그날 지출(-)과 수입(+), 날짜를 누르면 아래에 그날 내역
 * - 합계에서 뺀 항목은 칸 금액에 넣지 않음
 */
@Composable
fun MoneyCalendar(onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var ym by remember { mutableStateOf(YearMonth.from(today)) }
    var sel by remember { mutableStateOf(today) }
    var reload by remember { mutableIntStateOf(0) }
    var days by remember { mutableStateOf<Map<LocalDate, DayMoney>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val tick = rememberResumeTick()

    LaunchedEffect(ym, reload, tick) {
        days = null
        days = withContext(Dispatchers.IO) { runCatching { MoneyDays.month(ctx, ym) }.getOrDefault(emptyMap()) }
    }
    fun changed() { reload++; onChanged() }
    fun act(block: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { block() }; changed() } }

    if (picking) MoneyPicker(sel, sel, "${sel.monthValue}월 ${sel.dayOfMonth}일") { c -> picking = false; if (c) changed() }

    val ok = successColor()
    Panel {
        // ---- 월 이동 + 월 합계 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ym = ym.minusMonths(1); sel = ym.atDay(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
            Text("${ym.year}년 ${ym.monthValue}월", fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { ym = ym.plusMonths(1); sel = if (ym == YearMonth.from(today)) today else ym.atDay(1) }, enabled = ym < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
            }
        }
        val d = days.orEmpty()
        val spend = d.values.sumOf { it.spend }
        val income = d.values.sumOf { it.income }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("지출", won(spend), cs.onSurface, Modifier.weight(1f))
            Metric("수입", "+" + won(income), ok, Modifier.weight(1f))
        }
        val out = d.values.sumOf { it.excludedCount }
        if (out > 0) Text("합계에서 뺀 항목 ${out}건은 금액에 넣지 않았어요", fontSize = 12.sp, color = cs.onSurfaceVariant)
        val cutoff = today.minusDays(Prefs.retentionDays(ctx).toLong())
        if (ym.atDay(1) < cutoff) Text("기록 보관 기간(${Prefs.retentionDays(ctx)}일)보다 오래된 날은 금액이 없을 수 있어요", fontSize = 12.sp, color = cs.onSurfaceVariant)

        // ---- 달력 ----
        Row { listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, w ->
            Text(w, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                color = when (i) { 5 -> cs.primary; 6 -> cs.error; else -> cs.onSurfaceVariant })
        } }
        val lead = ym.atDay(1).dayOfWeek.value - 1
        val cells = List(lead) { 0 } + (1..ym.lengthOfMonth()).toList()
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    (0 until 7).forEach { i ->
                        val n = week.getOrNull(i) ?: 0
                        if (n == 0) { Box(Modifier.weight(1f).height(58.dp)); return@forEach }
                        val date = ym.atDay(n)
                        val m = d[date]
                        val future = date.isAfter(today)
                        val selected = date == sel
                        Column(
                            Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) cs.primaryContainer else Color.Transparent)
                                .then(if (date == today) Modifier.border(1.5.dp, cs.primary, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable(enabled = !future) { sel = date }
                                .padding(top = 4.dp, start = 2.dp, end = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("$n", fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = when { future -> cs.outline; selected -> cs.onPrimaryContainer; i == 6 -> cs.error; i == 5 -> cs.primary; else -> cs.onSurface })
                            if (m != null && m.spend > 0) Text("-" + MoneyDays.compact(m.spend), fontSize = 10.sp, maxLines = 1, color = cs.onSurface)
                            if (m != null && m.income > 0) Text("+" + MoneyDays.compact(m.income), fontSize = 10.sp, maxLines = 1, color = ok)
                        }
                    }
                }
            }
        }
        if (days == null) Text("불러오는 중…", fontSize = 12.sp, color = cs.onSurfaceVariant)

        // ---- 고른 날 내역 ----
        Divider()
        val day = d[sel]
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${sel.monthValue}월 ${sel.dayOfMonth}일(${dow(sel)})", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("지출 ${won(day?.spend ?: 0)} · 수입 +${won(day?.income ?: 0)}", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        if (day == null || day.items.isEmpty()) {
            Text(if (sel.isAfter(today)) "아직 오지 않은 날이에요" else "이날은 결제·입금 기록이 없어요", fontSize = 14.sp, color = cs.onSurfaceVariant)
        } else {
            day.items.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(timeOf(p.entry.ts), fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(42.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (p.excluded) cs.onSurfaceVariant else cs.onSurface)
                        val sub = listOfNotNull(if (p.deposit) "입금" else "결제", p.card, "합계 제외".takeIf { p.excluded }).joinToString(" · ")
                        Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Text(
                        (if (p.deposit) "+" else "") + won(p.amount), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = when { p.excluded -> cs.onSurfaceVariant; p.deposit -> ok; else -> cs.onSurface },
                        textDecoration = if (p.excluded) TextDecoration.LineThrough else null,
                    )
                    RowMenu(
                        (if (p.excluded) listOf("합계에 다시 넣기" to { act { MoneyExclude.includeEverywhere(ctx, p.id, p.label, p.deposit) } })
                        else listOf(
                            "합계에서 빼기" to { act { MoneyExclude.exclude(ctx, p.id, p.label, p.amount, p.deposit, p.entry.ts) } },
                            "‘${p.label}’ 항상 빼기" to { act { MoneyExclude.excludeAlways(ctx, p.label, p.deposit) } },
                        )) + ("원래 문자·알림 열기" to { openEntry(ctx, p.entry) }),
                    )
                }
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("이날 합계에 넣을 항목 고르기") }
        }
    }
}
