package com.mydaylog.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.mydaylog.app.data.Db
import com.mydaylog.app.security.SecLog
import com.mydaylog.app.security.Vault
import com.mydaylog.app.work.DailyWorker
import com.mydaylog.app.work.ReminderWorker
import java.security.MessageDigest
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        listOf(CH_JOURNAL to "요약 생성 알림", CH_REMIND to "마감·정기 결제 알림").forEach { (id, name) ->
            nm.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        DailyWorker.schedule(this, replace = false)
        ReminderWorker.schedule(this)
        Executors.newSingleThreadExecutor().execute {
            runCatching {
                Vault.ensure(this)
                Db.get(this).apply { readableDatabase; vacuumIfNeeded() }
                checkUpdateAndSignature()
            }.onFailure { SecLog.add(this, "보안 키 오류: ${it.message}") }
        }
    }

    /** S-19: 설치된 앱의 서명이 우리 키인지 확인하고, 버전이 바뀌면 기록 */
    private fun checkUpdateAndSignature() {
        // Android 12(API 31·32)에서도 동작하도록 예전 호출 방식 사용
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = info.signingInfo?.apkContentsSigners.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val ok = certs.any { it in EXPECTED_SIGNERS }
        val ver = info.longVersionCode.toInt()
        if (Prefs.lastVersion(this) != ver) {
            SecLog.add(this, "${info.versionName} 설치 · " + if (ok) "서명 확인됨" else "서명이 예상과 달라요")
            Prefs.setLastVersion(this, ver)
        } else if (!ok) {
            SecLog.add(this, "서명이 예상과 달라요")
        }
    }

    companion object {
        const val CH_JOURNAL = "journal"
        const val CH_REMIND = "remind"
        /**
         * 서명 키 인증서 SHA-256 (2.7-fold6부터 이 폰 전용으로 새로 만든 키, 키 교체 없음)
         * 다른 키로 서명된 APK가 설치되면 보안 기록에 "서명이 예상과 달라요"가 남음
         */
        val EXPECTED_SIGNERS = setOf(
            "87c4d061eb5357dee46d9358da278f8d37b05d6261d916966fdc3d375feb57c5",
        )
    }
}
