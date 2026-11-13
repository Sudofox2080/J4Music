package com.j4.music.crypto

import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.Base64

/**
 * QQ 音乐 v2（`.mflac` / `.mflac0` / `.mgg` / `.mggl` / `.mmp3`）解密器，
 * 也承担 `.QY2` 与 `.qmc2` 里 "其实已经是 v2" 的那一半（见 [Qy2Cipher]）。
 *
 * ## 为什么密钥在文件尾部
 *
 * v2 没有容器头：整个文件都是音频体，密钥 `ekey` 是一行 base64 文本，
 * 藏在文件**最后 1024 字节**里，形如 `QQMusic EncV2,Key:<base64>`。
 * 所以构造 cipher 必须先 `read(尾部)`，这也是 [CipherFactory] 要用 `LruCache` 缓存派生结果的原因
 * （Media3 每次试探容器都会新建 DataSource，不缓存就要重复解 704 字节 ekey）。
 *
 * ## 派生链（规格 2.2，任何一步失败都抛 [QmcKeyException]，绝不产出噪音）
 *
 * ```
 * 1. raw = base64decode(unquote(ekey).replace(' ', '+'))
 * 2. 若 raw 以 "QQMusic EncV2,Key:" 开头：
 *        buf = tencentTea(raw[20:], V2_KEY1)
 *        buf = tencentTea(buf,      V2_KEY2)
 *        raw = base64decode(buf)
 * 3. raw.length 必须 >= 16
 * 4. teaKey = interleave(simple8, raw[0:8])     // simple8 = 69 56 46 38 2B 20 15 0B
 * 5. key = raw[0:8] + tencentTea(raw[8:], teaKey)
 * ```
 *
 * 注意第 1 步里的 `raw[20:]` 与 `"QQMusic EncV2,Key:".length == 20` 一致；
 * 不要抄成 21 或 19，否则第一层 TEA 直接解错。
 *
 * ## 两个变体
 *
 * `key.length > 300` 走 [Rc4Cipher]（区域 A + 5120 字节分段），否则走 [MapCipher]
 * （`idx = (q*q + 71214) % 128`，`q = p > 0x7FFF ? p % 0x7FFF : p`）。
 */
