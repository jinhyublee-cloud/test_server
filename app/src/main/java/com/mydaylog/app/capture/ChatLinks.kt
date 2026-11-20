package com.mydaylog.app.capture

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.mydaylog.app.data.Entry
import com.mydaylog.app.ui.openApp

/**
 * 카톡·문자 알림을 눌렀을 때 열리는 "그 대화방" 바로가기를 기억합니다.
 *
 * - 알림을 보낸 앱(카카오톡 등)이 만든 PendingIntent를 메모리에만 보관 (디스크·DB에 저장하지 않음)
 * - 폰을 다시 켜거나 앱이 종료되면 사라짐 → 그때는 카카오톡 앱 자체를 엶
 * - 카카오톡이 바로가기를 취소했으면(오래된 알림) 역시 앱 자체를 엶
 */
object ChatLinks {
    private const val MAX = 300

    private val links = object : LinkedHashMap<String, PendingIntent>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > MAX
    }

    private fun key(pkg: String, room: String) = "$pkg|${room.trim()}"

    /** 알림 수집 시 호출: 방 이름(단톡) 또는 상대 이름(1:1)으로 기억 */
    fun remember(pkg: String, rooms: Collection<String>, pi: PendingIntent?) {
        if (pi == null) return
        synchronized(links) { rooms.filter { it.isNotBlank() }.forEach { links[key(pkg, it)] = pi } }
    }

    fun has(e: Entry): Boolean = e.pkg != null && synchronized(links) { links.containsKey(key(e.pkg, e.convKey)) }

    /** 대화방 열기. 성공하면 true */
    fun open(ctx: Context, e: Entry): Boolean {
        val pkg = e.pkg ?: return false
        val pi = synchronized(links) { links[key(pkg, e.convKey)] }
        if (pi != null) {
            val ok = runCatching {
                // 이 앱이 화면에 떠 있을 때 사용자가 누른 것 → 대화방 화면 열기를 허용
                val opts = ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }
                }.toBundle()
                pi.send(ctx, 0, null, null, null, null, opts)
            }.isSuccess
            if (ok) return true
            synchronized(links) { links.remove(key(pkg, e.convKey)) }
        }
        Toast.makeText(ctx, "대화방 바로가기가 없어 ${e.app.ifBlank { "앱" }}을 열었어요", Toast.LENGTH_SHORT).show()
        openApp(ctx, pkg)
        return false
    }

    fun clear() = synchronized(links) { links.clear() }
}
