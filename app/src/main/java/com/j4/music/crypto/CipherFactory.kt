package com.j4.music.crypto

import android.util.LruCache
import java.io.File
import java.io.RandomAccessFile

/**
 * 按文件挑解密器：**先魔数、再后缀、最后"像不像明文"**，三级判据。
 *
 * ## 为什么是这个顺序
 *
 * 魔数最可靠（`.ncm` 的 `CTEN` 不可能出现在明文音频里），
 * 但它只覆盖有头的两种格式；QMC 全系（含 `.QY2`）没有容器头，只能靠后缀。
 * 反过来只看后缀也不行：用户把 `.mflac` 改名成 `.mp3`、或者下载器把明文 `.flac` 存成 `.qmcflac`
 * 都真实存在，所以**后缀命中之后仍要验一次首字节**，一旦发现本来就是明文就直接返回 null。
 *
 * ## 缓存（规格 6.3 第 3 点）
 *
 * Media3 每次试探容器（读 16 字节判断格式）都会新建 DataSource，而 QMC v2 的密钥派生要
 * 解 704/364 字节的 ekey、做两轮 TEA，成本不低。所以这里用 [LruCache] 缓存
 * `File -> BlockCipher`（同一路径 + 同一长度 + 同一修改时间才复用），默认 32 项。
 *
 * 缓存键里带 `(length, lastModified)`：文件被重新下载覆盖后旧 cipher 自动失效，
 * 不会被错误复用。
 */
object CipherFactory {

    /**
     * 已知加密后缀 → 家族。
     *
     * 注意 `.qmc2` 同时存在 v1 与 v2：先按 v2 试（尾部有 `QQMusic Enc*` 才算数），
     * 失败再退回 v1 表槽位。
     */
    val ENCRYPTED_EXTS: Set<String> = setOf(
        "ncm",
        "qmc0", "qmc3", "qmcogg", "qmcflac", "qmc2",
        "mflac", "mflac0", "mgg", "mggl", "mmp3", "mmp4",
        "qy2"
    )

    /** 后缀 → 输出后缀（规格 2.5 与交付要求）。查不到返回 null。 */
    private val EXT_OUT = mapOf(
        "mflac" to "flac",
        "mflac0" to "flac",
        "mgg" to "ogg",
        "mggl" to "ogg",
        "mmp3" to "mp3",
        "mmp4" to "m4a",
        "qmcflac" to "flac",
        "qmcogg" to "ogg",
        "qmc0" to "mp3",
        "qmc3" to "mp3",
        "qmc2" to "ogg",
        "vpr" to "mp3",
        "qy2" to "ogg"
    )

    /** 默认缓存容量（项）。32 首同时播放/预载绰绰有余，且每项只是一把 key，不是整首歌。 */
    private const val DEFAULT_CACHE_ITEMS = 32

    /** 判魔数时读多少字节：16 字节覆盖 4 字节魔数 + MPEG 帧头 + 少量余量。 */
    private const val MAGIC_PROBE_LEN = 16

    private val cache = LruCache<CacheKey, BlockCipher>(DEFAULT_CACHE_ITEMS)

    private data class CacheKey(val path: String, val length: Long, val modified: Long)

    /** 清空缓存（测试或切换曲库时用）。 */
    @JvmStatic
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * 给一个文件挑解密器。
     *
     * @return 需要解密时返回对应的 [BlockCipher]；文件本来就是明文音频、
     *   或后缀不认识且魔数也对不上时返回 **null**（调用方据此走原文件路径）。
     */
    @JvmStatic
    fun forFile(file: File): BlockCipher? {
        if (!file.isFile) return null
        val key = CacheKey(file.absolutePath, file.length(), file.lastModified())
        cache.get(key)?.let { return it }
        val cipher = derive(file) ?: return null
        cache.put(key, cipher)
        return cipher
    }

    /**
     * 派生用的强异常版本：明确知道是加密格式但解不了时抛异常（缺表、ekey 坏等），
     * 而不是静默返回 null。上层要"报具体原因"时用这个。
     */
    @JvmStatic
    @Throws(CipherException::class)
    fun requireCipher(file: File): BlockCipher =
        forFile(file) ?: throw CipherException("文件不需要解密或不是已知加密格式：${file.name}")

    /**
     * @return true 表示"这个文件必须解密后才能播"。
     *
     * 判据与 [forFile] 一致但更便宜：先看后缀，再抽首字节确认不是明文音频。
     */
    @JvmStatic
    fun isEncrypted(file: File): Boolean {
        if (!file.isFile) return false
        val ext = extOf(file)
        if (ext !in ENCRYPTED_EXTS) {
            // 后缀不认识：仍可能被改名成 .mp3 的加密文件，靠魔数兜一次
            return familyByMagic(file) != null
        }
        if (familyByMagic(file) != null) return true
        // 后缀说是加密的，但首字节就是明文音频 → 其实是明文（改名而来），不必解密
        return !looksPlain(file)
    }

