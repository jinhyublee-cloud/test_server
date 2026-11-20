package com.mydaylog.app

import android.content.Context

object Prefs {
    val SMS_PKGS = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms",
    )
    const val KAKAO = "com.kakao.talk"
    val MAIL_PKGS = setOf(
        "com.google.android.gm",
        "com.samsung.android.email.provider",
        "com.nhn.android.mail",
        "com.microsoft.office.outlook",
        "net.daum.android.mail",
    )

    /** 기본으로 수집하는 앱 (설정에서 켜고 끌 수 있음) */
    val DEFAULT_APPS: LinkedHashMap<String, String> = linkedMapOf(
        "com.samsung.android.messaging" to "삼성 메시지",
        "com.google.android.apps.messaging" to "Google 메시지",
        "com.kakao.talk" to "카카오톡",
        "com.google.android.gm" to "Gmail",
        "com.samsung.android.email.provider" to "삼성 이메일",
        "com.nhn.android.mail" to "네이버 메일",
        "com.microsoft.office.outlook" to "Outlook",
    )

    fun categoryOf(pkg: String): String = when {
        pkg in SMS_PKGS -> "문자"
        pkg == KAKAO -> "카톡"
        pkg in MAIL_PKGS -> "메일"
        else -> "앱"
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("daylog", Context.MODE_PRIVATE)

    fun captureAll(ctx: Context): Boolean = sp(ctx).getBoolean("capture_all", false)
    fun setCaptureAll(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("capture_all", v).apply()

    fun enabledPkgs(ctx: Context): Set<String> =
        sp(ctx).getStringSet("pkgs", null)?.toSet() ?: DEFAULT_APPS.keys.toSet()

    fun setPkgEnabled(ctx: Context, pkg: String, on: Boolean) {
        val s = enabledPkgs(ctx).toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        sp(ctx).edit().putStringSet("pkgs", s).apply()
    }

    fun hour(ctx: Context): Int = sp(ctx).getInt("hour", 23)
    fun minute(ctx: Context): Int = sp(ctx).getInt("minute", 30)
    fun setTime(ctx: Context, h: Int, m: Int) = sp(ctx).edit().putInt("hour", h).putInt("minute", m).apply()

    /** 원본 알림 보관 일수 (0 = 무제한). 요약은 항상 보관 */
    fun retentionDays(ctx: Context): Int = sp(ctx).getInt("retention", 90)
    fun setRetention(ctx: Context, d: Int) = sp(ctx).edit().putInt("retention", d).apply()

    /** 앱 잠금 (지문/얼굴/화면 잠금) */
    fun lockEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("lock", true)
    fun setLockEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("lock", v).apply()

    /** 스크린샷·최근 앱 미리보기 차단 */
    fun secureScreen(ctx: Context): Boolean = sp(ctx).getBoolean("secure_screen", true)
    fun setSecureScreen(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("secure_screen", v).apply()

    /** S-08: 인증번호·본인확인 알림은 저장하지 않음 */
    fun skipOtp(ctx: Context): Boolean = sp(ctx).getBoolean("skip_otp", true)
    fun setSkipOtp(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("skip_otp", v).apply()

    /** S-04: 접근성 앱의 화면 읽기·다른 앱 덮어쓰기 막기 */
    fun protectScreen(ctx: Context): Boolean = sp(ctx).getBoolean("protect_screen", true)
    fun setProtectScreen(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("protect_screen", v).apply()

    /** 다른 앱에 다녀온 뒤 다시 잠그기까지 (초) */
    fun relockSeconds(ctx: Context): Int = sp(ctx).getInt("relock", 30)
    fun setRelockSeconds(ctx: Context, v: Int) = sp(ctx).edit().putInt("relock", v).apply()

    /** 챙길 것 분석을 마지막으로 한 시각 */
    fun lastScan(ctx: Context): Long = sp(ctx).getLong("last_scan", 0L)
    fun setLastScan(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_scan", v).apply()

    /** 약속 추출 엔진 버전 (2 = 대화 흐름 기반) */
    fun planEngine(ctx: Context): Int = sp(ctx).getInt("plan_engine", 1)
    fun setPlanEngine(ctx: Context, v: Int) = sp(ctx).edit().putInt("plan_engine", v).apply()

    /** 첫 실행 개인정보 안내 동의 버전 (0 = 아직 동의 안 함) */
    fun consentVersion(ctx: Context): Int = sp(ctx).getInt("consent_v", 0)
    fun setConsentVersion(ctx: Context, v: Int) = sp(ctx).edit().putInt("consent_v", v).putLong("consent_at", System.currentTimeMillis()).apply()

    /** 물어보기: 기기 내장 AI(Gemini Nano)로 답변 문장 다듬기 */
    fun askAi(ctx: Context): Boolean = sp(ctx).getBoolean("ask_ai", true)
    fun setAskAi(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("ask_ai", v).apply()

    fun lastVersion(ctx: Context): Int = sp(ctx).getInt("last_version", 0)
    fun setLastVersion(ctx: Context, v: Int) = sp(ctx).edit().putInt("last_version", v).apply()

    /** 마감 알림 시각(오전, 시) */
    fun remindHour(ctx: Context): Int = sp(ctx).getInt("remind_hour", 9)

    /**
     * S-12: "모든 앱 알림 모으기"를 켜도 제외하는 인증·비밀번호 앱
     * (사용자가 목록에서 직접 켠 경우만 저장)
     */
    val SENSITIVE_PKGS = setOf(
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid",
        "com.samsung.android.samsungpass", "com.sktelecom.tauth", "com.kt.ktauth", "com.lguplus.smartotp",
        "com.google.android.gms",
    )

    fun needsVacuum(ctx: Context): Boolean = sp(ctx).getBoolean("needs_vacuum", false)
    fun setNeedsVacuum(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("needs_vacuum", v).commit()
}
