package com.mydaylog.app.summary

/** 저장하기 전에 민감한 숫자 정보를 가립니다. (금액·날짜·시간은 그대로 둠) */
object Masker {
    private val otpWord = Regex(
        """(인증|본인\s?확인|OTP|일회용|verification|보안\s?코드|확인\s?코드|승인\s?번호|passcode|비밀\s?번호|code)""",
        RegexOption.IGNORE_CASE,
    )
    private val shortCode = Regex("""(?<![\d,.])\d{4,8}(?![\d,.]|\s?원)""")

    private val rules: List<Pair<Regex, String>> = listOf(
        // 주민등록번호
        Regex("""\b\d{6}-?[1-4]\d{6}\b""") to "[주민번호]",
        // 인증번호·OTP·비밀번호 + 숫자
        Regex("""(인증\s*번호|인증\s*코드|확인\s*코드|보안\s*코드|승인\s*번호|본인\s*확인|일회용\s*비밀번호|비밀\s*번호|OTP|verification code|passcode|code)([^\d]{0,12})\d{4,8}""", RegexOption.IGNORE_CASE) to "$1$2[인증번호]",
        // 괄호 안 4~8자리 숫자 ([482913])
        Regex("""\[(\d{4,8})\]""") to "[인증번호]",
        // 카드번호 (16자리, 일부 * 마스킹 포함)
        Regex("""\b\d{4}[- ]?[\d*]{4}[- ]?[\d*]{4}[- ]?\d{4}\b""") to "[카드번호]",
        // 계좌번호 형태 (예: 110-123-456789)
        Regex("""\b\d{3,6}-\d{2,6}-\d{4,8}(-\d{1,6})?\b""") to "[계좌번호]",
        // 하이픈 없는 긴 숫자열 (계좌·카드 등)
        Regex("""\b\d{10,16}\b""") to "[번호]",
    )

    fun mask(s: String): String = rules.fold(s) { acc, (re, rep) -> re.replace(acc, rep) }

    /** 인증번호 알림인지 (S-08: 이런 알림은 아예 저장하지 않음) */
    private val txn = Regex("""\d[\d,]*\s?원""")
    private val txnWord = Regex("""승인|결제|출금|입금|이체|취소""")

    fun isOtp(title: String, text: String): Boolean {
        val all = "$title $text"
        // 2.3: 카드 승인 문자의 "승인번호"는 인증번호가 아님 (금액 + 거래 단어가 있으면 결제 알림)
        if (txn.containsMatchIn(all) && txnWord.containsMatchIn(all)) return false
        return otpWord.containsMatchIn(all) && shortCode.containsMatchIn(all)
    }
}
