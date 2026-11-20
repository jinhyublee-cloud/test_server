package com.mydaylog.app.ui

import com.mydaylog.app.data.MoneyExclude
import com.mydaylog.app.data.ExcludedItem
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.DeadlineView
import com.mydaylog.app.insight.Insights
import com.mydaylog.app.insight.MailCat
import com.mydaylog.app.insight.MailItem
import com.mydaylog.app.insight.Plan
import com.mydaylog.app.insight.PlanView
import com.mydaylog.app.insight.Recurring
import com.mydaylog.app.insight.epoch
import com.mydaylog.app.security.SecLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

data class InboxData(
    val plans: List<PlanView> = emptyList(),
    val deadlines: List<DeadlineView> = emptyList(),
    val recurring: List<Recurring> = emptyList(),
    val mails: List<MailItem> = emptyList(),
    val deletedPlans: List<PlanView> = emptyList(),
    val deletedDeadlines: List<DeadlineView> = emptyList(),
    val dismissedRecurring: List<Recurring> = emptyList(),
    val payments: List<com.mydaylog.app.insight.Payment> = emptyList(),
    val excluded: List<ExcludedItem> = emptyList(),
)

fun loadInbox(ctx: Context): InboxData {
    runCatching { Insights.refresh(ctx) }
    return InboxData(
        runCatching { Insights.plans(ctx) }.getOrDefault(emptyList()),
        runCatching { Insights.deadlines(ctx) }.getOrDefault(emptyList()),
        runCatching { Insights.recurring(ctx) }.getOrDefault(emptyList()),
        runCatching { Insights.mails(ctx) }.getOrDefault(emptyList()),
        runCatching { Insights.plans(ctx, includeDeleted = true).filter { it.status == Insights.DELETED } }.getOrDefault(emptyList()),
        runCatching { Insights.deadlines(ctx, includeDeleted = true).filter { it.status == Insights.DELETED } }.getOrDefault(emptyList()),
        runCatching { Insights.dismissedRecurring(ctx) }.getOrDefault(emptyList()),
        runCatching { Insights.paymentsThisMonth(ctx) }.getOrDefault(emptyList()),
        runCatching { MoneyExclude.list(ctx) }.getOrDefault(emptyList()),
    )
}

/** 마감을 캘린더에 종일 일정으로 (저장은 사용자가 직접) */
fun addDeadlineToCalendar(ctx: Context, d: com.mydaylog.app.insight.Deadline) {
    val begin = epoch(d.due.atStartOfDay())
    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 86_400_000L)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        .putExtra(CalendarContract.Events.TITLE, "마감: ${d.title}")
        .putExtra(CalendarContract.Events.DESCRIPTION, "하루기록: ${d.srcLabel}에서 찾은 마감")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, "캘린더 앱을 열 수 없어요", Toast.LENGTH_SHORT).show() }
}

fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${dow(d)})"

/** 삼성 캘린더 입력 화면을 내용이 채워진 채로 열기 (저장은 사용자가 직접) */
fun addToCalendar(ctx: Context, p: Plan) {
    val begin = epoch(p.at)
    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 2 * 3_600_000L)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, !p.hasTime)
        .putExtra(CalendarContract.Events.TITLE, "${p.who} · ${p.title}")
        .putExtra(CalendarContract.Events.DESCRIPTION, "하루기록: ${p.src}에서 찾은 약속")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    p.place?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, "캘린더 앱을 열 수 없어요", Toast.LENGTH_SHORT).show() }
}

