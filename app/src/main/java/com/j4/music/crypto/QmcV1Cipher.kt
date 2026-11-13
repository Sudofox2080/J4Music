package com.j4.music.crypto

import java.io.File

/**
 * QQ 音乐 v1（`.qmc0` / `.qmc3` / `.qmcflac` / `.qmcogg` / `.qmc2`）解密流程。
 *
 * ## 本机没有静态 cipher 表，这一点必须说清楚
 *
 * v1 与 v2 的根本差异：v1 的密钥是**硬编码在客户端里的静态表**，
 * 表内容在 `FORMAT_DECRYPT.md` / `APPENDIX_QMC_V1.md` 里都不存在
 * （`musicdl/scripts/kuwo_qmc_decryptor.py` 只覆盖 v2，也没有 v1 表）。
 * 所以这里**不编造表数据**：算法流程（周期异或 + 0x7FFF 回卷 + rotate）写全、
 * 表由 [QmcV1Table.MAP] 这个可注入槽位提供，槽位为空时一律抛
 * [QmcV1UnsupportedException]「缺少静态 cipher 表」。
 *
 * 一旦按 APPENDIX_QMC_V1.md A.4 的步骤（已知明文头 → 逐字节异或得密钥流 → 自相关定周期）
 * 推出一张表，把它塞进 [QmcV1Table.MAP]（或用 [QmcV1Table.install] 在运行期装入）就能直接工作，
 * 不需要改这个文件里的任何逻辑。
 *
 * ## 变换（与 v2 的 MAP 变体同构，只是常数与表长不同）
 *
 * ```
 * q   = p
 * if q > 0x7FFF: q = q % 0x7FFF          // 回卷条件是 > 0x7FFF，不是 >= 0x8000
 * idx = (q*q + 71214) % tableLen
 * rot = rotateLeftByte(table[idx], 4 + ((idx & 7) >> 1))   // 等价于 (idx & 7) + 4 的位移
 * out = in ^ rot
 * ```
 *
 * 注意规格 2.3 同时给了两种等价的位移写法：`4 - ((idx & 7) >> 1)` 与参考实现的
 * `(idx & 7) + 4`。两者**并不等价**（前者对 8 取模后是 4/4/3/3/2/2/1/1，后者是 4..11）。
 * 真实 v2 实现用的是后者，本文件与 [MapCipher] 统一按后者；v1 没有样本可证，
 * 保留 [rotationMode] 开关以便上机时切换（见下方 KDOC 的【待验证】说明）。
 */
