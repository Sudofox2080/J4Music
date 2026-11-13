package com.j4.music.source

import android.content.Context
import com.j4.music.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 内置演示音源：完全离线，不碰网络，用来兜住「无网络 / 四个在线源全部失效」时的体验。
 *
 * 它存在的理由不是多一个音源，而是验收路径：
 * UI 的搜索结果、播放条、进度、歌词滚动、歌单导入、队列切换，全都依赖
 * 「能拿到一首歌 + 一个可播地址 + 一段 LRC」。只要这条链路是本地的，
 * 产品就能在飞行模式下被完整演示，也不会因为平台接口改版而变成一块打不开的黑屏。
 *
 * === 为什么给 file:// 绝对路径而不是 file:///android_asset/ ===
 *
 * 把音频塞在 assets/demo/ 里是对的（打包进 APK，装完就有），但交给播放器的
 * 必须是「把 assets 复制到私有目录之后」的绝对路径，理由是 Media3 的 URI 分发：
 *  1) ExoPlayer 用 DefaultDataSource 按 scheme 选 DataSource；
 *  2) file:///android_asset/xxx 的 scheme 是 file，于是会走 FileDataSource，
 *     而 FileDataSource 拿到的字面路径是 /android_asset/demo/demo_01.wav，
 *     在设备上并不存在，结果是 PlaybackException(FileNotFound)；
 *  3) 本项目的装配是默认 DataSource.Factory，没有注册自定义 scheme，
 *     所以 asset: 同样不可依赖；反过来本地明文文件这条路径是现成且成熟的。
 * 结论：先把 assets 里的 wav 落到 context.filesDir/demo/，再返回 file:// 绝对路径。
 * 这一步是幂等的，只在第一次取址时付出一笔拷贝成本，之后就是纯本地文件读取。
 */
class DemoSource : MusicSource {

    override val id: String = SOURCE_ID

    override val name: String = "本地演示"

    /** WAV 是无损 PCM，但这里刻意报 false：它不是高解析音源，前端别拿它当无损卖点 */
    override val supportsLossless: Boolean = false

    /** 演示源永远可用，不需要登录 */
    override val requiresLogin: Boolean = false

    override fun isLoggedIn(): Boolean = true

    // ==================== 曲目表 ====================

    /**
     * 一行歌词。刻意用两个具名字段而不是 Pair：Pair 的 first/second 在
     * 类型推断出岔子时会退化成 first() 调用并连带报出一串误导性的错误。
     */
    private data class LyricLine(val atMs: Long, val text: String)

    private data class DemoTrack(
        val index: Int,
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val keywords: List<String>,
        val lyricLines: List<LyricLine>
    )

    /** 写歌词行时少敲几个字 */
    private fun at(ms: Long, text: String): LyricLine = LyricLine(ms, text)

    private val tracks: List<DemoTrack> = listOf(
        DemoTrack(
            index = 1,
            fileName = "demo_01.wav",
            title = "演示·琶音上升",
            artist = "J4 内置合成器",
            album = "离线演示集",
            durationMs = 8850L,
            keywords = listOf("琶音", "演示", "arpeggio", "上升", "a4", "和弦"),
            lyricLines = listOf(
                at(0L, "（前奏）纯正弦琶音，从 A4 依次上行"),
                at(900L, "第一遍：A4 → C5 → E5 → G5 → C6"),
                at(3200L, "上升结束，开始反向回落"),
                at(5600L, "回落：C6 → G5 → E5 → C5 → A4"),
                at(7400L, "收尾和弦：A4 与 E5 同时延音"),
                at(8100L, "演示曲 01 结束，共 8.85 秒"),
                at(8500L, "→ 无网络时这段音频依然完全可播")
            )
        ),
        DemoTrack(
            index = 2,
            fileName = "demo_02.wav",
            title = "演示·和弦垫",
            artist = "J4 内置合成器",
            album = "离线演示集",
            durationMs = 10800L,
            keywords = listOf("和弦", "演示", "pad", "垫音", "c大调"),
            lyricLines = listOf(
                at(0L, "三和弦依次铺开，每段 3 秒、重叠 0.6 秒"),
                at(200L, "第一个和弦：C4 - E4 - G4"),
                at(3200L, "第二个和弦：F4 - A4 - C5"),
                at(6200L, "第三个和弦：G4 - B4 - D5"),
                at(9200L, "三个和弦走完，进度条与歌词要一直同步"),
                at(10200L, "演示曲 02 结束，共 10.80 秒")
            )
        ),
        DemoTrack(
            index = 3,
            fileName = "demo_03.wav",
            title = "演示·五声音阶",
            artist = "J4 内置合成器",
            album = "离线演示集",
            durationMs = 11440L,
            keywords = listOf("五声", "音阶", "演示", "快", "pentatonic"),
            lyricLines = listOf(
                at(0L, "C 大调五声：C D E G A，速度快于前两首"),
                at(2600L, "第二遍降八度，检验低频与节奏"),
                at(5200L, "第三遍回到高音区"),
                at(7800L, "第四遍再次降八度"),
                at(10200L, "收尾：C5 与 G5 叠音"),
                at(11000L, "演示曲 03 结束，共 11.44 秒")
            )
        ),
        DemoTrack(
            index = 4,
            fileName = "demo_04.wav",
            title = "演示·低音摇篮",
            artist = "J4 内置合成器",
            album = "离线演示集",
            durationMs = 12000L,
            keywords = listOf("低音", "摇篮", "演示", "慢", "lullaby"),
            lyricLines = listOf(
                at(0L, "慢速低音，最低到 F3，适合检验耳机低频"),
                at(1200L, "F3 - A3 - C4 - A3"),
                at(5200L, "G3 - Bb3 - D4 - Bb3"),
                at(9200L, "最后一个长音 F3，逐渐消散"),
                at(11200L, "演示曲 04 结束，共 12.00 秒"),
                at(11700L, "→ 这条链路全程不联网：assets → filesDir → 播放器")
            )
        )
    )