@Composable
fun InboxScreen(seg: Int, onSeg: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf(InboxData()) }
    LaunchedEffect(tick, reload) { data = withContext(Dispatchers.IO) { loadInbox(ctx) } }
    fun act(block: suspend () -> Unit) { scope.launch { withContext(Dispatchers.IO) { block() }; reload++ } }

    val upcomingDl = data.deadlines.count { it.status == "new" && it.dDay >= 0 }
    val planCount = data.plans.size
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Text("챙길 것", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("카톡·메일·문자에서 찾은 약속, 마감, 정기 결제", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SegmentBar(
            listOf("약속 $planCount", "마감 $upcomingDl", "결제 ${data.payments.size}", "메일 ${data.mails.size}"),
            seg, onSeg,
        )
        when (seg) {
            0 -> PlansPane(
                data.plans, data.deletedPlans,
                onAdd = { v -> addToCalendar(ctx, v.plan); act { Insights.setStatus(ctx, v.key, "added"); SecLog.add(ctx, "약속을 캘린더로 보냄") } },
                onDelete = { v -> act { Insights.setStatus(ctx, v.key, Insights.DELETED) } },
                onRestore = { v -> act { Insights.setStatus(ctx, v.key, "edited") } },
                onEdit = { v, p -> act { Insights.updatePlan(ctx, v.key, p) } },
            )
            1 -> DeadlinesPane(
                data.deadlines, data.deletedDeadlines,
                onDone = { v, done -> act { Insights.setStatus(ctx, v.key, if (done) "done" else "edited") } },
                onCalendar = { v -> addDeadlineToCalendar(ctx, v.dl); act { Insights.updateDeadline(ctx, v.key, v.dl.copy(cal = true)); SecLog.add(ctx, "마감을 캘린더로 보냄") } },
                onEdit = { v, d -> act { Insights.updateDeadline(ctx, v.key, d) } },
                onDelete = { v -> act { Insights.setStatus(ctx, v.key, Insights.DELETED) } },
                onRestore = { v -> act { Insights.setStatus(ctx, v.key, "edited") } },
            )
            2 -> RecurringPane(
                data.recurring, data.dismissedRecurring, data.payments, data.excluded,
                money = MoneyActions(
                    exclude = { p -> act { MoneyExclude.exclude(ctx, p.id, p.merchant, p.amount, false, p.ts) } },
                    always = { label, dep -> act { MoneyExclude.excludeAlways(ctx, label, dep) } },
                    include = { x -> act { if (x.always) MoneyExclude.removeRule(ctx, x.label, x.deposit) else MoneyExclude.include(ctx, x.id) } },
                    includePayment = { p -> act { MoneyExclude.includeEverywhere(ctx, p.id, p.merchant, false) } },
                    reload = { act { } },
                ),
                onDecide = { r, ok -> act { Insights.decide(ctx, r.key, ok) } },
                onEdit = { r, e -> act { Insights.editRecurring(ctx, r, e) } },
                onRestore = { r -> act { Insights.restoreRecurring(ctx, r.key) } },
            )
            else -> MailPane(data.mails)
        }
    }
}

@Composable
fun SegmentBar(labels: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerHighest).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, l ->
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (i == sel) cs.surface else Color.Transparent)
                    .clickable { onSel(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == sel) cs.onSurface else cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 20.dp, top = 4.dp))

@Composable
private fun Footnote(t: String) =
    Text(t, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))

@Composable
private fun EmptyNote(t: String) = Panel { Text(t, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

// ================= 약속 =================
@Composable
private fun PlansPane(
    plans: List<PlanView>, deleted: List<PlanView>,
    onAdd: (PlanView) -> Unit, onDelete: (PlanView) -> Unit, onRestore: (PlanView) -> Unit,
    onEdit: (PlanView, Plan) -> Unit,
) {
    var editing by remember { mutableStateOf<PlanView?>(null) }
    val pending = plans.filter { it.pending }.sortedWith(compareBy({ !it.plan.dateKnown }, { it.plan.at }))
    val done = plans.filter { !it.pending }.sortedBy { it.plan.at }
    SectionTitle("확인이 필요한 약속 ${pending.size}")
    if (pending.isEmpty()) EmptyNote("새로 찾은 약속이 없어요.")
    pending.forEach { v ->
        PlanCard(v,
            onAdd = { if (v.plan.dateKnown) onAdd(v) else editing = v },
            onEdit = { editing = v },
            onDelete = { onDelete(v) },
            onPickDate = { d -> onEdit(v, v.plan.copy(at = d.atTime(v.plan.at.toLocalTime()), dateKnown = true)) },
        )
    }
    if (done.isNotEmpty()) {
        SectionTitle("정리된 약속")
        Panel {
            done.forEachIndexed { i, v ->
                if (i > 0) Divider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(60.dp)) {
                        Text(md(v.plan.at.toLocalDate()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (v.plan.hasTime) v.plan.at.toLocalTime().toString().take(5) else "종일", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(v.plan.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(v.plan.place, "${v.plan.who} ${v.plan.src}에서").joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val cs = MaterialTheme.colorScheme
                    Tag(if (v.inCalendar) "캘린더에 있음" else "캘린더로 보냄", cs.primary, cs.primaryContainer)
                    RowMenu(listOf("수정" to { editing = v }, "캘린더에 다시 추가" to { onAdd(v) }, "삭제" to { onDelete(v) }))
                }
            }
        }
    }
    DeletedList("삭제한 약속", deleted, label = { "${md(it.plan.at.toLocalDate())} ${it.plan.who} · ${it.plan.title}" }, onRestore = onRestore)
    Footnote("카톡은 메시지 한 줄이 아니라 같은 방의 이어진 대화(4시간 이내)를 함께 보고 날짜·시간·장소를 합쳐요. 대화에서 날짜를 못 찾으면 대화한 날로 정하지 않고 \"날짜 확인 필요\"로 보여줘요. 삭제한 약속은 다시 분석해도 나타나지 않아요.")
    editing?.let { v -> PlanEditDialog(v.plan, onDismiss = { editing = null }) { p -> onEdit(v, p); editing = null } }
}

@Composable
private fun <T> DeletedList(title: String, items: List<T>, label: (T) -> String, onRestore: (T) -> Unit) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(start = 8.dp)) {
        Text(if (open) "$title 접기" else "$title ${items.size}개 · 보기", fontSize = 13.sp)
    }
    if (open) Panel {
        items.forEachIndexed { i, it ->
            if (i > 0) Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(it), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onRestore(it) }) { Text("되돌리기") }
            }
        }
    }
}

