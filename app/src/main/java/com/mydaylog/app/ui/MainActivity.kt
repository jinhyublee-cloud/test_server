package com.mydaylog.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mydaylog.app.Prefs
import com.mydaylog.app.R
import com.mydaylog.app.data.Source
import com.mydaylog.app.security.SecLog
import com.mydaylog.app.security.Vault
import java.time.LocalDate

object AppLock {
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
}

class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(false)
    private var noDeviceLock by mutableStateOf(false)
    private var vaultError by mutableStateOf<String?>(null)
    private var consented by mutableStateOf(false)
    private var lastStop = 0L
    private var lastStopDay: LocalDate = LocalDate.now()
    private var authFailures = 0

    // 화면 상태 (잠금 후에도 유지)
    private var tab by mutableIntStateOf(0)
    private var inboxSeg by mutableIntStateOf(0)
    private var day by mutableStateOf(LocalDate.now())
    private var filter by mutableStateOf<Source?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowProtection(this)
        enableEdgeToEdge()
        vaultError = runCatching { Vault.ensure(this) }.exceptionOrNull()?.message
        handleIntent(intent)
        consented = Prefs.consentVersion(this) >= CONSENT_VERSION
        if (consented) evaluateLock()
        setContent {
            AppTheme {
                when {
                    vaultError != null -> VaultErrorScreen(vaultError!!)
                    // 첫 실행: 개인정보 처리 안내 동의 전에는 아무것도 읽지 않음
                    !consented -> ConsentScreen(
                        onAgree = {
                            Prefs.setConsentVersion(this, CONSENT_VERSION)
                            SecLog.add(this, "개인정보 처리 안내 동의 (v$CONSENT_VERSION)")
                            consented = true
                            evaluateLock()
                        },
                        onDecline = { finishAndRemoveTask() },
                    )
                    noDeviceLock -> NoDeviceLockScreen()
                    locked -> LockScreen(::authenticate)
                    else -> MainScreen(
                        // 홈 탭은 언제나 오늘 기준으로 엶
                        tab = tab, onTab = { if (it == 0) day = LocalDate.now(); tab = it },
                        day = day, onDay = { if (!it.isAfter(LocalDate.now())) day = it },
                        filter = filter, onFilter = { filter = it },
                        inboxSeg = inboxSeg, onInboxSeg = { inboxSeg = it },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val t = i?.getIntExtra("tab", -1) ?: -1
        if (t in 0..5) tab = t
    }

    /** S-03: 잠금을 켰는데 기기 화면 잠금이 없으면 열지 않음 (fail-closed) */
    private fun evaluateLock() {
        if (!Prefs.lockEnabled(this)) { locked = false; noDeviceLock = false; return }
        if (!AppLock.available(this)) {
            noDeviceLock = true; locked = true
            SecLog.add(this, "열기 차단 · 기기 화면 잠금 없음")
            return
        }
        noDeviceLock = false
        locked = true
        AskSession.clear()
        authenticate()
    }

    override fun onStop() {
        super.onStop()
        lastStop = SystemClock.elapsedRealtime()
        lastStopDay = LocalDate.now()
    }

    /** 앱을 다시 열면 홈의 오늘 대시보드부터 (잠깐 다른 앱에 다녀온 경우는 그대로) */
    private fun goHomeIfReopened() {
        val away = SystemClock.elapsedRealtime() - lastStop
        if (away >= REOPEN_HOME_MS || LocalDate.now() != lastStopDay) {
            tab = 0
            day = LocalDate.now()
        }
    }

    override fun onRestart() {
        super.onRestart()
        applyWindowProtection(this)
        goHomeIfReopened()
        if (!consented) return
        if (noDeviceLock) { evaluateLock(); return }
        val relockMs = Prefs.relockSeconds(this) * 1000L
        if (Prefs.lockEnabled(this) && SystemClock.elapsedRealtime() - lastStop >= relockMs) evaluateLock()
    }

    private fun authenticate() {
        authFailures = 0
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                    val how = if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC) "지문" else "화면 잠금"
                    if (authFailures > 0) SecLog.add(this@MainActivity, "잠금 해제 실패 ${authFailures}회")
                    SecLog.add(this@MainActivity, "잠금 해제 · $how")
                }
                override fun onAuthenticationFailed() { authFailures++ }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (authFailures > 0) SecLog.add(this@MainActivity, "잠금 해제 실패 ${authFailures}회")
                    authFailures = 0
                }
            })
        prompt.authenticate(promptInfo("하루기록 잠금 해제", "지문 또는 화면 잠금으로 확인해 주세요"))
    }

    /** 민감한 설정 변경 전에 본인 확인 (S-03) */
    fun requireAuth(reason: String, onOk: () -> Unit) {
        if (!AppLock.available(this)) {
            Toast.makeText(this, "폰에 화면 잠금을 먼저 설정해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationFailed() {
                    SecLog.add(this@MainActivity, "본인 확인 실패 · $reason")
                }
            }).authenticate(promptInfo("본인 확인이 필요해요", reason))
    }

    private fun promptInfo(title: String, sub: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title).setSubtitle(sub)
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()

    companion object {
        /** 이 시간 넘게 떠나 있다가 돌아오면 앱을 새로 연 것으로 봄 */
        private const val REOPEN_HOME_MS = 3 * 60_000L

        /** S-04·S-15: 캡처 차단, 덮어쓰기 창 숨김, 가려진 터치 무시, 접근성 앱 화면 읽기 제한 */
        fun applyWindowProtection(a: Activity) {
            val w = a.window
            if (Prefs.secureScreen(a)) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val protect = Prefs.protectScreen(a)
            runCatching { w.setHideOverlayWindows(protect) }
            w.decorView.filterTouchesWhenObscured = protect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                w.decorView.setAccessibilityDataSensitive(
                    if (protect) View.ACCESSIBILITY_DATA_SENSITIVE_YES else View.ACCESSIBILITY_DATA_SENSITIVE_AUTO
                )
            }
        }
    }
}

