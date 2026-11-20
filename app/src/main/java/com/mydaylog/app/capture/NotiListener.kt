package com.mydaylog.app.capture

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mydaylog.app.Prefs
import com.mydaylog.app.data.Db
import com.mydaylog.app.summary.Masker
import java.util.concurrent.Executors

/**
 * 폰에 뜨는 알림을 받아 내부 DB에 저장합니다.
 * - 카톡처럼 MessagingStyle을 쓰는 앱은 메시지 단위로 저장
 * - 같은 알림이 여러 번 갱신돼도 hash로 중복 제거
 */
class NotiListener : NotificationListenerService() {

    private val io = Executors.newSingleThreadExecutor()
    private val labelCache = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        io.execute {
            runCatching { handle(sbn) }.onFailure { Log.w(TAG, "save failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 이미 떠 있는 알림들의 대화방 바로가기를 다시 기억 (앱 재시작 후) */
    override fun onListenerConnected() {
        runCatching {
            activeNotifications?.forEach { sbn ->
                val n = sbn.notification
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return@forEach
                val conv = style.conversationTitle?.toString()
                    ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                rememberRoom(sbn.packageName, conv, style, n)
            }
        }
    }

    /** 단톡은 방 이름, 1:1은 상대 이름으로 저장됨 (Entry.convKey와 같은 규칙) */
    private fun rememberRoom(pkg: String, conv: String?, style: NotificationCompat.MessagingStyle, n: Notification) {
        val rooms = if (!conv.isNullOrBlank()) listOf(conv)
        else style.messages.mapNotNull { it.person?.name?.toString() }.distinct() +
            listOfNotNull(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        ChatLinks.remember(pkg, rooms, n.contentIntent)
    }

    override fun onListenerDisconnected() {
        // 삼성 기기에서 리스너가 끊기는 경우 재연결 요청
        requestRebind(ComponentName(this, NotiListener::class.java))
    }

    private fun handle(sbn: StatusBarNotification) {
        // 개인정보 처리 안내에 동의하기 전에는 아무것도 저장하지 않음
        if (Prefs.consentVersion(this) < com.mydaylog.app.ui.CONSENT_VERSION) return
        val ctx = applicationContext
        val pkg = sbn.packageName
        val app = appLabel(pkg, sbn.notification)
        val db = Db.get(ctx)
        db.touchApp(pkg, app)

        val enabled = Prefs.enabledPkgs(ctx)
        val allowed = if (pkg in Prefs.SENSITIVE_PKGS) pkg in enabled   // S-12
        else Prefs.captureAll(ctx) || pkg in enabled
        if (!allowed) return
        val skipOtp = Prefs.skipOtp(ctx)

        val n = sbn.notification
        val ex = n.extras
        val cat = Prefs.categoryOf(pkg)

        // 1) 메신저형 알림 (카톡, 문자 등): 메시지마다 저장
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val conv = style.conversationTitle?.toString()
                ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            rememberRoom(pkg, conv, style, n)
            for (m in style.messages) {
                val raw = m.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) continue
                val sender = m.person?.name?.toString() ?: "나"
                val ts = if (m.timestamp > 0) m.timestamp else sbn.postTime
                if (skipOtp && Masker.isOtp(sender, raw)) continue
                // 인증번호·카드·계좌번호 등은 저장 전에 가림
                db.insertNoti(ts, pkg, app, cat, sender, Masker.mask(raw), conv, "$pkg|$conv|$sender|$raw|$ts")
            }
            return
        }

        // 2) 일반 알림 (메일, 은행, 택배 등)
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val small = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
        val body = when {
            !big.isNullOrEmpty() -> big
            !small.isNullOrEmpty() -> small
            !lines.isNullOrEmpty() -> lines
            else -> ""
        }
        if (title.isEmpty() && body.isEmpty()) return
        if (skipOtp && Masker.isOtp(title, body)) return
        val conv = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        db.insertNoti(
            sbn.postTime, pkg, app, cat, Masker.mask(title), Masker.mask(body), conv,
            "$pkg|${sbn.key}|$title|$body",
        )
    }

    private fun appLabel(pkg: String, n: Notification): String = labelCache.getOrPut(pkg) {
        n.extras.getString("android.substName")
            ?: runCatching {
                val pm = packageManager
                @Suppress("DEPRECATION") pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            ?: Prefs.DEFAULT_APPS[pkg] ?: pkg.substringAfterLast('.')
    }

    companion object { private const val TAG = "NotiListener" }
}
