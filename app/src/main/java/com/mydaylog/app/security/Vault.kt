package com.mydaylog.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultException(message: String) : Exception(message)

/**
 * 봉투 암호화:
 * - 마스터 키: Android Keystore 안의 꺼낼 수 없는 AES-256 키. 가능하면 보안 칩(StrongBox)에 둠 (S-13)
 * - 데이터 키: 무작위 64바이트(암호화 32 + HMAC 32). 마스터 키로 감싸 앱 전용 저장소에 보관
 * - 감싼 키를 풀 수 없으면 절대 새 키로 덮어쓰지 않고 오류를 알림 (S-07)
 */
object Vault {
    private const val ALIAS_TEE = "daylog_master_v1"
    private const val ALIAS_SB = "daylog_master_sb"
    private const val PREFS = "vault"

    @Volatile private var encKey: SecretKey? = null
    @Volatile private var macKey: ByteArray? = null

    /** 이번 실행 중 복호화 실패 횟수 (보안 기록용) */
    val decryptFailures = AtomicInteger(0)

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generate(alias: String): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(true)); gen.generateKey()
        } catch (e: Exception) {
            // StrongBox가 없거나 실패 → 일반 보안 영역(TEE)
            gen.init(spec(false)); gen.generateKey()
        }
    }

    private fun wrap(master: SecretKey, raw: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, master)
        return Base64.encodeToString(c.iv + c.doFinal(raw), Base64.NO_WRAP)
    }

    private fun unwrap(master: SecretKey, blob64: String): ByteArray {
        val blob = Base64.decode(blob64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** 키를 준비. 실패하면 VaultException (기존 데이터는 건드리지 않음) */
    fun ensure(ctx: Context) {
        if (encKey != null) return
        synchronized(this) {
            if (encKey != null) return
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ks = keyStore()
            val stored = sp.getString("dek", null)
            val raw: ByteArray
            if (stored == null) {
                if (sp.getBoolean("initialized", false)) {
                    throw VaultException("저장된 보안 키 정보가 사라졌어요")
                }
                val m = generate(ALIAS_SB)
                raw = Crypto.randomBytes(64)
                val ok = sp.edit().putString("dek", wrap(m, raw)).putString("alias", ALIAS_SB)
                    .putBoolean("initialized", true).commit()
                if (!ok) throw VaultException("보안 키를 저장하지 못했어요")
            } else {
                val alias = sp.getString("alias", ALIAS_TEE)!!
                val m = ks.getKey(alias, null) as? SecretKey
                    ?: throw VaultException("기기 보안 영역에서 키를 찾을 수 없어요")
                raw = runCatching { unwrap(m, stored) }.getOrElse {
                    throw VaultException("보안 키로 데이터를 풀 수 없어요")
                }
                // 이전 버전 키(TEE)를 보안 칩 키로 옮김. 이전 감싼 키는 백업으로 남김
                if (alias != ALIAS_SB) runCatching {
                    val m2 = generate(ALIAS_SB)
                    sp.edit().putString("dek_prev", stored).putString("alias_prev", alias)
                        .putString("dek", wrap(m2, raw)).putString("alias", ALIAS_SB)
                        .putBoolean("initialized", true).commit()
                }
            }
            encKey = SecretKeySpec(raw.copyOfRange(0, 32), "AES")
            macKey = raw.copyOfRange(32, 64)
            raw.fill(0)
        }
    }

    /** 마스터 키가 보안 칩(StrongBox)에 있는지 */
    fun isStrongBox(ctx: Context): Boolean = runCatching {
        val alias = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("alias", ALIAS_TEE)!!
        val key = keyStore().getKey(alias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    }.getOrDefault(false)

    fun enc(ctx: Context, s: String, aad: String): String {
        ensure(ctx); return Crypto.encrypt(encKey!!, s, aad)
    }

    fun encOrNull(ctx: Context, s: String?, aad: String): String? = s?.let { enc(ctx, it, aad) }

    fun dec(ctx: Context, s: String, aad: String): String {
        ensure(ctx)
        return runCatching { Crypto.decrypt(encKey!!, s, aad) }.getOrElse {
            decryptFailures.incrementAndGet()
            "(읽을 수 없음)"
        }
    }

    fun decOrNull(ctx: Context, s: String?, aad: String): String? = s?.let { dec(ctx, it, aad) }

    /** 2.0 형식(AAD 없음) 값 풀기 — 마이그레이션 전용 */
    fun decLegacy(ctx: Context, s: String): String {
        ensure(ctx)
        return if (Crypto.isLegacy(s)) Crypto.decryptLegacy(encKey!!, s) else s
    }

    fun hmac(ctx: Context, s: String): String {
        ensure(ctx); return Crypto.hmac(macKey!!, s)
    }
}
