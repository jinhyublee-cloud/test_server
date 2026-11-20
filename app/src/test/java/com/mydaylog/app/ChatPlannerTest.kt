package com.mydaylog.app

import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.ChatPlanner
import com.mydaylog.app.insight.KDate
import com.mydaylog.app.insight.Plan
import com.mydaylog.app.insight.epoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ChatPlannerTest {
    private var id = 0L
    private fun k(at: LocalDateTime, who: String, text: String, room: String? = null) =
        Entry("n${++id}", epoch(at), Source.KAKAO, who, text, room, "com.kakao.talk", "카카오톡")
    private val wed = LocalDateTime.of(2026, 11, 18, 15, 0) // 수요일
    private fun run(vararg m: Entry): List<Plan> = ChatPlanner.plans(m.toList()).also { ps ->
        ps.forEach { println("  → ${it.at} known=${it.dateKnown} time=${it.hasTime} place=${it.place} who=${it.who} title=${it.title} agreed=${it.agreed} cancelled=${it.cancelled}\n    ${it.quote.replace("\n", " / ")}") }
    }

    @Test fun dateFromEarlierMessage() {
        println("[날짜는 앞 메시지, 시간은 뒤 메시지]")
        val p = run(
            k(wed, "민지", "이번 주 토요일에 저녁 어때?"),
            k(wed.plusMinutes(3), "민지", "좋아 그럼 7시에 보자"),
            k(wed.plusMinutes(4), "민지", "강남역 11번 출구에서!"),
        ).single()
        assertEquals(LocalDateTime.of(2026, 11, 21, 19, 0), p.at)
        assertTrue(p.dateKnown)
        assertEquals("강남역 11번 출구", p.place)
        assertEquals("저녁 식사", p.title)
    }

    @Test fun groupMeetingNextTuesday() {
        println("[단톡: 다음 주 화요일 → 10시]")
        val p = run(
            k(wed.withHour(20), "김팀장", "다음 주 화요일에 고객사 미팅 잡겠습니다", "개발팀"),
            k(wed.withHour(20).plusMinutes(2), "이대리", "넵 알겠습니다", "개발팀"),
            k(wed.withHour(21), "김팀장", "회의는 10시로 하겠습니다", "개발팀"),
        ).single()
        assertEquals(LocalDateTime.of(2026, 11, 24, 10, 0), p.at)
        assertEquals("개발팀", p.who)
        assertEquals("고객사 미팅", p.title)
        assertTrue(p.agreed)
    }

    @Test fun timeOnlyIsNotToday() {
        println("[날짜 없이 시간만 → 대화한 날로 정하지 않음]")
        val p = run(k(wed, "준호", "7시에 보자")).single()
        assertFalse(p.dateKnown)
        assertEquals(LocalTime.of(19, 0), p.at.toLocalTime())
    }

    @Test fun sameDayWords() {
        println("[이따/퇴근하고 → 그날]")
        val p = run(k(wed, "준호", "이따 퇴근하고 7시에 보자")).single()
        assertTrue(p.dateKnown)
        assertEquals(LocalDateTime.of(2026, 11, 18, 19, 0), p.at)
    }

    @Test fun pastTalkIgnored() {
        println("[과거 이야기]")
        assertTrue(run(k(wed, "민지", "어제 7시에 만났던 카페 이름 뭐였지?")).isEmpty())
    }

    @Test fun timeChange() {
        println("[시간 변경]")
        val p = run(
            k(wed, "준호", "내일 7시에 저녁 먹자"),
            k(wed.plusMinutes(10), "준호", "아 미안 7시 말고 8시에 보는 걸로 하자"),
        ).single()
        assertEquals(LocalDateTime.of(2026, 11, 19, 20, 0), p.at)
    }

    @Test fun dateChange() {
        println("[날짜 변경]")
        val p = run(
            k(wed, "민지", "토요일에 영화 보자"),
            k(wed.plusMinutes(5), "민지", "토요일 말고 일요일 2시로 하자"),
        ).single()
        assertEquals(LocalDateTime.of(2026, 11, 22, 14, 0), p.at)
    }

    @Test fun sameWeekdayMeansNextWeek() {
        println("[토요일 밤에 말한 토요일 → 다음 주]")
        val sat = LocalDateTime.of(2026, 11, 21, 20, 0)
        val p = run(k(sat, "민지", "토요일 6시에 저녁 먹자")).single()
        assertEquals(LocalDateTime.of(2026, 11, 28, 18, 0), p.at)
    }

    @Test fun cancelled() {
        println("[취소 언급]")
        val p = run(
            k(wed, "엄마", "일요일 10시에 김장하러 와"),
            k(wed.plusHours(1), "엄마", "아 이번 주는 못 갈 것 같다 다음에 보자"),
        ).single()
        assertTrue(p.cancelled)
    }

    @Test fun separateThreadsAndPlans() {
        println("[같은 방, 다른 날 두 약속]")
        val ps = run(
            k(wed, "민지", "금요일 점심 같이 먹자"),
            k(wed.plusMinutes(1), "민지", "12시 반 판교역 2번 출구 앞에서"),
            k(wed.plusDays(1).withHour(21), "민지", "다음 주 수요일 저녁에 볼래?"),
            k(wed.plusDays(1).withHour(21).plusMinutes(2), "민지", "7시 어때"),
        )
        assertEquals(2, ps.size)
        assertEquals(LocalDateTime.of(2026, 11, 20, 12, 30), ps[0].at)
        assertEquals(LocalDateTime.of(2026, 11, 25, 19, 0), ps[1].at)
    }

    @Test fun noiseIgnored() {
        println("[약속 아닌 대화]")
        assertTrue(run(
            k(wed, "엄마", "저녁 먹었니?"),
            k(wed.plusMinutes(1), "친구", "그거 3시간 걸렸어"),
            k(wed.plusMinutes(2), "친구", "2시 방향에 있는 카페 알지?"),
        ).isEmpty())
    }

    @Test fun kdateBits() {
        val b = wed.toLocalDate()
        assertEquals(LocalTime.of(20, 0), KDate.findTime("7시 말고 8시로 바꾸자"))
        assertEquals(LocalDate.of(2026, 11, 21), KDate.findDate("이번 주말에 보자", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 28), KDate.findDate("다음 주 주말", b)?.first)
    }
}
