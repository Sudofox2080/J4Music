package com.j4.music.crypto

import java.io.File
import java.io.RandomAccessFile

/**
 * 华为音乐 `.QY2` 解密器。
 *
 * ## 规格第 4 节：QY2 与 QQ 音乐同源
 *
 * `.QY2` 绝大多数情况下就是 QQ 音乐 v2 的容器，尾部同样写着 `QQMusic EncV2,Key:` / `QQMusic EncV1,Key:`。
 * 所以第一选择是直接委托 [QmcV2Cipher]。只有尾部找不到这段明文掩码时，才会走"单层解尾部"的兜底。
 *
 * ## 三条分支（[parse] 依次尝试，全失败抛 [Qy2UnknownException]）
 *
 * 1. **尾部明文命中** `QQMusic Enc` → 直接把文件交给 [QmcV2Cipher.fromFile]，[mode] = [Mode.DELEGATED_V2]。
 * 2. **单层解尾部**：尾部那串是 base64，先 base64decode，再用 [V2_KEY1] / [V2_KEY2] 逐个试
 *    "TEA 解密后能否还原出合法 base64 文本"；能，就把还原出的 ekey 按 v2 流程继续派生，
 *    [mode] = [Mode.UNWRAPPED_TAIL]。
 * 3. 都不成立 → [Qy2UnknownException]。
 *
 * ### 【待验证】V7（TODO_VERIFY.md）
 *
 * "QY2 与 QQ 音乐同源"是**推断**：`.QY2` 的尾部结构、TEA 常数与 v2 一致，但没有对拍样本实证；
 * 尤其分支 2 的"解一层就能拿到合法 base64"这条，是从若干 v2 变体倒推出来的经验规则，
 * 不能保证覆盖所有 QY2 发布批次。因此本类保留 [Mode] 标记与 [ekeyPrefix] 字段，
 * 让运行期能看出走了哪条分支；分支 2 失败时**不假装成功**，直接抛异常交给上层降级。
 */