class QmcV2Cipher(
    override val dataStart: Long,
    /** 派生出来的明文密钥流种子，MAP 变体通常 128 字节，RC4 变体 > 300 字节。 */
    val key: ByteArray,
    /** 这个文件里找到的 ekey 行前缀，`QQMusic EncV2,Key:` 或 `QQMusic EncV1,Key:`。 */
    val ekeyPrefix: String,
    /** 实际的解密引擎（MAP 或 RC4），按 key 长度自动选。 */
    val engine: BlockCipher
) : BlockCipher {

    /** 走的是 RC4 变体（key > 300 字节）还是 MAP 变体。 */
    val isRc4Variant: Boolean get() = key.size > 300

    override fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long) =
        engine.decrypt(buf, off, len, absOffset)

    override fun toString(): String =
        "QmcV2Cipher(dataStart=$dataStart, keyLen=${key.size}, variant=${if (isRc4Variant) "RC4" else "MAP"}, ekey=$ekeyPrefix)"

    companion object {

        /** 尾部扫描范围：最后 1024 字节（规格 2.1 第 1 步）。 */
        const val TAIL_SCAN = 1024

        const val PREFIX_V2 = "QQMusic EncV2,Key:"
        const val PREFIX_V1 = "QQMusic EncV1,Key:"

        /** 两层 TEA 的 key 的 hex 原文，**一律用 hex 解析，不许手打 ASCII**（`!@#*$%^&)(` 极易抄错）。 */
        const val V2_KEY1_HEX = "3338365A4A592140232A24255E262928"
        const val V2_KEY2_HEX = "2A2A232128232425265E6131635A2C54"

        val V2_KEY1: ByteArray = CryptoPrimitives.hexToBytes(V2_KEY1_HEX)
        val V2_KEY2: ByteArray = CryptoPrimitives.hexToBytes(V2_KEY2_HEX)

        /**
         * `simple` 本应由 `int(abs(tan(106 + i*0.1) * 100.0)) & 0xFF` 算出来，
         * 这里直接硬编码（规格 2.2 第三步给出的固定值），并在 [CryptoProbe] 里用
         * tan 公式重新算一遍做等价断言。
         */
        val SIMPLE_KEY: ByteArray = CryptoPrimitives.hexToBytes("695646382B20150B")

        /** MAP 变体的下标常数（参考实现里就是 71214，与 refcheck.py 的 80923 不同，以真实实现为准）。 */
        const val MAP_INDEX_CONST = 71214

        /** MAP 变体的坐标回卷点。注意是 `p > 0x7FFF` 才回卷，不是 `p >= 0x8000`，两者差 1 字节。 */
        const val MAP_WRAP = 0x7FFF

        /** 区域 A（逐字节 `key[skip(p)]`）的终点。 */
        const val RC4_FIRST = 128

        /** RC4 变体的分段长度。 */
        const val RC4_SEGMENT = 5120

        /** key 短于这个值说明派生链没走通。 */
        const val MIN_KEY_LEN = 16

        /** 明文音频魔数检查用的头部长度（规格 2.6 最后一条）。 */
        private const val MAGIC_PROBE_LEN = 16

        /**
         * 从文件构造。
         *
         * 规格 2.1 第 3 条：`QQMusic EncV1,Key:` 说明这一代的 ekey 少包了一层，
         * 本实现仍按 v2 链试一次（等价于历史上"v1 ekey 只过单层 TEA"的情形）；
         * 解不通会抛 [QmcKeyException] 由上层降级，不产出噪音。
         */
        @Throws(QmcKeyException::class)
        fun fromFile(file: File, littleEndianTea: Boolean = false): QmcV2Cipher {
            val tail = readTail(file, TAIL_SCAN)
            val found = findEkey(tail) ?: throw QmcKeyException("尾部 1024 字节里没有 QQMusic EncV1/V2 标记，不是 QQ 音乐 v2 文件")
            return fromEkey(found.second, file.length(), headOf(file), found.first, littleEndianTea)
        }

        /**
         * 从内存字节构造（自检与单测用）。
         */
        @Throws(QmcKeyException::class)
        fun fromBytes(bytes: ByteArray, littleEndianTea: Boolean = false): QmcV2Cipher {
            val tailLen = minOf(TAIL_SCAN, bytes.size)
            val tail = bytes.copyOfRange(bytes.size - tailLen, bytes.size)
            val found = findEkey(tail) ?: throw QmcKeyException("尾部 1024 字节里没有 QQMusic EncV1/V2 标记，不是 QQ 音乐 v2 文件")
            return fromEkey(found.second, bytes.size.toLong(), bytes.copyOf(minOf(MAGIC_PROBE_LEN, bytes.size)), found.first, littleEndianTea)
        }

        /**
         * 已经拿到 ekey 文本时（`.QY2` 的单层解包分支会走到这里）直接用。
         *
         * @param headEnc 音频体的前 16 个**密文**字节，用于 V3 的字节序自校准；
         *   传 null 就跳过校准，按参考实现的大端/大端走。
         */
        @Throws(QmcKeyException::class)
        fun fromEkey(
            ekey: String,
            fileLength: Long = 0L,
            headEnc: ByteArray? = null,
            ekeyPrefix: String? = null,
            littleEndianTea: Boolean = false
        ): QmcV2Cipher {
            val key = if (littleEndianTea) {
                deriveKey(ekey, true)
            } else if (headEnc != null && headEnc.isNotEmpty()) {
                // V3 未实证：两条字节序都试，以解出的首字节像不像明文音频选胜者
                deriveKeyAutoEndian(ekey, headEnc).second
            } else {
                deriveKey(ekey, false)
            }
            val engine: BlockCipher = if (key.size > 300) Rc4Cipher(key) else MapCipher(key)
            return QmcV2Cipher(0L, key, ekeyPrefix ?: PREFIX_V2, engine)
        }

        /** v2 没有容器头：音频体从文件 0 开始，长度就是文件长度。 */
        fun tailScanLength(file: File): Int = minOf(TAIL_SCAN.toLong(), file.length()).toInt()

        private fun headOf(file: File): ByteArray = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val n = minOf(MAGIC_PROBE_LEN.toLong(), raf.length()).toInt()
                val b = ByteArray(n)
                raf.seek(0)
                raf.readFully(b)
                b
            }
        }.getOrDefault(ByteArray(0))

        private fun readTail(file: File, n: Int): ByteArray {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val take = minOf(n.toLong(), len).toInt()
                val buf = ByteArray(take)
                raf.seek(len - take)
                raf.readFully(buf)
                return buf
            }
        }

        /**
         * 在尾部找 `QQMusic EncV2,Key:` / `QQMusic EncV1,Key:`，返回 (前缀, ekey)。
         * 用 ISO-8859-1 做"字节 ↔ 字符"一一映射，避免 UTF-8 解码把 base64 里的字节改掉。
         */
        fun findEkey(tail: ByteArray): Pair<String, String>? {
            if (tail.isEmpty()) return null
            val s = String(tail, Charsets.ISO_8859_1)
            // 两种前缀都要找，取**更靠后**的那个：文件里若同时出现（v1 转 v2 的批次），
            // 后出现的才是当前代次。
            var bestAt = -1
            var bestPrefix: String? = null
            for (p in arrayOf(PREFIX_V2, PREFIX_V1)) {
                val i = s.lastIndexOf(p)
                if (i > bestAt) {
                    bestAt = i
                    bestPrefix = p
                }
            }
            val found = bestPrefix ?: return null
            val rest = s.substring(bestAt + found.length).trim()
            return found to rest
        }

        /**
         * ekey 的后处理：`%xx` 先 unquote，空格换 `+`（URL 参数传递会把 `+` 变成空格，
         * 不换回来后半截 base64 全错 —— 规格 2.6 第一条坑）。
         */
        fun normalizeEkey(ekey: String): String {
            var s = ekey.trim()
            s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
            return s.replace(' ', '+').filterNot { it == '\r' || it == '\n' }
        }

        /** 宽松 base64：去掉所有空白后再解，失败返回 null。 */
        fun b64(blob: ByteArray): ByteArray? = b64(String(blob, Charsets.ISO_8859_1))

        fun b64(s: String): ByteArray? {
            val cleaned = s.filterNot { it.isWhitespace() || it == '\u0000' }
            if (cleaned.isEmpty()) return null
            return runCatching { Base64.getDecoder().decode(cleaned) }.getOrNull()
        }

        /** `simple` 的 tan 公式版本，只给自检用，生产路径直接用 [SIMPLE_KEY]。 */
        fun simpleKeyByFormula(): ByteArray =
            ByteArray(8) { i -> (kotlin.math.abs(kotlin.math.tan(106.0 + i * 0.1) * 100.0).toInt() and 0xFF).toByte() }

        /**
         * 完整派生链。抛出 [QmcKeyException] 时说明这个 ekey 用 v2 链解不通。
         *
         * 【待验证】V3（TODO_VERIFY.md）：Tencent TEA 的字节序。参考实现是大端读入、大端输出；
         * 若真实样本解出噪音，问题优先怀疑这里。本实现保留 `littleEndianOut=false` 默认路径，
         * 并在两条路径都试之后以"首字节是否是明文音频魔数"作为判据选择
         * （见 [deriveKeyAutoEndian]），判定不了才用参考实现默认的大端/大端。
         */
        @Throws(QmcKeyException::class)
        fun deriveKey(ekey: String, littleEndianOut: Boolean = false): ByteArray {
            var raw = b64(normalizeEkey(ekey))
                ?: throw QmcKeyException("ekey 不是合法 base64（先 unquote、空格换 + 之后仍解不开）")

            if (raw.size >= PREFIX_V2.length && CryptoPrimitives.startsWith(raw, PREFIX_V2.toByteArray(Charsets.ISO_8859_1))) {
                val inner = raw.copyOfRange(PREFIX_V2.length, raw.size)
                val buf = CryptoPrimitives.tencentTea(inner, V2_KEY1, littleEndianOut)
                val buf2 = CryptoPrimitives.tencentTea(buf, V2_KEY2, littleEndianOut)
                raw = b64(buf2) ?: throw QmcKeyException("两层 TEA 之后不是合法 base64")
            }

            if (raw.size < MIN_KEY_LEN) {
                throw QmcKeyException("QMC key 太短（${raw.size} < $MIN_KEY_LEN）")
            }

            val simple = SIMPLE_KEY
            val teaKey = ByteArray(16)
            for (i in 0 until 8) {
                teaKey[i * 2] = simple[i]
                teaKey[i * 2 + 1] = raw[i]
            }
            val tail = CryptoPrimitives.tencentTea(raw.copyOfRange(8, raw.size), teaKey, littleEndianOut)
            val key = ByteArray(8 + tail.size)
            System.arraycopy(raw, 0, key, 0, 8)
            System.arraycopy(tail, 0, key, 8, tail.size)
            return key
        }

        /**
         * 两条 TEA 字节序都试一遍，用"解出来的前 16 字节像不像明文音频"做判据。
         * 这是在 V3 没实证之前唯一能自动收敛的办法：ErrV2 样本解对了 key，
         * 首字节必然是 `fLaC` / `OggS` / `ID3` / `0xFF 0xEx` 之一。
         *
         * @return (用来做魔数判定的前 16 字节, 选中的 key, 是否小端)
         */
        @Throws(QmcKeyException::class)
        fun deriveKeyAutoEndian(ekey: String, headEnc: ByteArray): Triple<ByteArray, ByteArray, Boolean> {
            var firstError: Throwable? = null
            for (le in booleanArrayOf(false, true)) {
                val key = try {
                    deriveKey(ekey, le)
                } catch (e: QmcKeyException) {
                    if (firstError == null) firstError = e
                    continue
                }
                val probe = headEnc.copyOf(minOf(MAGIC_PROBE_LEN, headEnc.size))
                val engine: BlockCipher = if (key.size > 300) Rc4Cipher(key) else MapCipher(key)
                engine.decrypt(probe, 0, probe.size, 0L)
                if (CryptoPrimitives.looksLikePlainAudio(probe)) return Triple(probe, key, le)
            }
            val e = firstError
            throw QmcKeyException("QMC v2 key 派生成功但解出的首字节不是音频魔数（TEA 字节序对不上？见 TODO_VERIFY.md V3）", e)
        }
    }
}

