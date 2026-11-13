package com.j4.music.crypto

import java.io.File
import java.io.RandomAccessFile
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Base64

/**
 * 网易云音乐 `.ncm` 解密器（规格 FORMAT_DECRYPT.md 第 1 节完整实现）。
 *
 * ## 文件布局（偏移全部是文件绝对偏移，从 0 开始，整数小端）
 *
 * ```
 * 0x00      8    magic + gap           前 4 字节 43 54 45 4E = "CTEN"；后 4 字节固定只跳不判
 * 0x08      4    keyLen   (uint32 LE)  keyData 字节数，必为 16 的倍数
 * 0x0C  keyLen   keyData               RC4(CORE_KEY) 之后再 AES-128-ECB(CORE_KEY)
 * +0        4    metaLen  (uint32 LE)  加密元数据长度，16 的倍数
 * +4    metaLen  metaData              AES-128-ECB(META_KEY) 的 "163 key(Don't modify):"+b64(json)
 * +0        4    crc32                 直接跳过，不校验
 * +4        2    gap                   固定 00 00，跳过
 * +2        4    imgLen   (uint32 LE)  封面字节数
 * +4     imgLen  img                   AES-128-ECB 的 JPEG/PNG 原字节
 * 剩余            audio                 keybox 流异或
 * ```
 *
 * 头长是 **8 + 4**，`keyLen` 在偏移 8 —— 不是历史上常见的 "8+2+4"。写成 8+2+4 会把
 * gap 里的 0x01 0x70 读成 keyLen 的低两字节，得到 4 亿级别的荒谬长度（规格 1.8 第一条坑）。
 * 本类在 `keyLen > 4096` 时直接抛 [NcmFormatException]「不是合法 NCM」。
 */
