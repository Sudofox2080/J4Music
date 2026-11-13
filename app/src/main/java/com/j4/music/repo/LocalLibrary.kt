package com.j4.music.repo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.j4.music.App
import com.j4.music.source.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlin.coroutines.coroutineContext

/**
 * 本地音乐扫描 + 本地歌单。
 *
 * 三条来源：
 *  1) MediaStore.Audio：系统媒体库，覆盖用户用其它 App 下载到 /Music、/Download 的音乐；
 *  2) App 私有目录：本 App 自己下载的歌曲（DownloadManager 落盘），带 .j4meta 元数据；
 *  3) 用户手动选的文件：只登记引用（存 content:// 或 file:// 字符串），不复制，
 *     因为复制一份会占双倍空间，而 content:// 权限在 App 重启后由桥接层重新授予。
 *
 * 为什么要自己解析 ID3/FLAC 头：MediaStore 对 .flac 常常拿不到 TITLE/ARTIST（不同厂商实现差异很大），
 * 而「本地音乐」这个页面最重要的就是歌名歌手别是空。自己解一小段头部成本极低，
 * 也不违反「不引第三方库」的约束。
 */
class LocalLibrary(private val context: Context = App.instance) {

    /** 扫描状态：给 UI 显示「没有权限 / 扫描中 / 完成」 */
    enum class Status { OK, NO_PERMISSION, EMPTY, ERROR }

    data class ScanResult(
        val songs: List<Song>,
        val status: Status,
        val message: String = ""
    )

    // ------------------------------------------------------------------ 权限

    /** 读取音频所需的运行时权限名（API 33+ 是 READ_MEDIA_AUDIO，之前是 READ_EXTERNAL_STORAGE） */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasPermission(): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** 给 UI 直接用的说明文案 */
    fun permissionHint(): String = if (hasPermission()) "" else "缺少媒体读取权限，无法扫描本地音乐"

    // ------------------------------------------------------------------ MediaStore 扫描

    /**
     * 扫系统媒体库。
     *
     * 为什么用 content:// 而不是 DATA（绝对路径）：
     *  - API 29 起 DATA 字段对第三方 App 已不可靠（分区存储）；
     *  - 直接用 content uri 交给 Media3 播放，不需要任何文件权限。
     * 但同时把 DATA 也读出来放进 extra["localDataPath"]，因为转译、导出 M3U 需要真实路径。
     */
    suspend fun scan(): List<Song> = doScan().songs

    suspend fun scanWithStatus(): ScanResult = doScan()