class Qy2Cipher internal constructor(
    override val dataStart: Long,
    /** 实际干活的 cipher（v2 派生出来的）。 */
    val delegate: BlockCipher,
    /** 尾部命中的前缀：`QQMusic EncV2,Key:` / `QQMusic EncV1,Key:` / `null`（分支 2）。 */
    val ekeyPrefix: String?,
    /** 命中的分支。 */
    val mode: Mode,
    /** 从尾部取出的原始 ekey 文本（已 unquote，便于日志排查）。 */
    val rawEkey: String?
) : BlockCipher {

    override fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long) =
        delegate.decrypt(buf, off, len, absOffset)

    override fun toString(): String =
        "Qy2Cipher(mode=$mode, ekeyPrefix=${ekeyPrefix ?: "-"}, delegate=$delegate)"

    /** 走了哪条分支。 */
    enum class Mode {
        /** 尾部明文命中 `QQMusic Enc*`，整体委托给 v2。 */
        DELEGATED_V2,

        /** 尾部是单层 TEA 包裹的 base64，解开后按 v2 派生。 */
        UNWRAPPED_TAIL
    }

    companion object {

        /** 与 v2 一致的尾部扫描窗口。 */
        const val TAIL_SCAN = 1024

        /** 尾部命中判据（比完整前缀短，两个版本都能匹配）。 */
        val MARKER: ByteArray = "QQMusic Enc".toByteArray(Charsets.US_ASCII)

        val PREFIX_V2: String = QmcV2Cipher.PREFIX_V2
        val PREFIX_V1: String = QmcV2Cipher.PREFIX_V1

        /** 单层解尾部用的两组 TEA key，与 v2 的 ekey 包裹复用同一对常量。 */
        val V2_KEY1: ByteArray = CryptoPrimitives.hexToBytes(QmcV2Cipher.V2_KEY1_HEX)
        val V2_KEY2: ByteArray = CryptoPrimitives.hexToBytes(QmcV2Cipher.V2_KEY2_HEX)

        /**
         * 解析一个 `.QY2` 文件。顺序见类 KDOC 的三条分支。
         *
         * @throws Qy2UnknownException 三条分支全部失败。
         */
        @Throws(Qy2UnknownException::class)
        fun fromFile(file: File, littleEndianTea: Boolean = false): Qy2Cipher {
            val len = file.length()
            val tailStart = maxOf(0L, len - TAIL_SCAN)
            val tail = RandomAccessFile(file, "r").use { raf ->
                val n = (len - tailStart).toInt().coerceAtMost(TAIL_SCAN)
                val b = ByteArray(n)
                raf.seek(tailStart)
                raf.readFully(b)
                b
            }
            return resolve(
                tail = tail,
                length = len,
                headEnc = readHead(file),
                v2Loader = { QmcV2Cipher.fromFile(file, littleEndianTea) },
                littleEndianTea = littleEndianTea
            )
        }

        /** 内存版：自检工具与单元测试用。 */
        @Throws(Qy2UnknownException::class)
        fun fromBytes(bytes: ByteArray, littleEndianTea: Boolean = false): Qy2Cipher {
            val tailStart = maxOf(0, bytes.size - TAIL_SCAN)
            val tail = bytes.copyOfRange(tailStart, bytes.size)
            return resolve(
                tail = tail,
                length = bytes.size.toLong(),
                headEnc = bytes.copyOf(minOf(16, bytes.size)),
                v2Loader = { QmcV2Cipher.fromBytes(bytes, littleEndianTea) },
                littleEndianTea = littleEndianTea
            )
        }

        private fun readHead(file: File): ByteArray = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val n = minOf(16L, raf.length()).toInt()
                val b = ByteArray(n)
                raf.seek(0)
                raf.readFully(b)
                b
            }
        }.getOrDefault(ByteArray(0))

        private fun resolve(
            tail: ByteArray,
            length: Long,
            headEnc: ByteArray,
            v2Loader: () -> QmcV2Cipher,
            littleEndianTea: Boolean
        ): Qy2Cipher {
            // 分支 1：尾部明文就有 QQMusic Enc*
            val hit = QmcV2Cipher.findEkey(tail)
            if (hit != null) {
                val v2 = try {
                    v2Loader()
                } catch (e: CipherException) {
                    throw Qy2UnknownException("QY2 尾部命中 ${hit.first}，但 v2 派生失败：${e.message}")
                }
                return Qy2Cipher(v2.dataStart, v2, hit.first, Mode.DELEGATED_V2, hit.second)
            }

            // 分支 2：尾部是单层 TEA 包裹，解开后应是一段合法 base64 文本
            val unwrapped = tryUnwrapTail(tail, littleEndianTea)
            if (unwrapped != null) {
                val v2 = try {
                    QmcV2Cipher.fromEkey(unwrapped.ekey, length, headEnc, unwrapped.prefix, littleEndianTea)
                } catch (e: CipherException) {
                    throw Qy2UnknownException("QY2 尾部可单层解开，但 ekey 无法派生密钥：${e.message}")
                }
                return Qy2Cipher(v2.dataStart, v2, unwrapped.prefix, Mode.UNWRAPPED_TAIL, unwrapped.ekey)
            }

            throw Qy2UnknownException(
                "QY2 尾部既没有 QQMusic Enc* 明文，也无法单层解开（扫描窗口 ${TAIL_SCAN}B，文件 ${length}B）"
            )
        }

        private class Unwrapped(val ekey: String, val prefix: String?)

        /**
         * 单层解尾部：base64decode → TEA(V2_KEY1) → 结果是不是合法 base64 文本？
         * 不是就再试 `TEA(V2_KEY2) → TEA(V2_KEY1)`（v2 的双层包裹去掉一层的情形）。
         */
        private fun tryUnwrapTail(tail: ByteArray, littleEndianTea: Boolean): Unwrapped? {
            // 从尾部窗口里挑出最长的可解码 base64 片段
            val text = String(tail, Charsets.ISO_8859_1)
            val candidate = text.trim().takeIf { it.length >= 16 && it.all { c -> c.isLetterOrDigit() || c == '+' || c == '/' || c == '=' } }
                ?: return null
            val raw = QmcV2Cipher.b64(candidate) ?: return null
            if (raw.size < 16 || raw.size % 8 != 0) return null

            val attempts = listOf(
                listOf(V2_KEY1),
                listOf(V2_KEY2, V2_KEY1)
            )
            for (chain in attempts) {
                var cur: ByteArray = raw
                try {
                    for (k in chain) cur = CryptoPrimitives.tencentTea(cur, k, littleEndianTea)
                } catch (e: CipherException) {
                    continue
                }
                val asText = String(cur, Charsets.ISO_8859_1)
                if (isBase64Text(asText)) {
                    // 去掉可能残留的 EncV* 前缀，剩下的才是 ekey 本体
                    val hit = QmcV2Cipher.findEkey(cur)
                    if (hit != null) return Unwrapped(hit.second, hit.first)
                    return Unwrapped(asText.trim(), null)
                }
            }
            return null
        }

        private fun isBase64Text(s: String): Boolean {
            val t = s.trim().trim('\u0000')
            if (t.length < 16) return false
            return t.all { c -> c.isLetterOrDigit() || c == '+' || c == '/' || c == '=' || c == '-' || c == '_' }
        }
    }
}