    /**
     * 平台内 id 必须带 source 前缀：Song.id 在前端是列表 key、在本地歌单里是去重键，
     * 而四个在线源的平台内 id 都是纯数字，1 会跟网易云的 1 撞车。
     */
    private fun DemoTrack.bareId(): String = "demo_$index"

    private fun DemoTrack.toSong(): Song = Song(
        id = idOf(bareId()),
        source = SOURCE_ID,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = null,
        vip = false,
        // localPath 留空是刻意的：一旦填了绝对路径，MediaItemFactory 会绕过 resolveUrl，
        // 那份 Song 就不可序列化地依赖本机路径（换设备或清数据后失效）。
        localPath = null,
        quality = QUALITY_WAV,
        extra = mapOf("demo_index" to index.toString())
    )

    private fun idOf(bare: String): String = if (bare.startsWith("$SOURCE_ID:")) bare else "$SOURCE_ID:$bare"

    private fun trackOf(song: Song): DemoTrack? {
        if (song.source != SOURCE_ID) return null
        val bare = song.id.removePrefix("$SOURCE_ID:")
        tracks.firstOrNull { it.bareId() == bare }?.let { return it }
        // 兜底：按标题匹配（前端可能把 id 洗过）
        return tracks.firstOrNull { it.title == song.title }
    }

    // ==================== 搜索 / 歌单 ====================

    /**
     * 搜索：标题或关键词命中优先，其余排在后面。
     *
     * 为什么不是命中就过滤掉其它：演示源的用途是永远有结果。
     * 用户搜任意词都能得到一份可播列表，演示才不会在空列表前停住。
     */
    override suspend fun search(keyword: String, offset: Int, limit: Int): List<Song> {
        val kw = keyword.trim().lowercase()
        val ordered = if (kw.isEmpty()) {
            tracks
        } else {
            val hit = tracks.filter { t ->
                t.title.lowercase().contains(kw) ||
                    t.keywords.any { k -> k.lowercase().contains(kw) || kw.contains(k.lowercase()) }
            }
            val miss = tracks.filter { !hit.contains(it) }
            hit + miss
        }
        val all = ordered.map { it.toSong() }
        if (offset >= all.size) return emptyList()
        val end = if (limit <= 0) all.size else minOf(all.size, offset + limit)
        return all.subList(offset, end)
    }

    /**
     * 演示歌单：一个固定歌单，remoteId = demo:local。
     *
     * 之所以也实现 fetchPlaylist：前端导入远端歌单时按音源 id 反查音源，
     * 有这条实现演示歌单就能真的被导入进本地库，顺带把远端镜像那条路径也演示出来。
     */
    override suspend fun fetchPlaylist(remoteId: String): PlaylistDetail = PlaylistDetail(
        id = idOf(PLAYLIST_ID),
        name = PLAYLIST_NAME,
        // 封面故意给 null：演示源不打包图片，前端会走本地占位样式
        cover = null,
        songs = tracks.map { it.toSong() }
    )

    override suspend fun userPlaylists(): List<RemotePlaylist> = listOf(
        RemotePlaylist(
            id = idOf(PLAYLIST_ID),
            name = PLAYLIST_NAME,
            cover = null,
            songCount = tracks.size,
            source = SOURCE_ID
        )
    )

    // ==================== 播放地址 ====================

    /**
     * 取播放地址：先把 assets/demo 同步到私有目录，再给 file:// 绝对路径。
     *
     * quality 固定报 wav：它不在 UrlResolver 的 QUALITY_ORDER 里，降级链第一节就命中，
     * 不会为了一个本地文件连续问五次。
     */
    override suspend fun resolveUrl(song: Song, preferQuality: String): PlayUrl? {
        val track = trackOf(song) ?: return null
        val extracted = ensureExtracted()
        if (extracted == null) {
            SourceLog.e(id, "assets 解包失败，演示曲不可用: ${track.fileName}")
            return null
        }
        val file = extracted[track.fileName]
        if (file == null) {
            SourceLog.e(id, "解包结果里没有 ${track.fileName}，已有键: ${extracted.keys}")
            return null
        }
        if (!file.exists() || file.length() <= 0L) {
            SourceLog.e(id, "演示文件缺失或为空: ${file.absolutePath}")
            return null
        }
        return PlayUrl(
            url = "file://" + file.absolutePath,
            quality = QUALITY_WAV,
            downgraded = false,
            preview = false,
            // 本地文件不需要请求头；给空 map 免得 SongMapper 去写 RequestMetadata
            headers = emptyMap()
        )
    }

