package com.mydaylog.app.ui

import com.mydaylog.app.data.MoneyExclude
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydaylog.app.R
import com.mydaylog.app.data.Dash
import com.mydaylog.app.data.Db
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Journal
import com.mydaylog.app.data.Repo
import com.mydaylog.app.data.Source
import com.mydaylog.app.data.Sub
import com.mydaylog.app.data.Todo
import com.mydaylog.app.data.TodoKind
import com.mydaylog.app.summary.JournalParse
import com.mydaylog.app.summary.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun HomeScreen(
    day: LocalDate,
    onDay: (LocalDate) -> Unit,
    openTimeline: (Source?) -> Unit,
    openSummary: () -> Unit,
    openSettings: () -> Unit,
    openInbox: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    var reload by remember { mutableIntStateOf(0) }
    var dash by remember { mutableStateOf<Dash?>(null) }
    var journal by remember { mutableStateOf<Journal?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inbox by remember { mutableStateOf(InboxData()) }

    LaunchedEffect(day, tick, reload) {
        val r = withContext(Dispatchers.IO) {
            runCatching { Dash.build(ctx, day) to Db.get(ctx).journal(day.toString()) }.getOrNull()
        }
        if (r != null) { dash = r.first; journal = r.second }
        inbox = withContext(Dispatchers.IO) { loadInbox(ctx) }
    }
    val missing = remember(tick) {
        buildList {
            if (!isListenerEnabled(ctx)) add("알림")
            if (!Repo.hasCalendar(ctx)) add("캘린더")
            if (!Repo.hasCallLog(ctx)) add("통화 기록")
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeHeader(day, onDay, dash?.entries?.size, openSettings) { reload++ }
        if (missing.isNotEmpty()) PermBanner(missing, openSettings)
        SummaryCard(day, journal, dash, busy, { generate() }, openSummary)
        if (day == LocalDate.now()) {
            PlansSoonCard(inbox.plans) { openInbox(0) }
            DeadlineSoonCard(inbox.deadlines) { openInbox(1) }
            RecurringMonthCard(inbox.recurring) { openInbox(2) }
        }
        val d = dash ?: return@Column
        StatsGrid(d, openTimeline)
        if (d.upcoming.isNotEmpty()) UpcomingCard(d.upcoming)
        if (d.todos.isNotEmpty()) TodoCard(d.todos) { t, done ->
            scope.launch {
                withContext(Dispatchers.IO) { Db.get(ctx).setDone(t.key, day.toString(), done) }
                reload++
            }
        }
        if (d.money.isNotEmpty()) MoneyCard(d, day == LocalDate.now(), onCalendar = { openInbox(2) }) { reload++ }
        if (d.recent.isNotEmpty()) RecentCard(d.recent) { openTimeline(null) }
    }
}

@Composable
private fun HomeHeader(day: LocalDate, onDay: (LocalDate) -> Unit, count: Int?, onSettings: () -> Unit, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val today = day == LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).clickable { pickDate(ctx, day, onDay) }) {
            Text(dateLong(day), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (today) "오늘 하루" else "${day.monthValue}월 ${day.dayOfMonth}일",
                fontSize = 26.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                count?.let { "기록 ${it}건 · 이 폰 안에만 저장" } ?: "불러오는 중…",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onDay(day.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 날") }
        IconButton(onClick = { onDay(day.plusDays(1)) }, enabled = !today) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 날") }
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "새로고침") }
        IconButton(onClick = onSettings) { Icon(androidx.compose.material.icons.Icons.Outlined.Settings, "설정") }
    }
}

@Composable
private fun PermBanner(missing: List<String>, openSettings: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${missing.joinToString("·")} 권한을 켜면 더 많은 기록을 모아요",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = openSettings) { Text("연결하기") }
    }
}

@Composable
private fun SummaryCard(
    day: LocalDate, journal: Journal?, dash: Dash?, busy: Boolean,
    onGenerate: () -> Unit, onOpen: () -> Unit,
) {
    val white = Color.White
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Indigo).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sparkle), null, tint = white, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (day == LocalDate.now()) "오늘의 요약" else "이 날의 요약",
                color = white, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f),
            )
            journal?.let {
                Text(
                    "폰에서 생성 · ${timeOf(it.createdAt)}", color = white, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(white.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = white, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Text("하루를 정리하는 중…", color = white)
            }
            journal != null -> Text(JournalParse.headline(journal.content), color = white, fontSize = 16.sp, lineHeight = 25.sp)
            else -> Text("아직 요약이 없어요. 지금까지 모인 기록으로 바로 만들 수 있어요.", color = white, fontSize = 15.sp, lineHeight = 23.sp)
        }
        if (dash != null) {
            val chips = buildList {
                val c = dash.counts[Source.CAL] ?: 0
                if (c > 0) add("일정 ${c}건")
                if (dash.spend > 0) add("지출 ${won(dash.spend)}")
                val t = dash.todos.count { !it.done }
                if (t > 0) add("챙길 일 $t")
                if (isEmpty()) add("기록 ${dash.entries.size}건")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach {
                    Text(
                        it, color = white, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(white.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        val whiteBtn = ButtonDefaults.buttonColors(
            containerColor = white, contentColor = Indigo,
            disabledContainerColor = white.copy(alpha = 0.4f), disabledContentColor = Indigo.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (journal != null) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = whiteBtn) {
                    Text("자세히 보기", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onGenerate, enabled = !busy,
                    modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, white.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = white, disabledContentColor = white.copy(alpha = 0.6f)),
                ) { Text("다시 생성") }
            } else {
                Button(
                    onClick = onGenerate, enabled = !busy && dash?.entries?.isNotEmpty() == true,
                    modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp), colors = whiteBtn,
                ) { Text("요약 만들기", fontWeight = FontWeight.Bold) }
            }
        }
        Text(
            "일정·문자·전화·카톡·메일·앱 기록 ${dash?.entries?.size ?: 0}건 · 인터넷 없이 폰에서 분석",
            color = white.copy(alpha = 0.85f), fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatsGrid(d: Dash, open: (Source) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Source.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s -> StatTile(s, d.counts[s] ?: 0, d.subs[s], Modifier.weight(1f)) { open(s) } }
            }
        }
    }
}