class QmcV1Cipher internal constructor(
    private val table: ByteArray,
    /** 表长，决定周期；[table] 的长度。 */
    val tableLen: Int,
    /** 位移模式：true 用规格 2.3 的字面写法 `4 - ((idx&7)>>1)`，false 用参考实现的 `(idx&7)+4`。 */
    private val specLiteralRotation: Boolean
) : BlockCipher {

    override val dataStart: Long = 0L

    override fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long) {
        for (i in 0 until len) {
            buf[off + i] = (buf[off + i].toInt() xor maskAt(absOffset + i)).toByte()
        }
    }

    /** 给定明文坐标，算该位置生效的掩码字节。 */
    fun maskAt(p0: Long): Int {
        var p = p0
        if (p > WRAP) p %= WRAP
        val idx = ((p * p + INDEX_CONST) % tableLen).toInt()
        val bits = if (specLiteralRotation) 4 - ((idx and 7) shr 1) else (idx and 7) + 4
        return rotateLeftByte(table[idx].toInt() and 0xFF, bits)
    }

    override fun toString(): String =
        "QmcV1Cipher(tableLen=$tableLen, rotation=${if (specLiteralRotation) "spec" else "reference"})"

    companion object {
        /** 坐标回卷点，与 v2 MAP 一致。 */
        const val WRAP = 0x7FFF

        /** 下标常数，与 v2 MAP 一致（参考实现 71214）。 */
        const val INDEX_CONST = 71214

        /** `.qmc0` / `.qmc3` / `.qmcogg` 一族的表长。 */
        const val TABLE_LEN_0X80 = 0x80

        /** `.qmcflac` 的表长。 */
        const val TABLE_LEN_0X400 = 0x400

        /** 后缀 → 表长（APPENDIX_QMC_V1.md A.3）。`.qmc2` 待定，只能靠尾部 ekey 判别代次。 */
        fun tableLenForExt(ext: String): Int? = when (ext.lowercase()) {
            "qmc0", "qmc3", "qmcogg" -> TABLE_LEN_0X80
            "qmcflac" -> TABLE_LEN_0X400
            else -> null
        }

        fun rotateLeftByte(v: Int, bits: Int): Int {
            val r = bits and 7
            if (r == 0) return v and 0xFF
            val x = v and 0xFF
            return ((x shl r) or (x ushr (8 - r))) and 0xFF
        }

        /** 用一张已注入的表建 cipher；表为空或长度不合法直接抛「缺少静态 cipher 表」。 */
        @Throws(QmcV1UnsupportedException::class)
        fun create(table: ByteArray?, specLiteralRotation: Boolean = false): QmcV1Cipher {
            if (table == null || table.isEmpty()) {
                throw QmcV1UnsupportedException("缺少静态 cipher 表")
            }
            return QmcV1Cipher(table.copyOf(), table.size, specLiteralRotation)
        }

        /**
         * 按后缀建 cipher：先从 [QmcV1Table] 取表，取不到就抛。
         *
         * 【待验证】V4（TODO_VERIFY.md）：v1 静态表内容。
         * 本机没有任何 v1 样本，也没有任何权威表数据；此方法在表槽位为空时
         * 走 [QmcV1UnsupportedException] 这条**可降级分支**，由 [CipherFactory] 转成
         * "此格式暂不支持"的可读提示，而不是产出噪音。
         */
        @Throws(QmcV1UnsupportedException::class)
        fun forFile(file: File, specLiteralRotation: Boolean = false): QmcV1Cipher {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val expect = tableLenForExt(ext)
            val table = QmcV1Table.select(ext)
                ?: throw QmcV1UnsupportedException(
                    "缺少静态 cipher 表（后缀 .$ext${if (expect != null) "，预期表长 0x${expect.toString(16)}" else ""}）"
                )
            if (expect != null && table.size != expect) {
                throw QmcV1UnsupportedException("表长不匹配：.$ext 预期 0x${expect.toString(16)}，实际 0x${table.size.toString(16)}")
            }
            return create(table, specLiteralRotation)
        }
    }
}

/**
 * v1 静态 cipher 表的**可注入槽位**。默认全空 —— 本机没有权威表数据，
 * 这里不填任何编造内容（`APPENDIX_QMC_V1.md` A.5：塞一张错表比明确报"不支持"更难排查）。
 *
 * 使用方式（拿到表之后）：
 *
 * ```kotlin
 * QmcV1Table.MAP = byteArrayOf(...)          // 通用表（按后缀匹配不到时兜底）
 * QmcV1Table.install("qmcflac", bytes)       // 按后缀装一张 0x400 的表
 * ```
 *
 * [MAP] 与 [BY_EXT] 都是 `@Volatile`，可以在运行期（比如从 assets 或远端下发）装入。
 */
object QmcV1Table {

    /** 通用表槽位；null 表示本机没有表数据。 */
    @Volatile
    @JvmStatic
    var MAP: IntArray? = null

    /** 按后缀装表：`install("qmc0", intArrayOf(...))`。 */
    private val BY_EXT = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    /** 按后缀装入一张表（表内容是 0..255 的字节值）。 */
    @JvmStatic
    fun install(ext: String, table: IntArray) {
        BY_EXT[ext.lowercase()] = table.copyOf()
    }

    @JvmStatic
    fun install(ext: String, table: ByteArray) {
        BY_EXT[ext.lowercase()] = IntArray(table.size) { table[it].toInt() and 0xFF }
    }

    @JvmStatic
    fun clear() {
        BY_EXT.clear()
        MAP = null
    }

    /** 后缀对应的表；没有就返回 null（调用方据此抛 [QmcV1UnsupportedException]）。 */
    @JvmStatic
    fun select(ext: String): ByteArray? {
        BY_EXT[ext.lowercase()]?.let { arr -> return ByteArray(arr.size) { arr[it].toByte() } }
        val m = MAP ?: return null
        return ByteArray(m.size) { m[it].toByte() }
    }

    /** 当前是否有任何一张可用的表。 */
    @JvmStatic
    fun hasAny(): Boolean = MAP != null || BY_EXT.isNotEmpty()
}
