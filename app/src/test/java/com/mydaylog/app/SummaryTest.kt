package com.mydaylog.app

import com.mydaylog.app.data.Dash
import com.mydaylog.app.data.Entry
import com.mydaylog.app.data.Source
import com.mydaylog.app.security.Crypto
import com.mydaylog.app.summary.JournalParse
import com.mydaylog.app.summary.Masker
import com.mydaylog.app.summary.Summarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.spec.SecretKeySpec

class SummaryTest {
    private val day = LocalDate.of(2026, 11, 18)
    private var id = 0L
    private fun t(h: Int, m: Int) = day.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun n(h: Int, m: Int, s: Source, title: String, text: String, conv: String? = null, app: String = "") =
        Entry("n${++id}", t(h, m), s, title, text, conv, "pkg", app)

    private val entries = listOf(
        n(8, 12, Source.SMS, "15881688", "[Web발신] 신한카드(1234)승인 홍*동 4,500원 일시불 11/18 08:12 스타벅스강남"),
        Entry("call:1", t(9, 40), Source.CALL, "미소치과", "발신 전화 · 2분 14초", number = "0212345678", callType = 2, durationSec = 134),
        n(9, 0, Source.KAKAO, "김팀장", "오늘 3시 회의 2층 회의실이에요", "개발팀"),
        n(9, 1, Source.KAKAO, "이대리", "넵 알겠습니다", "개발팀"),
        n(9, 2, Source.KAKAO, "박사원", "확인했습니다", "개발팀"),
        Entry("cal:1:1", t(10, 0), Source.CAL, "주간 회의", "", endTs = t(11, 0), location = "온라인"),
        n(11, 5, Source.MAIL, "이수진", "제안서 검토 부탁드립니다\n금요일까지"),
        n(11, 30, Source.APP, "배송 완료", "주문하신 상품이 문 앞에 도착했습니다", app = "쿠팡"),
        n(12, 40, Source.SMS, "15881688", "[Web발신] 신한카드(1234)승인 홍*동 12,000원 일시불 김밥천국"),
        n(13, 5, Source.SMS, "1588", "(광고) 블랙프라이데이 최대 70% 할인 쿠폰"),
        Entry("call:2", t(14, 12), Source.CALL, "박과장", "부재중 전화", number = "01011112222", callType = 3),
        Entry("cal:2:1", t(15, 0), Source.CAL, "개발팀 회의", "", endTs = t(16, 0), location = "2층 회의실"),
        n(17, 3, Source.APP, "입금", "김민수님이 50,000원을 보냈어요", app = "토스"),
        n(19, 2, Source.KAKAO, "엄마", "저녁 먹었니?"),
    )

    @Test fun crypto() {
        val key = SecretKeySpec(Crypto.randomBytes(32), "AES")
        val a = Crypto.encrypt(key, "엄마: 저녁 먹었니?", "x")
        val b = Crypto.encrypt(key, "엄마: 저녁 먹었니?", "x")
        assertNotEquals(a, b) // 매번 다른 IV
        assertFalse(a.contains("엄마"))
        assertEquals("엄마: 저녁 먹었니?", Crypto.decrypt(key, a, "x"))
        val other = SecretKeySpec(Crypto.randomBytes(32), "AES")
        assertTrue(runCatching { Crypto.decrypt(other, a, "x") }.isFailure) // 다른 키로는 못 읽음
        val tampered = a.dropLast(4) + "AAAA"
        assertTrue(runCatching { Crypto.decrypt(key, tampered, "x") }.isFailure) // 변조 감지
        val mk = Crypto.randomBytes(32)
        assertEquals(Crypto.hmac(mk, "x"), Crypto.hmac(mk, "x"))
    }

    @Test fun masking() {
        val s = Masker.mask("[Web발신] 인증번호 [482913] 입력. 계좌 110-123-456789, 카드 1234-****-****-5678, 9001011234567, 2026-11-18 결제 12,000원")
        println(s)
        assertFalse(s.contains("482913")); assertFalse(s.contains("456789")); assertFalse(s.contains("1234567"))
        assertTrue(s.contains("2026-11-18")); assertTrue(s.contains("12,000원"))
    }

    @Test fun dashboardAndSummary() {
        val now = t(19, 10)
        val d = Dash.from(day, entries.sortedBy { it.ts }, now, emptyList(), isDone = { false })
        assertEquals(16_500L, d.spend)
        assertEquals(50_000L, d.deposit)
        assertEquals("부재중 1", d.subs[Source.CALL]?.text)
        println("todos: " + d.todos.map { it.title })
        assertTrue(d.todos.any { it.title == "박과장에게 다시 전화하기" })
        assertTrue(d.todos.any { it.title.startsWith("이수진") })
        assertTrue(d.todos.any { it.title == "엄마에게 카톡 답장" })
        println("contacts: " + d.contacts)
        val tomorrow = listOf(Entry("cal:3:1", t(9, 30) + 86_400_000, Source.CAL, "치과 정기검진", "", endTs = null, location = "미소치과"))
        val md = Summarizer.compose(d, tomorrow, isToday = true)
        println(md)
        assertFalse(md.contains("블랙프라이데이"))
        val sec = JournalParse.sections(md)
        assertTrue(sec.keys.containsAll(listOf("한 줄 요약", "오전", "오후", "저녁", "챙겨야 할 일", "내일 이후")))
        // 체크 상태가 반영되면 요약의 할 일에서 빠짐
        val d2 = Dash.from(day, entries.sortedBy { it.ts }, now, emptyList(), isDone = { it == "todo:박과장에게 다시 전화하기" })
        assertFalse(JournalParse.bullets(JournalParse.sections(Summarizer.compose(d2, emptyList(), true))["챙겨야 할 일"]).contains("박과장에게 다시 전화하기"))
    }

    @Test fun josa() {
        assertEquals("메시지 47개가", Summarizer.josa("메시지 47개", "이", "가"))
        assertEquals("메일 7통이", Summarizer.josa("메일 7통", "이", "가"))
        assertEquals("통화 4건(부재중 1)이", Summarizer.josa("통화 4건(부재중 1)", "이", "가"))
        assertEquals("엄마와", Summarizer.josa("엄마", "과", "와"))
        assertEquals("개발팀과", Summarizer.josa("개발팀", "과", "와"))
    }
}
