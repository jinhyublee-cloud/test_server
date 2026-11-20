package com.mydaylog.app

import com.mydaylog.app.ask.AskEngine
import com.mydaylog.app.ask.QueryParser
import com.mydaylog.app.ask.Topic
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.Deadline
import com.mydaylog.app.insight.Plan
import com.mydaylog.app.insight.epoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AskTest {
    private val today = LocalDate.of(2026, 11, 19) // 목요일
    private fun p(q: String) = QueryParser.parse(q, today)
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    // ---------------- 질문 해석 ----------------
    @Test fun periods() {
        assertEquals(today to today, p("오늘 어디서 결제했어?").let { it.from to it.to })
        assertEquals(d(11, 18), p("어제 통화 기록 보여줘").from)
        assertEquals(d(11, 17), p("그저께 카톡").from)
        assertEquals(d(11, 9) to d(11, 15), p("지난주 결제 내역").let { it.from to it.to })
        assertEquals(d(11, 13), p("지난주 금요일 김민수랑 통화했나?").from)
        assertEquals(d(11, 13), p("지난 주 금요일에 뭐 했어").from)
        assertEquals(d(11, 16) to d(11, 22), p("이번 주 일정 알려줘").let { it.from to it.to })
        assertEquals(d(11, 27), p("다음주 금요일 일정").from)
        assertEquals(d(11, 1) to today, p("이번 달 카드 결제 총 얼마야?").let { it.from to it.to })
        assertEquals(d(10, 1) to d(10, 31), p("지난달 통화").let { it.from to it.to })
        assertEquals(d(11, 14), p("11월 14일 결제").from)
        assertEquals(d(11, 3), p("11/3에 누구랑 통화했지").from)
        // 미래 날짜를 지난 일로 물으면 작년
        assertEquals(LocalDate.of(2025, 12, 24), p("12월 24일에 어디서 결제했어").from)
        // 일정은 앞으로
        assertEquals(d(12, 24), p("12월 24일 일정").from)
        assertEquals(d(11, 13) to today, p("최근 일주일 결제").let { it.from to it.to })
        assertEquals(d(11, 17) to today, p("최근 3일 카톡").let { it.from to it.to })
        assertEquals(d(11, 13), p("금요일에 어디서 밥 먹었지").from) // 지난 금요일
        assertEquals(d(11, 15), p("지난주 일요일 카톡").from)
        assertEquals(d(11, 21), p("토요일 약속 있어?").from)         // 다가오는 토요일
        assertEquals(d(11, 16), p("3일 전 문자").from)
    }

    @Test fun topics() {
        assertEquals(Topic.PAY, p("오늘 어디서 결제했어?").topic)
        assertEquals(Topic.PAY, p("어제 돈 얼마 썼어").topic)
        assertEquals(Topic.DEPOSIT, p("이번 달 입금 내역").topic)
        assertEquals(Topic.CALL, p("부재중 전화 있었어?").topic)
        assertEquals(Topic.PLAN, p("지난주 카톡 약속 있었어?").topic)
        assertEquals(Topic.KAKAO, p("어제 카톡 정리해줘").topic)
        assertEquals(Topic.DEADLINE, p("다가오는 마감 알려줘").topic)
        assertEquals(Topic.MAIL, p("오늘 메일 온 거").topic)
        assertEquals(Topic.ALL, p("어제 뭐 했어?").topic)
    }

    @Test fun keywords() {
        assertEquals(listOf("스타벅스"), p("스타벅스에서 언제 결제했어?").keywords)
        assertEquals(listOf("김민수"), p("지난주 금요일 김민수랑 통화했나?").keywords)
        assertEquals(listOf("엄마"), p("엄마한테 온 카톡 보여줘").keywords)
        assertEquals(listOf("누나"), p("누나랑 통화했어?").keywords)
        assertTrue(p("오늘 어디서 결제했어?").keywords.isEmpty())
        assertTrue(p("이번 주 일정 알려줘").keywords.isEmpty())
        assertTrue(p("어제 뭐 했어?").keywords.isEmpty())
        // 기간 없이 이름만 물으면 최근 90일에서 찾기
        val q = p("쿠팡 언제 샀지")
        assertEquals(listOf("쿠팡"), q.keywords)
        assertEquals(today.minusDays(89), q.from)
        assertFalse(q.dateSaid)
    }

    // ---------------- 답 계산 ----------------
    private var id = 0L
    private fun at(m: Int, day: Int, h: Int, mi: Int = 0) = epoch(LocalDateTime.of(2026, m, day, h, mi))
    private fun sms(ts: Long, t: String) = Entry("n${++id}", ts, Source.SMS, "15881688", t, null, "com.samsung.android.messaging", "삼성 메시지")
    private fun kakao(ts: Long, who: String, t: String, room: String? = null) = Entry("n${++id}", ts, Source.KAKAO, who, t, room, "com.kakao.talk", "카카오톡")
    private fun call(ts: Long, who: String, type: Int, dur: Long) = Entry("call:${++id}", ts, Source.CALL, who, "통화", app = "전화", number = "01012345678", callType = type, durationSec = dur)

    private val data by lazy {
        listOf(
            sms(at(11, 19, 8, 12), "[Web발신]\nKB국민카드1234승인\n홍*동님\n4,500원 일시불\n11/19 08:12\n스타벅스강남\n누적523,000원"),
            sms(at(11, 19, 12, 40), "[Web발신]\n신한카드(1234)승인\n홍*동\n12,000원(일시불)\n11/19 12:40\n김밥천국\n누적1,234,567원"),
            sms(at(11, 19, 13, 0), "(광고) 신한카드 이벤트 최대 50,000원 할인 무료거부 080"),
            sms(at(11, 18, 19, 5), "[Web발신]\n삼성1234승인 홍*동\n38,900원 일시불\n11/18 19:05 쿠팡\n누적1,020,300원"),
            sms(at(11, 14, 9, 0), "[Web발신]\nKB국민카드1234승인\n홍*동님\n5,200원 일시불\n11/14 09:00\n스타벅스역삼\n누적400,000원"),
            kakao(at(11, 19, 9, 0), "김민수", "점심 뭐 먹을래"),
            kakao(at(11, 19, 9, 5), "박지영", "회의 자료 올렸어요", "개발팀"),
            kakao(at(11, 19, 9, 6), "이수진", "확인했습니다", "개발팀"),
            call(at(11, 13, 18, 0), "김민수", 1, 125),
            call(at(11, 13, 20, 0), "엄마", 3, 0),
            call(at(11, 18, 10, 0), "김민수", 2, 30),
            Entry("cal:1", at(11, 20, 15), Source.CAL, "팀 회의", "3층", null, "com.samsung.android.calendar", "내 캘린더", endTs = at(11, 20, 16), location = "3층"),
        )
    }

    private fun ask(q: String, plans: List<Plan> = emptyList(), dls: List<Deadline> = emptyList()) =
        AskEngine.answer(QueryParser.parse(q, today), data, plans, dls, today)

    @Test fun payToday() {
        val f = ask("오늘 어디서 결제했어?")
        assertEquals("오늘 결제 2건, 총 16,500원이에요. (스타벅스강남, 김밥천국)", f.headline)
        assertEquals(2, f.evidence.size)
        assertTrue(f.evidence.first().title.startsWith("스타벅스강남")) // 시간순
        assertTrue(f.details.any { it.contains("KB국민") && it.contains("신한") })
    }

    @Test fun payKeywordNoDate() {
        val f = ask("스타벅스 언제 결제했어?")
        assertTrue(f.headline, f.headline.startsWith("최근 90일 ‘스타벅스’ 결제 2건, 총 9,700원"))
        assertEquals(at(11, 19, 8, 12), f.evidence.first().ts) // 여러 날 → 최신순
    }

    @Test fun payMonthTotalExcludesAds() {
        val f = ask("이번 달 카드 결제 총 얼마야?")
        assertTrue(f.headline, f.headline.contains("결제 4건, 총 60,600원"))
    }

    @Test fun callsLastFriday() {
        val f = ask("지난주 금요일 통화 기록")
        assertEquals("11월 13일(금) 통화 2건이에요 (수신 1 · 부재중 1).", f.headline)
        val g = ask("지난주 금요일 김민수랑 통화했나?")
        assertTrue(g.headline, g.headline.contains("‘김민수’ 통화 1건"))
    }

    @Test fun kakaoRooms() {
        val f = ask("오늘 카톡 정리해줘")
        assertEquals("오늘 카톡 3개, 대화방 2곳이에요.", f.headline)
        assertTrue(f.details.first().contains("개발팀 2"))
        // 근거를 누르면 그 대화방: 단톡은 방 이름, 1:1은 상대 이름
        assertEquals(setOf("김민수", "개발팀"), f.evidence.map { it.entry!!.convKey }.toSet())
    }

    @Test fun plansFromCalendarAndChat() {
        val plan = Plan(LocalDateTime.of(2026, 11, 21, 19, 0), true, "강남역", "김민수", "저녁", "토요일 7시 강남역", "카톡",
            at(11, 19, 9, 0), "com.kakao.talk", dateKnown = true)
        val dupe = plan.copy(at = LocalDateTime.of(2026, 11, 20, 15, 0), title = "팀 회의", who = "개발팀")
        val dl = Deadline(d(11, 22), "스타벅스 쿠폰", "카톡 · 스타벅스", "11/22까지", at(11, 18, 9), "com.kakao.talk")
        val f = ask("이번 주 일정 알려줘", listOf(plan, dupe), listOf(dl))
        assertEquals("이번 주(11/16~11/22) 일정 2개(캘린더 1 · 대화에서 찾은 약속 1), 마감 1개가 있어요.", f.headline)
        val chat = f.evidence.first { it.source == Source.KAKAO }
        assertEquals("김민수", chat.entry!!.convKey) // 누르면 김민수 대화방
    }

    @Test fun overviewAndEmpty() {
        val f = ask("오늘 뭐 했어?")
        assertTrue(f.headline, f.headline.startsWith("오늘 기록 5건이에요"))
        assertTrue(f.details.any { it.startsWith("결제 2건, 총 16,500원") })
        assertEquals("어제 메일 기록이 없어요.", ask("어제 메일 온 거").headline)
        assertFalse(ask("어제 메일 온 거").found)
    }

    @Test fun aiGuard() {
        val f = ask("오늘 어디서 결제했어?")
        assertTrue(AskEngine.aiAnswerOk("오늘은 스타벅스강남과 김밥천국에서 2건, 총 16,500원을 결제하셨어요.", f))
        // 기록에 없는 금액을 만들면 버림
        assertFalse(AskEngine.aiAnswerOk("오늘 총 18,000원을 결제하셨어요.", f))
        assertFalse(AskEngine.aiAnswerOk("기록에서 찾지 못했어요.", f))
        assertFalse(AskEngine.aiAnswerOk("", f))
        assertTrue(AskEngine.prompt(f).contains("[기록]"))
        assertTrue(f.context.length <= 2600)
    }

    // ---------------- 2.4.1: 일반 단어가 검색어로 잡혀 0건이 되던 문제 ----------------
    @Test fun genericWordsAreNotKeywords() {
        listOf(
            "오늘 결제한 이력 총합을 알려줘", "오늘 카드 사용 내역", "오늘 지출 내역 보여줘", "오늘 결제 금액 합계",
            "오늘 제일 비싼 결제", "오늘 결제한 가게 목록", "이번 주 결제 현황", "최근 결제 내역 확인",
            "오늘 사용한 카드 정보", "오늘 일정 뭐야", "어제 하루 일과 정리해줘", "오늘 들어온 알림 보여줘",
            "어제 가장 많이 쓴 곳은?",
        ).forEach { assertTrue("$it → ${p(it).keywords}", p(it).keywords.isEmpty()) }
        assertEquals(Topic.PAY, p("어제 가장 많이 쓴 곳은?").topic)
        assertEquals(listOf("이수진"), p("이수진 카톡 보여줘").keywords)
    }

    @Test fun userQuestionTotalToday() {
        val f = ask("오늘 결제한 이력 총합을 알려줘")
        assertEquals("오늘 결제 2건, 총 16,500원이에요. (스타벅스강남, 김밥천국)", f.headline)
        listOf("오늘 카드 사용 내역", "오늘 지출 내역 보여줘", "오늘 결제 금액 합계", "오늘 얼마 썼어?", "오늘 몇 번 결제했어",
            "오늘 결제한 가게 목록", "오늘 제일 비싼 결제", "오늘 사용한 카드 정보", "오늘 결제한 거 다 합치면?")
            .forEach { assertTrue("$it → ${ask(it).headline}", ask(it).headline.startsWith("오늘 결제 2건, 총 16,500원")) }
        assertTrue(ask("오늘 제일 비싼 결제").details.any { it.startsWith("가장 큰 결제: 김밥천국 12,000원") })
    }

    @Test fun unknownWordsAreDroppedNotZero() {
        // 기록에 없는 말이 섞여도 0건이 되지 않음
        val f = ask("오늘 이마트 결제")
        assertTrue(f.headline, f.headline.startsWith("오늘 결제 2건, 총 16,500원"))
        assertTrue(f.details.last().contains("‘이마트’ 이(가) 들어간 기록은 없어서 오늘 결제 전체로 찾았어요"))
        // 기간 없이 없는 말만 물으면 기본 기간(오늘)으로
        assertTrue(ask("뭔가뭔가 결제").headline.startsWith("오늘 결제 2건"))
        // 있는 말은 계속 걸러냄
        assertTrue(ask("스타벅스 언제 결제했어?").headline.contains("‘스타벅스’ 결제 2건"))
        assertEquals(1, ask("오늘 이수진 카톡").evidence.size)
        // 가장 많이 쓴 곳
        val y = ask("어제 가장 많이 쓴 곳은?")
        assertTrue(y.headline, y.headline.startsWith("어제 결제 1건, 총 38,900원"))
        assertTrue(ask("어제 하루 일과 정리해줘").headline.startsWith("어제 기록 "))
    }

    // ---------------- 합계에서 빼기 ----------------
    @Test fun excludedNotInTotals() {
        val kimbap = data.first { it.text.contains("김밥천국") }
        val out: (Entry) -> Boolean = { it.key == kimbap.key }
        val f = AskEngine.answer(QueryParser.parse("오늘 결제 총합", today), data, emptyList(), emptyList(), today, out)
        assertEquals("오늘 결제 1건, 총 4,500원이에요. (스타벅스강남)", f.headline)
        assertTrue(f.details.any { it.contains("합계에서 빼기로 한 결제 1건(12,000원)") })
        val dash = com.mydaylog.app.data.Dash.from(today, data.filter { com.mydaylog.app.insight.epoch(today.atStartOfDay()) <= it.ts && it.ts < com.mydaylog.app.insight.epoch(today.plusDays(1).atStartOfDay()) },
            System.currentTimeMillis(), emptyList(), { false }, out)
        assertEquals(4500L, dash.spend)
        assertTrue(dash.money.first { it.label == "김밥천국" }.excluded) // 목록에는 남고 표시만
        assertEquals("p|스타벅스강남", com.mydaylog.app.data.MoneyFilter.rule("스타벅스 강남", false))
    }

    // ---------------- 날짜별 지출·수입 달력 ----------------
    @Test fun moneyCalendar() {
        val pay = { e: Entry, amt: Long, out: Boolean -> com.mydaylog.app.data.MoneyPick(e, e.key, "x", amt, false, null, out, false) }
        val dep = { e: Entry, amt: Long -> com.mydaylog.app.data.MoneyPick(e, e.key, "월급", amt, true, null, false, false) }
        val a = sms(at(11, 19, 8), "a"); val b = sms(at(11, 19, 12), "b"); val c = sms(at(11, 19, 13), "c"); val e = sms(at(11, 18, 9), "e")
        val m = com.mydaylog.app.data.MoneyDays.group(listOf(pay(a, 4_500, false), pay(b, 12_000, true), dep(c, 50_000), pay(e, 38_900, false)))
        assertEquals(4_500L, m[d(11, 19)]!!.spend)          // 뺀 12,000원은 제외
        assertEquals(50_000L, m[d(11, 19)]!!.income)
        assertEquals(3, m[d(11, 19)]!!.items.size)          // 내역에는 남음
        assertEquals(1, m[d(11, 19)]!!.excludedCount)
        assertEquals(38_900L, m[d(11, 18)]!!.spend)
        val c2 = com.mydaylog.app.data.MoneyDays::compact
        assertEquals("4,500", c2(4_500)); assertEquals("1.7만", c2(16_500)); assertEquals("5만", c2(50_000))
        assertEquals("3.9만", c2(38_900)); assertEquals("123만", c2(1_234_000)); assertEquals("1.5억", c2(150_000_000))
    }
}
