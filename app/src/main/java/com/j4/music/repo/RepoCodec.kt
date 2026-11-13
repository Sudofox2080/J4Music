package com.j4.music.repo

import android.content.Context
import android.net.Uri
import com.j4.music.App
import com.j4.music.data.Playlist
import com.j4.music.source.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * repo 包共用的编解码 / 文件助手。
 *
 * 为什么集中在 object 里而不是散在各模块：
 *  1) Song 的 JSON 形态是「歌单 / 下载元数据 / 分享文本 / 音源缓存」四处共用的契约，
 *     一旦有一处写出不同字段名（比如 duration 与 durationMs），跨模块回灌就会丢元数据；
 *  2) 后缀判定与目录创建在下载、扫描、转译三条路径上都要用，重复写必然漂移；
 *  3) 所有文件 IO 只在这里用 Dispatchers.IO 包裹，避免调用方在 Main 上读盘。
 *
 * 加解密相关的 helper（Base64 / SHA1 / MD5 / 字节与 hex 互转）也放在这里：
 * LocalLibrary 要解 FLAC 的 base64 帧头，PlaylistSync 要算稳定 id，Transcode 要算短哈希，
 * 都不允许引第三方库（minSdk 24 的 java.util.Base64 在 API 26 才有，必须用 android.util.Base64）。
 */
object RepoCodec {

    /** 歌单 JSON 里的格式标记，PlaylistSync 靠它区分「本 App 自己的 JSON」和「别人的 JSON」 */
    const val FORMAT_J4 = "j4"