    /**
     * 解密后应有的后缀。`.ncm` 按 meta.format、`.qy2` 固定 ogg，其余查表。
     *
     * @return 形如 `"flac"`（不带点）；无法判断时返回 `"bin"`。
     */
    @JvmStatic
    fun outputExtension(file: File): String {
        val ext = extOf(file)
        if (ext == "ncm") {
            val meta = runCatching { NcmCipher.parse(file).meta }.getOrNull()
            meta?.audioExtension()?.let { return it }
            val m = meta?.format?.lowercase()?.trim()
            if (!m.isNullOrEmpty()) return m
            return "mp3"
        }
        if (ext == "qy2") return "ogg"
        EXT_OUT[ext]?.let { return it }
        // 后缀不认识就按解密后的首字节猜
        val c = forFile(file) ?: return "bin"
        return runCatching {
            val head = ByteArray(MAGIC_PROBE_LEN)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(c.dataStart)
                val n = raf.read(head)
                if (n <= 0) return@runCatching null
                c.decrypt(head, 0, n, 0L)
                CryptoPrimitives.plainAudioFormat(head, 0, n)
            }
        }.getOrNull() ?: "bin"
    }

    /**
     * 只做后缀映射（不看文件内容），给自检与上层预览用。
     *
     * @return 形如 `"flac"`；后缀不在表里返回 `"bin"`。
     */
    @JvmStatic
    fun extensionOutForTest(ext: String): String {
        val e = ext.trim().removePrefix(".").lowercase()
        if (e == "qy2") return "ogg"
        if (e == "ncm") return "mp3"          // 真实值取决于 meta.format，这里给默认
        return EXT_OUT[e] ?: "bin"
    }

    // ---------------------------------------------------------------- 内部

    /** 家族判定用的枚举，只为让派生流程读起来清楚。 */
    private enum class Family { NCM, QMC_V2, QY2, QMC_V1 }

    private fun derive(file: File): BlockCipher? {
        // 1) 魔数优先
        when (familyByMagic(file)) {
            Family.NCM -> return NcmCipher.parse(file)
            // 尾部 ekey 这种"魔数"只有读尾部才知道，交给下面的后缀分支
            else -> Unit
        }

        // 2) 后缀
        val ext = extOf(file)
        val byExt = familyByExt(file, ext) ?: return null
        if (byExt != Family.QMC_V1 && byExt != Family.QY2 && looksPlain(file)) {
            // 后缀像加密、内容却是明文：改名过的明文文件，直接当明文播
            return null
        }
        return when (byExt) {
            Family.QMC_V2 -> QmcV2Cipher.fromFile(file)
            Family.QY2 -> Qy2Cipher.fromFile(file)
            Family.QMC_V1 -> QmcV1Cipher.forFile(file)
            Family.NCM -> NcmCipher.parse(file)
        }
    }

    /** 后缀 → 家族（不看文件内容）。[file] 只用于 `.qmc2` 需要读尾部判别代次的情形。 */
    private fun familyByExt(file: File, ext: String): Family? = when (ext) {
        "ncm" -> Family.NCM
        "mflac", "mflac0", "mgg", "mggl", "mmp3", "mmp4" -> Family.QMC_V2
        "qy2" -> Family.QY2
        "qmc0", "qmc3", "qmcogg", "qmcflac" -> Family.QMC_V1
        "qmc2" -> {
            // `.qmc2` 两代并存：尾部有 QQMusic Enc* 就走 v2，否则按 v1
            if (hasQmcEkey(file)) Family.QMC_V2 else Family.QMC_V1
        }
        else -> null
    }

    /** 后缀 → 家族（不看文件内容）。[file] 只用于 `.qmc2` 需要读尾部判别代次的情形。 */
    private fun familyByExt(ext: String, file: File): Family? = familyByExt(ext, file)

    /** 只看文件头就能确定的家族：目前只有 NCM。 */
    private fun familyByMagic(file: File): Family? {
        val head = readHead(file)
        if (head.size >= 4 && CryptoPrimitives.eqBytes(head, 0, NcmCipher.MAGIC, 0, 4)) return Family.NCM
        return null
    }

    private fun hasQmcEkey(file: File): Boolean = runCatching {
        val len = file.length()
        val take = minOf(len, QmcV2Cipher.TAIL_SCAN.toLong()).toInt()
        if (take <= 0) return@runCatching false
        val tail = ByteArray(take)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - take)
            raf.readFully(tail)
        }
        QmcV2Cipher.findEkey(tail) != null
    }.getOrDefault(false)

    private fun readHead(file: File): ByteArray = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val take = minOf(MAGIC_PROBE_LEN.toLong(), raf.length()).toInt()
            val b = ByteArray(take)
            raf.readFully(b)
            b
        }
    }.getOrDefault(ByteArray(0))

    /** 首字节是否已经是明文音频。 */
    private fun looksPlain(file: File): Boolean =
        CryptoPrimitives.looksLikePlainAudio(readHead(file))

    private fun extOf(file: File): String = file.name.substringAfterLast('.', "").lowercase()
}
