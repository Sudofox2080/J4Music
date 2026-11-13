package com.j4.music.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 加解密底层的小工具集合：hex、AES-128-ECB、RC4、Tencent TEA、字节魔数判定。
 *
 * 全部只用 JDK 自带类（`javax.crypto` / `java.util.Base64` / `MessageDigest`），
 * 不引入任何第三方依赖；不使用 `java.util.HexFormat`（API 34 才有），
 * 也不使用 `android.util.Base64`（本文件要能被纯 JVM 的 `main` 直接调用）。
 *
 * 这里只放**与具体格式无关**的原子操作，格式相关的派生链分别在
 * [NcmCipher] / [QmcV2Cipher] 里。
 */
object CryptoPrimitives {

    private const val HEX_UPPER = "0123456789ABCDEF"

    /** 把连续 hex 字符串解析成字节数组；奇数长度或非法字符直接抛。 */
    fun hexToBytes(hex: String): ByteArray {
        val s = hex.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
        require(s.length % 2 == 0) { "hex 长度必须是偶数: ${s.length}" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "非法 hex 字符 @${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun bytesToHex(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            val v = b[off + i].toInt() and 0xFF
            sb.append(HEX_UPPER[v ushr 4]).append(HEX_UPPER[v and 0x0F])
        }
        return sb.toString()
    }

    fun eqBytes(a: ByteArray, aOff: Int, b: ByteArray, bOff: Int, len: Int): Boolean {
        if (aOff < 0 || bOff < 0 || len < 0) return false
        if (aOff + len > a.size || bOff + len > b.size) return false
        for (i in 0 until len) if (a[aOff + i] != b[bOff + i]) return false
        return true
    }

    fun startsWith(buf: ByteArray, prefix: ByteArray): Boolean =
        eqBytes(buf, 0, prefix, 0, prefix.size)

    fun startsWith(buf: ByteArray, off: Int, prefix: ByteArray): Boolean =
        eqBytes(buf, off, prefix, 0, prefix.size)

    // ---------------------------------------------------------------- AES