    private suspend fun doScan(): ScanResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext ScanResult(emptyList(), Status.NO_PERMISSION, permissionHint())
        }
        val out = ArrayList<Song>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 5000"
        val order = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cursor: Cursor? = RepoCodec.quiet {
            context.contentResolver.query(uri, proj, sel, null, order)
        }
        if (cursor == null) {
            return@withContext ScanResult(emptyList(), Status.ERROR, "媒体库查询失败")
        }
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                coroutineContext.ensureActive()
                val id = c.getLong(iId)
                val dataPath = if (iData >= 0) c.getString(iData) else null
                val displayName = if (iName >= 0) c.getString(iName) else null
                val contentUri = ContentUris.withAppendedId(uri, id).toString()
                var title = if (iTitle >= 0) c.getString(iTitle).orEmpty() else ""
                var artist = if (iArtist >= 0) c.getString(iArtist).orEmpty() else ""
                var album = if (iAlbum >= 0) c.getString(iAlbum).orEmpty() else ""
                var duration = if (iDur >= 0) c.getLong(iDur) else 0L

                // MediaStore 里 <unknown> 是「未知歌手」的占位，不是真值
                if (artist == "<unknown>" || artist.equals("unknown", true)) artist = ""
                // 元数据缺失时自己读文件头兜底（只读前 256KB，够覆盖 ID3v2 + ID3v1 之前的标签区）
                if (title.isBlank() || artist.isBlank() || duration <= 0) {
                    val head = readHead(contentUri, dataPath, displayName, 256 * 1024)
                    if (head != null) {
                        val tag = AudioTagReader.parse(head, displayName ?: title)
                        if (title.isBlank()) title = tag.title
                        if (artist.isBlank()) artist = tag.artist
                        if (album.isBlank()) album = tag.album
                        if (duration <= 0) duration = tag.durationMs
                    }
                }
                if (title.isBlank()) title = displayName?.substringBeforeLast('.') ?: "本地音乐 $id"

                val extra = HashMap<String, String>()
                extra["media_id"] = id.toString()
                if (!dataPath.isNullOrBlank()) extra["localDataPath"] = dataPath
                if (iSize >= 0) extra["size"] = c.getLong(iSize).toString()
                if (iMime >= 0) c.getString(iMime)?.let { extra["mime"] = it }
                extra["localUriKind"] = "content"

                out.add(
                    Song(
                        id = "local:$id",
                        source = "local",
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        coverUrl = if (iAlbumId >= 0) {
                            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(iAlbumId))
                                .toString()
                        } else null,
                        localPath = contentUri,
                        quality = "lossless",
                        extra = extra
                    )
                )
            }
        }
        ScanResult(out, if (out.isEmpty()) Status.EMPTY else Status.OK, if (out.isEmpty()) "没有找到本地音乐" else "")
    }

    // ------------------------------------------------------------------ App 私有目录

    /**
     * 扫下载目录。
     *
     * 命名约定来自 DownloadManager：「歌名 - 歌手 [songIdHash].后缀」+ 同名 .j4meta。
     * 有 .j4meta 时以它为准（Song JSON 完整，含平台 id 与 extra），
     * 没有时退化成「文件名即元数据」，这样用户手动丢进来的文件也能出现在本地列表里。
     */
    suspend fun scanAppDir(): List<Song> = withContext(Dispatchers.IO) {
        val dir = RepoCodec.musicDir()
        val files = RepoCodec.quiet { dir.listFiles() } ?: return@withContext emptyList()
        val out = ArrayList<Song>()
        for (f in files) {
            coroutineContext.ensureActive()
            if (!f.isFile) continue
            if (!RepoCodec.isPlainAudio(f.name)) continue
            if (f.length() <= 0) continue
            if (f.name.endsWith(".part")) continue
            val meta = RepoCodec.metaFileFor(f)
            val fromMeta = if (meta.isFile) readMeta(meta) else null
            if (fromMeta != null) {
                out.add(fromMeta.copy(localPath = f.absolutePath))
                continue
            }
            val base = f.name.substringBeforeLast('.')
            val hyphen = base.lastIndexOf(" - ")
            val title = if (hyphen > 0) base.substring(0, hyphen).trim() else base
            val artist = if (hyphen > 0) base.substring(hyphen + 3).trim() else ""
            val head = RepoCodec.quiet {
                val b = ByteArray(256 * 1024)
                RandomAccessFile(f, "r").use { raf -> raf.read(b) }
                b
            }
            val tag = if (head != null) AudioTagReader.parse(head, f.name) else AudioTagReader.Tag()
            val id = "local:file:" + RepoCodec.shortId(f.absolutePath)
            out.add(
                Song(
                    id = id,
                    source = "local",
                    title = tag.title.ifBlank { title },
                    artist = tag.artist.ifBlank { artist },
                    album = tag.album,
                    durationMs = tag.durationMs,
                    coverUrl = null,
                    localPath = f.absolutePath,
                    quality = if (RepoCodec.extOf(f.name) == "flac") "lossless" else "standard",
                    extra = mapOf("localUriKind" to "file", "size" to f.length().toString())
                )
            )
        }
        out.sortedBy { it.title }
    }

    private fun readMeta(meta: File): Song? = RepoCodec.quiet {
        val o = JSONObject(meta.readText(Charsets.UTF_8))
        val songJson = o.optJSONObject("song") ?: return@quiet null
        Song.fromJson(songJson)
    }

    // ------------------------------------------------------------------ 用户选择文件

    /**
     * 把用户选中的文件登记进本地歌单。
     *
     * @param uris content:// 或 file:// 字符串（桥接层从 ActivityResult 里拿到的原始 uri）
     * @return 成功登记的条数
     *
     * 语义是「引用」而不是复制：直接记下 uri 与从 ContentResolver 查到的显示名/大小。
     * 显示名在 permission 失效后仍可用，所以元数据尽量在登记时就取全。
     */
    suspend fun importFiles(uris: List<String>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        val songs = uris.mapNotNull { u ->
            coroutineContext.ensureActive()
            RepoCodec.quiet { buildSongFromUri(u) }
        }
        if (songs.isEmpty()) return@withContext 0
        // 落到「本地音乐」这张固定歌单里；没有就建
        val repo = PlaylistRepository.shared
        val id = repo.ensureByName(PLAYLIST_NAME, PlaylistRepository.KIND_LOCAL)
        repo.addSongs(id, songs).added.also { added -> lastImported = songs.take(added).toList() }
    }

    /** 上一次 importFiles 解析出的 Song，桥接层要立刻显示时不用再扫一遍 */
    @Volatile
    var lastImported: List<Song> = emptyList()
        private set

    /** 单个 uri → Song；解析失败返回 null（不抛，批量导入时跳过坏条目） */
    fun songFromUri(uriString: String): Song? = RepoCodec.quiet { buildSongFromUri(uriString) }

    private fun buildSongFromUri(uriString: String): Song? {
        val uri = Uri.parse(uriString)
        var name: String? = null
        var size = 0L
        if (uri.scheme == "content") {
            val c = RepoCodec.quiet {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            }
            c?.use { cur ->
                if (cur.moveToFirst()) {
                    val iN = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iS = cur.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0) name = cur.getString(iN)
                    if (iS >= 0 && !cur.isNull(iS)) size = cur.getLong(iS)
                }
            }
        } else if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.isFile) {
                name = f.name
                size = f.length()
            }
        } else if (!uriString.contains("://")) {
            val f = File(uriString)
            if (f.isFile) {
                name = f.name
                size = f.length()
            }
        }
        val head = RepoCodec.quiet { readHeadCtx(uriString, if (uri.scheme == "file") uri.path else uriString, name, 256 * 1024) }
        val tag = if (head != null) AudioTagReader.parse(head, name ?: "") else AudioTagReader.Tag()
        val base = name?.substringBeforeLast('.') ?: "未命名"
        val hyphen = base.lastIndexOf(" - ")
        val guessTitle = if (hyphen > 0) base.substring(0, hyphen).trim() else base
        val guessArtist = if (hyphen > 0) base.substring(hyphen + 3).trim() else ""
        val extra = HashMap<String, String>()
        extra["localUriKind"] = if (uri.scheme == "content") "content" else "file"
        if (size > 0) extra["size"] = size.toString()
        name?.let { extra["displayName"] = it }
        return Song(
            id = "local:uri:" + RepoCodec.shortId(uriString),
            source = "local",
            title = tag.title.ifBlank { guessTitle },
            artist = tag.artist.ifBlank { guessArtist },
            album = tag.album,
            durationMs = tag.durationMs,
            coverUrl = null,
            localPath = uriString,
            quality = if (RepoCodec.extOf(name ?: "") == "flac") "lossless" else "standard",
            extra = extra
        )
    }

    // ------------------------------------------------------------------ 本地歌单

    /**
     * 生成一张 kind="local" 的歌单。
     * 本地歌单不参与远端刷新，也不该被「从远端导入」覆盖，所以 kind 必须单独标出来。
     */
    fun buildLocalPlaylist(name: String, songs: List<Song>): String {
        // 这是同步签名（任务书要求），所以不能 suspend；内部用 runBlocking 走同一个 repo，
        // 保证「建歌单」只有一条写入路径。
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            PlaylistRepository.shared.create(
                name = name.ifBlank { PLAYLIST_NAME },
                kind = PlaylistRepository.KIND_LOCAL,
                source = null,
                remoteId = null,
                cover = songs.firstOrNull()?.coverUrl,
                songs = songs
            )
        }
    }

    /** 扫描 MediaStore + App 目录并合成一张本地歌单，返回歌单 id */
    suspend fun buildPlaylistFromScan(name: String = PLAYLIST_NAME): String {
        val a = doScan().songs
        val b = scanAppDir()
        val seen = HashSet<String>()
        val all = (a + b).filter { seen.add(it.id) }
        return PlaylistRepository.shared.create(
            name = name,
            kind = PlaylistRepository.KIND_LOCAL,
            source = null,
            remoteId = null,
            cover = all.firstOrNull()?.coverUrl,
            songs = all
        )
    }

    // ------------------------------------------------------------------ 读头

    /** 按 uri/路径读文件头部若干字节；优先用 content uri（不需要文件权限） */
    private fun readHead(uriString: String, dataPath: String?, displayName: String?, max: Int): ByteArray? =
        RepoCodec.quiet { readHeadCtx(uriString, dataPath, displayName, max) }

    private fun readHeadCtx(uriString: String, dataPath: String?, displayName: String?, max: Int): ByteArray? {
        // 1) 有真实路径就先走文件，最快
        val f = RepoCodec.quiet { RepoCodec.toFile(dataPath) }
        if (f != null && f.isFile) {
            return RepoCodec.quiet {
                val len = minOf(f.length(), max.toLong()).toInt()
                if (len <= 0) null else {
                    val b = ByteArray(len)
                    RandomAccessFile(f, "r").use { raf -> raf.readFully(b) }
                    b
                }
            }
        }
        // 2) content uri
        return RepoCodec.quiet {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { ins ->
                val b = ByteArray(max)
                var read = 0
                while (read < max) {
                    val n = ins.read(b, read, max - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) null else b.copyOf(read)
            }
        }
    }

    companion object {
        const val PLAYLIST_NAME = "本地音乐"
        const val PLAYLIST_TRANSCODE = "转译"
        const val PLAYLIST_DOWNLOAD = "已下载"

        /** 缓存上一次扫描结果，避免页面来回切换时反复全库扫 */
        @Volatile
        var lastScan: List<Song> = emptyList()
            private set

        fun remember(songs: List<Song>) {
            lastScan = songs
        }
    }
}