@Composable
private fun StatTile(s: Source, count: Int, sub: Sub?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = "${srcName(s)} 타임라인 보기", onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SourceIcon(s)
        Column {
            Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(srcName(s), fontSize = 13.sp)
            Text(
                sub?.text ?: " ", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (sub?.warn == true) FontWeight.Medium else FontWeight.Normal,
                color = if (sub?.warn == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun relLabel(e: Entry, now: Long): String? {
    if (e.allDay) return null
    val diff = e.ts - now
    return when {
        diff <= 0 && (e.endTs ?: e.ts) > now -> "진행 중"
        diff in 1 until 3_600_000 -> "${diff / 60_000 + 1}분 후"
        diff in 3_600_000 until 12 * 3_600_000 -> "${diff / 3_600_000}시간 후"
        else -> null
    }
}

@Composable
private fun UpcomingCard(list: List<Entry>) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    Panel {
        PanelHeader("다음 일정")
        list.forEachIndexed { i, e ->
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().clickable { openEntry(ctx, e) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.width(56.dp)) {
                    val d = dateOf(e.ts)
                    if (d != today) Text(
                        if (d == today.plusDays(1)) "내일" else "${d.monthValue}/${d.dayOfMonth}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(if (e.allDay) "종일" else timeOf(e.ts), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (d == today && !e.allDay && e.endTs != null) {
                        Text(timeOf(e.endTs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    e.location?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                relLabel(e, now)?.let { Tag(it, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) }
            }
        }
    }
}

@Composable
private fun TodoCard(todos: List<Todo>, onToggle: (Todo, Boolean) -> Unit) {
    val ctx = LocalContext.current
    Panel {
        PanelHeader("챙겨야 할 일", trailing = "${todos.count { !it.done }}개")
        todos.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceIcon(t.source)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        t.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                        textDecoration = if (t.done) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(t.sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!t.done && t.kind == TodoKind.CALL) {
                    Button(onClick = { dial(ctx, t.target) }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text("전화", fontSize = 13.sp)
                    }
                } else if (!t.done && t.kind == TodoKind.OPEN) {
                    OutlinedButton(onClick = { if (t.room != null) openRoom(ctx, t.target, t.room, "카톡") else openApp(ctx, t.target) }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text("열기", fontSize = 13.sp)
                    }
                }
                Checkbox(checked = t.done, onCheckedChange = { onToggle(t, it) })
            }
        }
    }
}

@Composable
internal fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun MoneyCard(d: Dash, today: Boolean, onCalendar: () -> Unit, onChanged: () -> Unit) {
    val ok = successColor()
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    if (picking) MoneyPicker(d.day, d.day, "${d.day.monthValue}월 ${d.day.dayOfMonth}일") { changed -> picking = false; if (changed) onChanged() }
    fun act(block: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { block() }; onChanged() } }
    val out = d.money.count { it.excluded }
    Panel {
        PanelHeader(
            if (today) "오늘 돈 흐름" else "돈 흐름",
            action = "합계 항목 고르기", onAction = { picking = true },
        )
        Text("결제 ${d.money.count { !it.deposit }} · 입금 ${d.money.count { it.deposit }}" + if (out > 0) " · 합계 제외 $out" else "",
            fontSize = 12.sp, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("지출", won(d.spend), cs.onSurface, Modifier.weight(1f))
            Metric("입금", "+" + won(d.deposit), ok, Modifier.weight(1f))
        }
        (if (all) d.money else d.money.takeLast(6)).forEach { m ->
            val strike = if (m.excluded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(timeOf(m.ts), fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(44.dp))
                Text(m.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    color = if (m.excluded) cs.onSurfaceVariant else cs.onSurface)
                if (m.excluded) Tag("합계 제외", cs.onSurfaceVariant, cs.surfaceContainerHighest)
                Text(
                    if (m.deposit) "+" + won(m.amount) else won(m.amount), fontSize = 14.sp, textDecoration = strike,
                    fontWeight = FontWeight.Medium, color = when { m.excluded -> cs.onSurfaceVariant; m.deposit -> ok; else -> cs.onSurface },
                )
                val e = m.entry
                if (e != null) RowMenu(
                    if (m.excluded) listOf("합계에 다시 넣기" to { act { MoneyExclude.includeEverywhere(ctx, MoneyExclude.idOf(ctx, e), m.label, m.deposit) } })
                    else listOf(
                        "합계에서 빼기" to { act { MoneyExclude.exclude(ctx, e) } },
                        "‘${m.label}’ 항상 빼기" to { act { MoneyExclude.excludeAlways(ctx, m.label, m.deposit) } },
                        "원래 문자·알림 열기" to { openEntry(ctx, e) },
                    ),
                )
            }
        }
        if (d.money.size > 6) TextButton(onClick = { all = !all }, contentPadding = PaddingValues(0.dp)) {
            Text(if (all) "접기" else "${d.money.size - 6}건 더 보기")
        }
        Text("‘합계 항목 고르기’에서 합계에 넣지 않을 결제·이체를 체크 해제하세요 (기록은 그대로 남아요)", fontSize = 12.sp, color = cs.onSurfaceVariant)
        OutlinedButton(onClick = onCalendar, modifier = Modifier.fillMaxWidth()) { Text("달력에서 날짜별 지출·수입 보기") }
    }
}

@Composable
private fun RecentCard(list: List<Entry>, onMore: () -> Unit) {
    val ctx = LocalContext.current
    Panel {
        PanelHeader("최근 활동", action = "전체 보기", onAction = onMore)
        list.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable { openEntry(ctx, e) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(srcColor(e.source).fg))
                Column(Modifier.weight(1f)) {
                    Text("${timeOf(e.ts)} · ${e.source.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(recentLine(e), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- 2.1: 챙길 것 카드 ----------
@Composable
private fun PlansSoonCard(plans: List<com.mydaylog.app.insight.PlanView>, onMore: () -> Unit) {
    val limit = java.time.LocalDateTime.now().plusDays(7)
    val list = plans.filter { it.plan.at.isBefore(limit) }.sortedBy { it.plan.at }.take(2)
    if (list.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val kakao = srcColor(Source.KAKAO)
    Panel {
        PanelHeader("다가오는 약속", action = "모두 보기", onAction = onMore)
        list.forEach { v ->
            val pending = v.pending
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (pending) kakao.bg.copy(alpha = 0.55f) else Color.Transparent)
                    .clickable(onClick = onMore).padding(if (pending) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceIcon(if (pending) Source.KAKAO else Source.CAL)
                Column(Modifier.weight(1f)) {
                    Text("${v.plan.who} · ${v.plan.title}", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val t = if (v.plan.hasTime) " " + v.plan.at.toLocalTime().toString().take(5) else ""
                    val dateText = if (v.plan.dateKnown) md(v.plan.at.toLocalDate()) else "날짜 미정"
                    Text(dateText + t + (v.plan.place?.let { " · $it" } ?: ""), fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    !v.plan.dateKnown -> Tag("날짜 확인 필요", Color(0xFF7A5200), Color(0xFFFFF3B8))
                    pending -> Tag("확인 필요", kakao.fg, kakao.bg)
                    else -> Tag("캘린더에 있음", cs.primary, cs.primaryContainer)
                }
            }
        }
    }
}

@Composable
private fun DeadlineSoonCard(list: List<com.mydaylog.app.insight.DeadlineView>, onMore: () -> Unit) {
    val soon = list.filter { it.open && it.dDay in 0..14 }.sortedBy { it.dl.due }.take(3)
    if (soon.isEmpty()) return
    Panel {
        PanelHeader("마감 임박", action = "모두 보기", onAction = onMore)
        soon.forEach { v ->
            Row(Modifier.fillMaxWidth().clickable(onClick = onMore), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DBadge(v.dDay, big = false)
                Column(Modifier.weight(1f)) {
                    Text(v.dl.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${md(v.dl.due)}까지 · ${v.dl.srcLabel}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RecurringMonthCard(list: List<com.mydaylog.app.insight.Recurring>, onMore: () -> Unit) {
    val conf = list.filter { it.confirmed }
    if (conf.isEmpty()) return
    val total = conf.sumOf { it.amount }
    val unpaid = conf.filter { !it.paidThisMonth }.sortedBy { it.next }
    Panel {
        PanelHeader("이번 달 정기 결제", action = "모두 보기", onAction = onMore)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("${LocalDate.now().monthValue}월 합계 · ${conf.size}건", won(total), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Metric("남은 결제 · ${unpaid.size}건", won(unpaid.sumOf { it.amount }), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        unpaid.take(2).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${r.next.monthValue}/${r.next.dayOfMonth}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                Text(r.merchant, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(won(r.amount), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