/**
 * MAP 变体（key 通常 128 字节）。纯函数式、天然可 seek。
 *
 * 周期行为（`tools/streamcheck.py` 已验）：`p = 0x7FFF` 与 `p = 0` 得到**不同**的密钥字节，
 * `p = 0x8000` 与 `p = 1` 相同 —— 周期是 0x7FFF 但第 0 个元素被整体挪到了末尾。
 */
class MapCipher(private val key: ByteArray) : BlockCipher {

    override val dataStart: Long = 0L

    init {
        require(key.isNotEmpty()) { "QMC MAP key 不能为空" }
    }

    override fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long) {
        val size = key.size
        for (i in 0 until len) {
            var p = absOffset + i
            if (p > QmcV2Cipher.MAP_WRAP) p %= QmcV2Cipher.MAP_WRAP.toLong()
            val idx = ((p * p + QmcV2Cipher.MAP_INDEX_CONST) % size).toInt()
            val rot = rotateLeftByte(key[idx].toInt() and 0xFF, (idx and 7) + 4)
            buf[off + i] = (buf[off + i].toInt() xor rot).toByte()
        }
    }

    companion object {
        /** 位移量先对 8 取模：`r = (bits + 4) % 8`，然后 `((v << r) | (v >> (8-r))) & 0xFF`。 */
        fun rotateLeftByte(v: Int, bits: Int): Int {
            val r = (bits + 4) and 7
            if (r == 0) return v and 0xFF
            val x = v and 0xFF
            return ((x shl r) or (x ushr (8 - r))) and 0xFF
        }

        /** 给定 key 与明文坐标，直接算该位置生效的那个掩码字节，供自检断言使用。 */
        fun maskAt(key: ByteArray, p0: Long): Int {
            var p = p0
            if (p > QmcV2Cipher.MAP_WRAP) p %= QmcV2Cipher.MAP_WRAP.toLong()
            val idx = ((p * p + QmcV2Cipher.MAP_INDEX_CONST) % key.size).toInt()
            return rotateLeftByte(key[idx].toInt() and 0xFF, (idx and 7) + 4)
        }
    }
}

