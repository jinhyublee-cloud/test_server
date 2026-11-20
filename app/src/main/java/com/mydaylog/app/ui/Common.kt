package com.mydaylog.app.ui

import android.app.DatePickerDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mydaylog.app.R
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// ---------- 테마 (설계안의 인디고 팔레트) ----------
val Indigo = Color(0xFF3446C4)

private val LightScheme = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EBFC), onPrimaryContainer = Color(0xFF26359A),
    secondary = Color(0xFF5B606B), onSecondary = Color.White,
    background = Color(0xFFF4F5F8), onBackground = Color(0xFF15171C),
    surface = Color.White, onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFF4F5F8), onSurfaceVariant = Color(0xFF5B606B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF4F5F8),
    surfaceContainerHighest = Color(0xFFEEF0F3),
    outline = Color(0xFFD5D8DF), outlineVariant = Color(0xFFE6E8ED),
    error = Color(0xFFB23A1E), onError = Color.White,
    errorContainer = Color(0xFFFCE8E4), onErrorContainer = Color(0xFF7A2410),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFAEB8FF), onPrimary = Color(0xFF101A5C),
    primaryContainer = Color(0xFF2A3266), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFA3A7B0),
    background = Color(0xFF111216), onBackground = Color(0xFFE8E9EC),
    surface = Color(0xFF1A1C21), onSurface = Color(0xFFE8E9EC),
    surfaceVariant = Color(0xFF23262C), onSurfaceVariant = Color(0xFFA3A7B0),
    surfaceContainerLowest = Color(0xFF111216), surfaceContainerLow = Color(0xFF1A1C21),
    surfaceContainer = Color(0xFF1A1C21), surfaceContainerHigh = Color(0xFF23262C),
    surfaceContainerHighest = Color(0xFF2A2D34),
    outline = Color(0xFF454951), outlineVariant = Color(0xFF2C2F36),
    error = Color(0xFFF2A08C), onError = Color(0xFF54221A),
    errorContainer = Color(0xFF54221A), onErrorContainer = Color(0xFFFCE8E4),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}

data class SrcColor(val fg: Color, val bg: Color)

@Composable
fun srcColor(s: Source): SrcColor {
    val dark = isSystemInDarkTheme()
    return if (!dark) when (s) {
        Source.CAL -> SrcColor(Color(0xFF3446C4), Color(0xFFE8EBFC))
        Source.SMS -> SrcColor(Color(0xFF0E7A55), Color(0xFFE0F4EC))
        Source.CALL -> SrcColor(Color(0xFF1A64A8), Color(0xFFE3F0FB))
        Source.KAKAO -> SrcColor(Color(0xFF6E5200), Color(0xFFFFF3B8))
        Source.MAIL -> SrcColor(Color(0xFFB23A1E), Color(0xFFFCE8E4))
        Source.APP -> SrcColor(Color(0xFF5B4699), Color(0xFFEEEAF7))
    } else when (s) {
        Source.CAL -> SrcColor(Color(0xFFAEB8FF), Color(0xFF2A3266))
        Source.SMS -> SrcColor(Color(0xFF7FD8B4), Color(0xFF15402F))
        Source.CALL -> SrcColor(Color(0xFF8DC2F2), Color(0xFF163650))
        Source.KAKAO -> SrcColor(Color(0xFFF2D46B), Color(0xFF4A3D00))
        Source.MAIL -> SrcColor(Color(0xFFF2A08C), Color(0xFF54221A))
        Source.APP -> SrcColor(Color(0xFFC4B5F0), Color(0xFF352A55))
    }
}

@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7FD8B4) else Color(0xFF0E7A55)

fun srcIcon(s: Source): Int = when (s) {
    Source.CAL -> R.drawable.ic_src_cal
    Source.SMS -> R.drawable.ic_src_sms
    Source.CALL -> R.drawable.ic_src_call
    Source.KAKAO -> R.drawable.ic_src_kakao
    Source.MAIL -> R.drawable.ic_src_mail
    Source.APP -> R.drawable.ic_src_app
}

fun srcName(s: Source) = if (s == Source.APP) "앱 알림" else s.label

@Composable
fun SourceIcon(s: Source, size: Dp = 34.dp) {
    val c = srcColor(s)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(c.bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(srcIcon(s)), contentDescription = srcName(s), tint = c.fg, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun PanelHeader(title: String, trailing: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.height(36.dp)) { Text(action, fontSize = 13.sp) }
        }
    }
}

@Composable
fun Tag(text: String, fg: Color, bg: Color) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

// ---------- 시간·날짜 ----------
fun timeOf(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

fun hourOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).hour

fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

fun dateLong(d: LocalDate) =
    "${d.monthValue}월 ${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"