    /** 常见明文音频后缀（下载完成的判定、扫描过滤、转译校验共用） */
    val PLAIN_AUDIO_EXTS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "mka", "ape", "wv", "mp4"
    )

    /** 加密容器后缀（只用于展示与命名，真正的判定交给 crypto.CipherFactory） */
    val ENCRYPTED_EXTS = setOf(
        "ncm", "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8", "qmcflac", "qmcogg", "qmcra",
        "mflac", "mflac0", "mgg", "mgg0", "mggl", "mmp3", "mmp4", "mnac", "bkcmp3", "bkcflac",
        "kwm", "mg3d", "qy2", "tm0", "tm2", "tm3", "tm6"
    )

    // ------------------------------------------------------------------ Song 列表 <-> JSON

    fun dumpSongs(songs: List<Song>, pretty: Boolean = false): String {
        val arr = JSONArray()
        songs.forEach { arr.put(it.toJson()) }
        return if (pretty) arr.toString(2) else arr.toString()
    }

    /** 容错解析：单条 Song 坏掉不影响整张歌单，坏条目直接丢弃 */
    fun parseSongs(json: String?): List<Song> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = runCatching { Song.fromJson(o) }.getOrNull() ?: continue
            if (s.id.isBlank() && s.title.isBlank()) continue
            out.add(s)
        }
        return out
    }

    /** 读歌单实体里的歌曲数组 */
    fun songsOf(p: Playlist): List<Song> = parseSongs(p.songsJson)

    // ------------------------------------------------------------------ 歌单文件

    /**
     * 写一个歌单文件（歌单自己导出的 JSON）。
     * 目录不存在会先建；先写 .tmp 再改名，避免半截文件被当成有效歌单读进来。
     */
    suspend fun writePlaylistFile(
        dir: File,
        baseName: String,
        songs: List<Song>,
        name: String,
        kind: String = "custom",
        extra: JSONObject? = null
    ): File = withContext(Dispatchers.IO) {
        ensureDir(dir)
        val file = uniqueFile(dir, sanitizeFileName(baseName), "json")
        val head = JSONObject().apply {
            put("format", FORMAT_J4)
            put("version", 1)
            put("name", name)
            put("kind", kind)
            put("exportedAt", System.currentTimeMillis())
            put("count", songs.size)
            extra?.let { o -> o.keys().forEach { k -> put(k, o.opt(k)) } }
            put("songs", JSONArray(dumpSongs(songs)))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(head.toString(2), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        file
    }

    /**
     * 解析一个歌单文件：既支持本 App 的 {format:"j4", songs:[...]}，
     * 也支持「裸数组」和 {"songs":[...]} 两种常见的第三方导出形态。
     * 返回 null 表示这个文件不是 JSON 歌单（调用方应当转走按文本格式处理）。
     */
    fun parsePlaylistFile(text: String): List<Song>? {
        val t = text.trim().removePrefix("\uFEFF").trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return null
        if (t.startsWith("[")) return runCatching { parseSongs(t) }.getOrNull()
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        o.optJSONArray("songs")?.let { return parseSongs(it.toString()) }
        // 有些工具把歌曲放在 data/tracks 里
        o.optJSONArray("tracks")?.let { return parseSongs(it.toString()) }
        o.optJSONObject("data")?.optJSONArray("songs")?.let { return parseSongs(it.toString()) }
        return null
    }

    // ------------------------------------------------------------------ 后缀 / 文件名

    fun extOf(name: String): String {
        val n = name.trim().substringAfterLast('/').substringAfterLast('\\')
        val dot = n.lastIndexOf('.')
        return if (dot < 0 || dot == n.length - 1) "" else n.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isPlainAudio(name: String): Boolean = PLAIN_AUDIO_EXTS.contains(extOf(name))

    fun isEncryptedName(name: String): Boolean = ENCRYPTED_EXTS.contains(extOf(name))

    /**
     * 从 Song.localPath / file:// / content:// 里尽量取一个可读的文件。
     * content:// 在 API 24 上没有稳定映射，这里走已知表：MediaStore 的音频路径形如
     * content://media/external/audio/media/<id>，真实文件由 LocalLibrary 通过 MediaStore 查得；
     * 其它 authority 交给调用方用 ContentResolver 处理（本函数返回 null）。
     */
    fun toFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        return try {
            when {
                path.startsWith("file://") -> Uri.parse(path).path?.let { File(it) }
                path.startsWith("content://") -> null
                path.contains("://") -> null
                else -> File(path)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 文件名加固：去掉 / \ : * ? " < > | 以及控制字符，控制长度在 120 字符以内。
     * 为什么要做：歌名里带 "/" 会让 File 直接跨目录，".." 更是能写到私有目录之外。
     */
    fun sanitizeFileName(name: String, maxLen: Int = 120): String {
        val sb = StringBuilder(name.length)
        for (ch in name) {
            when {
                ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' ||
                    ch == '"' || ch == '<' || ch == '>' || ch == '|' -> sb.append('_')
                ch.code < 0x20 -> sb.append('_')
                else -> sb.append(ch)
            }
        }
        var s = sb.toString().trim().trimEnd('.')
        if (s.isEmpty()) s = "untitled"
        if (s == "." || s == "..") s = "untitled"
        if (s.length > maxLen) s = s.substring(0, maxLen)
        return s
    }

    /** 在 dir 里找一个不冲突的文件名：name.ext、name (1).ext、name (2).ext ... */
    fun uniqueFile(dir: File, baseName: String, ext: String, maxTries: Int = 500): File {
        val base = sanitizeFileName(baseName)
        val e = ext.removePrefix(".").lowercase(Locale.ROOT)
        var f = if (e.isEmpty()) File(dir, base) else File(dir, "$base.$e")
        var i = 1
        while (f.exists() && f.length() > 0 && i < maxTries) {
            val n = "$base ($i)" + if (e.isEmpty()) "" else ".$e"
            f = File(dir, n)
            i++
        }
        return f
    }

    fun ensureDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------ 应用目录

    /** 下载 / 转译统一的落盘目录：优先外部私有目录，没有外部存储才退到内部 */
    fun musicDir(): File {
        val ctx: Context = App.instance
        val base = ctx.getExternalFilesDir("Music") ?: File(ctx.filesDir, "Music")
        return ensureDir(base)
    }

    fun transcodeDir(): File = ensureDir(File(musicDir(), "transcoded"))

    /** 下载时与音频同名的元数据文件（Song JSON），用于本地歌单恢复 */
    fun metaFileFor(audio: File): File = File(audio.parentFile, audio.name + META_SUFFIX)

    const val META_SUFFIX = ".j4meta"

    // ------------------------------------------------------------------ 字节 / 编码

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun unBase64(s: String): ByteArray? = try {
        android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }

    fun sha1Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return hex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    fun md5Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return hex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    /** 取稳定的短 id：先按 md5 取前 16 个 hex 字符，冲突概率足够低且长度可控 */
    fun shortId(seed: String): String = md5Hex(seed).substring(0, 16)

    // ------------------------------------------------------------------ 异常过滤

    /**
     * 只吞「普通 Exception」，CancellationException 必须原样抛出。
     * 协程取消发生在任何一处 runCatching 里都会让上层永远等不到取消信号，
     * 所以 repo 包里所有 catch 一律走这个函数。
     */
    inline fun <T> quiet(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }
}