/**
 * RC4 变体（key > 300 字节）。
 *
 * 三个区域（规格 2.4）：
 * - 区域 A：`p < 128`，逐字节 `out = in ^ key[skip(p)]`；
 * - 区域 B：`p >= 128`，按 5120 字节分段，每段都从初始 box 出发空转 `skip(seg) + (p % 5120)` 步；
 * - 跨段绝不累积 box（累积的典型症状是"前 5KB 正常、后面全噪音"）。
 *
 * ## 性能
 *
 * 按字节从段首重算出密钥流是 O(n²)。这里按规格 6.4 的要求做**段内快照**：
 * 缓存 `(seg, inSegStart, box, j, k)`，同一段内连续读时直接从上次的 `(box, j, k)` 继续推进；
 * 只要调用方给的 `absOffset` 落在缓存段内且不早于 `inSegStart`，就只是"少走几步"，
 * 最坏也只是回到段首重算一次，绝不会跨段复用状态。
 *
 * `CryptoProbe` 把"分块 / 乱序 seek / 单字节边界读"与"一次性解完"逐字节比对（对应 streamcheck.py）。
 */
class Rc4Cipher internal constructor(private val key: ByteArray) : BlockCipher {

    override val dataStart: Long = 0L

    private val size: Int = key.size

    /**
     * 初始 box。注意参考实现是 `[i & 0xFF for i in range(size)]`：
     * size > 256 时初值会按 256 截断重复，这里**照实复刻**（`i and 0xFF`），
     * 否则和真实样本的密钥流对不上。声明的类型是 IntArray 而不是 ByteArray，正是因为
     * 截断后 box 里装的是 0..255 的下标，交换逻辑用的是 `% size`。
     */
    private val box0: IntArray = IntArray(size) { it and 0xFF }

