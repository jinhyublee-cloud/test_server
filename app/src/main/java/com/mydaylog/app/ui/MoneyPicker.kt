package com.mydaylog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mydaylog.app.data.MoneyExclude
import com.mydaylog.app.data.MoneyPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 합계에 넣을 결제·입금 고르기
 * 체크 = 합계에 포함, 체크 해제 = 합계에서 뺌 (기록은 지우지 않음)
 * 창은 앱 화면의 캡처 방지 설정을 그대로 따름 (SecureFlagPolicy.Inherit)
 */
@Composable
fun MoneyPicker(from: LocalDate, to: LocalDate, title: String, onClose: (changed: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var picks by remember { mutableStateOf<List<MoneyPick>?>(null) }
    var keep by remember { mutableStateOf(emptySet<String>()) }
    var kind by remember { mutableStateOf(0) } // 0 전체, 1 결제, 2 입금
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(from, to) {
        val list = withContext(Dispatchers.IO) { runCatching { MoneyExclude.picks(ctx, from, to) }.getOrDefault(emptyList()) }
        picks = list
        keep = list.filter { !it.excluded }.map { it.id }.toSet()
    }

    Dialog(onDismissRequest = { onClose(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("합계에 넣을 항목 고르기", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$title · 체크를 풀면 합계에서 빠져요. 기록은 지워지지 않고 언제든 다시 넣을 수 있어요.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                val list = picks
                if (list == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    // 고르는 대로 바로 바뀌는 합계
                    val spend = list.filter { !it.deposit && it.id in keep }.sumOf { it.amount }
                    val dep = list.filter { it.deposit && it.id in keep }.sumOf { it.amount }
                    val out = list.count { it.id !in keep }
                    MetricPair(Triple("지출 합계", won(spend), cs.onSurface), Triple("입금 합계", "+" + won(dep), successColor()))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(kind == 0, { kind = 0 }, { Text("전체 ${list.size}") })
                        FilterChip(kind == 1, { kind = 1 }, { Text("결제 ${list.count { !it.deposit }}") })
                        FilterChip(kind == 2, { kind = 2 }, { Text("입금 ${list.count { it.deposit }}") })
                        Box(Modifier.weight(1f))
                        if (out > 0) Text("뺀 항목 $out", fontSize = 12.sp, color = cs.error)
                    }
                    val shown = list.filter { kind == 0 || (kind == 1) == !it.deposit }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("이 기간에 결제·입금 기록이 없어요", fontSize = 14.sp, color = cs.onSurfaceVariant)
                        }
                    } else LazyColumn(Modifier.weight(1f)) {
                        items(shown, key = { it.id }) { p ->
                            val on = p.id in keep
                            Row(
                                Modifier.fillMaxWidth().clickable { keep = if (on) keep - p.id else keep + p.id }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Checkbox(checked = on, onCheckedChange = { keep = if (it) keep + p.id else keep - p.id })
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (on) cs.onSurface else cs.onSurfaceVariant)
                                    val d = dateOf(p.entry.ts)
                                    val sub = listOfNotNull(
                                        "${d.monthValue}/${d.dayOfMonth} ${timeOf(p.entry.ts)}",
                                        p.card ?: p.entry.title.takeIf { it.isNotBlank() },
                                        "‘${p.label}’ 항상 빼기 중 · 체크하면 해제".takeIf { p.byRule },
                                    ).joinToString(" · ")
                                    Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    (if (p.deposit) "+" else "") + won(p.amount), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    color = when { !on -> cs.onSurfaceVariant; p.deposit -> successColor(); else -> cs.onSurface },
                                    textDecoration = if (on) null else TextDecoration.LineThrough,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { keep = list.map { it.id }.toSet() }) { Text("모두 넣기") }
                        Box(Modifier.weight(1f))
                        TextButton(onClick = { onClose(false) }) { Text("취소") }
                        Button(enabled = !saving, onClick = {
                            saving = true
                            scope.launch {
                                withContext(Dispatchers.IO) { MoneyExclude.saveSelection(ctx, list, keep) }
                                onClose(true)
                            }
                        }) { Text("저장") }
                    }
                }
            }
        }
    }
}
