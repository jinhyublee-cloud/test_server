package com.mydaylog.app.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 암복호화와 HMAC-SHA256 (순수 JVM 코드, 단위 테스트 가능)
 * v2: 부가 인증 데이터(AAD)로 "어느 표·열·행의 값인지"를 묶어서,
 *     암호문을 다른 자리로 옮기면 복호화가 실패함 (S-06)
 */
object Crypto {
    private const val V1 = "v1:"
    private const val V2 = "v2:"
    private const val IV_LEN = 12
    private val rnd = SecureRandom()

    fun encrypt(key: SecretKey, plain: String, aad: String): String {
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return V2 + Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(key: SecretKey, data: String, aad: String): String {
        require(data.startsWith(V2)) { "not v2" }
        val raw = Base64.getDecoder().decode(data.substring(V2.length))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, IV_LEN))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }

    /** 2.0에서 저장된 값(AAD 없음). 마이그레이션에서만 사용 */
    fun decryptLegacy(key: SecretKey, data: String): String {
        require(data.startsWith(V1)) { "not v1" }
        val raw = Base64.getDecoder().decode(data.substring(V1.length))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, IV_LEN))
        return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }

    fun isLegacy(data: String) = data.startsWith(V1)

    fun hmac(key: ByteArray, s: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
}
