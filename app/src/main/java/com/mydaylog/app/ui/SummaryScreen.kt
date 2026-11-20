package com.mydaylog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydaylog.app.data.Dash
import com.mydaylog.app.data.Db
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Journal
import com.mydaylog.app.data.Repo
import com.mydaylog.app.data.Source
import com.mydaylog.app.summary.JournalParse
import com.mydaylog.app.summary.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun SummaryScreen(day: LocalDate, onDay: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    var reload by remember { mutableIntStateOf(0) }
    var dash by remember { mutableStateOf<Dash?>(null) }
    var journal by remember { mutableStateOf<Journal?>(null) }
    var nextDay by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var isDone by remember { mutableStateOf<(String) -> Boolean>({ false }) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(day, tick, reload) {
        withContext(Dispatchers.IO) {
            val d = Dash.build(ctx, day)
            val j = Db.get(ctx).journal(day.toString())
            val n = Repo.calendarDay(ctx, day.plusDays(1))
            val done = Db.get(ctx).isDoneFn(day.toString())
            withContext(Dispatchers.Main) { dash = d; journal = j; nextDay = n; isDone = done }
        }
    }

    fun generate() {
        busy = true
        scope.launch {
            Summarizer.generate(ctx, day.toString()).onSuccess { journal = it }
            busy = false
            reload++
        }
    }

    val sec = journal?.let { JournalParse.sections(it.content) } ?: emptyMap()
    val today = day == LocalDate.now()
    val d = dash

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDay(day.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 날") }
            Text("하루 요약", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { onDay(day.plusDays(1)) }, enabled = !today) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 날") }
        }

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dateLong(day), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                journal?.let { JournalParse.headline(it.content) } ?: "아직 요약이 없어요",
                fontSize = 22.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold,
            )
            if (d != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Source.entries.forEach { s ->
                    val n = d.counts[s] ?: 0
                    if (n > 0) srcColor(s).let { Tag("${s.label} $n", it.fg, it.bg) }
                }
            }
        }

        if (busy) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        if (journal == null && !busy) Panel {
            Text("모인 기록 ${d?.entries?.size ?: 0}건으로 하루 요약을 만들 수 있어요.", fontSize = 15.sp)
            Button(onClick = { generate() }, enabled = (d?.entries?.size ?: 0) > 0) { Text("요약 만들기") }
        }

        if (d != null && d.entries.isNotEmpty()) HourlyPanel(d.hourly)

        val periods = listOf("오전", "오후", "저녁").filter { !sec[it].isNullOrBlank() }
        if (periods.isNotEmpty()) Panel {
            PanelHeader(if (today) "오늘의 흐름" else "하루의 흐름")
            periods.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(p, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, softWrap = false, modifier = Modifier.widthIn(min = 36.dp).padding(top = 1.dp))
                    val items = JournalParse.bullets(sec[p])
                    if (items.isNotEmpty()) BulletList(items) else Text(sec[p]!!, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        } else if (journal != null && sec["한 줄 요약"] == null) Panel {
            // 이전 버전에서 만든 요약
            Text(journal!!.content, fontSize = 14.sp, lineHeight = 21.sp)
        }

        if (d != null && d.contacts.isNotEmpty()) Panel {
            PanelHeader("많이 연락한 사람")
            d.contacts.forEach { c ->
                val col = srcColor(c.source)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(col.bg), contentAlignment = Alignment.Center) {
                        Text(c.name.take(2), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = col.fg, maxLines = 1)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val cal = d?.entries?.filter { it.source == Source.CAL }.orEmpty()
        if (d != null && (cal.isNotEmpty() || d.money.isNotEmpty())) Panel {
            PanelHeader("일정과 돈")
            val now = System.currentTimeMillis()
            cal.forEach { e ->
                val ended = (e.endTs ?: e.ts) <= now
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ended) Icon(Icons.Filled.Check, "끝남", tint = successColor(), modifier = Modifier.size(18.dp))
                    else Box(Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                    Text("${if (e.allDay) "종일" else timeOf(e.ts)} ${e.title}", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!ended) Text("예정", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (cal.isNotEmpty() && d.money.isNotEmpty()) Divider()
            if (d.money.isNotEmpty()) {
                Row {
                    Text("지출 (${d.money.count { !it.deposit }}건)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(won(d.spend), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Row {
                    Text("입금 (${d.money.count { it.deposit }}건)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("+" + won(d.deposit), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = successColor())
                }
            }
        }

        val todoItems = JournalParse.bullets(sec["챙겨야 할 일"]).ifEmpty { d?.todos?.map { it.title }.orEmpty() }
        if (todoItems.isNotEmpty()) Panel {
            PanelHeader("챙겨야 할 일")
            todoItems.forEach { item ->
                val key = "todo:$item"
                val checked = isDone(key)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { v ->
                        scope.launch {
                            withContext(Dispatchers.IO) { Db.get(ctx).setDone(key, day.toString(), v) }
                            reload++
                        }
                    })
                    Text(
                        item, fontSize = 14.sp,
                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        val tomorrow = JournalParse.bullets(sec["내일 이후"]).ifEmpty {
            nextDay.take(4).map { "${if (today) "내일" else "다음 날"} ${if (it.allDay) "종일" else timeOf(it.ts)} ${it.title}" }
        }
        if (tomorrow.isNotEmpty()) Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (today) "내일 미리보기" else "다음 날 미리보기", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            BulletList(tomorrow, MaterialTheme.colorScheme.onPrimaryContainer)
        }

        if (journal != null) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { generate() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
            ) { Text("최신 기록으로 다시 만들기", fontWeight = FontWeight.Bold) }
            Text(
                "기록 ${d?.entries?.size ?: 0}건 · 인터넷 연결 없이 이 폰 안에서 생성 · ${timeOf(journal!!.createdAt)}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HourlyPanel(hourly: IntArray) {
    val hrs = 6..23
    val max = hrs.maxOf { hourly[it] }.coerceAtLeast(1)
    val peak = hrs.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 }
    Panel {
        PanelHeader("시간대별 활동", trailing = peak?.let { "가장 바쁨 ${it}–${it + 1}시" })
        Row(
            Modifier.fillMaxWidth().height(96.dp).semantics {
                contentDescription = "시간대별 기록 수 막대그래프" + (peak?.let { ", ${it}시가 가장 많음" } ?: "")
            },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            hrs.forEach { h ->
                val f = (hourly[h].toFloat() / max).coerceAtLeast(0.04f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(3.dp))
                        .background(if (h == peak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("06시", "12시", "18시", "23시").forEach {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