@Composable
private fun PlanCard(v: PlanView, onAdd: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onPickDate: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val p = v.plan
    val cs = MaterialTheme.colorScheme
    val warn = Color(0xFF9A6A00)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceIcon(if (p.src == "문자") Source.SMS else Source.KAKAO)
            Column(Modifier.weight(1f).clickable(onClickLabel = "대화방 열기") { openRoom(ctx, p.pkg, p.who, p.src) }) {
                Text(p.who, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("${p.src} · ${md(dateOf(p.msgTs))} ${timeOf(p.msgTs)} 대화 · 눌러서 대화방 열기", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            RowMenu(listOf("대화방 열기" to { openRoom(ctx, p.pkg, p.who, p.src) }, "수정" to onEdit, "삭제" to onDelete))
        }
        val tags = buildList {
            if (!p.dateKnown) add(Triple("날짜 확인 필요", Color(0xFF7A5200), Color(0xFFFFF3B8)))
            if (p.cancelled) add(Triple("취소 언급 있음", cs.error, cs.errorContainer))
            else if (p.agreed) add(Triple("약속 확정된 듯", successColor(), srcColor(Source.SMS).bg))
            if (v.status == "edited") add(Triple("직접 고침", cs.primary, cs.primaryContainer))
        }
        if (tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { (t, f, b) -> Tag(t, f, b) } }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.background).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            p.quote.lines().forEach { line -> Text(line, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.dateKnown) Field("날짜", "${p.at.monthValue}월 ${p.at.dayOfMonth}일 (${dow(p.at.toLocalDate())})")
            else Field("날짜", "대화에서 찾지 못했어요", missing = true)
            Field("시간", if (p.hasTime) p.at.toLocalTime().toString().take(5) else "찾지 못했어요 · 수정에서 입력", !p.hasTime)
            Field("장소", p.place ?: "찾지 못했어요 · 수정에서 입력", p.place == null)
            Field("함께", p.who)
            Field("내용", p.title)
        }
        if (!p.dateKnown) {
            val today = LocalDate.now()
            Text("언제인가요?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = warn)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onPickDate(today) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("오늘 ${md(today)}", fontSize = 13.sp) }
                OutlinedButton(onClick = { onPickDate(today.plusDays(1)) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("내일", fontSize = 13.sp) }
                OutlinedButton(onClick = { pickDate(ctx, today, { onPickDate(it) }, allowFuture = true) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("날짜 고르기", fontSize = 13.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, enabled = p.dateKnown, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp)) {
                Text(if (p.dateKnown) "캘린더에 추가" else "날짜를 먼저 골라 주세요", fontWeight = FontWeight.Bold, fontSize = if (p.dateKnown) 14.sp else 13.sp)
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("수정") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("삭제", color = cs.error) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, missing: Boolean = false) {
    Row {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Text(value, fontSize = 14.sp, fontWeight = if (missing) FontWeight.Normal else FontWeight.Medium,
            color = if (missing) Color(0xFF9A6A00) else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlanEditDialog(p: Plan, onDismiss: () -> Unit, onSave: (Plan) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(p.title) }
    var who by remember { mutableStateOf(p.who) }
    var place by remember { mutableStateOf(p.place.orEmpty()) }
    var at by remember { mutableStateOf(p.at) }
    var dateSet by remember { mutableStateOf(p.dateKnown) }
    var hasTime by remember { mutableStateOf(p.hasTime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("약속 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("내용", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(title, { title = it }, "예: 저녁 식사", Modifier.fillMaxWidth())
                Text("함께", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(who, { who = it }, "사람이나 방 이름", Modifier.fillMaxWidth())
                Text("장소", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(place, { place = it }, "예: 강남역 11번 출구", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDate(ctx, at.toLocalDate(), { d -> at = d.atTime(at.toLocalTime()); dateSet = true }, allowFuture = true) }) {
                        Text(if (dateSet) md(at.toLocalDate()) else "날짜 고르기", color = if (dateSet) Color.Unspecified else Color(0xFF9A6A00))
                    }
                    OutlinedButton(onClick = { pickTime(ctx, at.hour, at.minute) { h, m -> at = at.withHour(h).withMinute(m); hasTime = true } }) {
                        Text(if (hasTime) at.toLocalTime().toString().take(5) else "시간")
                    }
                    if (hasTime) TextButton(onClick = { hasTime = false }) { Text("종일로", fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = dateSet, onClick = {
                onSave(p.copy(title = title.ifBlank { p.title }, who = who.ifBlank { p.who }, place = place.ifBlank { null },
                    at = if (hasTime) at else at.toLocalDate().atTime(12, 0), hasTime = hasTime, dateKnown = true))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ================= 마감 =================
@Composable
fun DBadge(d: Long, big: Boolean = true) {
    val (fg, bg) = when {
        d <= 1 -> Color.White to Color(0xFFB23A1E)
        d <= 3 -> Color(0xFF9A2F17) to Color(0xFFFCE8E4)
        d <= 7 -> Color(0xFF6E5200) to Color(0xFFFFF3B8)
        else -> MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Text(
        if (d == 0L) "D-day" else "D-$d", fontSize = if (big) 15.sp else 14.sp, fontWeight = FontWeight.Bold, color = fg,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.width(if (big) 56.dp else 50.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(vertical = 7.dp),
    )
}

@Composable
private fun DeadlinesPane(
    list: List<DeadlineView>, deleted: List<DeadlineView>,
    onDone: (DeadlineView, Boolean) -> Unit, onCalendar: (DeadlineView) -> Unit,
    onEdit: (DeadlineView, com.mydaylog.app.insight.Deadline) -> Unit,
    onDelete: (DeadlineView) -> Unit, onRestore: (DeadlineView) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<DeadlineView?>(null) }
    val upcoming = list.filter { it.open && it.dDay >= 0 }.sortedBy { it.dl.due }
    val past = list.filter { it !in upcoming }.sortedByDescending { it.dl.due }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer).padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Text("마감 알림: 3일 전 · 하루 전 · 당일 오전 9시 (내용 없이 개수만)", fontSize = 14.sp, color = cs.onPrimaryContainer) }
    SectionTitle("다가오는 마감 ${upcoming.size}")
    if (upcoming.isEmpty()) EmptyNote("다가오는 마감이 없어요.")
    else Panel {
        upcoming.forEachIndexed { i, v ->
            if (i > 0) Divider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DBadge(v.dDay)
                Column(Modifier.weight(1f).clickable(enabled = v.dl.pkg != null) { openDeadlineSource(ctx, v.dl) }) {
                    Text(v.dl.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${md(v.dl.due)}까지 · ${v.dl.srcLabel}", fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v.dl.cal) Text("캘린더로 보냄", fontSize = 12.sp, color = cs.primary)
                }
                if (!v.dl.cal) OutlinedButton(onClick = { onCalendar(v) }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("캘린더", fontSize = 13.sp)
                }
                RowMenu(listOf("완료" to { onDone(v, true) }, "캘린더에 추가" to { onCalendar(v) }, "수정" to { editing = v }, "삭제" to { onDelete(v) }))
            }
        }
    }
    if (past.isNotEmpty()) {
        SectionTitle("지난 마감·완료 ${past.size}")
        Panel {
            past.take(10).forEachIndexed { i, v ->
                if (i > 0) Divider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(md(v.dl.due), fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    Text(v.dl.title, fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (v.status == "done") TextDecoration.LineThrough else TextDecoration.None)
                    if (v.status == "done") TextButton(onClick = { onDone(v, false) }) { Text("완료", fontSize = 12.sp, color = successColor()) }
                    else Text("놓침", fontSize = 12.sp, color = cs.error)
                    RowMenu(listOf("수정" to { editing = v }, "삭제" to { onDelete(v) }))
                }
            }
        }
    }
    DeletedList("삭제한 마감", deleted, label = { "${md(it.dl.due)} ${it.dl.title}" }, onRestore = onRestore)
    Footnote("\"까지\", \"유효기간\", \"만료\", \"마감\", \"D-3\" 같은 표현과 날짜가 함께 있는 알림을 마감으로 모아요. 광고 문자의 할인 기한은 빼요. 고치거나 삭제한 마감은 다시 분석해도 바뀌지 않아요.")
    editing?.let { v ->
        DeadlineEditDialog(v.dl, onDismiss = { editing = null }) { d -> onEdit(v, d); editing = null }
    }
}

@Composable
private fun DeadlineEditDialog(d: com.mydaylog.app.insight.Deadline, onDismiss: () -> Unit, onSave: (com.mydaylog.app.insight.Deadline) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(d.title) }
    var due by remember { mutableStateOf(d.due) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("마감 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("내용", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(title, { title = it }, "예: 스타벅스 쿠폰", Modifier.fillMaxWidth())
                Text("마감일", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { pickDate(ctx, due, { due = it }, allowFuture = true) }) { Text("${md(due)}까지") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d.copy(title = title.ifBlank { d.title }, due = due)) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ================= 정기 결제 =================
@Composable
private fun RecurringPane(
    list: List<Recurring>, dismissed: List<Recurring>, payments: List<com.mydaylog.app.insight.Payment>,
    excluded: List<ExcludedItem>, money: MoneyActions,
    onDecide: (Recurring, Boolean) -> Unit, onEdit: (Recurring, Insights.RecurringEdit) -> Unit,
    onRestore: (Recurring) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<Recurring?>(null) }
    MoneyCalendar(onChanged = money.reload)
    PaymentsPanel(payments, excluded, money)
    val today = LocalDate.now()
    val confirmed = list.filter { it.confirmed }
    val cands = list.filter { !it.confirmed }
    val total = confirmed.sumOf { it.amount }
    val paid = confirmed.filter { it.paidThisMonth }.sumOf { it.amount }
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${today.monthValue}월 정기 결제 · ${confirmed.size}건", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(won(total), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.primaryContainer)) {
            if (total > 0) Box(Modifier.fillMaxWidth(paid.toFloat() / total).height(10.dp).background(cs.primary))
        }
        Row {
            Text("결제됨 ${won(paid)}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary, modifier = Modifier.weight(1f))
            Text("남은 결제 ${won(total - paid)}", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        MonthGrid(today, confirmed)
        Text("진한 칸 = 남은 결제일 · 연한 칸 = 결제됨 · 테두리 = 오늘", fontSize = 12.sp, color = cs.onSurfaceVariant)
    }
    SectionTitle("정기 결제 ${confirmed.size}")
    if (confirmed.isEmpty()) EmptyNote("아직 찾은 정기 결제가 없어요. 카드·은행 문자나 앱 알림이 두 달 이상 쌓이면 보여요.")
    else Panel {
        confirmed.forEachIndexed { i, r ->
            if (i > 0) Divider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("매월", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Text("${r.day}일", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(r.merchant, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (r.months >= 2) "${r.months}개월 연속" else "자동이체·결제 예정 안내", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(won(r.amount), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (r.paidThisMonth) cs.onSurfaceVariant else cs.onSurface)
                    val d = java.time.temporal.ChronoUnit.DAYS.between(today, r.next)
                    Text(
                        if (r.paidThisMonth) "이번 달 결제됨" else if (d == 0L) "오늘" else "D-$d", fontSize = 12.sp,
                        color = if (r.paidThisMonth) successColor() else if (d <= 3) cs.error else cs.onSurfaceVariant,
                    )
                }
                RowMenu(listOf("수정" to { editing = r }, "정기 결제 아님" to { onDecide(r, false) }))
            }
        }
    }
    cands.forEach { r ->
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFF8D6)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("정기 결제일 수 있어요", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6E5200))
            Text("${r.merchant} ${won(r.amount)} · 매월 ${r.day}일쯤, ${r.months}개월 연속", fontSize = 15.sp, color = Color(0xFF3A2C00))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecide(r, true) }, modifier = Modifier.weight(1f)) { Text("맞아요") }
                OutlinedButton(onClick = { editing = r }) { Text("고쳐서 등록") }
                OutlinedButton(onClick = { onDecide(r, false) }, modifier = Modifier.weight(1f)) { Text("아니에요") }
            }
        }
    }
    DeletedList("정기 결제 아님으로 뺀 항목", dismissed, label = { "${it.merchant} ${won(it.amount)}" }, onRestore = onRestore)
    editing?.let { r -> RecurringEditDialog(r, onDismiss = { editing = null }) { e -> onEdit(r, e); editing = null } }
    Footnote("같은 곳에서 비슷한 금액(±10%)이 매달 비슷한 날짜(±3일)에 결제되거나, 자동이체·결제 예정 안내가 오면 정기 결제로 봐요. 결제일 사흘 전에 알려줘요.")
}

@Composable
private fun RecurringEditDialog(r: Recurring, onDismiss: () -> Unit, onSave: (Insights.RecurringEdit) -> Unit) {
    var name by remember { mutableStateOf(r.merchant) }
    var amount by remember { mutableStateOf(r.amount.toString()) }
    var day by remember { mutableStateOf(r.day.toString()) }
    val amt = amount.filter { it.isDigit() }.toLongOrNull()
    val d = day.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..31 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정기 결제 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("이름", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(name, { name = it }, "예: 넷플릭스", Modifier.fillMaxWidth())
                Text("금액 (원)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(amount, { amount = it }, "17000", Modifier.fillMaxWidth(), numeric = true)
                Text("매월 결제일", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivateTextField(day, { day = it }, "1~31", Modifier.fillMaxWidth(), numeric = true)
                if (d == null) Text("결제일은 1~31 사이로 넣어 주세요", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = amt != null && d != null && name.isNotBlank(), onClick = {
                onSave(Insights.RecurringEdit(name.trim(), amt!!, d!!))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 결제 탭의 합계에서 빼기·다시 넣기 */
class MoneyActions(
    val exclude: (com.mydaylog.app.insight.Payment) -> Unit,
    val always: (String, Boolean) -> Unit,
    val include: (ExcludedItem) -> Unit,
    val includePayment: (com.mydaylog.app.insight.Payment) -> Unit,
    val reload: () -> Unit,
)

@Composable
private fun PaymentsPanel(payments: List<com.mydaylog.app.insight.Payment>, excluded: List<ExcludedItem>, money: MoneyActions) {
    val cs = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    var showOut by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val month = LocalDate.now().monthValue
    if (picking) MoneyPicker(LocalDate.now().withDayOfMonth(1), LocalDate.now(), "${month}월 결제·입금") { changed ->
        picking = false; if (changed) money.reload()
    }
    val counted = payments.filter { !it.excluded }
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${month}월 카드·결제 · ${counted.size}건", fontSize = 14.sp, color = cs.onSurfaceVariant)
                val out = payments.size - counted.size
                if (out > 0) Text("합계 제외 ${out}건 · ${won(payments.filter { it.excluded }.sumOf { it.amount })}", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            Text(won(counted.sumOf { it.amount }), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("합계에 넣을 항목 고르기 (결제·입금)") }
        if (payments.isEmpty()) {
            Text("이번 달 결제 알림이 아직 없어요. 카드사 문자나 알림톡이 오면 여기에 쌓여요.", fontSize = 14.sp, color = cs.onSurfaceVariant)
        } else {
            val byCard = counted.groupBy { it.card ?: "기타" }.mapValues { (_, v) -> v.sumOf { it.amount } }.entries.sortedByDescending { it.value }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                byCard.forEach { (card, sum) -> Tag("$card ${won(sum)}", cs.onSurface, cs.surfaceContainerHighest) }
            }
            Divider()
            (if (all) payments else payments.take(8)).forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(52.dp)) {
                        Text(md(dateOf(p.ts)).substringBefore("("), fontSize = 13.sp, color = cs.onSurfaceVariant)
                        Text(timeOf(p.ts), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.merchant, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (p.excluded) cs.onSurfaceVariant else cs.onSurface)
                        val sub = listOfNotNull(p.card, "합계 제외".takeIf { p.excluded }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Text(won(p.amount), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (p.excluded) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (p.excluded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    RowMenu(
                        if (p.excluded) listOf("합계에 다시 넣기" to { money.includePayment(p) })
                        else listOf("합계에서 빼기" to { money.exclude(p) }, "‘${p.merchant}’ 항상 빼기" to { money.always(p.merchant, false) }),
                    )
                }
            }
            if (payments.size > 8) TextButton(onClick = { all = !all }) { Text(if (all) "접기" else "${payments.size - 8}건 더 보기") }
        }
        // ---- 합계에서 뺀 항목 (결제·입금 모두, 되돌리기) ----
        if (excluded.isNotEmpty()) {
            Divider()
            TextButton(onClick = { showOut = !showOut }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showOut) "합계에서 뺀 항목 접기" else "합계에서 뺀 항목 ${excluded.size}개 보기")
            }
            if (showOut) excluded.forEach { x ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(x.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (x.always) "${if (x.deposit) "입금" else "결제"} · 항상 빼기"
                            else "${md(dateOf(x.ts))} ${timeOf(x.ts)} · ${if (x.deposit) "입금 +" else "결제 "}${won(x.amount)}",
                            fontSize = 12.sp, color = cs.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { money.include(x) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("다시 넣기") }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(today: LocalDate, list: List<Recurring>) {
    val cs = MaterialTheme.colorScheme
    val ym = YearMonth.from(today)
    val payDays = list.associateBy { minOf(it.day, ym.lengthOfMonth()) }
    val lead = ym.atDay(1).dayOfWeek.value - 1
    val cells = List(lead) { 0 } + (1..ym.lengthOfMonth()).toList()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row { listOf("월", "화", "수", "목", "금", "토", "일").forEach { Text(it, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i) ?: 0
                    val r = payDays[d]
                    val bg = when { d == 0 || r == null -> Color.Transparent; r.paidThisMonth -> cs.primaryContainer; else -> cs.primary }
                    val fg = when { r == null -> cs.onSurface; r.paidThisMonth -> cs.onPrimaryContainer; else -> cs.onPrimary }
                    Box(
                        Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(bg)
                            .then(if (d == today.dayOfMonth) Modifier.border(2.dp, cs.onSurface, RoundedCornerShape(8.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (d > 0) Text("$d", fontSize = 13.sp, color = fg, fontWeight = if (r != null) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }
    }
}

// ================= 메일 =================
@Composable
private fun MailPane(mails: List<MailItem>) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf<MailCat?>(null) }
    var showNews by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("전체 ${mails.size}") })
        MailCat.entries.forEach { c ->
            val n = mails.count { it.cat == c }
            if (n > 0) FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c }, label = { Text("${c.label} $n") })
        }
    }
    if (mails.isEmpty()) EmptyNote("최근 7일 동안 기록된 메일 알림이 없어요.")
    val order = listOf(MailCat.REPLY, MailCat.BILL, MailCat.NOTICE, MailCat.OTHER, MailCat.NEWS)
    order.filter { filter == null || filter == it }.forEach { cat ->
        val items = mails.filter { it.cat == cat }
        if (items.isEmpty()) return@forEach
        if (cat == MailCat.NEWS && filter == null && !showNews) {
            OutlinedButton(onClick = { showNews = true }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Text("뉴스레터 ${items.size}개 접혀 있어요 · 펼치기", color = cs.onSurfaceVariant)
            }
            return@forEach
        }
        SectionTitle(cat.label, if (cat == MailCat.REPLY) cs.error else cs.onSurfaceVariant)
        Panel {
            items.forEachIndexed { i, m ->
                if (i > 0) Divider()
                Column(Modifier.fillMaxWidth().clickable { openApp(ctx, m.e.pkg) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(m.sender, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${m.e.app} · ${if (dateOf(m.e.ts) == LocalDate.now()) "" else md(dateOf(m.e.ts)) + " "}${timeOf(m.e.ts)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Text(m.subject, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (m.bullets.isNotEmpty()) BulletList(m.bullets, cs.onSurface.copy(alpha = 0.85f))
                    m.due?.let { d ->
                        Spacer(Modifier.height(2.dp))
                        Tag("마감 ${md(d)} · 마감 목록에 추가됨", Color(0xFF9A2F17), Color(0xFFFCE8E4))
                    }
                }
            }
        }
    }
    Footnote("메일 알림에 보이는 보낸 사람·제목·미리보기만으로 요약해요. 메일 계정에 접속하거나 본문 전체를 읽지 않아요.")
}
