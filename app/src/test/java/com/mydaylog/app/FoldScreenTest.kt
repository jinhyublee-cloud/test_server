package com.mydaylog.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mydaylog.app.data.Db
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.Insights
import com.mydaylog.app.security.Vault
import com.mydaylog.app.ui.AppTheme
import com.mydaylog.app.ui.MainActivity
import com.mydaylog.app.ui.MainScreen
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import javax.crypto.spec.SecretKeySpec

/**
 * 갤럭시 Z 폴드6 화면 크기에서 모든 탭을 그려 보고 스크린샷으로 남김 (app/build/fold 폴더)
 * - 접은 화면(커버): 968 x 2376 px, 420dpi → 약 369 x 905 dp
 * - 펼친 화면(메인): 1856 x 2160 px, 420dpi → 약 707 x 823 dp
 * 실제 기기 대신 Robolectric으로 그림. 기기 보안 키 대신 테스트용 키를 넣음.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class)
class FoldScreenTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun seed() {
        TestKeys.install()
        Prefs.setConsentVersion(ctx, 1)
        Prefs.setLockEnabled(ctx, false)
        SampleData.insert(ctx)
    }

    private val tabs = listOf(0 to "home", 1 to "timeline", 5 to "ask", 2 to "inbox", 3 to "summary", 4 to "settings")

    private fun captureAllTabs(prefix: String, fontScale: Float = 1f) {
        var tab by mutableIntStateOf(0)
        var seg by mutableIntStateOf(0)
        var filter by mutableStateOf<Source?>(null)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                AppTheme {
                    MainScreen(
                        tab = tab, onTab = { tab = it },
                        day = LocalDate.now(), onDay = {},
                        filter = filter, onFilter = { filter = it },
                        inboxSeg = seg, onInboxSeg = { seg = it },
                    )
                }
            }
        }
        val shots = tabs.map { it } + listOf(-1 to "inbox_due", -2 to "inbox_pay")
        for ((t, name) in shots) {
            when (t) {
                -1 -> { tab = 2; seg = 1 }
                -2 -> { tab = 2; seg = 2 }
                else -> { tab = t; seg = 0 }
            }
            settle()
            rule.onRoot().captureRoboImage("build/fold/${prefix}_$name.png")
        }
    }

    /** 화면이 IO 스레드에서 기록을 읽어 오는 시간을 기다림 */
    private fun settle() {
        repeat(8) { Thread.sleep(120); rule.waitForIdle() }
    }

    @Config(qualifiers = "w369dp-h905dp-port-420dpi")
    @Test fun cover() = captureAllTabs("1_cover")

    /** 접은 화면 + 큰 글씨 (설정 → 글자 크기 키움) */
    @Config(qualifiers = "w369dp-h905dp-port-420dpi")
    @Test fun coverLargeFont() = captureAllTabs("2_cover_font130", fontScale = 1.3f)

    @Config(qualifiers = "w707dp-h823dp-port-420dpi")
    @Test fun inner() = captureAllTabs("3_inner")

    @Config(qualifiers = "w823dp-h707dp-land-420dpi")
    @Test fun innerLandscape() = captureAllTabs("4_inner_land")
}

/**
 * 접기·펴기: 화면 크기가 바뀌어도 앱을 다시 만들지 않아야 함
 * (다시 만들면 보던 탭이 홈으로 돌아가고, 잠금이 켜져 있으면 지문을 또 물어봄)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w369dp-h905dp-port-420dpi")
class FoldConfigChangeTest {
    @Test fun unfoldKeepsActivity() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        TestKeys.install()
        Prefs.setConsentVersion(ctx, 1)
        Prefs.setLockEnabled(ctx, false)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val before = c.get()
        val unfolded = Configuration(before.resources.configuration).apply {
            screenWidthDp = 707; screenHeightDp = 823; smallestScreenWidthDp = 707
            screenLayout = (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()) or Configuration.SCREENLAYOUT_SIZE_LARGE
        }
        c.configurationChange(unfolded)
        assertSame("펼쳤을 때 화면을 다시 만들면 안 됨", before, c.get())
        val folded = Configuration(unfolded).apply {
            screenWidthDp = 369; screenHeightDp = 905; smallestScreenWidthDp = 369
            screenLayout = (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()) or Configuration.SCREENLAYOUT_SIZE_NORMAL
        }
        c.configurationChange(folded)
        assertSame("접었을 때 화면을 다시 만들면 안 됨", before, c.get())
        // 다음 테스트에 화면(Compose)이 남아 있지 않게 정리
        c.pause().stop().destroy()
    }
}

/** 작업 예약(WorkManager)·서명 확인 없이 뜨는 테스트용 Application */
class TestApp : android.app.Application()