    private val hashBase: Long
    private val skipCache = HashMap<Int, Int>()

    init {
        require(size > 0) { "QMC RC4 key 不能为空" }
        var j = 0
        for (i in 0 until size) {
            j = (j + box0[i] + (key[i].toInt() and 0xFF)) % size
            val t = box0[i]; box0[i] = box0[j]; box0[j] = t
        }
        hashBase = computeHashBase(key)
    }

    // ---- 段内快照（规格 6.4：同段连续读时不要每次从段首重算）
    /** 上次解完的那一段的段号（-1 表示还没解过）。 */
    private var snapSeg = -1
    /** 上次解完时段内的下一个密钥流下标。 */
    private var snapInSeg = -1
    /** 只有 snapValid 为 true 时，snapSeg / snapInSeg / snapJ / snapK / stateBox 才可信。 */
    private var snapValid = false
    /** 当前这次解密的工作区：每次 xorSegment 都从 box0 重新开始，绝不跨段累积。 */
    private val runBox: IntArray = IntArray(size)
    /** 上次解完时的工作区副本；只有当本次起点正好等于 snapInSeg 时才拿来续写。 */
    private val stateBox: IntArray = IntArray(size)
    private var snapJ = 0
    private var snapK = 0

    override fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long) {
        var p = absOffset
        var n = 0
        // 区域 A：p < 128，逐字节 key[skip(p)]
        while (n < len && p < QmcV2Cipher.RC4_FIRST) {
            val k = key[skip(p.toInt())]
            buf[off + n] = (buf[off + n].toInt() xor k.toInt()).toByte()
            p++
            n++
        }
        // 区域 B：按 5120 字节分段
        while (n < len) {
            val seg = (p / QmcV2Cipher.RC4_SEGMENT).toInt()
            val inSeg = (p % QmcV2Cipher.RC4_SEGMENT).toInt()
            val remainingInSeg = QmcV2Cipher.RC4_SEGMENT - inSeg
            val take = minOf(len - n, remainingInSeg)
            xorSegment(buf, off + n, take, seg, inSeg)
            p += take
            n += take
        }
    }

    /**
     * 解第 seg 段、段内起点 inSeg 的 take 个字节。
     *
     * 密钥流只由 `(box0, skip(seg), inSeg)` 决定，与调用顺序无关，所以这里永远
     * "从段首复制 box0 → 空转 skip(seg) + inSeg 步 → 顺序产出"。代价 O(段长)，最坏 5120 步。
     *
     * 规格 6.4 要求的"段内快照"用 [stateBox] / [snapSeg] / [snapInSeg] 记下
     * "某段已经空转到第几个密钥流字节"，命中时只跳过重复的空转（同一段连续顺序读时
     * 是常见情形：Media3 每次 read 都会从上次位置继续），未命中就整段重算，
     * 因此 seek / 乱序 / 单字节读的结果与一次性解完逐字节相同（[CryptoProbe] 有断言）。
     */
    private fun xorSegment(buf: ByteArray, off: Int, take: Int, seg: Int, inSeg: Int) {
        var i: Int
        var j: Int
        var k: Int
        // 快照命中：同一段、且上次已经推进过的段内位置整块覆盖本次的起点，
        // 此时可以直接从快照状态续写（只在 inSeg == snapInSeg 时才真正省事，其他情况仍重算）。
        if (snapValid && snapSeg == seg && snapInSeg == inSeg) {
            System.arraycopy(stateBox, 0, runBox, 0, size)
            i = snapInSeg
            j = snapJ
            k = snapK
        } else {
            // 从段首空转 skip(seg) + inSeg 步
            System.arraycopy(box0, 0, runBox, 0, size)
            i = 0
            j = 0
            k = 0
            val skipLen = skip(seg) + inSeg
            while (i < skipLen) {
                j = (j + 1) % size
                k = (runBox[j] + k) % size
                val t = runBox[j]; runBox[j] = runBox[k]; runBox[k] = t
                i++
            }
        }
        var written = 0
        while (written < take) {
            j = (j + 1) % size
            k = (runBox[j] + k) % size
            val t = runBox[j]; runBox[j] = runBox[k]; runBox[k] = t
            val ks = runBox[(runBox[j] + runBox[k]) % size]
            buf[off + written] = (buf[off + written].toInt() xor ks).toByte()
            i++
            written++
        }
        // 记住"段内第 i 个密钥流字节已产出"的状态，供下一次同段请求命中
        System.arraycopy(runBox, 0, stateBox, 0, size)
        snapSeg = seg
        snapInSeg = i
        snapJ = j
        snapK = k
        snapValid = true
    }

    private fun skip(seg: Int): Int {
        skipCache[seg]?.let { return it }
        val seed = key[seg % size].toInt() and 0xFF
        val v = if (seed == 0) 0 else ((hashBase / ((seg + 1).toDouble() * seed) * 100.0).toInt()) % size
        skipCache[seg] = v
        return v
    }

    companion object {
        /**
         * 参考实现 `_hash_base`：从 1 开始累乘非零 key 字节（32 位截断），
         * 一旦溢出回绕或不再增大就停。用 double 做除法保留参考实现的浮点行为。
         */
        internal fun computeHashBase(key: ByteArray): Long {
            var h = 1L
            for (b in key) {
                val c = b.toInt() and 0xFF
                if (c == 0) continue
                val nxt = (h * c) and 0xFFFFFFFFL
                if (nxt == 0L || nxt <= h) break
                h = nxt
            }
            return h
        }
    }
}
