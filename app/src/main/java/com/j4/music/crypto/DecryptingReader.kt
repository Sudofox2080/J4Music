package com.j4.music.crypto

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * "边读边解密"的随机访问读取器（规格 6.2 的骨架，按 Kotlin 落地）。
 *
 * ## 关键约定
 *
 * `readAt(decPos, ...)` 的 [decPos] 是 **明文坐标**（音频体第 0 字节 → 0），
 * 文件绝对偏移由 `dataStart + decPos` 算出来，`cipher.decrypt` 的第四个参数
 * 收到的是同一个 `decPos` 而不是文件偏移 —— 因为所有格式的异或流都是从音频体第一个字节开始计数的。
 *
 * 这一条是整个可 seek 能力的根基：NCM 的 keybox 周期 256、QMC MAP 的 0x7FFF 周期、
 * QMC RC4 的 5120 分段，全部在 `decPos` 上计算都成立，
 * `tools/streamcheck.py` 与 [CryptoProbe] 都用"分块 / 乱序 seek / 单字节边界读"对比一次性解完验证过。
 *
 * 用 `RandomAccessFile.readFully` 直接读，不套 `BufferedInputStream`（规格 6.4）：
 * 后者 seek 会失效，而 Media3 一定会 seek。
 */
class DecryptingReader(
    file: File,
    private val cipher: BlockCipher
) : Closeable {

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")

    /** 文件总长度（含被加密头部）。 */
    val fileLength: Long = runCatching { raf.length() }.getOrElse { 0L }

    /** 音频体在文件里的起点。 */
    val dataStart: Long = cipher.dataStart

    /** 明文总长度 = 文件长度 - 音频体起点（QMC v2 没有长度字段，只能这样推）。 */
    val size: Long = maxOf(0L, fileLength - dataStart)

    /**
     * 从明文坐标 [decPos] 读最多 [len] 字节到 `dst[off..]`。
     *
     * @return 实际读到的字节数；[decPos] 已达末尾时返回 -1（与 `InputStream.read` 语义一致，
     *   方便 DataSource 直接映射成 `C.RESULT_END_OF_INPUT`）。
     */
    @Throws(IOException::class)
    fun readAt(decPos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        val filePos = dataStart + decPos
        val avail = fileLength - filePos
        if (avail <= 0) return -1
        val n = minOf(len.toLong(), avail).toInt()
        raf.seek(filePos)
        raf.readFully(dst, off, n)
        // 注意第三个参数传的是明文坐标 decPos，不是 filePos
        cipher.decrypt(dst, off, n, decPos)
        return n
    }

    /**
     * 便利方法：一次性取回 `[decPos, decPos + len)` 的明文（越过末尾时返回能读到的部分）。
     * 自检工具与"只看前 N 字节判断格式"的场景用。
     */
    @Throws(IOException::class)
    fun readRange(decPos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val r = readAt(decPos + got, out, got, len - got)
            if (r <= 0) break
            got += r
        }
        return if (got == len) out else out.copyOf(got)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}