class NcmCipher(
    /** 音频体在文件里的起始偏移 = 0x0C + keyLen + 4 + metaLen + 4 + 2 + 4 + imgLen。 */
    override val dataStart: Long,
    val keyLen: Int,
    val metaLen: Int,
    val imageLen: Int,
    /** 解出来的音频体密钥种子（`key` 明文去掉 17 字节前缀之后的 key_raw）。 */
    val keyRaw: ByteArray,
    /** 解析出来的元数据；meta 解不出来时为 null，不影响音频体解密。 */
    val meta: NcmMeta?
) : BlockCipher {

    /** 256 字节的自建置换表，音频体按 `(absOffset + i) & 0xFF` 取下标异或。 */
    private val keyBox: ByteArray = ncmKeyBox(keyRaw)

    override fun decrypt(buf: ByteArray, off: Int, len: Int, absOffset: Long) {
        require(len >= 0 && off >= 0 && off + len <= buf.size) { "buf 越界: off=$off len=$len size=${buf.size}" }
        if (len == 0) return
        // 起始下标先取模，之后逐字节自增，避免每字节一次 long 与 0xFF 运算
        var kb = (absOffset and 0xFF).toInt()
        val box = keyBox
        for (i in 0 until len) {
            buf[off + i] = (buf[off + i].toInt() xor box[kb].toInt()).toByte()
            kb = (kb + 1) and 0xFF
        }
    }

    override fun toString(): String =
        "NcmCipher(dataStart=$dataStart, keyLen=$keyLen, metaLen=$metaLen, imgLen=$imageLen, " +
            "meta=${if (meta != null) "已解析" else "无"})"

    companion object {

        /** 文件前 4 字节的魔数 `43 54 45 4E`（小端读作 0x4354454E，ASCII "CTEN"）。 */
        val MAGIC: ByteArray = byteArrayOf(0x43, 0x54, 0x45, 0x4E)

        /** `hzHRAmso5kInbaxW`：RC4 与 AES-128-ECB 共用。用 hex 写，避免手抄大小写出错。 */
        val CORE_KEY: ByteArray = CryptoPrimitives.hexToBytes("687A4852416D736F356B496E62617857")

        /** `#14ljk_!\]&0U<'(`：解 metaData 的 AES-128 密钥（含反斜杠方括号单引号，绝不手抄 ASCII）。 */
        val META_KEY: ByteArray = CryptoPrimitives.hexToBytes("2331346C6A6B5F215C5D2630553C2728")

        /** 22 字节：meta 明文的前缀，同时是整段 meta 明文的 XOR 掩码。 */
        val XOR_KEY: ByteArray = CryptoPrimitives.hexToBytes("313633206B657928446F6E2774206D6F64696679293A")

        /** 17 字节：keyData 明文的固定前缀，用于自校验（注意是 17 不是 16）。 */
        val KEY_PREFIX: ByteArray = "neteasecloudmusic".toByteArray(Charsets.US_ASCII)

        /** JPEG 魔数 FFD8 与 PNG 魔数 8950，用来判断封面解对没解对。 */
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50)

        /** 元数据 JSON 常见键（网易云各版本客户端写法略有差异）。 */
        private val NAME_KEYS = arrayOf("musicName", "music_name", "name")
        private val ARTIST_KEYS = arrayOf("artist", "artists", "singer")
        private val ALBUM_KEYS = arrayOf("album", "albumName", "album_name")
        private val FORMAT_KEYS = arrayOf("format", "musicFormat")
        private val DURATION_KEYS = arrayOf("duration", "durationMs", "duration_ms")
        private val BITRATE_KEYS = arrayOf("bitrate", "br", "bitRate")

        /** keyLen 的合理上限；超过就说明头布局读错了（规格 1.8）。 */
        const val MAX_KEY_LEN = 4096

        /** 解析头部 + 解 key + 解 meta + 解封面。任何一步读不通都抛明确异常。 */
        @Throws(NcmFormatException::class)
        fun parse(file: File): NcmCipher = HeadReader.from(file).use { parseReader(it) }

        /**
         * 直接解析已在内存里的整段（或至少是头部 + meta + 封面）字节。
         * [CryptoProbe] 的合成往返测试走这条路径，不需要落盘。
         */
        @Throws(NcmFormatException::class)
        fun parse(bytes: ByteArray): NcmCipher = HeadReader.from(bytes).use { parseReader(it) }

        private fun parseReader(h: HeadReader): NcmCipher = parseBuffer(h::readAt)

        /**
         * 统一的解析主流程。`readAt(fileOffset, dst, dstOff, n)` 返回实际读到的字节数，
         * 读到文件尾返回 -1，用来同时支持 RandomAccessFile 与内存字节数组。
         *
         * 注意：这个函数**不能**是 `inline` —— 里面用了局部函数 `read(...)`，
         * Kotlin 不允许 inline 函数体内声明局部函数（K2 报
         * "Local functions are not yet supported in inline functions"）。
         */
        private fun parseBuffer(
            readAt: (Long, ByteArray, Int, Int) -> Int
        ): NcmCipher {
            fun read(off: Long, n: Int): ByteArray = readExact(readAt, off, n)

            // ---- 1. magic + gap（8 字节），keyLen 在偏移 8
            val head = read(0L, 12)
            if (!CryptoPrimitives.eqBytes(head, 0, MAGIC, 0, 4)) {
                throw NcmFormatException("不是合法 NCM：前 4 字节不是 CTEN（实际 ${CryptoPrimitives.bytesToHex(head, 0, 4)}）")
            }
            val keyLenL = CryptoPrimitives.u32le(head, 8)
            if (keyLenL <= 0 || keyLenL > MAX_KEY_LEN || keyLenL % 16L != 0L) {
                throw NcmFormatException(
                    "不是合法 NCM：keyLen=$keyLenL（偏移 8，要求 1..$MAX_KEY_LEN 且为 16 的倍数；" +
                        "出现 4 亿量级说明头布局按 8+2+4 读错了）"
                )
            }
            val keyLen = keyLenL.toInt()

            // ---- 2. keyData：先 RC4(CORE_KEY)，再 AES-128-ECB(CORE_KEY) 去 PKCS#7
            val keyBlob = read(12L, keyLen)
            val keyPlain = try {
                val rc4ed = CryptoPrimitives.rc4(CORE_KEY, keyBlob)
                CryptoPrimitives.aesEcbDecryptPkcs5(CORE_KEY, rc4ed)
            } catch (e: Exception) {
                throw NcmFormatException("NCM keyData 解密失败（RC4 后 AES）：${e.message}", e)
            }
            if (!CryptoPrimitives.eqBytes(keyPlain, 0, KEY_PREFIX, 0, KEY_PREFIX.size)) {
                throw NcmFormatException(
                    "不是合法 NCM：key 明文前缀自校验失败（期望 neteasecloudmusic，实际 " +
                        "${CryptoPrimitives.bytesToHex(keyPlain, 0, minOf(17, keyPlain.size))}）"
                )
            }
            val keyRaw = keyPlain.copyOfRange(KEY_PREFIX.size, keyPlain.size)

            // ---- 3. metaLen + metaData
            var pos = 12L + keyLen
            val metaLenL = CryptoPrimitives.u32le(read(pos, 4), 0)
            pos += 4
            if (metaLenL > 0x10000L || metaLenL % 16L != 0L) {
                throw NcmFormatException("不是合法 NCM：metaLen=$metaLenL（要求 16 的倍数且 <= 65536）")
            }
            val metaLen = metaLenL.toInt()
            val metaEnc = read(pos, metaLen)
            pos += metaLen
            val meta = runCatching { parseMeta(metaEnc) }.getOrNull()

            // ---- 4. crc32(4) + gap(2) + imgLen(4) + img
            pos += 4 + 2
            val imgLenL = CryptoPrimitives.u32le(read(pos, 4), 0)
            pos += 4
            if (imgLenL > 0x6000000L) {
                throw NcmFormatException("不是合法 NCM：imgLen=$imgLenL 超出合理范围")
            }
            val imgLen = imgLenL.toInt()
            val imgEnc = if (imgLen > 0) read(pos, imgLen) else ByteArray(0)
            pos += imgLen

            val cover = if (imgLen > 0) decryptCover(imgEnc) else null

            val finalMeta = if (meta == null) {
                if (cover == null) null
                else NcmMeta(null, emptyList(), null, null, null, null, cover.first, cover.second)
            } else if (cover != null) {
                meta.copy(cover = cover.first, coverExt = cover.second)
            } else {
                meta
            }

            return NcmCipher(
                dataStart = pos,
                keyLen = keyLen,
                metaLen = metaLen,
                imageLen = imgLen,
                keyRaw = keyRaw,
                meta = finalMeta
            )
        }

        private fun readExact(readAt: (Long, ByteArray, Int, Int) -> Int, off: Long, n: Int): ByteArray {
            if (n == 0) return ByteArray(0)
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = readAt(off + got, out, got, n - got)
                if (r <= 0) throw NcmFormatException("不是合法 NCM：在偏移 $off 处需要 $n 字节，实际只读到 $got")
                got += r
            }
            return out
        }

        // ------------------------------------------------------------ meta

        /**
         * 解 metaData：AES-128-ECB(META_KEY) → 去填充 → 整段 XOR [XOR_KEY] →
         * 校验前 22 字节还原成 `163 key(Don't modify):` → 其余部分 base64 解成 JSON。
         */
        fun parseMeta(metaEnc: ByteArray): NcmMeta {
            if (metaEnc.isEmpty()) return emptyMeta()
            val plain = CryptoPrimitives.aesEcbDecryptPkcs5(META_KEY, metaEnc)
            for (i in plain.indices) plain[i] = (plain[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
            if (!CryptoPrimitives.eqBytes(plain, 0, XOR_KEY, 0, XOR_KEY.size)) {
                throw NcmFormatException("NCM meta 自校验失败：XOR 后前 22 字节不是 163 key(Don't modify):")
            }
            val b64 = plain.copyOfRange(XOR_KEY.size, plain.size)

            // 【待验证】V1（TODO_VERIFY.md）：少数老版本 NCM 的 meta 用了自定义 base64 换表，
            // 把标准表的 0x40 段换成了 0x14 段。本机没有任何样本能证实这一步，因此保留为
            // 可降级分支：标准 Base64 抛异常时再试一次自定义换表，只有能解出 '{' 开头的 JSON 才采纳。
            val json = decodeJsonLenient(b64) ?: throw NcmFormatException("NCM meta base64 解不开（标准表与 0x40↔0x14 换表都失败）")
            return parseMetaJson(json)
        }

        private fun emptyMeta(): NcmMeta =
            NcmMeta(null, emptyList(), null, null, null, null, null, null)

        private fun decodeJsonLenient(b64: ByteArray): String? {
            runCatching {
                val s = String(Base64.getDecoder().decode(stripNonBase64(b64)), Charsets.UTF_8)
                if (s.trimStart().startsWith("{")) return s
            }
            runCatching {
                val s = String(Base64.getDecoder().decode(stripNonBase64(remapLegacyBase64(b64))), Charsets.UTF_8)
                if (s.trimStart().startsWith("{")) return s
            }
            return null
        }

        private fun stripNonBase64(b: ByteArray): ByteArray {
            var n = 0
            for (x in b) {
                val c = x.toInt() and 0xFF
                if ((c in 0x41..0x5A) || (c in 0x61..0x7A) || (c in 0x30..0x39) ||
                    c == 0x2B || c == 0x2F || c == 0x3D
                ) n++
            }
            val out = ByteArray(n)
            var k = 0
            for (x in b) {
                val c = x.toInt() and 0xFF
                if ((c in 0x41..0x5A) || (c in 0x61..0x7A) || (c in 0x30..0x39) ||
                    c == 0x2B || c == 0x2F || c == 0x3D
                ) out[k++] = x
            }
            return out
        }

        private const val STD_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private const val LEGACY_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabdcefghijklmnopqrstwxvyz0123456789+/"

        /** 把旧版自定义表映射回标准表（只做字符到字符的重编码，不改变语义）。 */
        private fun remapLegacyBase64(b: ByteArray): ByteArray {
            val out = ByteArray(b.size)
            for (i in b.indices) {
                val c = (b[i].toInt() and 0xFF).toChar()
                val pos = LEGACY_B64.indexOf(c)
                out[i] = if (pos >= 0) STD_B64[pos].code.toByte() else b[i]
            }
            return out
        }

        /**
         * 最小 JSON 解析：只取 NcmMeta 需要的字段，不引第三方库。
         * 字段名兼容网易云各版本客户端见过的几种写法。
         */
        internal fun parseMetaJson(json: String): NcmMeta {
            val j = JsonMini(json)
            val name = j.firstStr(*NAME_KEYS)
            val album = j.firstStr(*ALBUM_KEYS)
            val format = j.firstStr(*FORMAT_KEYS)
            val durationMs = j.firstLong(*DURATION_KEYS)
            val bitrate = j.firstLong(*BITRATE_KEYS)
            val artist = j.firstArtists(*ARTIST_KEYS)
            return NcmMeta(name, artist, album, format, durationMs, bitrate, null, null)
        }

        // ------------------------------------------------------------ cover

        /**
         * 解封面。规格 1.7 的【待验证】项：部分客户端版本用 CORE_KEY 或 AES-256-ECB 加封面。
         * 这里保留为**可降级分支**：先 META_KEY，再 CORE_KEY，各自 noPadding 解一遍，
         * 以 JPEG（FF D8）/ PNG（89 50）魔数作为判据；都失败返回 null（不抛，封面不是必需）。
         */
        fun decryptCover(imgEnc: ByteArray): Pair<ByteArray, String?>? {
            if (imgEnc.isEmpty()) return null
            val data = if (imgEnc.size % 16 == 0) imgEnc else imgEnc.copyOf(imgEnc.size / 16 * 16)
            if (data.isEmpty()) return null
            for (key in arrayOf(META_KEY, CORE_KEY)) {
                val out = runCatching { CryptoPrimitives.aesEcbDecryptNoPadding(key, data) }.getOrNull() ?: continue
                if (CryptoPrimitives.eqBytes(out, 0, JPEG_MAGIC, 0, 2)) return trimPkcs(out) to "jpg"
                if (CryptoPrimitives.eqBytes(out, 0, PNG_MAGIC, 0, 2)) return trimPkcs(out) to "png"
            }
            return null
        }

        /** 若尾部是合法 PKCS#7 填充就剥掉，否则原样返回（封面不是分组密码的流式产物时也能用）。 */
        private fun trimPkcs(b: ByteArray): ByteArray {
            if (b.isEmpty()) return b
            val pad = b[b.size - 1].toInt() and 0xFF
            if (pad in 1..16 && pad <= b.size) {
                var ok = true
                for (i in 0 until pad) if ((b[b.size - 1 - i].toInt() and 0xFF) != pad) { ok = false; break }
                if (ok) return b.copyOf(b.size - pad)
            }
            return b
        }

        // ------------------------------------------------------------ keybox

        /**
         * 网易云自建的 keybox 置换（**不是**标准 RC4-KSA，必须逐字节照抄）。
         *
         * 错误不会报错，只会产出噪音，所以 [CryptoProbe] 里加了"输出必须是 0..255 置换"的断言。
         */
        fun ncmKeyBox(key: ByteArray): ByteArray {
            require(key.isNotEmpty()) { "NCM key_raw 不能为空" }
            val box = ByteArray(256) { it.toByte() }
            var c = 0
            var last = 0
            for (i in 0 until 256) {
                val v = box[i].toInt() and 0xFF
                c = (v + last + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                box[i] = box[c]
                box[c] = v.toByte()
                last = c
            }
            return box
        }

        // ------------------------------------------------------------ ekey 辅助

        /**
         * NCM 的 `ekey`（有些下载器会附在 URL 参数里）里可能带 `%xx` 和空格，
         * 先 unquote 再把空格换成 `+` 才能正确 base64 解。这个方法给上层取播放地址的代码复用。
         */
        fun unquoteEkey(raw: String): String {
            var s = raw.trim()
            try {
                s = URLDecoder.decode(s, "UTF-8")
            } catch (e: UnsupportedEncodingException) {
                // UTF-8 一定存在，这里只是不让异常逃出去
            }
            return s.replace(' ', '+')
        }
    }
}

/**
 * NCM 元数据。字段对应规格 1.5 里 JSON 的常见键：
 * `musicName` / `artist`（二维数组，这里拍平成名字列表）/ `album` / `format` / `duration` / `bitrate`。
 *
 * `cover` 是**已解密的**图片原字节，可能为 null（imgLen = 0 或两把钥匙都解不对）；
 * `coverExt` 是 "jpg" / "png"。
 *
 * 用普通 class + 手写 equals/hashCode 而不是 data class：`cover` 是 ByteArray，
 * data class 的自动 equals 只比引用，缓存与测试里都会误判。
 */
class NcmMeta(
    val name: String?,
    val artist: List<String>,
    val album: String?,
    val format: String?,
    val durationMs: Long?,
    val bitrate: Long?,
    val cover: ByteArray?,
    val coverExt: String?
) {
    /** 第一个歌手名字，方便直接当"作者"展示。 */
    val primaryArtist: String? get() = artist.firstOrNull()

    /** `["a","b"]` → `a / b`；没有歌手返回 null。 */
    val artistText: String? get() = if (artist.isEmpty()) null else artist.joinToString(" / ")

    fun copy(
        name: String? = this.name,
        artist: List<String> = this.artist,
        album: String? = this.album,
        format: String? = this.format,
        durationMs: Long? = this.durationMs,
        bitrate: Long? = this.bitrate,
        cover: ByteArray? = this.cover,
        coverExt: String? = this.coverExt
    ): NcmMeta = NcmMeta(name, artist, album, format, durationMs, bitrate, cover, coverExt)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NcmMeta) return false
        return name == other.name && artist == other.artist && album == other.album &&
            format == other.format && durationMs == other.durationMs && bitrate == other.bitrate &&
            coverExt == other.coverExt &&
            ((cover == null && other.cover == null) || (cover != null && other.cover != null && cover.contentEquals(other.cover)))
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + artist.hashCode()
        r = 31 * r + (album?.hashCode() ?: 0)
        r = 31 * r + (format?.hashCode() ?: 0)
        r = 31 * r + ((durationMs ?: 0L).hashCode())
        r = 31 * r + ((bitrate ?: 0L).hashCode())
        r = 31 * r + (cover?.contentHashCode() ?: 0)
        r = 31 * r + (coverExt?.hashCode() ?: 0)
        return r
    }

    override fun toString(): String =
        "NcmMeta(name=$name, artist=$artist, album=$album, format=$format, durationMs=$durationMs, " +
            "bitrate=$bitrate, cover=${cover?.size ?: 0}B/$coverExt)"

    /**
     * 输出文件后缀：优先用 meta 里的 `format`（规格 6.5/交付要求：ncm→按 meta.format），
     * format 缺失或不可信时返回 null，由 [CipherFactory.outputExtension] 兜底。
     */
    fun audioExtension(): String? {
        val f = format?.lowercase()?.trim() ?: return null
        return when (f) {
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "ape" -> if (f == "opus") "ogg" else f
            else -> null
        }
    }
}

