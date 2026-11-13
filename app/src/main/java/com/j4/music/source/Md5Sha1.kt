package com.j4.music.source

import java.security.MessageDigest
import android.util.Base64 as AndroidBase64

/**
 * 纯 JDK 的哈希/编码工具。QQ 的 zzcSign 只靠这几个函数，
 * 不引 BouncyCastle 之类的第三方加密库：APK 体积与混淆都省事，逻辑也更可控。
 *
 * 为什么用 android.util.Base64 而不是 java.util.Base64：minSdk 24 虽然刚好有
 * java.util.Base64，但那是 API 26 才真正稳定的（早期设备上的 NO_WRAP 行为有差异），
 * 用 Android 自带的更稳，而且这里本来就在 Android 模块里。
 */
object Md5Sha1 {

    private fun hex(bytes: ByteArray, upper: Boolean): String {
        val chars = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(chars[v ushr 4]).append(chars[v and 0x0F])
        }
        return sb.toString()
    }

    /** 小写 md5 hex（各种签名用） */
    fun md5Hex(input: String): String = hex(md5Bytes(input.toByteArray(Charsets.UTF_8)), false)

    fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    /** 小写 sha1 hex */
    fun sha1Hex(input: String): String = sha1Hex(input.toByteArray(Charsets.UTF_8))

    fun sha1Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes), false)

    /** QQ zzcSign 要的是 40 位大写 hex */
    fun sha1HexUpper(input: String): String =
        hex(MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8)), true)

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    fun hmacSha1Hex(key: String, data: String): String = hex(hmacSha1(key.toByteArray(), data.toByteArray()), false)

    /** 标准 base64（带 = 补齐） */
    fun base64(bytes: ByteArray): String = AndroidBase64.encodeToString(bytes, AndroidBase64.NO_WRAP)

    fun base64(input: String): String = base64(input.toByteArray(Charsets.UTF_8))

    /** URL-safe base64（- 和 _ 代替 + /），少数接口用这个形态 */
    fun base64Url(bytes: ByteArray): String = AndroidBase64.encodeToString(bytes, AndroidBase64.NO_WRAP or AndroidBase64.URL_SAFE)

    /** 解 base64；失败返回 null，调用方不必 try/catch（歌词 content 偶尔不是合法 base64） */
    fun unbase64(s: String?): ByteArray? {
        if (s.isNullOrEmpty()) return null
        val cleaned = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        return try {
            AndroidBase64.decode(cleaned, AndroidBase64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            try {
                AndroidBase64.decode(cleaned, AndroidBase64.DEFAULT or AndroidBase64.URL_SAFE)
            } catch (e2: IllegalArgumentException) {
                null
            }
        }
    }

    fun unbase64String(s: String?): String? = unbase64(s)?.toString(Charsets.UTF_8)

    /** AES-128-ECB/PKCS5Padding 加密，返回原始字节（eapi 的 params 再转大写 hex） */
    fun aesEcbEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        return c.doFinal(plain)
    }

    /** AES-128-CBC/PKCS7Padding 加密（weapi 两段都用它） */
    fun aesCbcEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return c.doFinal(plain)
    }

    fun toUpperHex(bytes: ByteArray): String = hex(bytes, true)

    fun toLowerHex(bytes: ByteArray): String = hex(bytes, false)

    /** 十六进制串转字节；奇数长度或非法字符返回 null */
    fun fromHex(s: String): ByteArray? {
        val t = s.trim()
        if (t.length % 2 != 0 || t.isEmpty()) return null
        val out = ByteArray(t.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(t[i * 2], 16)
            val lo = Character.digit(t[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 32 位大写随机 hex，生成 QQ 的 guid 用；持久化在 KV，保证一直是同一台设备 */
    fun randomHexUpper(len: Int = 32): String {
        val rnd = java.security.SecureRandom()
        val b = ByteArray((len + 1) / 2)
        rnd.nextBytes(b)
        return hex(b, true).take(len)
    }
}
