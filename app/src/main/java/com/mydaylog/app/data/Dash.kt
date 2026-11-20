package com.mydaylog.app.data

import android.content.Context
import android.provider.CallLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TodoKind { CALL, OPEN, NONE }

data class Todo(
    val key: String, val source: Source, val title: String, val sub: String,
    val kind: TodoKind, val target: String?, val done: Boolean = false,
    /** 카톡 답장: 열 대화방 (방 이름 또는 상대 이름) */
    val room: String? = null,
)

data class MoneyItem(
    val ts: Long, val label: String, val amount: Long, val deposit: Boolean,
    /** 원래 기록 (합계에서 빼기·다시 넣기용) */
    val entry: Entry? = null,
    /** 사용자가 합계에서 뺀 항목 */
    val excluded: Boolean = false,
)

data class Contact(val name: String, val source: Source, val count: Int, val hint: String)

data class Sub(val text: String, val warn: Boolean = false)

/** 알림 문구로 결제·입금·배송·광고 등을 구분하는 규칙 */
object Classify {
    private val amountRe = Regex("""(\d{1,3}(?:,\d{3})+|\d+)\s?원""")
    private val spendRe = Regex("승인|결제|사용|출금|체크")
    private val depositRe = Regex("""입금|받았|송금받|님이\s.*보냈""")
    /** 2.3: "잔여한도"는 정상 결제 → "한도 초과"만 제외 */
    private val cancelRe = Regex("""취소|승인\s?거절|거절|실패|한도\s?초과|잔액\s?부족""")
    private val deliveryRe = Regex("""배송\s?완료|배달\s?완료|도착했|문\s?앞|배송\s?시작|배송\s?출발|출발했""")
    private val deliveredRe = Regex("""완료|도착|문\s?앞""")
    private val adMark = Regex("""\(광고\)|\[광고\]|광고\)|수신\s?거부|무료\s?거부""")
    private val promo = Regex("""광고|이벤트|쿠폰|혜택|할인|특가|세일|프로모션""")
    /** 거래·배송·예약 같은 실제 알림이면 "할인" 같은 단어가 있어도 광고가 아님 */
    private val real = Regex("""승인|결제|출금|입금|이체|배송|예약\s?확정|주문\s?완료""")
    val requestRe = Regex("""검토|회신|요청|부탁|확인\s?바|답변|제출|마감""")
    private val serviceSenderRe = Regex("""^[0-9\-+ ()]+$""")
    /** 카톡 알림톡(카드사·은행·페이) */
    private val bizSender = Regex("""카드|은행|뱅크|페이|Pay|증권|캐피탈|보험|토스|저축""")
    private val issuerRe = Regex("""(KB국민|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨|NH농협|NH|농협|씨티|카카오뱅크|카카오페이|네이버페이|토스|케이뱅크|IBK|기업|SC제일|수협|우체국)""")
    private val notAmountBefore = Regex("""(누적|잔액|잔여|한도|포인트|할인|적립)\s?:?\s?$""")

    /** 결제 금액: 누적·잔여한도·할인 금액은 건너뜀 */
    fun amount(text: String): Long? = amountRe.findAll(text).firstOrNull { m ->
        val before = text.substring(maxOf(0, m.range.first - 8), m.range.first)
        !notAmountBefore.containsMatchIn(before)
    }?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()

    fun isAd(e: Entry): Boolean {
        if (e.source == Source.CAL || e.source == Source.CALL) return false
        val all = e.title + " " + e.text
        if (adMark.containsMatchIn(all)) return true
        return promo.containsMatchIn(all) && !real.containsMatchIn(all)
    }

    private fun moneySource(e: Entry) = e.source == Source.SMS || e.source == Source.APP ||
        (e.source == Source.KAKAO && bizSender.containsMatchIn(e.title + " " + (e.conv ?: "")))

    fun isDeposit(e: Entry) = moneySource(e) && !isAd(e) && amount(e.text) != null &&
        depositRe.containsMatchIn(e.text) && !cancelRe.containsMatchIn(e.text)

    fun isPayment(e: Entry) = moneySource(e) && !isAd(e) && amount(e.text) != null &&
        spendRe.containsMatchIn(e.text) && !cancelRe.containsMatchIn(e.text) && !isDeposit(e)

