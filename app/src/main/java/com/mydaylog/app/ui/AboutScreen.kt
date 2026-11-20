package com.mydaylog.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

/** 동의 문구가 바뀌면 올려서 다시 동의를 받음 */
const val CONSENT_VERSION = 1

/** 개인정보 처리 안내 (첫 실행 동의 화면과 설정에서 같은 내용) */
val PRIVACY_SECTIONS: List<Pair<String, String>> = listOf(
    "무엇을 읽나요" to
        "이 폰에 오는 알림(카카오톡·문자·메일·선택한 앱), 캘린더 일정, 통화 기록을 읽어 하루 기록·요약·챙길 것·결제 내역을 만들어요. " +
        "각 항목은 설정에서 켜고 끌 수 있고, 인증번호 알림과 인증·비밀번호 앱은 저장하지 않아요.",
    "어디에 저장하나요" to
        "이 폰 안에만 저장해요. 알림 내용은 폰의 보안 하드웨어(Android Keystore)로 보호되는 키로 암호화해요. " +
        "캘린더와 통화 기록은 화면에 보여줄 때만 읽고 따로 저장하지 않아요.",
    "밖으로 보내나요" to
        "보내지 않아요. 이 앱에는 인터넷 권한이 없어서 기록을 어디로도 전송할 수 없어요. 개발자도 여러분의 기록을 볼 수 없어요. " +
        "백업·기기 간 전송에서도 기록은 빠져요.",
    "기기 AI(물어보기)" to
        "지원되는 갤럭시 폰에서는 폰에 들어 있는 Gemini Nano(Android AICore)가 답변 문장을 다듬어요. 처리는 폰 안에서만 이뤄져요. " +
        "AI 모델 파일은 폰 시스템(AICore)이 관리해요. 설정에서 끌 수 있어요.",
    "얼마나 보관하나요" to
        "알림 원본은 설정한 기간(기본 90일)이 지나면 자동으로 지워요. 설정 → 모든 기록 삭제로 언제든 전부 지울 수 있고, 앱을 지우면 기록도 모두 사라져요.",
    "꼭 지켜 주세요" to
        "본인 폰에만 설치해 본인 기록을 정리하는 용도로 써 주세요. 다른 사람 몰래 그 사람 폰에 설치해 메시지·통화 기록을 보는 것은 " +
        "통신비밀보호법·개인정보보호법 등에 따라 처벌될 수 있어요. 앱을 열려면 폰의 화면 잠금(지문·PIN 등)이 필요해요.",
    "책임의 한계" to
        "개인이 만든 무료 앱으로, 있는 그대로 제공돼요. 알림 문구를 규칙으로 해석하므로 일정·금액이 틀리거나 빠질 수 있어요. " +
        "중요한 결제·일정은 원래 문자·앱에서 꼭 확인해 주세요.",
)

@Composable
private fun PrivacyBody() {
    val cs = MaterialTheme.colorScheme
    PRIVACY_SECTIONS.forEach { (h, b) ->
        Text(h, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(b, fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
}

/** 첫 실행: 안내를 읽고 동의해야 권한 요청·잠금 설정으로 넘어감 */
@Composable
fun ConsentScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var ownPhone by remember { mutableStateOf(false) }
    var agree by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    if (licenses) LicensesDialog { licenses = false }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("하루기록 시작 전에", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("기록이 어떻게 다뤄지는지 확인해 주세요.", fontSize = 15.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            PrivacyBody()
            TextButton(onClick = { licenses = true }) { Text("오픈소스 라이선스 보기") }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            CheckLine(ownPhone, { ownPhone = it }, "본인 폰에 본인 기록을 정리하려고 설치했어요")
            CheckLine(agree, { agree = it }, "위 개인정보 처리 안내와 책임의 한계에 동의해요")
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("동의 안 함") }
                Button(onClick = onAgree, enabled = ownPhone && agree, modifier = Modifier.weight(1f)) { Text("동의하고 시작") }
            }
        }
    }
}

@Composable
private fun CheckLine(on: Boolean, set: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, set)
        Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun PrivacyDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("개인정보 처리 안내") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { PrivacyBody() } },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

private val LIBS = listOf(
    Triple("AndroidX (Compose, Activity, Core, Lifecycle, Work, Biometric, Fragment, Startup)", "The Android Open Source Project", "Apache License 2.0"),
    Triple("Material Components / Material Icons for Compose", "The Android Open Source Project", "Apache License 2.0"),
    Triple("Kotlin 표준 라이브러리, kotlinx.coroutines", "JetBrains s.r.o.", "Apache License 2.0"),
    Triple("Google Android DataTransport", "Google LLC", "Apache License 2.0"),
    Triple("ML Kit GenAI Prompt API (Gemini Nano)", "Google LLC", "ML Kit 서비스 약관 및 생성형 AI 금지 사용 정책 적용 (developers.google.com/ml-kit/terms)"),
    Triple("Google Play services 기본 라이브러리", "Google LLC", "Android Software Development Kit License"),
)

@Composable
fun LicensesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var full by remember { mutableStateOf(false) }
    val apache = remember { runCatching { ctx.assets.open("LICENSE-Apache-2.0.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("오픈소스 라이선스") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LIBS.forEach { (name, by, lic) ->
                    Column {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("$by · $lic", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { full = !full }) { Text(if (full) "Apache License 2.0 전문 접기" else "Apache License 2.0 전문 보기") }
                if (full) Text(apache, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

/** 받은 APK가 원본인지 확인용: 버전과 서명 인증서 SHA-256 */
fun signingFingerprint(ctx: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
    MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
}.getOrDefault("확인 불가")

@Composable
fun AppInfoDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val fp = remember { signingFingerprint(ctx) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("앱 정보") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("하루기록 $ver", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("앱 ID: ${ctx.packageName}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Text("서명 인증서 SHA-256", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(fp, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                Text("배포한 사람이 알려준 값(Android 13 이상: 20:B0:5C…, Android 12: F9:A1:8C…)과 같으면 원본 앱이에요. 다르면 지우고 원래 배포처에서 다시 받으세요.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}
