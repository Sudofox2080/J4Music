package com.j4.music.crypto

/**
 * 加密音频格式的"块级就地解密"契约。
 *
 * 之所以不用传统的 `InputStream`，而是定义"可随机访问的块解密"：
 * 1. 这一族格式（NCM / QMC v1 / QMC v2）的音频体全部是**流式异或**类变换，
 *    没有分组密码的填充与扩散，`解密后的第 n 字节` 只依赖 `(key, n)`；
 * 2. Media3 拖动进度条时会从任意位置 `open`，顺序流失效；
 * 3. 解密后的总长度在 QMC v2 里根本不存在于文件里（没有长度字段），
 *    只能按"文件长度 - 音频体起点"推算。
 *
 * 因此唯一的不动点是：解密函数必须只依赖 `absOffset`，而 `absOffset` 是
 * **音频体内的明文坐标**（音频体第 0 字节 → 0），不是文件绝对偏移。
 * 文件绝对偏移由 [dataStart] 一次性吸收掉。
 *
 * 调用方（[DecryptingReader]）保证：
 * - 传入的 `absOffset` 是 `buf[off]` 这一个字节在音频体内的明文坐标；
 * - 只会在 `absOffset + len <= 音频体长度` 的范围内调用；
 * - 允许任意块长、任意起点、单字节边界读，结果必须与一次性解完逐字节相同
 *   （`streamcheck.py` 验过的数学前提，[CryptoProbe] 里也有对应的 Java 版断言）。
 */
interface BlockCipher {

    /**
     * 音频体（解密后的数据）在文件里的起始字节偏移。
     *
     * NCM 是 `0x0C + keyLen + 4 + metaLen + 4 + 2 + 4 + imgLen`，
     * QMC v2 与 QMC v1 是 0（整个文件都是音频体）。
     */
    val dataStart: Long

    /**
     * 就地解密 `buf[off, off + len)`。
     *
     * @param absOffset 该块在**音频体内**的起始明文坐标（不是文件偏移）。
     */
    fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long)
}

/**
 * 解密过程中遇到的、可以明确归因的失败。
 *
 * 单独分出来是为了让上层（[CipherFactory] / [DecryptingDataSource]）能把
 * "这不是加密格式"和"这是加密格式但缺表/缺数据"区分开，给出不同的降级路径。
 */
open class CipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** NCM 头部不合法（魔数对但结构读不通、keyLen 荒谬、key 明文前缀自校验失败等）。 */
class NcmFormatException(message: String, cause: Throwable? = null) : CipherException(message, cause)

/** QMC v1 缺少静态 cipher 表。见 [QmcV1Table] 与 APPENDIX_QMC_V1.md。 */
class QmcV1UnsupportedException(message: String) : CipherException(message)

/** `.QY2` 既不像 QQ v2，也解不出合法 key，按未知格式处理（规格第 4 节第 3 条）。 */
class Qy2UnknownException(message: String) : CipherException(message)

/** QQ v2 密钥派生链某一步失败（ekey 缺失、base64 不合法、TEA 校验失败等）。 */
class QmcKeyException(message: String, cause: Throwable? = null) : CipherException(message, cause)