    fun isDelivery(e: Entry) = moneySource(e) && !isAd(e) && deliveryRe.containsMatchIn(e.title + " " + e.text)

    fun isDelivered(e: Entry) = isDelivery(e) && deliveredRe.containsMatchIn(e.text)

    fun isServiceSender(name: String) = serviceSenderRe.matches(name.trim())

    /** 결제한 카드사 (문자 본문·보낸 곳에서) */
    fun cardOf(e: Entry): String? = issuerRe.find(e.text)?.value?.let { if (it == "국민") "KB국민" else it }
        ?: issuerRe.find(e.title)?.value

    private val bracket = Regex("""\[([^\]]{1,15})\]""")

    private fun lastWord(text: String): String? {
        val tokens = text.replace('\n', ' ').split(' ', '|').map { it.trim() }.filter { it.isNotEmpty() }
        return tokens.lastOrNull { t ->
            t.any { it.isLetter() } && !t.contains("원") && !t.contains("누적") && !t.contains("잔액") && !t.contains("한도") &&
                !t.startsWith("[") && !t.contains("일시불") && !t.contains("승인") && !t.contains("체크") &&
                !t.endsWith("니다") && !t.contains("예정") && !t.contains("결제") && !t.contains("적용") &&
                !t.contains("할인") && !t.endsWith("님") && !t.contains("*")
        }
    }

    /** 가게 이름: 카드 승인은 마지막 단어(가게), 요금 안내는 [보낸 곳] */
    fun moneyLabel(e: Entry): String {
        if (e.source == Source.APP) return e.app
        if (spendRe.containsMatchIn(e.text) && cardOf(e) != null) lastWord(e.text)?.let { return it }
        bracket.findAll(e.title + " " + e.text).map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("발신") && !it.contains("광고") && it.any { c -> c.isLetter() } }
            ?.let { return it }
        return lastWord(e.text) ?: cardOf(e) ?: e.title
    }
}

