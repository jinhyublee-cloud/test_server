package com.mydaylog.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mydaylog.app.Prefs
import com.mydaylog.app.security.Vault
import java.time.Instant
import java.time.ZoneId

data class NotiItem(
    val id: Long, val ts: Long, val day: String, val pkg: String, val app: String,
    val category: String, val title: String, val text: String, val conv: String?,
)

data class Journal(val day: String, val content: String, val source: String, val createdAt: Long)

data class SeenApp(val pkg: String, val app: String, val lastSeen: Long, val count: Int)

data class InsightRow(val key: String, val kind: String, val status: String, val data: String, val due: Long, val updated: Long)

data class PaymentRow(val ts: Long, val data: String, val hash: String = "")

data class LogRow(val ts: Long, val text: String)

/**
 * 폰 내부 저장소(daylog.db)에만 저장합니다. (v3)
 * - 알림의 앱·제목·내용·대화방, 요약, 챙길 것, 결제 내역, 보안 기록은 모두 AES-GCM 암호화
 * - 각 암호문은 "표:행:열"에 묶여 있어 다른 자리로 옮기면 풀리지 않음 (AAD)
 * - 키·식별자는 HMAC 값만 저장
 */
class Db private constructor(private val ctx: Context) : SQLiteOpenHelper(ctx, "daylog.db", null, 3) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }

        fun dayOf(ts: Long): String =
            Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

        const val MAX_TEXT = 4000      // S-11 본문 길이 상한
        const val MAX_PER_DAY = 3000   // S-11 하루 저장 상한
    }

    private fun aN(hash: String, col: String) = "n:$hash:$col"
    private fun aS(key: String, col: String) = "s:$key:$col"

    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL, day TEXT NOT NULL,
                pkg TEXT NOT NULL, app TEXT NOT NULL, category TEXT NOT NULL,
                title TEXT NOT NULL, text TEXT NOT NULL, conv TEXT,
                hash TEXT NOT NULL UNIQUE)"""
        )
        db.execSQL("CREATE INDEX idx_noti_day ON notifications(day, ts)")
        db.execSQL("CREATE TABLE journals (day TEXT PRIMARY KEY, content TEXT NOT NULL, source TEXT NOT NULL, created_at INTEGER NOT NULL)")
        createSeen(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS todo_done (key TEXT PRIMARY KEY, day TEXT NOT NULL)")
        createV3(db)
    }

    private fun createSeen(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE seen_apps (
                key TEXT PRIMARY KEY, pkg TEXT NOT NULL, app TEXT NOT NULL,
                last_seen INTEGER NOT NULL, count INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS insights (
                key TEXT PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL,
                data TEXT NOT NULL, due INTEGER NOT NULL, updated INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ins_kind ON insights(kind, due)")
        db.execSQL("CREATE TABLE IF NOT EXISTS payments (hash TEXT PRIMARY KEY, ts INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pay_ts ON payments(ts)")
        db.execSQL("CREATE TABLE IF NOT EXISTS seclog (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, nonce TEXT NOT NULL, data TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS todo_done (key TEXT PRIMARY KEY, day TEXT NOT NULL)")
        if (oldVersion < 3) {
            createV3(db)
            migrateToV3(db, fromPlain = oldVersion < 2)
            Prefs.setNeedsVacuum(ctx, true)
        }
    }

    /** v1(평문)·v2(AAD 없는 암호문) → v3(앱·패키지까지 암호화 + AAD) */
    private fun migrateToV3(db: SQLiteDatabase, fromPlain: Boolean) {
        fun old(s: String?): String? = s?.let { if (fromPlain) it else Vault.decLegacy(ctx, it) }
        db.rawQuery("SELECT id, pkg, app, title, text, conv, hash FROM notifications", null).use { c ->
            while (c.moveToNext()) {
                val hash = if (fromPlain) Vault.hmac(ctx, c.getString(6)) else c.getString(6)
                val cv = ContentValues().apply {
                    put("pkg", Vault.enc(ctx, c.getString(1), aN(hash, "pkg")))
                    put("app", Vault.enc(ctx, c.getString(2), aN(hash, "app")))
                    put("title", Vault.enc(ctx, old(c.getString(3))!!, aN(hash, "title")))
                    put("text", Vault.enc(ctx, old(c.getString(4))!!.take(MAX_TEXT), aN(hash, "text")))
                    put("conv", if (c.isNull(5)) null else Vault.enc(ctx, old(c.getString(5))!!, aN(hash, "conv")))
                    put("hash", hash)
                }
                db.update("notifications", cv, "id=?", arrayOf(c.getLong(0).toString()))
            }
        }
        db.rawQuery("SELECT day, content FROM journals", null).use { c ->
            while (c.moveToNext()) {
                val day = c.getString(0)
                val cv = ContentValues().apply { put("content", Vault.enc(ctx, old(c.getString(1))!!, "j:$day")) }
                db.update("journals", cv, "day=?", arrayOf(day))
            }
        }
        val apps = ArrayList<Triple<String, String, Pair<Long, Int>>>()
        db.rawQuery("SELECT pkg, app, last_seen, count FROM seen_apps", null).use { c ->
            while (c.moveToNext()) apps += Triple(c.getString(0), c.getString(1), c.getLong(2) to c.getInt(3))
        }
        db.execSQL("DROP TABLE seen_apps")
        createSeen(db)
        apps.forEach { (pkg, app, meta) -> insertSeen(db, pkg, app, meta.first, meta.second) }
        if (fromPlain) db.execSQL("DELETE FROM todo_done")
    }

    private fun insertSeen(db: SQLiteDatabase, pkg: String, app: String, lastSeen: Long, count: Int) {
        val key = Vault.hmac(ctx, "pkg:$pkg")
        val cv = ContentValues().apply {
            put("key", key); put("pkg", Vault.enc(ctx, pkg, aS(key, "pkg"))); put("app", Vault.enc(ctx, app, aS(key, "app")))
            put("last_seen", lastSeen); put("count", count)
        }
        db.insertWithOnConflict("seen_apps", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun vacuumIfNeeded() {
        if (!Prefs.needsVacuum(ctx)) return
        writableDatabase.execSQL("VACUUM")
        Prefs.setNeedsVacuum(ctx, false)
    }

    // ---------- 알림 ----------
    fun insertNoti(
        ts: Long, pkg: String, app: String, category: String,
        title: String, text: String, conv: String?, dedupe: String,
    ): Boolean {
        val day = dayOf(ts)
        val count = DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM notifications WHERE day=?", arrayOf(day))
        if (count >= MAX_PER_DAY) return false
        val hash = Vault.hmac(ctx, dedupe)
        val cv = ContentValues().apply {
            put("ts", ts); put("day", day); put("category", category); put("hash", hash)
            put("pkg", Vault.enc(ctx, pkg, aN(hash, "pkg")))
            put("app", Vault.enc(ctx, app, aN(hash, "app")))
            put("title", Vault.enc(ctx, title.take(200), aN(hash, "title")))
            put("text", Vault.enc(ctx, text.take(MAX_TEXT), aN(hash, "text")))
            put("conv", Vault.encOrNull(ctx, conv?.take(200), aN(hash, "conv")))
        }
        return writableDatabase.insertWithOnConflict("notifications", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun readNotis(where: String, args: Array<String>): List<NotiItem> =
        readableDatabase.rawQuery(
            "SELECT id, ts, day, pkg, app, category, title, text, conv, hash FROM notifications WHERE $where ORDER BY ts", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val h = c.getString(9)
                    add(
                        NotiItem(
                            c.getLong(0), c.getLong(1), c.getString(2),
                            Vault.dec(ctx, c.getString(3), aN(h, "pkg")),
                            Vault.dec(ctx, c.getString(4), aN(h, "app")),
                            c.getString(5),
                            Vault.dec(ctx, c.getString(6), aN(h, "title")),
                            Vault.dec(ctx, c.getString(7), aN(h, "text")),
                            if (c.isNull(8)) null else Vault.dec(ctx, c.getString(8), aN(h, "conv")),
                        )
                    )
                }
            }
        }

    fun notisForDay(day: String) = readNotis("day=?", arrayOf(day))

    fun notisSince(ts: Long) = readNotis("ts>=?", arrayOf(ts.toString()))

    fun notisBetween(from: Long, to: Long) = readNotis("ts>=? AND ts<?", arrayOf(from.toString(), to.toString()))

    // ---------- 요약 ----------
    fun saveJournal(day: String, content: String, source: String) {
        val cv = ContentValues().apply {
            put("day", day); put("content", Vault.enc(ctx, content, "j:$day")); put("source", source)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("journals", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun journal(day: String): Journal? =
        readableDatabase.rawQuery("SELECT day, content, source, created_at FROM journals WHERE day=?", arrayOf(day)).use { c ->
            if (c.moveToFirst()) Journal(c.getString(0), Vault.dec(ctx, c.getString(1), "j:$day"), c.getString(2), c.getLong(3)) else null
        }

    // ---------- 할 일 체크 ----------
    fun doneKeys(day: String): Set<String> =
        readableDatabase.rawQuery("SELECT key FROM todo_done WHERE day=?", arrayOf(day)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun isDoneFn(day: String): (String) -> Boolean {
        val set = doneKeys(day)
        return { key -> Vault.hmac(ctx, key) in set }
    }

    fun setDone(key: String, day: String, done: Boolean) {
        val h = Vault.hmac(ctx, key)
        if (done) writableDatabase.insertWithOnConflict(
            "todo_done", null, ContentValues().apply { put("key", h); put("day", day) }, SQLiteDatabase.CONFLICT_REPLACE
        ) else writableDatabase.delete("todo_done", "key=?", arrayOf(h))
    }

    // ---------- 알림 보낸 앱 ----------
    fun touchApp(pkg: String, app: String) {
        val key = Vault.hmac(ctx, "pkg:$pkg")
        val n = writableDatabase.compileStatement("UPDATE seen_apps SET last_seen=?, count=count+1 WHERE key=?").apply {
            bindLong(1, System.currentTimeMillis()); bindString(2, key)
        }.executeUpdateDelete()
        if (n == 0) insertSeen(writableDatabase, pkg, app, System.currentTimeMillis(), 1)
    }

    fun seenApps(): List<SeenApp> =
        readableDatabase.rawQuery("SELECT key, pkg, app, last_seen, count FROM seen_apps ORDER BY count DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val k = c.getString(0)
                    add(SeenApp(Vault.dec(ctx, c.getString(1), aS(k, "pkg")), Vault.dec(ctx, c.getString(2), aS(k, "app")), c.getLong(3), c.getInt(4)))
                }
            }
        }

    // ---------- 챙길 것 (약속·마감·정기 결제 판단) ----------
    /** 처음 보는 항목만 추가. 이미 있으면 사용자가 정한 상태를 유지 */
    fun addInsightIfAbsent(plainKey: String, kind: String, json: String, due: Long): Boolean {
        val key = Vault.hmac(ctx, plainKey)
        val cv = ContentValues().apply {
            put("key", key); put("kind", kind); put("status", "new")
            put("data", Vault.enc(ctx, json, "i:$key")); put("due", due); put("updated", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("insights", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun keyOf(plainKey: String): String = Vault.hmac(ctx, plainKey)

    /** 없으면 추가, 사용자가 아직 손대지 않은(new) 항목이면 최신 대화 내용으로 갱신 */
    fun upsertInsight(plainKey: String, kind: String, json: String, due: Long) {
        val key = keyOf(plainKey)
        val status = readableDatabase.rawQuery("SELECT status FROM insights WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        when (status) {
            null -> {
                val cv = ContentValues().apply {
                    put("key", key); put("kind", kind); put("status", "new")
                    put("data", Vault.enc(ctx, json, "i:$key")); put("due", due); put("updated", System.currentTimeMillis())
                }
                writableDatabase.insertWithOnConflict("insights", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            "new" -> updateInsightData(key, json, due)
            else -> Unit // 사용자가 수정·추가·삭제한 항목은 건드리지 않음
        }
    }

    fun deleteInsight(key: String) {
        writableDatabase.delete("insights", "key=?", arrayOf(key))
    }

    fun deleteInsightsByStatus(kind: String, status: String) {
        writableDatabase.delete("insights", "kind=? AND status=?", arrayOf(kind, status))
    }

    /** 정기 결제 판단(상태 + 사용자가 고친 이름·금액·날짜) */
    fun decisionRow(plainKey: String): Pair<String, String>? {
        val key = keyOf(plainKey)
        return readableDatabase.rawQuery("SELECT status, data FROM insights WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) to Vault.dec(ctx, c.getString(1), "i:$key") else null
        }
    }

    fun setInsightStatus(key: String, status: String) {
        val cv = ContentValues().apply { put("status", status); put("updated", System.currentTimeMillis()) }
        writableDatabase.update("insights", cv, "key=?", arrayOf(key))
    }

    /** 키를 HMAC으로 바꿔 상태 저장 (정기 결제 판단 등) */
    fun setDecision(plainKey: String, kind: String, status: String, json: String = "{}") {
        val key = Vault.hmac(ctx, plainKey)
        val cv = ContentValues().apply {
            put("key", key); put("kind", kind); put("status", status)
            put("data", Vault.enc(ctx, json, "i:$key")); put("due", 0L); put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("insights", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun decision(plainKey: String): String? {
        val key = Vault.hmac(ctx, plainKey)
        return readableDatabase.rawQuery("SELECT status FROM insights WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun updateInsightData(key: String, json: String, due: Long) {
        val cv = ContentValues().apply {
            put("data", Vault.enc(ctx, json, "i:$key")); put("due", due); put("updated", System.currentTimeMillis())
        }
        writableDatabase.update("insights", cv, "key=?", arrayOf(key))
    }

    fun insights(kind: String, dueFrom: Long = Long.MIN_VALUE): List<InsightRow> =
        readableDatabase.rawQuery(
            "SELECT key, kind, status, data, due, updated FROM insights WHERE kind=? AND due>=? ORDER BY due",
            arrayOf(kind, dueFrom.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val k = c.getString(0)
                    add(InsightRow(k, c.getString(1), c.getString(2), Vault.dec(ctx, c.getString(3), "i:$k"), c.getLong(4), c.getLong(5)))
                }
            }
        }

    // ---------- 결제 내역 (정기 결제 판단용, 400일 보관) ----------
    fun addPayment(plainKey: String, ts: Long, json: String) {
        val h = Vault.hmac(ctx, plainKey)
        val cv = ContentValues().apply { put("hash", h); put("ts", ts); put("data", Vault.enc(ctx, json, "p:$h")) }
        writableDatabase.insertWithOnConflict("payments", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun paymentsSince(ts: Long): List<PaymentRow> =
        readableDatabase.rawQuery("SELECT hash, ts, data FROM payments WHERE ts>=? ORDER BY ts", arrayOf(ts.toString())).use { c ->
            buildList { while (c.moveToNext()) add(PaymentRow(c.getLong(1), Vault.dec(ctx, c.getString(2), "p:${c.getString(0)}"), c.getString(0))) }
        }

    // ---------- 보안 기록 (S-10) ----------
    fun log(text: String) {
        val ts = System.currentTimeMillis()
        val nonce = Vault.hmac(ctx, "log:$ts:${System.nanoTime()}").take(16)
        val cv = ContentValues().apply { put("ts", ts); put("nonce", nonce); put("data", Vault.enc(ctx, text, "l:$ts:$nonce")) }
        writableDatabase.insert("seclog", null, cv)
    }

    fun logs(limit: Int = 50): List<LogRow> =
        readableDatabase.rawQuery("SELECT ts, nonce, data FROM seclog ORDER BY ts DESC LIMIT $limit", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val ts = c.getLong(0)
                    add(LogRow(ts, Vault.dec(ctx, c.getString(2), "l:$ts:${c.getString(1)}")))
                }
            }
        }

    // ---------- 보관 기간 ----------
    fun cleanup(notiBeforeDay: String?) {
        val now = System.currentTimeMillis()
        if (notiBeforeDay != null) {
            writableDatabase.delete("notifications", "day < ?", arrayOf(notiBeforeDay))
            writableDatabase.delete("todo_done", "day < ?", arrayOf(notiBeforeDay))
        }
        writableDatabase.delete("seclog", "ts < ?", arrayOf((now - 90L * 86_400_000).toString()))
        writableDatabase.delete("payments", "ts < ?", arrayOf((now - 400L * 86_400_000).toString()))
        writableDatabase.delete("insights", "due > 0 AND due < ? AND kind != 'rc'", arrayOf((now - 30L * 86_400_000).toString()))
    }

    fun deleteAll() {
        writableDatabase.apply {
            listOf("notifications", "journals", "seen_apps", "todo_done", "insights", "payments", "seclog").forEach { delete(it, null, null) }
            execSQL("VACUUM")
        }
    }
}
