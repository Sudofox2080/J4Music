package com.j4.music.source

/**
 * QQ 音乐签名网关（musics.fcg?sign=）的签名函数。
 *
 * 单独抽成一个 object 的理由：它是全项目唯一「一个字符算错就静默失败」的算法
 * （服务端只会返回 {"code":2000} 或者干脆空列表），必须能脱离网络单独对拍。
 * 对拍方式：给一段固定 payload，期望签名是确定值，任何重构只要变了结果就立刻暴露。
 *
 * 算法来自规格 2.6，三条常量数组的顺序就是索引语义，**不能重排、不能排序**。
 */
object QqSign {

    private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 7, 19)
    private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val SCRAMBLE_VALUES = intArrayOf(
        89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123,
        120, 64, 242, 133, 143, 161, 121, 179
    )

    /**
     * @param payload 必须是**最终发出去的 body 字符串**。字段顺序、有无空格都参与 sha1，
     *                所以调用方必须拿同一个字符串既签名又发送，不能签一份、发另一份。
     */
    fun zzcSign(payload: String): String {
        val hash = Md5Sha1.sha1HexUpper(payload) // 40 位大写 hex
        if (hash.length != 40) return ""          // 理论上不会发生，防空串 key 下去
        val p1 = StringBuilder(PART_1_INDEXES.size)
        for (i in PART_1_INDEXES) p1.append(hash[i])
        val p2 = StringBuilder(PART_2_INDEXES.size)
        for (i in PART_2_INDEXES) p2.append(hash[i])

        val buf = ByteArray(SCRAMBLE_VALUES.size)
        for (i in SCRAMBLE_VALUES.indices) {
            val b = Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16)
            buf[i] = (SCRAMBLE_VALUES[i] xor b).toByte()
        }
        // 规格要求删掉 \ / + = 四种字符，只留字母数字
        val b64 = Md5Sha1.base64(buf).replace("\\", "").replace("/", "").replace("+", "").replace("=", "")
        return ("zzc" + p1.toString() + b64 + p2.toString()).lowercase()
    }

    /**
     * ptlogin 的 hash33（QQ 扫码登录第 2 步的 ptqrtoken）。
     * 规格 2.5：`h=0; for c in s: h = (h<<5) + h + ord(c); return 2147483647 & h`
     * Kotlin 的 Int 就是 32 位溢出截断，与 JS 的 `<<`/`+` 在最终 & 后结果一致。
     */
    fun hash33(s: String, seed: Int = 0): Int {
        var h = seed
        for (c in s) {
            h = (h shl 5) + h + c.code
        }
        return 0x7FFFFFFF and h
    }

    /** g_tk，ptlogin 与 y.qq.com 的通用令牌：hash33(skey) & 0x7fffffff，种子固定 5381 */
    fun gtk(skey: String): Long = hash33(skey, 5381).toLong()
}