data class Dash(
    val day: LocalDate,
    val entries: List<Entry>,
    val counts: Map<Source, Int>,
    val subs: Map<Source, Sub>,
    val upcoming: List<Entry>,
    val todos: List<Todo>,
    val spend: Long,
    val deposit: Long,
    val money: List<MoneyItem>,
    val hourly: IntArray,
    val recent: List<Entry>,
    val contacts: List<Contact>,
) {
    companion object {
        fun build(ctx: Context, day: LocalDate): Dash {
            val now = System.currentTimeMillis()
            val entries = Repo.entriesForDay(ctx, day)
            val upcoming = if (day == LocalDate.now()) Repo.upcoming(ctx, now).take(3) else emptyList()
            return from(day, entries, now, upcoming, Db.get(ctx).isDoneFn(day.toString()), MoneyExclude.excludedFn(ctx))
        }

        private fun hm(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime().let {
            "%02d:%02d".format(it.hour, it.minute)
        }

        /** 순수 계산부 (단위 테스트 대상) */
        fun from(
            day: LocalDate, entries: List<Entry>, now: Long,
            upcoming: List<Entry>, isDone: (String) -> Boolean,
            excluded: (Entry) -> Boolean = { false },
        ): Dash {
            val zone = ZoneId.systemDefault()
            val useful = entries.filterNot { Classify.isAd(it) }
            val counts = Source.entries.associateWith { s -> entries.count { it.source == s } }

            val pays = useful.filter { Classify.isPayment(it) }
            val deps = useful.filter { Classify.isDeposit(it) }
            val money = (pays.map { MoneyItem(it.ts, Classify.moneyLabel(it), Classify.amount(it.text)!!, false, it, excluded(it)) } +
                deps.map { MoneyItem(it.ts, Classify.moneyLabel(it), Classify.amount(it.text)!!, true, it, excluded(it)) })
                .sortedBy { it.ts }

            val todos = buildTodos(entries, useful).map { it.copy(done = isDone(it.key)) }
                .sortedBy { it.done }

            val missed = entries.count { it.isMissed }
            val rooms = entries.filter { it.source == Source.KAKAO }.map { it.convKey }.distinct().size
            val remaining = entries.count { it.source == Source.CAL && it.ts > now }
            val smsPays = pays.count { it.source == Source.SMS }
            val deliveries = useful.count { Classify.isDelivery(it) }
            val mailTodos = todos.count { it.source == Source.MAIL && !it.done }
            val subs = buildMap {
                if (remaining > 0) put(Source.CAL, Sub("남은 일정 $remaining"))
                if (smsPays > 0) put(Source.SMS, Sub("결제 $smsPays"))
                if (missed > 0) put(Source.CALL, Sub("부재중 $missed", warn = true))
                if (rooms > 0) put(Source.KAKAO, Sub("대화방 $rooms"))
                if (mailTodos > 0) put(Source.MAIL, Sub("회신 필요 $mailTodos"))
                if (deliveries > 0) put(Source.APP, Sub("배송 $deliveries"))
            }

            val hourly = IntArray(24)
            entries.forEach { hourly[Instant.ofEpochMilli(it.ts).atZone(zone).hour]++ }

            val recent = entries.filter { it.ts <= now && !Classify.isAd(it) }.takeLast(4).reversed()

            val people = entries.filter {
                it.source in setOf(Source.KAKAO, Source.CALL, Source.MAIL, Source.SMS) &&
                    !Classify.isAd(it) && !Classify.isPayment(it) && !Classify.isDeposit(it) &&
                    !Classify.isServiceSender(it.title) && it.title != "나"
            }
            val contacts = people.groupBy { it.source to (if (it.source == Source.KAKAO) it.convKey else it.title) }
                .map { (k, list) ->
                    val hint = when {
                        k.first == Source.CALL && list.any { it.isMissed } -> "전화 ${list.size} · 부재중 포함"
                        else -> "${k.first.label} ${list.size}"
                    }
                    Contact(k.second, k.first, list.size, hint)
                }
                .sortedByDescending { it.count }.take(4)

            return Dash(
                day, entries, counts, subs, upcoming, todos,
                // 합계: 사용자가 뺀 항목 제외
                money.filter { !it.deposit && !it.excluded }.sumOf { it.amount },
                money.filter { it.deposit && !it.excluded }.sumOf { it.amount },
                money, hourly, recent, contacts,
            )
        }

        /** 할 일 키는 제목 기반 → 홈과 요약 화면에서 같은 완료 상태를 공유 */
        private fun buildTodos(entries: List<Entry>, useful: List<Entry>): List<Todo> {
            val out = mutableListOf<Todo>()
            val calls = entries.filter { it.source == Source.CALL }
            calls.filter { it.isMissed }.forEach { m ->
                val handled = calls.any {
                    it.ts > m.ts && it.number == m.number &&
                        (it.callType == CallLog.Calls.OUTGOING_TYPE || it.callType == CallLog.Calls.INCOMING_TYPE)
                }
                if (!handled && m.number != null && out.none { it.target == m.number }) {
                    val t = "${m.title}에게 다시 전화하기"
                    out += Todo("todo:$t", Source.CALL, t, "${hm(m.ts)} 부재중", TodoKind.CALL, m.number)
                }
            }
            useful.filter { it.source == Source.MAIL && Classify.requestRe.containsMatchIn(it.title + " " + it.text) }
                .takeLast(3).forEach { e ->
                    val subject = e.text.lineSequence().firstOrNull().orEmpty().take(28)
                    val t = "${e.title} 메일 회신"
                    out += Todo("todo:$t", Source.MAIL, t, "$subject · ${hm(e.ts)}", TodoKind.OPEN, e.pkg)
                }
            useful.filter { it.source == Source.KAKAO && !it.isGroupChat && it.title != "나" }
                .groupBy { it.title }.values.map { it.last() }
                .sortedByDescending { it.ts }.take(2).forEach { e ->
                    val t = "${e.title}에게 카톡 답장"
                    out += Todo("todo:$t", Source.KAKAO, t, "\"${e.text.take(18)}\" · ${hm(e.ts)}", TodoKind.OPEN, e.pkg, room = e.convKey)
                }
            useful.lastOrNull { Classify.isDelivered(it) }?.let { e ->
                val t = "도착한 택배 챙기기"
                out += Todo("todo:$t", e.source, t, "${e.app} · ${hm(e.ts)}", TodoKind.NONE, null)
            }
            return out
        }
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