fun dow(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

fun won(v: Long) = "%,d원".format(v)

/** S-15: 시스템 대화상자 창에도 화면 캡처 차단 적용 */
fun secureShow(ctx: Context, d: android.app.Dialog) {
    if (com.mydaylog.app.Prefs.secureScreen(ctx)) d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    d.show()
}

fun pickDate(ctx: Context, day: LocalDate, onDay: (LocalDate) -> Unit, allowFuture: Boolean = false) {
    val dlg = DatePickerDialog(ctx, { _, y, m, d -> onDay(LocalDate.of(y, m + 1, d)) }, day.year, day.monthValue - 1, day.dayOfMonth)
    if (!allowFuture) dlg.datePicker.maxDate = System.currentTimeMillis()
    secureShow(ctx, dlg)
}

fun pickTime(ctx: Context, h: Int, m: Int, onTime: (Int, Int) -> Unit) =
    secureShow(ctx, android.app.TimePickerDialog(ctx, { _, hh, mm -> onTime(hh, mm) }, h, m, true))

/** S-16: 키보드가 입력을 학습·추천하지 않는 입력칸 */
@Composable
fun PrivateTextField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, numeric: Boolean = false, onDone: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val fg = cs.onSurface.toArgb(); val hintColor = cs.onSurfaceVariant.toArgb()
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            android.widget.EditText(c).apply {
                this.hint = hint
                isSingleLine = true
                background = null
                textSize = 16f
                setTextColor(fg); setHintTextColor(hintColor)
                inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setText(value)
                if (onDone != null) setOnEditorActionListener { _, _, _ -> onDone(); true }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString().orEmpty()) }
                })
            }
        },
        update = { if (it.text.toString() != value) it.setText(value) },
        modifier = modifier.clip(shape).background(cs.surface).border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

// ---------- 항목 표시 ----------
fun headOf(e: Entry): String = if (e.isGroupChat) e.conv!! else e.title

fun bodyOf(e: Entry): String = when {
    e.isGroupChat -> "${e.title}: ${e.text}"
    e.source == Source.CAL -> buildString {
        append(if (e.allDay) "종일" else "${timeOf(e.ts)}–${timeOf(e.endTs ?: e.ts)}")
        e.location?.let { append(" · ").append(it) }
    }
    else -> e.text
}

fun recentLine(e: Entry): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(headOf(e)) }
    append("  ")
    append(bodyOf(e).replace('\n', ' '))
}

// ---------- 폰 안의 다른 앱 열기 (외부 전송 없음) ----------
fun openApp(ctx: Context, pkg: String?) {
    val i = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
    if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    else Toast.makeText(ctx, "앱을 열 수 없어요", Toast.LENGTH_SHORT).show()
}

fun dial(ctx: Context, number: String?) {
    if (number.isNullOrBlank()) return
    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openEntry(ctx: Context, e: Entry) {
    runCatching {
        when (e.source) {
            Source.CALL -> dial(ctx, e.number)
            Source.CAL -> ctx.startActivity(
                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.refId))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.ts)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endTs ?: e.ts)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            // 카톡·문자: 그 대화방을 바로 엶 (바로가기가 없으면 앱만 엶)
            Source.KAKAO, Source.SMS -> com.mydaylog.app.capture.ChatLinks.open(ctx, e)
            else -> openApp(ctx, e.pkg)
        }
    }.onFailure { Toast.makeText(ctx, "열 수 없어요", Toast.LENGTH_SHORT).show() }
}

/** 약속·마감 카드에서 원래 대화방 열기 (room = 단톡방 이름 또는 상대 이름) */
fun openRoom(ctx: Context, pkg: String?, room: String, srcLabel: String) {
    val src = if (srcLabel.startsWith("문자")) Source.SMS else Source.KAKAO
    openEntry(ctx, Entry("room", 0L, src, room, "", room, pkg, if (src == Source.SMS) "문자" else "카카오톡"))
}

fun openDeadlineSource(ctx: Context, d: com.mydaylog.app.insight.Deadline) {
    val parts = d.srcLabel.split(" · ", limit = 2)
    if (parts.size == 2 && (parts[0] == "카톡" || parts[0] == "문자")) openRoom(ctx, d.pkg, parts[1], parts[0])
    else openApp(ctx, d.pkg)
}

// ---------- 권한·상태 ----------
fun isListenerEnabled(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

fun isBatteryUnrestricted(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** 화면으로 돌아올 때마다 값이 바뀜 → 권한 상태/목록 새로고침용 */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun BulletList(items: List<String>, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach {
            Row {
                Text("•", fontSize = 14.sp, color = color, modifier = Modifier.padding(end = 8.dp))
                Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = color)
            }
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** 항목 오른쪽의 ⋮ 메뉴 (수정·삭제 등) */
@Composable
fun RowMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(androidx.compose.material.icons.Icons.Default.MoreVert, "더보기")
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, color = if (label.contains("삭제") || label.contains("아님")) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}
