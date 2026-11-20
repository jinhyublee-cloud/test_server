package com.mydaylog.app

import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import com.mydaylog.app.insight.DeadlineFinder
import com.mydaylog.app.insight.KDate
import com.mydaylog.app.insight.MailCat
import com.mydaylog.app.insight.MailDigest
import com.mydaylog.app.insight.Payment
import com.mydaylog.app.insight.PlanFinder
import com.mydaylog.app.insight.RecurringFinder
import com.mydaylog.app.insight.epoch
import com.mydaylog.app.security.Crypto
import com.mydaylog.app.summary.Masker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.crypto.spec.SecretKeySpec

class InsightTest {
    private val now = LocalDateTime.of(2026, 11, 18, 15, 42) // 수요일
    private var id = 0L
    private fun e(src: Source, title: String, text: String, conv: String? = null, app: String = "", at: LocalDateTime = now) =
        Entry("n${++id}", epoch(at), src, title, text, conv, "pkg", app)

    @Test fun dates() {
        val b = now.toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 21), KDate.findDate("토요일 6시 반에 봐", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 24), KDate.findDate("다음 주 화요일 오전 10시", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 19), KDate.findDate("유효기간 2026.11.19까지", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 30), KDate.findDate("11월 30일까지 사용", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 20), KDate.findDate("금요일까지 부탁드립니다", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 25), KDate.findDate("25일 자동이체 예정", b)?.first)
        assertEquals(LocalDate.of(2026, 11, 19), KDate.findDate("내일 보자", b)?.first)
        assertEquals(LocalDate.of(2027, 1, 5), KDate.findDate("1/5까지", b)?.first)
        assertNull(KDate.findDate("3일 동안 고생했어", b))
        assertEquals(LocalTime.of(18, 30), KDate.findTime("6시 반에 강남역"))
        assertEquals(LocalTime.of(10, 0), KDate.findTime("오전 10시에"))
        assertEquals(LocalTime.of(19, 0), KDate.findTime("저녁 7시"))
        assertEquals(LocalTime.of(13, 0), KDate.findTime("점심 1시"))
        assertEquals(LocalTime.of(14, 30), KDate.findTime("14:30 회의"))
        assertNull(KDate.findTime("2시간 걸려"))
    }

    @Test fun plans() {
        val p = com.mydaylog.app.insight.ChatPlanner.plans(listOf(e(Source.KAKAO, "민지", "토요일 6시 반에 강남역 11번 출구에서 봐! 저녁 먹자"))).single()
        assertEquals(LocalDateTime.of(2026, 11, 21, 18, 30), p.at)
        assertEquals("강남역 11번 출구", p.place)
        assertEquals("민지", p.who)
        assertEquals("저녁 식사", p.title)
        val g = com.mydaylog.app.insight.ChatPlanner.plans(listOf(e(Source.KAKAO, "이대리", "다음 주 화요일 오전 10시에 고객사 미팅 잡혔습니다", conv = "개발팀"))).single()
        assertEquals(LocalDateTime.of(2026, 11, 24, 10, 0), g.at)
        assertEquals("개발팀", g.who)
        assertEquals("고객사 미팅", g.title)
        assertNull(g.place)
        assertTrue(com.mydaylog.app.insight.ChatPlanner.plans(listOf(e(Source.KAKAO, "엄마", "저녁 먹었니?"))).isEmpty())
        // 문자는 날짜가 분명한 한 통만
        val sms = PlanFinder.find(e(Source.SMS, "미소치과", "[미소치과] 11월 23일 오후 2시 진료 예약이 확정되었습니다"))!!
        assertEquals(LocalDateTime.of(2026, 11, 23, 14, 0), sms.at)
        assertNull(PlanFinder.find(e(Source.SMS, "미소치과", "오후 2시 진료 예약 확정")))
    }

    @Test fun deadlines() {
        val c = DeadlineFinder.find(e(Source.KAKAO, "카카오톡 선물하기", "스타벅스 아메리카노 T 쿠폰이 도착했어요. 유효기간 2026.11.19까지"))!!
        println(c)
        assertEquals(LocalDate.of(2026, 11, 19), c.due)
        assertTrue(c.title.contains("쿠폰"))
        // 천 단위 쉼표에서 금액이 잘리지 않아야 한다 ("000원 할인 쿠폰" 버그)
        val cp = DeadlineFinder.find(e(Source.APP, "쿠폰 만료 예정", "보유하신 5,000원 할인 쿠폰 유효기간이 11.30(월)까지입니다. 기간 내 사용해 주세요."))!!
        assertEquals("5,000원 할인 쿠폰", cp.title)
        assertEquals(LocalDate.of(2026, 11, 30), cp.due)
        val m = DeadlineFinder.find(e(Source.MAIL, "이수진", "제안서 검토 부탁드립니다\n초안 첨부합니다. 금요일까지 의견 부탁드려요."))!!
        assertEquals(LocalDate.of(2026, 11, 20), m.due)
        assertNull(DeadlineFinder.find(e(Source.SMS, "1588", "(광고) 블랙프라이데이 11/30까지 최대 70% 할인 쿠폰")))
    }

    @Test fun mail() {
        val items = MailDigest.digest(listOf(
            e(Source.MAIL, "이수진", "제안서 검토 부탁드립니다\n신규 제안서 초안을 공유드립니다. 금요일까지 의견 회신 부탁드립니다."),
            e(Source.MAIL, "KT", "11월 휴대폰 요금 안내\n이용요금 52,800원이 25일 자동이체 예정입니다."),
            e(Source.MAIL, "무신사", "주말 특가 세일 시작\n최대 80% 할인"),
            e(Source.MAIL, "네이버", "새로운 기기에서 로그인되었습니다\nWindows PC에서 로그인. 본인이 아니면 비밀번호를 변경하세요."),
        ))
        items.forEach { println("${it.cat} ${it.sender} | ${it.subject} | ${it.bullets} | ${it.due}") }
        assertEquals(MailCat.REPLY, items.first { it.sender == "이수진" }.cat)
        assertEquals(LocalDate.of(2026, 11, 20), items.first { it.sender == "이수진" }.due)
        assertEquals(MailCat.BILL, items.first { it.sender == "KT" }.cat)
        assertEquals(MailCat.NEWS, items.first { it.sender == "무신사" }.cat)
        assertEquals(MailCat.NOTICE, items.first { it.sender == "네이버" }.cat)
    }

    @Test fun recurring() {
        fun pay(y: Int, m: Int, d: Int, who: String, amt: Long) = Payment(epoch(LocalDateTime.of(y, m, d, 9, 0)), who, amt)
        val pays = listOf(
            pay(2026, 8, 20, "넷플릭스", 17000), pay(2026, 9, 20, "넷플릭스", 17000), pay(2026, 10, 20, "넷플릭스", 17000),
            pay(2026, 9, 9, "유튜브프리미엄", 14900), pay(2026, 10, 9, "유튜브프리미엄", 14900), pay(2026, 11, 9, "유튜브프리미엄", 14900),
            pay(2026, 10, 12, "밀리의서재", 9900), pay(2026, 11, 12, "밀리의서재", 9900),
            pay(2026, 11, 3, "김밥천국", 12000), pay(2026, 10, 15, "김밥천국", 8000),
            Payment(epoch(LocalDateTime.of(2026, 11, 25, 9, 0)), "KT", 52800, scheduled = true),
        )
        val r = RecurringFinder.detect(pays, LocalDate.of(2026, 11, 18)) { null }
        r.forEach { println(it) }
        assertTrue(r.any { it.merchant == "넷플릭스" && it.confirmed && !it.paidThisMonth && it.next == LocalDate.of(2026, 11, 20) })
        assertTrue(r.any { it.merchant == "유튜브프리미엄" && it.paidThisMonth })
        assertTrue(r.any { it.merchant == "밀리의서재" && !it.confirmed })
        assertTrue(r.any { it.merchant == "KT" && it.day == 25 })
        assertFalse(r.any { it.merchant == "김밥천국" })
        val r2 = RecurringFinder.detect(pays, LocalDate.of(2026, 11, 18)) { if (it == "rc|밀리의서재") "dismissed" else null }
        assertFalse(r2.any { it.merchant == "밀리의서재" })
    }

    @Test fun aadBindsLocation() {
        val k = SecretKeySpec(Crypto.randomBytes(32), "AES")
        val c = Crypto.encrypt(k, "엄마: 저녁 먹었니?", "n:abc:text")
        assertEquals("엄마: 저녁 먹었니?", Crypto.decrypt(k, c, "n:abc:text"))
        assertTrue(runCatching { Crypto.decrypt(k, c, "n:xyz:text") }.isFailure)   // 다른 행으로 옮기면 실패
        assertTrue(runCatching { Crypto.decrypt(k, c, "n:abc:title") }.isFailure)  // 다른 열로 옮기면 실패
    }

    @Test fun otp() {
        assertTrue(Masker.isOtp("15881234", "[Web발신] 본인확인 인증번호 [482913]를 입력하세요"))
        assertTrue(Masker.isOtp("Google", "G-582913 is your Google verification code"))
        assertFalse(Masker.isOtp("15881688", "[Web발신] 신한카드(1234)승인 홍*동 12,000원 일시불"))
        assertFalse(Masker.isOtp("민지", "토요일 6시 반에 보자"))
        println(Masker.mask("본인확인 [482913] 입력"))
    }
}
