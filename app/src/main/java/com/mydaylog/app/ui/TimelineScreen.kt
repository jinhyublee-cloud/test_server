package com.mydaylog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydaylog.app.data.Classify
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Repo
import com.mydaylog.app.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

/** 같은 카톡방의 연속 메시지를 한 줄로 묶음 */
data class TRow(val e: Entry, val more: List<Entry>)

fun buildRows(desc: List<Entry>): List<TRow> {
    val out = ArrayList<TRow>()
    for (e in desc) {
        val last = out.lastOrNull()
        if (last != null && e.source == Source.KAKAO && last.e.source == Source.KAKAO && e.convKey == last.e.convKey) {
            out[out.lastIndex] = last.copy(more = last.more + e)
        } else out += TRow(e, emptyList())
    }
    return out
}

@Composable
fun TimelineScreen(day: LocalDate, onDay: (LocalDate) -> Unit, filter: Source?, onFilter: (Source?) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(day, tick) { entries = withContext(Dispatchers.IO) { Repo.entriesForDay(ctx, day) } }

    val q = query.trim()
    val shown = remember(entries, filter, q) {
        entries.filter { e ->
            (filter == null || e.source == filter) &&
                (q.isEmpty() || listOf(e.title, e.text, e.conv.orEmpty(), e.app).any { it.contains(q, ignoreCase = true) })
        }.sortedByDescending { it.ts }
    }
    val groups = remember(shown) {
        listOf("저녁" to 18..23, "오후" to 12..17, "오전" to 0..11)
            .map { (n, r) -> n to buildRows(shown.filter { hourOf(it.ts) in r }) }
            .filter { it.second.isNotEmpty() }
    }
    val now = System.currentTimeMillis()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("타임라인", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${day.year}년 ${day.monthValue}월", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onDay(day.minusWeeks(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 주") }
                IconButton(onClick = { onDay(minOf(day.plusWeeks(1), LocalDate.now())) }, enabled = day.isBefore(LocalDate.now())) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 주")
                }
            }
        }
        item { WeekStrip(day, onDay) }
        item {
            PrivateTextField(
                value = query, onChange = { query = it }, hint = "사람이나 내용으로 찾기",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("전체 ${entries.size}") })
                Source.entries.forEach { s ->
                    FilterChip(
                        selected = filter == s, onClick = { onFilter(if (filter == s) null else s) },
                        label = { Text("${s.label} ${entries.count { it.source == s }}", color = if (filter == s) Color.Unspecified else srcColor(s).fg) },
                    )
                }
            }
        }
        if (groups.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (q.isNotEmpty()) "'$q'에 해당하는 기록이 없어요" else "이 날 기록이 없어요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { (name, rows) ->
            item(key = "h$name") {
                Text(
                    name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(rows, key = { _, r -> r.e.key }) { i, r -> TimelineRow(r, i == 0, i == rows.lastIndex, now) }
        }
    }
}

@Composable
private fun WeekStrip(day: LocalDate, onDay: (LocalDate) -> Unit) {
    val start = day.with(DayOfWeek.MONDAY)
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0L..6L).forEach { i ->
            val d = start.plusDays(i)
            val sel = d == day
            val enabled = !d.isAfter(today)
            val fg = when {
                sel -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.outline
            }
            Column(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = enabled, onClickLabel = dateLong(d)) { onDay(d) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(dow(d), fontSize = 12.sp, color = if (sel) fg else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${d.dayOfMonth}", fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = fg)
            }
        }
    }
}

@Composable
fun EntryBadge(e: Entry, now: Long) {
    val cs = MaterialTheme.colorScheme
    when {
        e.isMissed -> Tag("부재중", cs.error, cs.errorContainer)
        e.source == Source.CAL && e.ts > now -> Tag("예정", cs.primary, cs.primaryContainer)
        Classify.isPayment(e) -> srcColor(Source.SMS).let { Tag("결제", it.fg, it.bg) }
        Classify.isDeposit(e) -> srcColor(Source.SMS).let { Tag("입금", it.fg, it.bg) }
        Classify.isDelivery(e) -> srcColor(Source.APP).let { Tag("배송", it.fg, it.bg) }
    }
}

@Composable
private fun TimelineRow(r: TRow, first: Boolean, last: Boolean, now: Long) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val top = if (first) 16.dp else 0.dp
    val bottom = if (last) 16.dp else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable { openEntry(ctx, r.e) }.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (r.e.allDay) "종일" else timeOf(r.e.ts), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp).padding(top = 8.dp),
            )
            SourceIcon(r.e.source)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        headOf(r.e), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (r.more.isNotEmpty()) Text(
                        "  메시지 ${r.more.size + 1}개", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(bodyOf(r.e), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (r.more.isNotEmpty()) {
                    if (expanded) r.more.forEach { m ->
                        Text(
                            "${timeOf(m.ts)}  ${bodyOf(m)}", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                        Text(if (expanded) "접기" else "${r.more.size}개 더 보기", fontSize = 13.sp)
                    }
                }
            }
            EntryBadge(r.e, now)
        }
        if (!last) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
