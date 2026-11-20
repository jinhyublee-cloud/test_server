package com.mydaylog.app

import com.mydaylog.app.data.Classify
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.ChatPlanner
import com.mydaylog.app.insight.DeadlineFinder
import com.mydaylog.app.insight.RecurringFinder
import com.mydaylog.app.insight.epoch
import com.mydaylog.app.summary.Masker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class Fix23Test {
    private var id = 0L
    private val now = LocalDateTime.of(2026, 11, 19, 9, 30) // 목요일
    private fun sms(t: String) = Entry("n${++id}", epoch(now), Source.SMS, "15881688", t, null, "com.samsung.android.messaging", "삼성 메시지")
    private fun kakao(who: String, t: String, room: String? = null, at: LocalDateTime = now) =
        Entry("n${++id}", epoch(at), Source.KAKAO, who, t, room, "com.kakao.talk", "카카오톡")

    private val cards = listOf(
        Triple("[Web발신]\n신한카드(1234)승인\n홍*동\n12,000원(일시불)\n11/19 09:12\n김밥천국\n누적1,234,567원", 12000L, "김밥천국"),
        Triple("[Web발신]\nKB국민카드1234승인\n홍*동님\n4,500원 일시불\n11/19 08:12\n스타벅스강남\n누적523,000원", 4500L, "스타벅스강남"),
        Triple("[Web발신]\n삼성1234승인 홍*동\n38,900원 일시불\n11/19 08:40 쿠팡\n누적1,020,300원", 38900L, "쿠팡"),
        Triple("[Web발신]\n현대카드 M 승인\n홍*동\n15,000원 일시불\n11/19 07:55\nGS25 역삼점\n누적 812,000원", 15000L, "역삼점"),
        Triple("[Web발신]\n하나(1234)승인 홍*동 9,800원 일시불 11/19 09:01 올리브영 잔여한도 3,210,000원", 9800L, "올리브영"),
        Triple("[Web발신]\n우리(1234)승인\n홍*동님\n6,000원 체크\n11/19 08:30\n이디야커피", 6000L, "이디야커피"),
        Triple("[Web발신]\nNH카드1*2*승인\n홍*동\n21,000원 일시불\n11/19 08:20\n홈플러스\n총누적 433,000원", 21000L, "홈플러스"),
        Triple("[Web발신]\n롯데카드 승인 홍*동 3,300원(일시불) 11/19 08:05 CU 할인 330원 적용", 3300L, "CU"),
        Triple("[Web발신]\nBC카드(1234) 승인 홍*동 52,000원 일시불 11/19 08:00 SK주유소 승인번호 12345678", 52000L, "SK주유소"),
    )

    @Test fun cardPayments() {
        cards.forEach { (t, amt, shop) ->
            val e = sms(t)
            println("  ${Classify.cardOf(e)} | ${Classify.moneyLabel(e)} | ${Classify.amount(t)}")
            assertTrue(t, Classify.isPayment(e))
            assertFalse("인증문자로 오인: $t", Masker.isOtp(e.title, t))
            assertEquals(t, amt, Classify.amount(t))
            assertTrue("$shop vs ${Classify.moneyLabel(e)}", Classify.moneyLabel(e).contains(shop) || shop.contains(Classify.moneyLabel(e)))
            assertTrue(Classify.cardOf(e) != null)
        }
        // 카톡 알림톡 결제
        val talk = kakao("신한카드", "[신한카드] 홍*동님 12,000원 승인\n일시불 | 11/19 09:12\n김밥천국")
        assertTrue(Classify.isPayment(talk))
        assertEquals("김밥천국", RecurringFinder.paymentOf(talk)!!.merchant)
        // 여전히 제외: 승인 취소, 한도 초과, 광고, 진짜 인증번호
        assertFalse(Classify.isPayment(sms("[Web발신] 신한카드(1234) 승인취소 12,000원 김밥천국")))
        assertFalse(Classify.isPayment(sms("[Web발신] 신한카드 한도초과로 승인거절 12,000원")))
        assertFalse(Classify.isPayment(sms("(광고) 신한카드 결제 시 최대 50% 할인 10,000원 쿠폰")))
        assertTrue(Masker.isOtp("1588", "[Web발신] 본인확인 인증번호 [482913]를 입력하세요"))
        // 친구가 보낸 일반 카톡은 결제 아님
        assertFalse(Classify.isPayment(kakao("민지", "나 오늘 12,000원 결제했어")))
    }

    @Test fun kakaoDeadlines() {
        fun dl(t: String) = DeadlineFinder.find(kakao("김팀장", t, "개발팀"))?.due
        assertEquals(LocalDate.of(2026, 11, 30), dl("보고서는 11.30(월)까지 제출 부탁드립니다"))
        assertEquals(LocalDate.of(2026, 11, 27), dl("자료는 ~11/27까지 회신 주세요"))
        assertEquals(LocalDate.of(2026, 11, 20), dl("이번 주 금요일까지 자료 정리해 주세요"))
        assertEquals(LocalDate.of(2026, 11, 27), dl("다음 주까지 견적서 보내주세요"))
        assertEquals(LocalDate.of(2026, 11, 30), dl("정산은 월말까지 마감입니다"))
        assertEquals(LocalDate.of(2026, 11, 19), dl("금일까지 취합 부탁드립니다"))
        // 소수점은 날짜가 아님
        assertNull(DeadlineFinder.find(kakao("민지", "평점 4.5 받았대 대박")))
        // '~까지'는 약속(캘린더 일정 후보)으로 잡히지 않고 마감으로만
        assertTrue(ChatPlanner.plans(listOf(kakao("김팀장", "11/30까지 송년회 참석 여부 알려주세요", "개발팀"))).isEmpty())
    }

    @Test fun eventNotices() {
        fun plan(t: String, at: LocalDateTime = now.withHour(10)) =
            ChatPlanner.plans(listOf(kakao("총무팀", t, "전사공지", at))).singleOrNull()
        val a = plan("[공지] 2026 시무식 안내\n일시: 오늘(11/19) 오후 4시\n장소: 본관 3층 대강당\n전 직원 참석 바랍니다")!!
        assertEquals(LocalDateTime.of(2026, 11, 19, 16, 0), a.at)
        assertEquals("본관 3층 대강당", a.place)
        assertEquals("시무식", a.title)
        val b = plan("금일 16시 시무식 진행 예정입니다. 대강당으로 와주세요")!!
        assertEquals(LocalDateTime.of(2026, 11, 19, 16, 0), b.at)
        assertEquals("대강당", b.place)
        // 시간 없는 오늘 행사: 오후에 와도 놓치지 않음
        val c = plan("오늘 시무식 있습니다. 전원 참석 부탁드려요", now.withHour(15))!!
        assertTrue(c.dateKnown); assertFalse(c.hasTime)
        assertEquals(LocalDate.of(2026, 11, 19), c.at.toLocalDate())
        val d = plan("시무식은 11월 19일(목) 오후 4시, 본관 대강당에서 진행합니다")!!
        assertEquals(LocalDateTime.of(2026, 11, 19, 16, 0), d.at)
        val e = plan("다음 주 금요일 저녁 7시 개발팀 회식 있습니다")!!
        assertEquals(LocalDateTime.of(2026, 11, 27, 19, 0), e.at)
        assertEquals("개발팀 회식", e.title)
    }
}