/** Android Keystore는 Robolectric에 없으므로 테스트용 데이터 키를 직접 넣음 */
object TestKeys {
    fun install() {
        // 테스트마다 새 앱 환경이므로 이전 테스트의 DB 연결을 버림
        Db::class.java.getDeclaredField("inst").apply { isAccessible = true }.set(null, null)
        val raw = ByteArray(64) { it.toByte() }
        Vault::class.java.getDeclaredField("encKey").apply { isAccessible = true }
            .set(Vault, SecretKeySpec(raw.copyOfRange(0, 32), "AES"))
        Vault::class.java.getDeclaredField("macKey").apply { isAccessible = true }
            .set(Vault, raw.copyOfRange(32, 64))
    }
}

/** 화면에 보일 가상의 기록 (실제 사람·가게와 무관) */
object SampleData {
    fun insert(ctx: Context) {
        val db = Db.get(ctx)
        val now = System.currentTimeMillis()
        fun ago(min: Int) = now - min * 60_000L
        var n = 0
        fun add(min: Int, pkg: String, app: String, cat: String, title: String, text: String, conv: String? = null) {
            db.insertNoti(ago(min), pkg, app, cat, title, text, conv, "sample${n++}")
        }
        val sms = "com.samsung.android.messaging"
        val kakao = Prefs.KAKAO
        add(5, kakao, "카카오톡", "카톡", "김하늘", "이번 주 토요일 저녁 어때? 7시에 강남역 11번 출구에서 보자", "대학 동기방")
        add(7, kakao, "카카오톡", "카톡", "박서준", "좋아 나도 갈게! 오랜만에 다 모이겠다 ㅎㅎ", "대학 동기방")
        add(9, kakao, "카카오톡", "카톡", "이지은", "나는 8시쯤 도착할 것 같아. 먼저 시작하고 있어", "대학 동기방")
        add(25, sms, "삼성 메시지", "문자", "신한카드", "[Web발신]\n신한카드(1234)승인 홍*동 12,500원 일시불 11/20 12:31 스타벅스강남대로점 누적1,234,000원")
        add(48, sms, "삼성 메시지", "문자", "KB국민카드", "[Web발신]\nKB국민카드 5678 승인 홍*동 48,900원 일시불 11/20 11:02 쿠팡(주)")
        add(70, sms, "삼성 메시지", "문자", "하나은행", "[Web발신]\n하나 입금 2,350,000원 11/20 10:40 (주)회사급여 잔액3,512,400원")
        add(95, "com.google.android.gm", "Gmail", "메일", "연말정산 서류 제출 안내", "2026년 연말정산 간소화 자료를 12.5(금)까지 회신 부탁드립니다. 미제출 시 불이익이 있을 수 있습니다.")
        add(130, "com.coupang.mobile", "쿠팡", "앱", "쿠폰 만료 예정", "보유하신 5,000원 할인 쿠폰 유효기간이 11.30(월)까지입니다. 기간 내 사용해 주세요.")
        add(160, kakao, "카카오톡", "카톡", "엄마", "주말에 집에 올 때 김치 가져가라. 일요일 점심 같이 먹자")
        add(200, sms, "삼성 메시지", "문자", "SK텔레콤", "[Web발신]\n11월 휴대폰 요금 68,200원이 11/25 자동이체 예정입니다.")
        add(260, kakao, "카카오톡", "카톡", "팀장님", "내일 오전 10시 주간회의 자료 오늘 중으로 공유 부탁해요", "프로젝트팀")
        add(300, "com.google.android.gm", "Gmail", "메일", "[뉴스레터] 이번 주 IT 소식", "폴더블 폰 시장 동향과 새로운 AI 기능 소개")
        runCatching { Insights.refresh(ctx) }
    }
}
