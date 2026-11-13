package com.j4.music.repo

import com.j4.music.crypto.BlockCipher
import com.j4.music.crypto.CipherFactory
import com.j4.music.source.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * 加密文件转译（.ncm / .QY2 / .mflac / .mgg 等 → 明文音频）。
 *
 * 实现完全走「流式 + 固定内存」：
 *  1) 打开源文件，用 CipherFactory.forFile 拿 BlockCipher（null = 本来就是明文）；
 *  2) 从 cipher.dataStart 开始，每次读 64KB，用 cipher.decrypt(buf, 0, n, absOffset) 就地解密
 *     —— absOffset 是「音频体内的明文坐标」，不是文件偏移；文件偏移由 dataStart 吃掉；
 *  3) 写到同名不同后缀的临时文件，完成后改名；
 *  4) 校验输出首字节是 fLaC / OggS / ID3 / RIFF / 0xFF 0xEx 之一；
 *  5) 校验通过才（可选）删源文件，并把结果写进「转译」歌单。
 *
 * 为什么内存上限这么重要：一首无损 30~80MB，一个歌单可能几十首，
 * 只要有一处把整个文件读进 ByteArray，低端机（heap 192MB）会在第二首就 OOM。
 * 所以本文件里没有任何「读全文件」的调用。
 *
 * 关于 crypto 包：本类直接引用 com.j4.music.crypto.CipherFactory / BlockCipher。
 * 同一次编译里它们已存在（CipherFactory.kt / BlockCipher.kt），所以不需要反射；
 * 万一将来那段代码被移走，运行时会抛 NoClassDefFoundError —— 已在 [isAvailable] 里兜住。
 */
object Transcode {

    const val PLAYLIST_NAME = "转译"

    /** 每块 64KB：与 DownloadManager 一致，也是 Android 上流式 IO 的性价比拐点 */
    private const val BUF_SIZE = 64 * 1024

    /** 判定输出是否合法的头部长度 */
    private const val PROBE_LEN = 16