    // ==================== 歌词 ====================

    /**
     * 内嵌 LRC。translated 直接返回空串而不是给一份翻译：
     * 前端会按时间轴把 translated 合并成每行副字幕，给一份和原文一样的翻译
     * 会渲染出两行重复文字，看起来像 bug。
     */
    override suspend fun lyric(song: Song): Lyric {
        val track = trackOf(song) ?: return Lyric()
        val sb = StringBuilder(512)
        sb.append("[ti]").append(track.title).append('\n')
        sb.append("[ar]").append(track.artist).append('\n')
        sb.append("[al]").append(track.album).append('\n')
        sb.append("[by]J4 离线演示源\n")
        for (line in track.lyricLines) {
            sb.append('[').append(formatLrcTime(line.atMs)).append(']').append(line.text).append('\n')
        }
        return Lyric(lrc = sb.toString(), translated = "", romanized = "")
    }

    // ==================== assets 解包 ====================

    /**
     * 把 assets 里 demo 目录下的 wav 复制到 filesDir/demo/，返回 文件名 → File。
     *
     * 并发安全：Mutex 保护「检查 + 拷贝」整段临界区，不只锁写入。
     * 两个线程同时看到目标不存在时会都去 open 输出流，结果会出现一方读到
     * 另一方写了一半的文件（时长变短，甚至播放器解析报错）。锁住整段后，
     * 后到者直接拿到完成态文件。
     *
     * 注意：本注释里不要出现斜杠加星号的字面写法（比如写通配符路径），
     * Kotlin 的块注释可以嵌套，那样会把后面的代码整段吞掉。
     */
    private suspend fun ensureExtracted(): Map<String, File>? = withContext(Dispatchers.IO) {
        extractLock.withLock { extractLocked() }
    }

    private fun extractLocked(): Map<String, File>? {
        val ctx = appContext()
        if (ctx == null) {
            SourceLog.e(id, "解包失败：拿不到 Context（App.instance 未初始化）")
            return null
        }
        val outDir = File(ctx.filesDir, "demo")
        if (!outDir.exists() && !outDir.mkdirs()) {
            SourceLog.e(id, "无法创建目录 ${outDir.absolutePath}")
            return null
        }
        val names = runCatching { ctx.assets.list("demo")?.toList() }.getOrElse { e ->
            SourceLog.e(id, "assets.list(demo) 抛错: ${e.message}")
            null
        }.orEmpty().filter { it.endsWith(".wav", true) }
        SourceLog.d(id, "assets/demo 里找到 ${names.size} 个 wav: $names")
        if (names.isEmpty()) {
            SourceLog.w(id, "assets/demo 下没有音频，演示源将返回空列表")
            return emptyMap()
        }
        val result = LinkedHashMap<String, File>(names.size)
        for (name in names) {
            val dst = File(outDir, name)
            if (!dst.exists() || dst.length() <= 0L) {
                val ok = runCatching {
                    ctx.assets.open("demo/$name").use { input ->
                        FileOutputStream(dst).use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    true
                }.getOrElse { e ->
                    SourceLog.e(id, "拷贝 $name 失败: ${e.message}")
                    runCatching { dst.delete() }
                    false
                }
                if (!ok) continue
            }
            result[name] = dst
        }
        SourceLog.d(id, "解包完成 ${result.size}/${names.size} 到 ${outDir.absolutePath}")
        return result
    }

    /**
     * 应用 Application 上下文。
     *
     * App.instance 是 lateinit，在极早的调用点（比如桥接层第一帧就点播放）
     * 读它会抛 UninitializedPropertyAccessException；runCatching 会把异常
     * 变成 null，于是上层拼出 "file://null" 这种地址，播放器报 ENOENT 却
     * 看不出原因。这里把异常也记下来。
     */
    private fun appContext(): Context? = try {
        App.instance.applicationContext
    } catch (e: Throwable) {
        SourceLog.e(id, "取 App.instance 失败: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    companion object {
        const val SOURCE_ID = "demo"
        const val PLAYLIST_ID = "local"
        const val PLAYLIST_NAME = "离线演示集"
        const val QUALITY_WAV = "wav"

        private val extractLock = Mutex()

        /** 把毫秒格式化成 LRC 时间标签 [mm:ss.cc] */
        fun formatLrcTime(ms: Long): String {
            val total = if (ms < 0) 0 else ms
            val m = total / 60000
            val s = (total % 60000) / 1000
            val cs = (total % 1000) / 10
            return "%02d:%02d.%02d".format(m, s, cs)
        }

        /** 预热：进程启动后挑个 IO 线程把音频铺好，第一次点播放就不用等拷贝 */
        suspend fun warmUpAsync() {
            runCatching { DemoSource().ensureExtracted() }
        }
    }
}
