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
        // Android 13 이상은 새 키, Android 12는 키 교체 이전의 원래 키로 확인됨 (둘 다 우리 키)
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
        /** 서명 키 인증서 SHA-256: 2.1부터 쓰는 새 키, 키 교체 이전 원래 키 (Android 12에서 보이는 값) */
        val EXPECTED_SIGNERS = setOf(
            "20b05c54484da1aabe5fd9cdedb1aab420b164fe34ec899d50954009a3ef4e72",
            "f9a18c0ff29af5fc1c7aae3f057e414728ce74b8effbc09a881f7df7d9327ee9",
        )
    }
}