/** 极简 JSON 读取器：只支持"按键名取标量/数组"，够读 NCM meta 用，不引第三方库。 */
internal class JsonMini(private val src: String) {

    fun str(key: String): String? {
        val i = indexOfKey(key) ?: return null
        return readStringAt(i)
    }

    fun long(key: String): Long? {
        val i = indexOfKey(key) ?: return null
        var p = i
        while (p < src.length && src[p].isWhitespace()) p++
        val start = p
        if (p < src.length && (src[p] == '-' || src[p] == '+')) p++
        while (p < src.length && src[p].isDigit()) p++
        if (p == start) return null
        return src.substring(start, p).toLongOrNull()
    }

    fun firstStr(vararg keys: String): String? {
        for (k in keys) str(k)?.let { if (it.isNotBlank()) return it }
        return null
    }

    fun firstLong(vararg keys: String): Long? {
        for (k in keys) long(k)?.let { return it }
        return null
    }

    fun firstArtists(vararg keys: String): List<String> {
        for (k in keys) {
            val a = artists(k)
            if (a.isNotEmpty()) return a
        }
        return emptyList()
    }

    /** artist 是二维数组 `[["名字", id], ...]`；也兼容一维字符串数组。 */
    fun artists(key: String): List<String> {
        val i = indexOfKey(key) ?: return emptyList()
        var p = i
        while (p < src.length && src[p].isWhitespace()) p++
        if (p >= src.length || src[p] != '[') return emptyList()
        val close = matchBracket(p, '[', ']') ?: return emptyList()
        val body = src.substring(p + 1, close)
        val out = ArrayList<String>()
        var q = 0
        while (q < body.length) {
            if (body[q] == '"') {
                val s = readStringIn(body, q) ?: break
                out.add(s.first)
                q = s.second
            } else if (body[q] == '[') {
                // 内层数组的第一个字符串就是歌手名
                var r = q + 1
                while (r < body.length && body[r].isWhitespace()) r++
                if (r < body.length && body[r] == '"') {
                    val s = readStringIn(body, r)
                    if (s != null) {
                        out.add(s.first)
                        q = s.second
                        continue
                    }
                }
                q++
            } else q++
        }
        return out
    }

