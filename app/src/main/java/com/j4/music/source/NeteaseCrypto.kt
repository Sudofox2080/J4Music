package com.j4.music.source

import java.math.BigInteger

/**
 * 网易 eapi / weapi 的加密。
 *
 * 为什么要自己写：明文 api 接口虽然够用，但 weapi 是登录态校验（weapi/w/nuser/account/get）
 * 与部分老接口的唯一入口，eapi 则是逐字歌词 yrc（补充包）的来源。两套都在规格 1.6 给了公式，
 * 这里逐字翻译成 Kotlin，公式一个字符都没改。
 *
 * 所有函数都是纯函数（输入字符串、输出字符串），可以在 JVM 单测里直接对拍。
 */
object NeteaseCrypto {

    // ---- eapi：AES-128-ECB，单字段 params ----
    const val EAPI_KEY = "e82ckenh8dichen8"
    const val EAPI_SEP = "-36cd479b6b5-"
    const val EAPI_PREFIX = "nobody"
    const val EAPI_SUFFIX = "md5forencrypt"

    // ---- weapi：双 AES-128-CBC + RSA ----
    const val WEAPI_NONCE = "0CoJUm6Qyw8W8jud"
    const val WEAPI_IV = "0102030405060708"
    const val WEAPI_SECRET_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** 规格 1.6 里的公钥 modulus，256 位 hex，前导 00 已去掉 */
    const val WEAPI_RSA_MODULUS =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda9255744b1f9d"

    const val WEAPI_RSA_EXP = 65537L

    /**
     * eapi 的 params。
     *
     * @param apiPath 规格明确：digest 里用 `/api/xxx` 拼，但请求实际打到 `/eapi/xxx`。
     *                调用方传进来的必须是 `/api/...` 形态，这里不再改写，避免二次歧义。
     */
    fun eapiParams(apiPath: String, jsonBody: String): String {
        val digest = Md5Sha1.md5Hex(EAPI_PREFIX + apiPath + "use" + jsonBody + EAPI_SUFFIX)
        val plain = apiPath + EAPI_SEP + jsonBody + EAPI_SEP + digest
        val enc = Md5Sha1.aesEcbEncrypt(plain.toByteArray(Charsets.UTF_8), EAPI_KEY.toByteArray(Charsets.UTF_8))
        return Md5Sha1.toUpperHex(enc)
    }

    /** eapi 请求体（form 编码单字段） */
    fun eapiBody(apiPath: String, jsonBody: String): String =
        "params=" + java.net.URLEncoder.encode(eapiParams(apiPath, jsonBody), "UTF-8")

    /** weapi 的 secret：16 个随机 base62 字符，每次请求重新生成 */
    fun randomSecret(rnd: java.util.Random = java.util.Random()): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(WEAPI_SECRET_CHARS[rnd.nextInt(WEAPI_SECRET_CHARS.length)]) }
        return sb.toString()
    }

    /** weapi 的 params（两段 AES-128-CBC 的 base64） */
    fun weapiParams(jsonBody: String, secret: String = randomSecret()): String {
        val nonce = WEAPI_NONCE.toByteArray(Charsets.UTF_8)
        val iv = WEAPI_IV.toByteArray(Charsets.UTF_8)
        val step1 = Md5Sha1.aesCbcEncrypt(jsonBody.toByteArray(Charsets.UTF_8), nonce, iv)
        val step1b64 = Md5Sha1.base64(step1).toByteArray(Charsets.UTF_8)
        val step2 = Md5Sha1.aesCbcEncrypt(step1b64, secret.toByteArray(Charsets.UTF_8), iv)
        return Md5Sha1.base64(step2)
    }

    /**
     * weapi 的 encSecKey。
     *
     * 规格 1.6 原文：`HEX(RSA(reverse(secret), modulus, 0x010001))`，长度必须补齐 256。
     * 注意这个 RSA 不是标准 OAEP/PKCS1，而是「明文 = secret 字节串反转后的整数值，做 pow(e, n)」，
     * 这等价于把 secret 当指数做「解密」，所以必须用 BigInteger 裸算，不能走 Cipher。
     */
    fun weapiEncSecKey(secret: String): String {
        val reversed = secret.reversed().toByteArray(Charsets.UTF_8)
        val text = BigInteger(1, reversed)
        val mod = BigInteger(1, Md5Sha1.fromHex(WEAPI_RSA_MODULUS)!!)
        val enc = text.modPow(BigInteger.valueOf(WEAPI_RSA_EXP), mod)
        val hex = enc.toString(16)
        // 左侧补 0 到 256 位
        return if (hex.length >= 256) hex else "0".repeat(256 - hex.length) + hex
    }

    /** 拼 weapi 的 form body（两个字段都要 URL 编码） */
    fun weapiBody(jsonBody: String, secret: String = randomSecret()): String {
        val p = weapiParams(jsonBody, secret)
        val k = weapiEncSecKey(secret)
        return "params=" + java.net.URLEncoder.encode(p, "UTF-8") +
            "&encSecKey=" + java.net.URLEncoder.encode(k, "UTF-8")
    }

    /** 匿名设备 cookie 用的 32 位 hex（NMTID / _ntes_nuid） */
    fun randomHex32(): String = Md5Sha1.randomHexUpper(32).lowercase()
}