@Composable
private fun CenterMessage(title: String, body: String, button: String?, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_shield), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (button != null) Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) =
    CenterMessage("하루기록이 잠겨 있어요", "기록은 이 폰 안에만 암호화되어 있어요", "잠금 해제", onUnlock)

@Composable
private fun NoDeviceLockScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    CenterMessage(
        "폰 화면 잠금이 꺼져 있어요",
        "앱 잠금을 켜 둔 상태라, 폰에 PIN·패턴·지문을 설정해야 열 수 있어요. 설정 후 돌아오면 바로 열려요.",
        "화면 잠금 설정하기",
    ) { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun VaultErrorScreen(msg: String) = CenterMessage(
    "기록을 열 수 없어요",
    "$msg. 기록은 지우지 않고 그대로 두었어요. 폰을 다시 켠 뒤에도 같으면 알려주세요.",
    null,
) {}

@Composable
fun MainScreen(
    tab: Int, onTab: (Int) -> Unit,
    day: LocalDate, onDay: (LocalDate) -> Unit,
    filter: Source?, onFilter: (Source?) -> Unit,
    inboxSeg: Int, onInboxSeg: (Int) -> Unit,
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(tab == 0, { onTab(0) }, { Icon(painterResource(R.drawable.ic_nav_home), null) }, label = { Text("홈") }, colors = itemColors)
                NavigationBarItem(tab == 1, { onTab(1) }, { Icon(painterResource(R.drawable.ic_nav_list), null) }, label = { Text("타임라인") }, colors = itemColors)
                NavigationBarItem(tab == 5, { onTab(5) }, { Icon(painterResource(R.drawable.ic_ask), null) }, label = { Text("물어보기") }, colors = itemColors)
                NavigationBarItem(tab == 2, { onTab(2) }, { Icon(painterResource(R.drawable.ic_nav_inbox), null) }, label = { Text("챙길 것") }, colors = itemColors)
                NavigationBarItem(tab == 3, { onTab(3) }, { Icon(painterResource(R.drawable.ic_sparkle), null) }, label = { Text("요약") }, colors = itemColors)
            }
        }
    ) { pad ->
        // 키보드가 올라오면 입력창이 가려지지 않게 (하단 바 높이는 빼고)
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(
                    day, onDay,
                    openTimeline = { onFilter(it); onTab(1) },
                    openSummary = { onTab(3) },
                    openSettings = { onTab(4) },
                    openInbox = { onInboxSeg(it); onTab(2) },
                )
                1 -> TimelineScreen(day, onDay, filter, onFilter)
                2 -> InboxScreen(inboxSeg, onInboxSeg)
                3 -> SummaryScreen(day, onDay)
                5 -> AskScreen()
                else -> SettingsScreen(onBack = { onTab(0) })
            }
        }
    }
}