    private fun indexOfKey(key: String): Int? {
        val needle = "\"$key\""
        val at = src.indexOf(needle)
        if (at < 0) return null
        var p = at + needle.length
        while (p < src.length && src[p].isWhitespace()) p++
        if (p >= src.length || src[p] != ':') return null
        p++
        while (p < src.length && src[p].isWhitespace()) p++
        return p
    }

    private fun readStringAt(start: Int): String? {
        if (start >= src.length || src[start] != '"') return null
        return readStringIn(src, start)?.first
    }

    /** 返回 (解转义后的字符串, 结束引号之后的下标)。 */
    private fun readStringIn(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val sb = StringBuilder()
        var p = start + 1
        while (p < s.length) {
            val c = s[p]
            when {
                c == '\\' && p + 1 < s.length -> {
                    val n = s[p + 1]
                    when (n) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (p + 5 < s.length) {
                                val code = s.substring(p + 2, p + 6).toIntOrNull(16)
                                if (code != null) sb.append(code.toChar())
                                p += 4
                            }
                        }
                        else -> sb.append(n)
                    }
                    p += 2
                }
                c == '"' -> return sb.toString() to (p + 1)
                else -> { sb.append(c); p++ }
            }
        }
        return null
    }

    private fun matchBracket(start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var p = start
        var inStr = false
        while (p < src.length) {
            val c = src[p]
            if (inStr) {
                if (c == '\\') p++
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    open -> depth++
                    close -> { depth--; if (depth == 0) return p }
                }
            }
            p++
        }
        return null
    }
}

/**
 * 头部读取的两种来源：文件（[RandomAccessFile]）或内存字节数组。
 * 抽象出来是为了让 [NcmCipher.parse] 既能解析真实文件，也能解析 [CryptoProbe] 合成的内存样本。
 */
internal class HeadReader private constructor(
    private val raf: RandomAccessFile?,
    private val bytes: ByteArray?
) : AutoCloseable {

    fun readAt(off: Long, dst: ByteArray, dstOff: Int, n: Int): Int {
        if (raf != null) {
            if (off >= raf.length()) return -1
            raf.seek(off)
            return raf.read(dst, dstOff, n)
        }
        val b = bytes!!
        if (off >= b.size) return -1
        val take = minOf(n.toLong(), b.size - off).toInt()
        System.arraycopy(b, off.toInt(), dst, dstOff, take)
        return take
    }

    override fun close() {
        runCatching { raf?.close() }
    }

    companion object {
        fun from(file: File): HeadReader = HeadReader(RandomAccessFile(file, "r"), null)
        fun from(bytes: ByteArray): HeadReader = HeadReader(null, bytes)
    }
}
