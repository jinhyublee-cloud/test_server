package com.mydaylog.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

enum class Source(val label: String) {
    CAL("일정"), SMS("문자"), CALL("전화"), KAKAO("카톡"), MAIL("메일"), APP("앱")
}

/** 타임라인의 한 줄: 캘린더 일정, 통화 기록, 저장된 알림을 같은 모양으로 합친 것 */
data class Entry(
    val key: String,
    val ts: Long,
    val source: Source,
    val title: String,
    val text: String,
    val conv: String? = null,
    val pkg: String? = null,
    val app: String = "",
    val endTs: Long? = null,
    val location: String? = null,
    val allDay: Boolean = false,
    val number: String? = null,
    val callType: Int = 0,
    val durationSec: Long = 0,
    val refId: Long = 0,
) {
    val convKey: String get() = conv?.takeIf { it.isNotBlank() } ?: title
    val isMissed: Boolean get() = source == Source.CALL && callType == CallLog.Calls.MISSED_TYPE
    val isGroupChat: Boolean get() = source == Source.KAKAO && !conv.isNullOrBlank() && conv != title
}

/** 캘린더·통화 기록은 저장하지 않고 화면을 열 때마다 폰에서 직접 읽습니다. */
object Repo {
    val DIALER_PKGS = setOf(
        "com.samsung.android.dialer", "com.google.android.dialer",
        "com.android.dialer", "com.samsung.android.incallui", "com.android.server.telecom",
    )
    val CAL_PKGS = setOf("com.samsung.android.calendar", "com.google.android.calendar")
    const val CAL_APP_PKG = "com.samsung.android.calendar"

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun dayRange(day: LocalDate): Pair<Long, Long> =
        day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun hasCalendar(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasCallLog(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun entriesForDay(ctx: Context, day: LocalDate): List<Entry> {
        val (s, e) = dayRange(day)
        val cal = hasCalendar(ctx)
        val calls = hasCallLog(ctx)
        val list = ArrayList<Entry>()
        Db.get(ctx).notisForDay(day.toString()).forEach { n ->
            // 캘린더·통화 기록을 직접 읽으면 해당 앱의 알림은 중복이므로 제외
            if (calls && n.pkg in DIALER_PKGS) return@forEach
            if (cal && n.pkg in CAL_PKGS) return@forEach
            list += entryOf(n)
        }
        if (cal) list += runCatching { calendar(ctx, s, e, day) }.getOrDefault(emptyList())
        if (calls) list += runCatching { callLog(ctx, s, e) }.getOrDefault(emptyList())
        return list.sortedBy { it.ts }
    }

    /** 물어보기용: 여러 날의 기록 (from~to, 양 끝 포함) */
    fun entriesBetween(ctx: Context, from: LocalDate, to: LocalDate): List<Entry> {
        val s = dayRange(from).first
        val e = dayRange(to).second
        val cal = hasCalendar(ctx)
        val calls = hasCallLog(ctx)
        val list = ArrayList<Entry>()
        Db.get(ctx).notisBetween(s, e).forEach { n ->
            if (calls && n.pkg in DIALER_PKGS) return@forEach
            if (cal && n.pkg in CAL_PKGS) return@forEach
            list += entryOf(n)
        }
        if (cal) list += runCatching { calendar(ctx, s, e, null) }.getOrDefault(emptyList())
            .filter { !it.allDay || it.ts in s until e }
        if (calls) list += runCatching { callLog(ctx, s, e) }.getOrDefault(emptyList())
        return list.sortedBy { it.ts }
    }

    fun entryOf(n: NotiItem): Entry {
        val src = when (n.category) {
            "문자" -> Source.SMS; "카톡" -> Source.KAKAO; "메일" -> Source.MAIL; else -> Source.APP
        }
        return Entry("n${n.id}", n.ts, src, n.title, n.text, n.conv, n.pkg, n.app)
    }

    fun calendarDay(ctx: Context, day: LocalDate): List<Entry> {
        if (!hasCalendar(ctx)) return emptyList()
        val (s, e) = dayRange(day)
        return runCatching { calendar(ctx, s, e, day) }.getOrDefault(emptyList())
    }

    /** 지금부터 내일 밤까지 남은 일정 */
    fun upcoming(ctx: Context, now: Long): List<Entry> {
        if (!hasCalendar(ctx)) return emptyList()
        val today = LocalDate.now()
        val end = dayRange(today.plusDays(1)).second
        return runCatching {
            calendar(ctx, now, end, null).filter { (it.endTs ?: it.ts) > now || it.allDay }
                .filter { !it.allDay || !Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate().isBefore(today) }
        }.getOrDefault(emptyList())
    }

    private fun calendar(ctx: Context, start: Long, end: Long, onlyDay: LocalDate?): List<Entry> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start); ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = ArrayList<Entry>()
        ctx.contentResolver.query(
            uri, proj, "${CalendarContract.Instances.VISIBLE}=1", null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                var begin = c.getLong(1)
                var finish = c.getLong(2)
                val allDay = c.getInt(5) == 1
                if (allDay) {
                    // 종일 일정은 UTC 자정 기준 → 현지 날짜로 변환
                    val d = Instant.ofEpochMilli(begin).atOffset(ZoneOffset.UTC).toLocalDate()
                    if (onlyDay != null && d != onlyDay) continue
                    begin = d.atStartOfDay(zone).toInstant().toEpochMilli()
                    finish = begin
                }
                val title = c.getString(3)?.takeIf { it.isNotBlank() } ?: "(제목 없음)"
                val loc = c.getString(4)?.takeIf { it.isNotBlank() }
                out += Entry(
                    key = "cal:$id:${c.getLong(1)}", ts = begin, source = Source.CAL,
                    title = title, text = loc ?: "", pkg = CAL_APP_PKG,
                    app = c.getString(6) ?: "캘린더", endTs = finish, location = loc,
                    allDay = allDay, refId = id,
                )
            }
        }
        return out
    }

    private fun callLog(ctx: Context, start: Long, end: Long): List<Entry> {
        val proj = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        val out = ArrayList<Entry>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, proj,
            "${CallLog.Calls.DATE}>=? AND ${CallLog.Calls.DATE}<?",
            arrayOf(start.toString(), end.toString()), "${CallLog.Calls.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1)
                val name = c.getString(2)?.takeIf { it.isNotBlank() }
                    ?: number?.let { PhoneNumberUtils.formatNumber(it, "KR") ?: it } ?: "알 수 없는 번호"
                val type = c.getInt(3)
                val dur = c.getLong(5)
                val label = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "수신 전화"
                    CallLog.Calls.OUTGOING_TYPE -> "발신 전화"
                    CallLog.Calls.MISSED_TYPE -> "부재중 전화"
                    CallLog.Calls.REJECTED_TYPE -> "거절한 전화"
                    CallLog.Calls.BLOCKED_TYPE -> "차단된 전화"
                    CallLog.Calls.VOICEMAIL_TYPE -> "음성사서함"
                    else -> "전화"
                }
                val text = if (dur > 0) "$label · ${durText(dur)}" else label
                out += Entry(
                    key = "call:${c.getLong(0)}", ts = c.getLong(4), source = Source.CALL,
                    title = name, text = text, app = "전화", number = number,
                    callType = type, durationSec = dur, refId = c.getLong(0),
                )
            }
        }
        return out
    }

    fun durText(sec: Long): String = when {
        sec >= 3600 -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
        sec >= 60 -> "${sec / 60}분 ${sec % 60}초"
        else -> "${sec}초"
    }
}
