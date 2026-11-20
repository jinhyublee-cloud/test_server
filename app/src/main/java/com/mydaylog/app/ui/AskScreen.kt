package com.mydaylog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.genai.common.DownloadStatus
import com.mydaylog.app.Prefs
import com.mydaylog.app.ask.AiState
import com.mydaylog.app.ask.Ask
import com.mydaylog.app.ask.AskResult
import com.mydaylog.app.ask.Ev
import com.mydaylog.app.ask.Nano
import com.mydaylog.app.capture.ChatLinks
import com.mydaylog.app.data.Source
import kotlinx.coroutines.launch

/** 물어본 내용은 메모리에만 (저장하지 않음, 앱이 잠기면 지움) */
object AskSession {
    data class Turn(val id: Long, val q: String, val result: AskResult? = null)
    val turns = mutableStateListOf<Turn>()
    fun clear() = turns.clear()
}

val ASK_SUGGESTIONS = listOf(
    "오늘 어디서 결제했어?",
    "어제 통화 기록 보여줘",
    "이번 주 일정 알려줘",
    "이번 달 카드 결제 총 얼마야?",
    "지난주 카톡 약속 있었어?",
    "다가오는 마감 알려줘",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf(AiState.CHECKING) }
    var dlText by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val tick = rememberResumeTick()

    LaunchedEffect(tick) { ai = Nano.state(ctx) }
    LaunchedEffect(AskSession.turns.size, AskSession.turns.lastOrNull()?.result) {
        if (AskSession.turns.isNotEmpty()) list.animateScrollToItem(AskSession.turns.size - 1)
    }

    fun ask(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        val id = System.nanoTime()
        AskSession.turns += AskSession.Turn(id, q)
        scope.launch {
            val r = runCatching { Ask.run(ctx, q, ai == AiState.READY) }.getOrNull()
            val i = AskSession.turns.indexOfFirst { it.id == id }
            if (i >= 0) AskSession.turns[i] = AskSession.turns[i].copy(result = r)
            busy = false
        }
    }

    fun download() {
        scope.launch {
            ai = AiState.DOWNLOADING
            runCatching {
                Nano.download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadStarted -> dlText = "모델 받는 중 (${s.bytesToDownload / 1_048_576}MB)"
                        is DownloadStatus.DownloadCompleted -> { dlText = null; ai = Nano.state(ctx) }
                        is DownloadStatus.DownloadFailed -> { dlText = "모델을 받지 못했어요. 와이파이에서 다시 해 주세요"; ai = Nano.state(ctx) }
                        else -> {}
                    }
                }
            }.onFailure { dlText = "모델을 받지 못했어요"; ai = Nano.state(ctx) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 머리말 ----
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("물어보기", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("기록은 이 폰 안에서만 찾아요 · 물어본 내용은 저장하지 않아요", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (fg, bg) = when (ai) {
                    AiState.READY -> successColor() to srcColor(Source.SMS).bg
                    AiState.DOWNLOADABLE, AiState.DOWNLOADING -> cs.primary to cs.primaryContainer
                    else -> cs.onSurfaceVariant to cs.surfaceContainerHighest
                }
                Tag(dlText ?: ai.label, fg, bg)
                if (ai == AiState.DOWNLOADABLE) TextButton(onClick = ::download, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("받기") }
                if (ai == AiState.UNAVAILABLE || ai == AiState.OFF) Text("기본 답변으로 알려드려요", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }

        // ---- 대화 ----
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (AskSession.turns.isEmpty()) item {
                Panel {
                    Text("이렇게 물어보세요", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("날짜(오늘·어제·지난주 금요일·11월 14일)와 궁금한 것(결제·통화·일정·카톡·문자·메일·마감)을 함께 말하면 정확해요. 가게나 사람 이름만 물어도 최근 90일에서 찾아요.",
                        fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ASK_SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { ask(s) }, label = { Text(s) }) }
                    }
                }
            }
            items(AskSession.turns, key = { it.id }) { t -> TurnView(t) }
        }

        // ---- 추천 질문 + 입력 ----
        if (AskSession.turns.isNotEmpty()) LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { items(ASK_SUGGESTIONS) { s -> SuggestionChip(onClick = { ask(s) }, label = { Text(s, fontSize = 13.sp) }, enabled = !busy) } }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrivateTextField(input, { input = it }, "예: 어제 어디서 결제했어?", Modifier.weight(1f), onDone = { ask(input) })
            FilledIconButton(onClick = { ask(input) }, enabled = !busy && input.isNotBlank(), modifier = Modifier.size(48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, "묻기")
            }
        }
    }
}

@Composable
private fun TurnView(t: AskSession.Turn) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                t.q, fontSize = 15.sp, color = cs.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(cs.primaryContainer).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        val r = t.result
        if (r == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("기록을 찾는 중…", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        } else AnswerCard(r)
    }
}

@Composable
private fun AnswerCard(r: AskResult) {
    val cs = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(false) }
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.byAi) Tag("기기 AI", cs.primary, cs.primaryContainer) else Tag("기본 답변", cs.onSurfaceVariant, cs.surfaceContainerHighest)
            Tag("${r.facts.query.label} · ${r.facts.query.topic.label}", cs.onSurfaceVariant, cs.surfaceContainerHighest)
        }
        Text(r.text, fontSize = 15.sp, lineHeight = 23.sp)
        if (r.byAi && r.facts.details.isNotEmpty()) BulletList(r.facts.details, cs.onSurfaceVariant)
        val ev = r.facts.evidence
        if (ev.isNotEmpty()) {
            Divider()
            Text("근거 ${ev.size}건${if (ev.size >= com.mydaylog.app.ask.AskEngine.MAX_EVIDENCE) "+" else ""} · 누르면 원래 앱·대화방이 열려요",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
            (if (all) ev else ev.take(5)).forEach { EvRow(it) }
            if (ev.size > 5) TextButton(onClick = { all = !all }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                Text(if (all) "접기" else "${ev.size - 5}건 더 보기")
            }
        }
    }
}

@Composable
private fun EvRow(e: Ev) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val d = dateOf(e.ts)
    val chat = e.entry != null && (e.source == Source.KAKAO || e.source == Source.SMS)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = e.entry != null) { e.entry?.let { openEntry(ctx, it) } }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SourceIcon(e.source, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(
                "${d.monthValue}/${d.dayOfMonth}(${dow(d)}) ${if (e.allDay) "종일" else timeOf(e.ts)} · ${e.title}",
                fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (e.body.isNotBlank()) Text(e.body.replace('\n', ' '), fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (chat) Text(if (e.entry != null && ChatLinks.has(e.entry)) "대화방" else "열기", fontSize = 12.sp, color = cs.primary)
    }
}