    /** AES/ECB/PKCS5Padding 解密（PKCS#7 填充由 JCE 去掉）。 */
    fun aesEcbDecryptPkcs5(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /** AES/ECB/NoPadding 解密：调用方自己要保证长度是 16 的倍数。 */
    fun aesEcbDecryptNoPadding(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /** AES/ECB/PKCS5Padding 加密，仅自检工具（[CryptoProbe]）合成样本时用。 */
    fun aesEcbEncryptPkcs5(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    // ---------------------------------------------------------------- RC4

    /**
     * 标准 RC4（key 通常 16 字节）。RC4 是自反的，加密解密同一个函数。
     *
     * 网易云 NCM 的 keyData 是「先 AES 加密、再 RC4」得到的，所以解密端必须先 RC4
     * 再做 AES 解密，顺序反了会得到前缀不是 `neteasecloudmusic` 的垃圾数据。
     */
    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------- TEA

    const val TEA_DELTA: Int = 0x9E3779B9.toInt()

    /**
     * Tencent TEA 的 8 字节分组解密（16 轮，DELTA = 0x9E3779B9）。
     *
     * 注意这不是标准 TEA 加密的逆运算的"教科书写法"：参考实现
     * （musicdl/scripts/kuwo_qmc_decryptor.py:124-135）是
     * `v0 = int.from_bytes(block[:4], "big")`、输出 `v0.to_bytes(4, "big")`，
     * 即**大端读入、大端输出**。本函数按这一默认实现。
     *
     * 【待验证】V3（TODO_VERIFY.md）：有资料认为 v1 表那一代是"大端读入、小端输出"。
     * 因此这里额外提供 [teaDecryptBlockLe]，并在 [tencentTea] 里提供
     * `littleEndianOut` 开关；QMC v2 的派生链默认走大端/大端，与参考实现一致。
     */
    fun teaDecryptBlock(block: ByteArray, off: Int, key16: ByteArray, littleEndianOut: Boolean = false): ByteArray {
        var v0 = beInt(block, off)
        var v1 = beInt(block, off + 4)
        val k0 = beInt(key16, 0)
        val k1 = beInt(key16, 4)
        val k2 = beInt(key16, 8)
        val k3 = beInt(key16, 12)
        var total = TEA_DELTA * 16
        for (i in 0 until 16) {
            v1 -= ((v0 shl 4) + k2) xor (v0 + total) xor ((v0 ushr 5) + k3)
            v0 -= ((v1 shl 4) + k0) xor (v1 + total) xor ((v1 ushr 5) + k1)
            total -= TEA_DELTA
        }
        val out = ByteArray(8)
        if (littleEndianOut) {
            lePut(out, 0, v0)
            lePut(out, 4, v1)
        } else {
            bePut(out, 0, v0)
            bePut(out, 4, v1)
        }
        return out
    }

    fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun bePut(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun lePut(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    fun putU32le(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * Tencent TEA 的分块解密流程，逐行对应参考实现 `_tencent_tea`：
     *
     * 1. 首块：`dec = teaBlock(data[0:8], key)`，`pad = dec[0] & 7`；
     * 2. `outLen = total - 1 - pad - 2 - 7`，负数说明填充非法 → 抛；
     * 3. 从 `idx = 1 + pad` 起先跳过 2 字节；
     * 4. 之后每个输出字节 = `dec[idx] ^ ivPrev[idx]`，ivPrev 是**上一块密文**；
     * 5. 最后 7 字节必须满足 `dec[idx] == ivPrev[idx]`（全零尾巴校验），否则 key 错。
     *
     * 任何一条断言不成立都说明 key 错了，所以这里失败一律抛 [QmcKeyException]，
     * 由上层把它当作"这个 key 不对"处理，而不是产出噪音。
     */
    fun tencentTea(data: ByteArray, key16: ByteArray, littleEndianOut: Boolean = false): ByteArray {
        require(key16.size == 16) { "Tencent TEA key 必须 16 字节，实际 ${key16.size}" }
        if (data.size < 16 || data.size % 8 != 0) {
            throw QmcKeyException("Tencent TEA 密文长度非法: ${data.size}（需 >=16 且为 8 的倍数）")
        }
        var dec = teaDecryptBlock(data, 0, key16, littleEndianOut)
        val pad = dec[0].toInt() and 7
        val total = data.size
        val outLen = total - 1 - pad - 2 - 7
        if (outLen < 0) throw QmcKeyException("Tencent TEA 填充非法: total=$total pad=$pad")

        var ivPrev = ByteArray(8)              // 第一块之前 IV 全 0
        var ivCur = data.copyOfRange(0, 8)
        var pos = 8
        var idx = 1 + pad

        fun nextBlock() {
            if (pos + 8 > total) throw QmcKeyException("Tencent TEA 密文被截断: pos=$pos total=$total")
            ivPrev = ivCur
            ivCur = data.copyOfRange(pos, pos + 8)
            // 参考实现：dec = teaBlock(dec XOR ivCur)，即把上一块的解密结果当作本轮输入
            val mix = ByteArray(8) { i -> (dec[i].toInt() xor ivCur[i].toInt()).toByte() }
            dec = teaDecryptBlock(mix, 0, key16, littleEndianOut)
            pos += 8
            idx = 0
        }

        // 跳过 2 字节
        repeat(2) {
            if (idx >= 8) nextBlock()
            idx += 1
        }

        val out = ByteArray(outLen)
        var written = 0
        while (written < outLen) {
            if (idx >= 8) nextBlock()
            out[written] = (dec[idx].toInt() xor ivPrev[idx].toInt()).toByte()
            written += 1
            idx += 1
        }

        // 尾部 7 字节全零校验
        repeat(7) {
            if (idx >= 8) nextBlock()
            if (dec[idx] != ivPrev[idx]) {
                throw QmcKeyException("Tencent TEA 尾部零校验失败（key 或字节序不对）")
            }
            idx += 1
        }
        return out
    }

    // ---------------------------------------------------------------- 其它

    fun sha1Hex(bytes: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("SHA-1").digest(bytes)).lowercase()

    /** 明文音频魔数：ID3 / fLaC / OggS / RIFF (WAV) / 0xFF 0xEx (MPEG 帧) / ADTS 0xFFF。 */
    val PLAIN_MAGIC_ID3 = byteArrayOf(0x49, 0x44, 0x33)              // "ID3"
    val PLAIN_MAGIC_FLAC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)       // "fLaC"
    val PLAIN_MAGIC_OGGS = byteArrayOf(0x4F, 0x67, 0x67, 0x53)       // "OggS"
    val PLAIN_MAGIC_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)       // "RIFF"

    /**
     * 判断一小段字节是否像明文音频的头部。
     *
     * 用于 QMC/QY2 解完 key 之后的自检：解出来必须像音频，否则 key 不对。
     * （规格 2.6 最后一条"解完一定要验首字节"）。0xFF 0xEx 的 MPEG 帧同步按
     * 参考实现 `head[0] == 0xFF && head[1] & 0xE0 == 0xE0` 判定。
     */
    fun looksLikePlainAudio(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Boolean {
        if (len < 4 || off < 0 || off + len > buf.size) return false
        if (eqBytes(buf, off, PLAIN_MAGIC_ID3, 0, 3)) return true
        if (eqBytes(buf, off, PLAIN_MAGIC_FLAC, 0, 4)) return true
        if (eqBytes(buf, off, PLAIN_MAGIC_OGGS, 0, 4)) return true
        if (eqBytes(buf, off, PLAIN_MAGIC_RIFF, 0, 4)) return true
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return true
        return false
    }

    /** 按魔数猜明文格式后缀，猜不出返回 null。 */
    fun plainAudioFormat(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): String? {
        if (len < 4 || off < 0 || off + len > buf.size) return null
        if (eqBytes(buf, off, PLAIN_MAGIC_ID3, 0, 3)) return "mp3"
        if (eqBytes(buf, off, PLAIN_MAGIC_FLAC, 0, 4)) return "flac"
        if (eqBytes(buf, off, PLAIN_MAGIC_OGGS, 0, 4)) return "ogg"
        if (eqBytes(buf, off, PLAIN_MAGIC_RIFF, 0, 4)) return "wav"
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return "mp3"
        return null
    }
}
