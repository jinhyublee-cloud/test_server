package com.mydaylog.app.security

import android.content.Context
import com.mydaylog.app.data.Db

/** S-10: 보안 기록 (암호화 저장, 90일 보관). 실패해도 앱 동작에는 영향 없음 */
object SecLog {
    fun add(ctx: Context, text: String) {
        runCatching { Db.get(ctx).log(text) }
    }
}