    data class TranscodeResult(
        val input: String,
        val output: String?,
        val ok: Boolean,
        val error: String? = null,
        val bytes: Long = 0L,
        val extension: String = "",
        /** 源文件是否被删除（deleteSource=true 且成功时才可能为 true） */
        val sourceDeleted: Boolean = false
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "input" to input,
            "output" to output,
            "ok" to ok,
            "error" to error,
            "bytes" to bytes,
            "extension" to extension,
            "sourceDeleted" to sourceDeleted
        )
    }

    /** crypto 包是否可用（缺类时给出明确错误而不是崩在 NoClassDefFoundError） */
    fun isAvailable(): Boolean = RepoCodec.quiet {
        Class.forName("com.j4.music.crypto.CipherFactory", false, Transcode::class.java.classLoader)
        true
    } ?: false

    /** 这个文件看起来是不是需要转译的加密件（后缀兜底 + crypto 判定） */
    fun looksEncrypted(file: File): Boolean {
        if (!file.isFile) return false
        if (isAvailable()) {
            RepoCodec.quiet { CipherFactory.isEncrypted(file) }?.let { return it }
        }
        return RepoCodec.isEncryptedName(file.name)
    }

    /**
     * 批量转译。
     *
     * @param deleteSource 成功后是否删掉源文件（默认 false；真删了在结果里标出来）
     * @param addToPlaylist 是否把成功的结果累进「转译」歌单（默认 true）
     * @param onProgress (已完成数, 总数)，每首开始时回调一次，进度对 UI 线性可用
     */
    suspend fun translate(
        paths: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        deleteSource: Boolean = false,
        addToPlaylist: Boolean = true,
        outputDir: File? = null
    ): List<TranscodeResult> = withContext(Dispatchers.IO) {
        val total = paths.size
        val results = ArrayList<TranscodeResult>(total)
        if (total == 0) return@withContext results
        if (!isAvailable()) {
            onProgress(0, total)
            return@withContext paths.map {
                TranscodeResult(it, null, false, "crypto 包不可用（CipherFactory 缺失），无法转译")
            }
        }
        val dir = outputDir ?: RepoCodec.transcodeDir()
        RepoCodec.ensureDir(dir)

        for ((index, path) in paths.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(index, total)
            val r = translateOne(path, dir, deleteSource)
            results.add(r)
            onProgress(index + 1, total)
        }

        if (addToPlaylist) {
            // 单条失败不影响已成功的，整组写入后再刷新歌单
            val songs = results.filter { it.ok && it.output != null }.map { it.toSong() }
            if (songs.isNotEmpty()) {
                RepoCodec.quiet {
                    val repo = PlaylistRepository.shared
                    val id = repo.ensureByName(PLAYLIST_NAME, PlaylistRepository.KIND_CUSTOM)
                    repo.addSongs(id, songs)
                }
            }
        }
        results
    }

    /** 只转译一个文件 */
    suspend fun translateOne(path: String, outputDir: File? = null, deleteSource: Boolean = false): TranscodeResult =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val src = resolveFile(path)
                ?: return@withContext TranscodeResult(path, null, false, "文件不存在或无法访问")
            if (!src.isFile) return@withContext TranscodeResult(path, null, false, "不是文件")
            if (src.length() <= 0) return@withContext TranscodeResult(path, null, false, "空文件")
            if (!isAvailable()) {
                return@withContext TranscodeResult(path, null, false, "crypto 包不可用（CipherFactory 缺失）")
            }

            val cipher: BlockCipher? = RepoCodec.quiet { CipherFactory.forFile(src) }
            val ext = RepoCodec.quiet { CipherFactory.outputExtension(src) } ?: "bin"
            val dir = outputDir ?: RepoCodec.transcodeDir()
            RepoCodec.ensureDir(dir)

            val baseName = src.nameWithoutExtension
            val safeExt = if (ext.isBlank() || ext == "bin") "mp3" else ext.lowercase()
            val target = File(dir, "$baseName.$safeExt")
            val part = File(dir, "$baseName.$safeExt.part")

            // 已是明文：不重复解密，直接复制一份到转译目录（用户在界面上点过就给他一个结果文件），
            // 但目标已存在且大小相同时跳过，避免重复点按钮重复占空间。
            if (cipher == null) {
                if (target.isFile && target.length() == src.length()) {
                    return@withContext TranscodeResult(src.absolutePath, target.absolutePath, true, null, target.length(), safeExt)
                }
                val copied = RepoCodec.quiet { src.copyTo(target, overwrite = true) }
                return@withContext if (copied != null && copied.length() > 0) {
                    TranscodeResult(src.absolutePath, target.absolutePath, true, null, copied.length(), safeExt)
                } else {
                    RepoCodec.quiet { part.delete() }
                    TranscodeResult(src.absolutePath, null, false, "复制失败")
                }
            }

            try {
                val written = streamDecrypt(src, cipher, part)
                coroutineContext.ensureActive()
                if (written <= 0) {
                    part.delete()
                    return@withContext TranscodeResult(src.absolutePath, null, false, "解密后为空（密钥或格式不匹配）")
                }
                val head = readHead(part)
                val fmt = AudioSniff.format(head)
                if (fmt == null) {
                    part.delete()
                    return@withContext TranscodeResult(
                        src.absolutePath, null, false,
                        "输出首字节不是合法音频（${AudioSniff.hexHead(head)}），可能密钥不匹配"
                    )
                }
                // 后缀以真实头部为准：CipherFactory.outputExtension 对 .ncm 依赖元数据，
                // 元数据坏了会给出 mp3，而真实是 flac；这里用头部纠正一次，播放器才认。
                val corrected = File(dir, "$baseName.$fmt")
                if (corrected.exists()) corrected.delete()
                val okRename = RepoCodec.quiet { part.renameTo(corrected) } ?: false
                val out = if (okRename) corrected else {
                    // 改名失败：直接以 part 的名字留用（少数文件系统会拒绝同名替换）
                    RepoCodec.quiet {
                        part.inputStream().use { i -> corrected.outputStream().use { o -> i.copyTo(o, BUF_SIZE) } }
                        part.delete()
                    }
                    corrected
                }
                if (out == null || !out.isFile || out.length() <= 0) {
                    return@withContext TranscodeResult(src.absolutePath, null, false, "落盘失败")
                }
                var deleted = false
                if (deleteSource && out.length() > 0) {
                    deleted = RepoCodec.quiet { src.delete() } ?: false
                }
                TranscodeResult(
                    input = src.absolutePath,
                    output = out.absolutePath,
                    ok = true,
                    error = null,
                    bytes = out.length(),
                    extension = fmt,
                    sourceDeleted = deleted
                )
            } catch (e: CancellationException) {
                // 取消：清掉半截产物再向上抛，不让用户看到一个 8MB 的坏文件
                RepoCodec.quiet { part.delete() }
                throw e
            } catch (e: Throwable) {
                RepoCodec.quiet { part.delete() }
                TranscodeResult(src.absolutePath, null, false, e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * 流式解密：源文件从 dataStart 起逐块读出 → 就地解密 → 写入 dst。
     * 返回写入字节数。absOffset 从 0 开始累加（音频体内坐标）。
     */
    private fun streamDecrypt(src: File, cipher: BlockCipher, dst: File): Long {
        val bodyLen = src.length() - cipher.dataStart
        if (bodyLen < 0) throw IllegalArgumentException("dataStart 超出文件长度")
        var written = 0L
        val buf = ByteArray(BUF_SIZE)
        RandomAccessFile(src, "r").use { raf ->
            raf.seek(cipher.dataStart)
            dst.outputStream().use { out ->
                while (written < bodyLen) {
                    val want = minOf(BUF_SIZE.toLong(), bodyLen - written).toInt()
                    val n = raf.read(buf, 0, want)
                    if (n <= 0) break
                    cipher.decrypt(buf, 0, n, written)
                    out.write(buf, 0, n)
                    written += n
                }
                out.flush()
            }
        }
        return written
    }

    private fun readHead(f: File): ByteArray = RepoCodec.quiet {
        RandomAccessFile(f, "r").use { raf ->
            val n = minOf(raf.length(), PROBE_LEN.toLong()).toInt()
            val b = ByteArray(n)
            raf.readFully(b)
            b
        }
    } ?: ByteArray(0)

    /** 把 content:// 或路径统一变成可读 File；content 用 /proc 映射不可靠，这里只处理文件路径 */
    private fun resolveFile(path: String): File? {
        val f = RepoCodec.toFile(path)
        if (f != null && f.isFile) return f
        // 可能是裸路径
        if (!path.contains("://")) {
            val g = File(path)
            if (g.isFile) return g
        }
        return null
    }

    /** 转译结果 → Song（source="local"，id 用输出路径哈希，重名也能区分） */
    fun TranscodeResult.toSong(): Song {
        val out = output ?: return Song(
            id = "local:transcode:" + RepoCodec.shortId(input),
            source = "local", title = File(input).nameWithoutExtension, artist = "", album = "",
            durationMs = 0L, coverUrl = null, localPath = input
        )
        val f = File(out)
        val base = f.nameWithoutExtension
        val hyphen = base.lastIndexOf(" - ")
        val title = if (hyphen > 0) base.substring(0, hyphen).trim() else base
        val artist = if (hyphen > 0) base.substring(hyphen + 3).trim() else ""
        return Song(
            id = "local:transcode:" + RepoCodec.shortId(out),
            source = "local",
            title = title,
            artist = artist,
            album = "",
            durationMs = 0L,
            coverUrl = null,
            localPath = out,
            quality = if (f.extension.equals("flac", true)) "lossless" else "standard",
            extra = mapOf(
                "localUriKind" to "file",
                "transcodedFrom" to input,
                "size" to f.length().toString()
            )
        )
    }
}

/**
 * 输出文件头识别。
 *
 * 判据来自 FORMAT_DECRYPT.md 的校验规则：解密后的首字节必须是
 *   fLaC（FLAC）/ OggS（OGG 容器）/ ID3（MP3 带标签）/ RIFF（WAV）
 *   或者是 MPEG 帧同步 0xFF 0xEx（MP3 裸帧，第 2 字节高 3 位全 1）。
 * 这个校验是「密钥是否正确」的唯一低成本证据：密钥错时输出是随机字节，
 * 命中上面任意一条的概率约为 1/1000，连续多次转译都误判的可能性可以忽略。
 */
object AudioSniff {

    fun format(head: ByteArray): String? {
        if (head.size < 4) return null
        if (match(head, 0, "fLaC")) return "flac"
        if (match(head, 0, "OggS")) return "ogg"
        if (match(head, 0, "RIFF")) return "wav"
        if (match(head, 0, "MO3.")) return "mp3"
        if (match(head, 0, "ID3")) return "mp3"
        if (match(head, 0, "MAC ")) return "ape"
        if (match(head, 0, "wvpk")) return "wv"
        // MPEG 帧同步：第 0 字节 0xFF，第 1 字节高 3 位全 1
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return "mp3"
        // ADTS AAC：0xFFF1 / 0xFFF9
        if (b0 == 0xFF && (b1 and 0xF6) == 0xF0) return "aac"
        return null
    }

    private fun match(b: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > b.size) return false
        for (i in s.indices) {
            if (b[off + i] != s[i].code.toByte()) return false
        }
        return true
    }

    fun hexHead(b: ByteArray, max: Int = 8): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(max, b.size)) {
            val v = b[i].toInt() and 0xFF
            if (i > 0) sb.append(' ')
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