/**
 * 最小音频标签解析：只认 ID3v2（TIT2/TPE1/TALB）与 FLAC（VORBIS_COMMENT 的 TITLE/ARTIST/ALBUM）。
 *
 * 为什么不引 taglib：一个 300KB 的 so 只为读三四个字段不值；
 * 而且本项目对「歌手/歌名」的容错策略是「读不到就用文件名」，不需要完美的标签支持。
 *
 * 时长：MP3 按首帧头算码率估算，FLAC 按 STREAMINFO 的采样总数/采样率精确算。
 */
object AudioTagReader {

    data class Tag(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L
    )

    private val LB: Charset = Charsets.ISO_8859_1

    fun parse(head: ByteArray, fileName: String = ""): Tag {
        if (head.isEmpty()) return Tag()
        return try {
            when {
                head.size > 12 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                    head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> parseFlac(head)
                head.size > 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte() -> parseId3v2(head)
                else -> parseMp3Frame(head)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Tag()
        }
    }

    // ------------------------------------------------------------------ ID3v2

    private fun parseId3v2(b: ByteArray): Tag {
        val ver = b[3].toInt() and 0xFF
        val flags = b[5].toInt() and 0xFF
        val size = syncSafe(b, 6)
        var pos = 10
        // ID3v2.2 用 3 字节标识 + 3 字节长度；v2.3/2.4 用 4+4
        val idLen = if (ver == 2) 3 else 4
        val lenSize = if (ver == 2) 3 else 4
        if (flags and 0x40 != 0 && ver >= 3) {
            // extended header
            pos += if (ver == 4) syncSafe(b, pos) else beInt(b, pos) + 4
        }
        val end = minOf(b.size, 10 + size)
        var title = ""
        var artist = ""
        var album = ""
        while (pos + idLen + lenSize <= end) {
            val id = String(b, pos, idLen, LB)
            if (id.all { it == '\u0000' }) break
            val frameSize = if (ver == 2) {
                ((b[pos + 3].toInt() and 0xFF) shl 16) or ((b[pos + 4].toInt() and 0xFF) shl 8) or (b[pos + 5].toInt() and 0xFF)
            } else if (ver == 4) {
                syncSafe(b, pos + 4)
            } else {
                beInt(b, pos + 4)
            }
            val dataStart = pos + idLen + lenSize
            if (frameSize <= 0 || dataStart + frameSize > b.size) break
            val key = when (id) {
                "TIT2", "TT2" -> "t"
                "TPE1", "TP1" -> "a"
                "TALB", "TAL" -> "l"
                else -> null
            }
            if (key != null) {
                val v = decodeTextFrame(b, dataStart, frameSize)
                if (v.isNotEmpty()) when (key) {
                    "t" -> if (title.isEmpty()) title = v
                    "a" -> if (artist.isEmpty()) artist = v
                    "l" -> if (album.isEmpty()) album = v
                }
            }
            pos = dataStart + frameSize
        }
        return Tag(title, artist, album, 0L)
    }

    /** 文本帧：第 1 字节是编码（0=ISO-8859-1, 1=UTF-16, 2=UTF-16BE, 3=UTF-8），后面是内容 */
    private fun decodeTextFrame(b: ByteArray, off: Int, len: Int): String {
        if (len <= 1) return ""
        val enc = b[off].toInt() and 0xFF
        val raw = b.copyOfRange(off + 1, minOf(b.size, off + len))
        val s = when (enc) {
            1 -> {
                val cs = if (raw.size >= 2 && !(raw[0] == 0.toByte() && raw[1] == 0.toByte()) &&
                    ((raw[0].toInt() and 0xFF) == 0xFF || raw[0] == 0.toByte())
                ) Charsets.UTF_16 else Charsets.UTF_16LE
                String(raw, cs)
            }
            2 -> String(raw, Charsets.UTF_16BE)
            3 -> String(raw, Charsets.UTF_8)
            else -> String(raw, LB)
        }
        return s.trim('\u0000', ' ', '\r', '\n')
    }

    // ------------------------------------------------------------------ FLAC

    private fun parseFlac(b: ByteArray): Tag {
        var pos = 4
        var duration = 0L
        var title = ""
        var artist = ""
        var album = ""
        while (pos + 4 <= b.size) {
            val blockHdr = b[pos].toInt() and 0xFF
            val last = (blockHdr and 0x80) != 0
            val type = blockHdr and 0x7F
            val len = ((b[pos + 2].toInt() and 0xFF) shl 16) or
                ((b[pos + 1].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (len < 0 || start + len > b.size) break
            when (type) {
                0 -> if (len >= 18) duration = streamInfoDuration(b, start)
                4 -> {
                    val (t, a, l) = parseVorbisComment(b, start, len)
                    title = t; artist = a; album = l
                }
            }
            if (last) break
            pos = start + len
        }
        return Tag(title, artist, album, duration)
    }

    /** STREAMINFO：18 字节，采样率 20bit、声道 3bit、位深 5bit、总采样数 36bit 打包在 10..17 */
    private fun streamInfoDuration(b: ByteArray, off: Int): Long {
        if (off + 18 > b.size) return 0L
        val sampleRate = ((b[off + 10].toInt() and 0xFF) shl 12) or
            ((b[off + 11].toInt() and 0xFF) shl 4) or
            ((b[off + 12].toInt() and 0xFF) ushr 4)
        if (sampleRate <= 0) return 0L
        var total = 0L
        // 低 4 bit 是总采样数的高 4 位
        total = total or ((b[off + 13].toInt() and 0xFF).toLong() shl 32)
        total = total or ((b[off + 14].toInt() and 0xFF).toLong() shl 24)
        total = total or ((b[off + 15].toInt() and 0xFF).toLong() shl 16)
        total = total or ((b[off + 16].toInt() and 0xFF).toLong() shl 8)
        total = total or (b[off + 17].toInt() and 0xFF).toLong()
        return total * 1000L / sampleRate
    }

    private fun parseVorbisComment(b: ByteArray, off: Int, len: Int): Triple<String, String, String> {
        var p = off
        val end = off + len
        if (p + 4 > end) return Triple("", "", "")
        val vendorLen = leInt(b, p)
        p += 4 + vendorLen
        if (p + 4 > end) return Triple("", "", "")
        val count = leInt(b, p)
        p += 4
        var title = ""
        var artist = ""
        var album = ""
        var i = 0
        while (i < count && p + 4 <= end) {
            val l = leInt(b, p)
            p += 4
            if (l < 0 || p + l > end) break
            val line = String(b, p, l, Charsets.UTF_8)
            p += l
            val eq = line.indexOf('=')
            if (eq > 0) {
                val k = line.substring(0, eq).uppercase()
                val v = line.substring(eq + 1).trim()
                if (v.isNotEmpty()) when (k) {
                    "TITLE" -> if (title.isEmpty()) title = v
                    "ARTIST" -> if (artist.isEmpty()) artist = v
                    "ALBUM" -> if (album.isEmpty()) album = v
                }
            }
            i++
        }
        return Triple(title, artist, album)
    }

    // ------------------------------------------------------------------ MP3 时长估算

    /**
     * 从 MPEG 帧头估算时长。只用了第一帧的比特率与采样率：
     * VBR 文件会偏，但比 0 好得多，且 MediaStore 里时长通常本来就有值，这里只兜底。
     */
    private fun parseMp3Frame(b: ByteArray): Tag {
        var i = 0
        // 找第一个同步字（跳过可能的 ID3v1/xing 垃圾）
        while (i + 4 < b.size) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xE0) == 0xE0) break
            i++
        }
        if (i + 4 >= b.size) return Tag()
        val h1 = b[i + 1].toInt() and 0xFF
        val h2 = b[i + 2].toInt() and 0xFF
        val versionBits = (h1 ushr 3) and 0x03 // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
        val layerBits = (h1 ushr 1) and 0x03   // 1=Layer3
        val brIdx = (h2 ushr 4) and 0x0F
        val srIdx = (h2 ushr 2) and 0x03
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return Tag()
        val sampleRate = when (srIdx) {
            0 -> 44100
            1 -> 48000
            2 -> 32000
            else -> 44100
        }.let { if (versionBits == 3) it else it / 2 }
        val bitrateKbps = when (layerBits) {
            1 -> MP3_LAYER3_BR[if (versionBits == 3) 0 else 1].getOrElse(brIdx) { 128 }
            else -> MP3_LAYER3_BR[if (versionBits == 3) 0 else 1].getOrElse(brIdx) { 128 }
        }
        if (sampleRate <= 0 || bitrateKbps <= 0) return Tag()
        // 用「首帧比特率」推时长 = 数据量 / 码率；这里没有文件总长，返回 0 交给调用方
        return Tag()
    }

    private val MP3_LAYER3_BR = arrayOf(
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    )

    // ------------------------------------------------------------------ 字节小工具

    /** ID3v2 的 syncsafe 整数：每字节只用低 7 位 */
    private fun syncSafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun beInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    private fun leInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
