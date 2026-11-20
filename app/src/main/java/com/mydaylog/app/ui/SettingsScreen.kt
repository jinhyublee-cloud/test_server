package com.mydaylog.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mydaylog.app.Prefs
import com.mydaylog.app.R
import com.mydaylog.app.data.Db
import com.mydaylog.app.data.LogRow
import com.mydaylog.app.data.Repo
import com.mydaylog.app.data.SeenApp
import com.mydaylog.app.security.SecLog
import com.mydaylog.app.security.Vault
import com.mydaylog.app.work.DailyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun openAppDetails(ctx: Context) {
    Toast.makeText(ctx, "권한 → 해당 항목을 '허용'으로 바꿔 주세요", Toast.LENGTH_LONG).show()
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun logTime(ts: Long): String {
    val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    val d = t.toLocalDate()
    val day = when (d) { LocalDate.now() -> "오늘"; LocalDate.now().minusDays(1) -> "어제"; else -> "${d.monthValue}/${d.dayOfMonth}" }
    return "%s %02d:%02d".format(day, t.hour, t.minute)
}

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }

    val listenerOn = remember(tick, refresh) { isListenerEnabled(ctx) }
    val calOn = remember(tick, refresh) { Repo.hasCalendar(ctx) }
    val callOn = remember(tick, refresh) { Repo.hasCallLog(ctx) }
    val batteryOk = remember(tick, refresh) { isBatteryUnrestricted(ctx) }
    // Android 12까지는 알림 권한이 따로 없고 기본으로 허용됨
    val notifOk = remember(tick, refresh) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU)
            androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok) openAppDetails(ctx)
    }

    var captureAll by remember { mutableStateOf(Prefs.captureAll(ctx)) }
    var enabled by remember { mutableStateOf(Prefs.enabledPkgs(ctx)) }
    var hour by remember { mutableIntStateOf(Prefs.hour(ctx)) }
    var minute by remember { mutableIntStateOf(Prefs.minute(ctx)) }
    var retention by remember { mutableIntStateOf(Prefs.retentionDays(ctx)) }
    var lock by remember { mutableStateOf(Prefs.lockEnabled(ctx)) }
    var secure by remember { mutableStateOf(Prefs.secureScreen(ctx)) }
    var protect by remember { mutableStateOf(Prefs.protectScreen(ctx)) }
    var skipOtp by remember { mutableStateOf(Prefs.skipOtp(ctx)) }
    var relock by remember { mutableIntStateOf(Prefs.relockSeconds(ctx)) }
    var askAi by remember { mutableStateOf(Prefs.askAi(ctx)) }
    var about by remember { mutableStateOf(0) } // 1 개인정보, 2 라이선스, 3 앱 정보
    when (about) {
        1 -> PrivacyDialog { about = 0 }
        2 -> LicensesDialog { about = 0 }
        3 -> AppInfoDialog { about = 0 }
    }
    val lockAvailable = remember(tick) { AppLock.available(ctx) }
    var strongBox by remember { mutableStateOf<Boolean?>(null) }
    var seen by remember { mutableStateOf<List<SeenApp>>(emptyList()) }
    var logs by remember { mutableStateOf<List<LogRow>>(emptyList()) }
    var showAllLogs by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCaptureAll by remember { mutableStateOf(false) }

    LaunchedEffect(tick, refresh) {
        withContext(Dispatchers.IO) {
            val s = runCatching { Db.get(ctx).seenApps() }.getOrDefault(emptyList())
            val l = runCatching { Db.get(ctx).logs(50) }.getOrDefault(emptyList())
            val sb = Vault.isStrongBox(ctx)
            withContext(Dispatchers.Main) { seen = s; logs = l; strongBox = sb }
        }
    }

    /** 보호 기능을 끄는 쪽은 본인 확인 후에만 (S-03) */
    fun guarded(turningOff: Boolean, reason: String, apply: () -> Unit) {
        if (!turningOff) { apply(); refresh++; return }
        if (activity == null) return
        activity.requireAuth(reason) { apply(); refresh++ }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.activity.compose.BackHandler(onBack = onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "홈으로")
            }
            Text("설정", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        Section("모을 기록") {
            PermRow("알림 접근 (필수)", "문자·카톡·메일·앱 알림을 읽어 기록해요", listenerOn) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            PermRow("캘린더", "일정을 읽기만 해요 (저장하지 않음)", calOn) { permLauncher.launch(Manifest.permission.READ_CALENDAR) }
            PermRow("통화 기록", "수신·발신·부재중 전화를 읽기만 해요 (저장하지 않음)", callOn) { permLauncher.launch(Manifest.permission.READ_CALL_LOG) }
            PermRow("배터리 사용 제한 없음", "갤럭시에서 수집이 끊기지 않게 해요", batteryOk) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
            PermRow("알림 표시", "요약·마감·정기 결제를 알려줘요 (내용 없이 개수만)", notifOk) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }

        Section("물어보기") {
            SwitchRow(
                "기기 내장 AI로 답변 다듬기",
                "갤럭시에 들어 있는 Gemini Nano가 폰 안에서 문장을 만들어요. 숫자·금액은 앱이 계산한 값만 쓰고, 다르면 기본 답변을 보여줘요",
                askAi,
            ) { v -> askAi = v; Prefs.setAskAi(ctx, v); SecLog.add(ctx, "설정 변경 · 기기 AI 답변 ${if (v) "켬" else "끔"}") }
        }

        Section("보안") {
            SwitchRow(
                "앱 잠금",
                if (lockAvailable) "끄려면 지문이나 화면 잠금으로 다시 확인해요" else "폰에 화면 잠금을 설정해야 쓸 수 있어요",
                lock, enabledSwitch = lockAvailable || lock,
            ) { v ->
                guarded(!v, "앱 잠금을 끄려면 확인해 주세요") {
                    lock = v; Prefs.setLockEnabled(ctx, v); SecLog.add(ctx, "설정 변경 · 앱 잠금 ${if (v) "켬" else "끔"}")
                }
            }
            SwitchRow("화면 캡처 막기", "스크린샷과 최근 앱 미리보기에서 가려요", secure) { v ->
                guarded(!v, "화면 캡처 막기를 끄려면 확인해 주세요") {
                    secure = v; Prefs.setSecureScreen(ctx, v); activity?.let { MainActivity.applyWindowProtection(it) }
                    SecLog.add(ctx, "설정 변경 · 화면 캡처 막기 ${if (v) "켬" else "끔"}")
                }
            }
            SwitchRow("다른 앱의 화면 엿보기 막기", "접근성 앱이 화면 글자를 읽거나, 다른 앱이 화면 위를 덮는 것을 막아요", protect) { v ->
                guarded(!v, "화면 보호를 끄려면 확인해 주세요") {
                    protect = v; Prefs.setProtectScreen(ctx, v); activity?.let { MainActivity.applyWindowProtection(it) }
                    SecLog.add(ctx, "설정 변경 · 화면 엿보기 막기 ${if (v) "켬" else "끔"}")
                }
            }
            SwitchRow("인증 문자 저장 안 함", "인증번호·본인확인 알림은 아예 기록하지 않아요", skipOtp) { v ->
                guarded(!v, "인증 문자를 저장하려면 확인해 주세요") {
                    skipOtp = v; Prefs.setSkipOtp(ctx, v); SecLog.add(ctx, "설정 변경 · 인증 문자 저장 안 함 ${if (v) "켬" else "끔"}")
                }
            }
            Text("다시 잠그는 시간", fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "바로", 30 to "30초", 60 to "1분", 300 to "5분").forEach { (s, label) ->
                    FilterChip(selected = relock == s, onClick = {
                        guarded(s > relock, "잠금 시간을 늘리려면 확인해 주세요") {
                            relock = s; Prefs.setRelockSeconds(ctx, s); SecLog.add(ctx, "설정 변경 · 다시 잠그는 시간 $label")
                        }
                    }, label = { Text(label) })
                }
            }
            Divider()
            SecureFact("인터넷 권한이 없는 앱이에요. 기록이 폰 밖으로 나갈 수 없어요.")
            SecureFact(
                "저장된 기록은 기기 보안 키로 암호화돼요" + when (strongBox) {
                    true -> " (보안 칩 StrongBox에 보관)."
                    false -> " (기기 보안 영역에 보관)."
                    null -> "."
                }
            )
            SecureFact("캘린더와 통화 기록은 화면을 열 때만 읽고 따로 저장하지 않아요.")
            SecureFact("인증 앱·비밀번호 앱 알림은 \"모든 앱\"을 켜도 모으지 않아요.")
            SecureFact("클라우드 백업과 기기 이전 대상에서 제외돼요.")
        }

        Section("보안 기록") {
            Text("최근 90일 · 암호화 저장", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (logs.isEmpty()) Text("아직 기록이 없어요.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (if (showAllLogs) logs else logs.take(6)).forEach { l ->
                val color = when {
                    l.text.contains("실패") || l.text.contains("차단") || l.text.contains("오류") || l.text.contains("달라요") -> Color(0xFFC98A00)
                    l.text.startsWith("잠금 해제") -> successColor()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(l.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(logTime(l.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (logs.size > 6) TextButton(onClick = { showAllLogs = !showAllLogs }) { Text(if (showAllLogs) "접기" else "더 보기 (${logs.size})") }
        }

        Section("요약·알림") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("하루 요약 자동 생성", fontSize = 16.sp)
                    Hint("새벽 6시 전으로 정하면 전날 요약을 만들어요")
                }
                TextButton(onClick = {
                    pickTime(ctx, hour, minute) { h, m ->
                        hour = h; minute = m
                        Prefs.setTime(ctx, h, m)
                        DailyWorker.schedule(ctx, replace = true)
                    }
                }) { Text("매일 %02d:%02d".format(hour, minute)) }
            }
            Hint("마감은 3일 전·하루 전·당일, 정기 결제는 사흘 전 오전 9시에 알려줘요.")
        }

        Section("알림을 모을 앱") {
            SwitchRow("모든 앱 알림 모으기", "켜면 목록과 상관없이 저장해요 (인증·비밀번호 앱 제외)", captureAll) { v ->
                if (v) confirmCaptureAll = true
                else { captureAll = false; Prefs.setCaptureAll(ctx, false); SecLog.add(ctx, "설정 변경 · 모든 앱 알림 모으기 끔") }
            }
            val apps = Prefs.DEFAULT_APPS.map { it.key to it.value } +
                seen.filter { it.pkg !in Prefs.DEFAULT_APPS && it.pkg !in Repo.DIALER_PKGS }.map { it.pkg to it.app }
            apps.forEach { (pkg, name) ->
                val sensitive = pkg in Prefs.SENSITIVE_PKGS
                SwitchRow(
                    name, if (sensitive) "인증·비밀번호 앱 · 직접 켤 때만 저장" else pkg,
                    (captureAll && !sensitive) || pkg in enabled, enabledSwitch = !captureAll || sensitive,
                ) { on ->
                    Prefs.setPkgEnabled(ctx, pkg, on); enabled = Prefs.enabledPkgs(ctx)
                }
            }
            Hint("알림을 보낸 적 있는 앱은 여기에 자동으로 추가돼요. 카드·은행·쿠팡·선물하기 앱을 켜두면 결제와 마감이 잘 잡혀요.")
        }

        Section("데이터") {
            Text("원본 알림 보관 기간 (요약·결제 내역은 따로 보관)", fontSize = 16.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30일", 90 to "90일", 365 to "1년", 0 to "무제한").forEach { (d, label) ->
                    FilterChip(selected = retention == d, onClick = { retention = d; Prefs.setRetention(ctx, d) }, label = { Text(label) })
                }
            }
            OutlinedButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("모든 기록 삭제 (본인 확인 필요)") }
        }

        Section("앱 정보") {
            TextButton(onClick = { about = 1 }) { Text("개인정보 처리 안내") }
            TextButton(onClick = { about = 2 }) { Text("오픈소스 라이선스") }
            TextButton(onClick = { about = 3 }) { Text("버전·서명 확인 (원본 앱인지 확인)") }
        }
    }

    if (confirmCaptureAll) {
        AlertDialog(
            onDismissRequest = { confirmCaptureAll = false },
            title = { Text("모든 앱 알림을 모을까요?") },
            text = { Text("은행·쇼핑·SNS 등 모든 앱의 알림이 이 폰에 암호화돼 저장돼요. 인증·비밀번호 앱은 계속 제외돼요. 필요한 앱만 켜는 쪽이 더 안전해요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCaptureAll = false
                    captureAll = true; Prefs.setCaptureAll(ctx, true); SecLog.add(ctx, "설정 변경 · 모든 앱 알림 모으기 켬")
                }) { Text("켜기") }
            },
            dismissButton = { TextButton(onClick = { confirmCaptureAll = false }) { Text("취소") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("모든 기록을 삭제할까요?") },
            text = { Text("모은 알림, 요약, 챙길 것, 결제 내역, 보안 기록이 모두 지워지고 되돌릴 수 없어요. 폰의 캘린더와 통화 기록은 그대로예요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    activity?.requireAuth("모든 기록을 지우려면 확인해 주세요") {
                        scope.launch {
                            withContext(Dispatchers.IO) { Db.get(ctx).deleteAll(); SecLog.add(ctx, "모든 기록 삭제") }
                            com.mydaylog.app.capture.ChatLinks.clear(); AskSession.clear()
                            seen = emptyList(); refresh++
                            Toast.makeText(ctx, "삭제했어요", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SecureFact(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_shield), null, tint = successColor(), modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun PermRow(title: String, desc: String, ok: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 16.sp)
            Hint(desc)
        }
        if (ok) Icon(Icons.Filled.CheckCircle, "허용됨", tint = MaterialTheme.colorScheme.primary)
        else Button(onClick = onClick) { Text("허용") }
    }
}

@Composable
private fun SwitchRow(
    title: String, desc: String, checked: Boolean,
    enabledSwitch: Boolean = true, onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 16.sp)
            Hint(desc)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabledSwitch)
    }
}
