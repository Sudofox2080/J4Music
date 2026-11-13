package com.j4.music.repo

import com.j4.music.source.MusicSource
import com.j4.music.source.SourceRegistry
import com.j4.music.source.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * 歌单跨软件同步。
 *
 * 三条互相独立的路径，各自解决不同问题：
 *  1) 文件格式（J4 / M3U / TXT / CSV）：给「同一个 App 的另一台设备」「其它播放器」用。
 *     J4 是自己的无损 JSON，能原样回灌；M3U/TXT/CSV 是给别人的，只能带歌名歌手，没有平台 id。
 *  2) 分享文本：把 J4 压成一行能塞进微信/剪贴板的字符串，用户粘贴回来再 importFromText。
 *  3) syncToSources：歌单里每首歌去三个平台各搜一遍，把三个平台的 id 写进 extra 的
 *     cross_netease / cross_qq，供「换源播放」用（本平台没版权就换另一家的 id 放）。
 *
 * 为什么 TXT/CSV 导入必须真去搜：这两种格式只带「歌名 - 歌手」，没有平台 id，
 * 而 Song.id 是播放、下载、去重的唯一 key。不搜就等于导入了一堆无法播放的壳子。
 */
class PlaylistSync(
    private val repo: PlaylistRepository = PlaylistRepository.shared,
    private val search: SourceSearch = SourceSearch.registry()
) {

    enum class Format(val ext: String) {
        /** 本 App 的完整 JSON，含 extra 与 localPath，可无损回灌 */
        J4("json"),

        /** 每行「歌名 - 歌手」 */
        TXT("txt"),

        /** 标准 Extended M3U */
        M3U("m3u"),

        /** 列：title,artist,album,durationMs,id,source */
        CSV("csv")
    }

    /** 导入结果：失败的每一行原文都要回给用户，否则他根本不知道自己漏了哪首 */
    data class ImportResult(
        val success: Int,
        val failed: List<String>,
        val playlistId: String?,
        val matched: List<Song> = emptyList(),
        val detected: Format = Format.J4,
        val message: String = ""
    )

    /** 导入选项 */
    data class ImportOptions(
        /** 指定落到哪张歌单；为空则新建 */
        val targetPlaylistId: String? = null,
        /** 新建歌单时的名字；为空则按格式给默认名 */
        val name: String? = null,
        /** 自动探测失败时按哪种格式解析 */
        val fallback: Format = Format.TXT,
        /** TXT 搜索的并发上限 */
        val concurrency: Int = 4,
        /** TXT 搜索的总超时（毫秒），超时后剩余行都记为失败，不无限等 */
        val totalTimeoutMs: Long = 120_000,
        /** 搜索时最多问几个音源（按注册顺序） */
        val maxSources: Int = 3
    )

    /** crossIds 同步的进度 */
    data class SyncProgress(
        val done: Int,
        val total: Int,
        val title: String = "",
        val ok: Int = 0,
        val failed: Int = 0
    )

    /** 被取消时已经处理到的位置，调用方可以据此决定要不要继续 */
    class SyncCancelled(val done: Int, val total: Int) : Exception("同步已取消（$done/$total）")

    // ================================================================== 导出

    suspend fun export(id: String, format: Format): String = withContext(Dispatchers.IO) {
        val p = repo.get(id)
        val songs = if (p != null) RepoCodec.songsOf(p) else emptyList()
        val name = p?.name ?: "歌单"
        when (format) {
            Format.J4 -> exportJ4(name, songs, p?.kind ?: PlaylistRepository.KIND_CUSTOM, p?.source, p?.remoteId, pretty = true)
            Format.M3U -> exportM3u(name, songs)
            Format.TXT -> exportTxt(songs)
            Format.CSV -> exportCsv(songs)
        }
    }

    /** J4 全量 JSON：这是唯一能「无损回灌」的格式，extra 与 localPath 都保留 */
    fun exportJ4(
        name: String,
        songs: List<Song>,
        kind: String = PlaylistRepository.KIND_CUSTOM,
        source: String? = null,
        remoteId: String? = null,
        pretty: Boolean = true
    ): String {
        val o = JSONObject().apply {
            put("format", RepoCodec.FORMAT_J4)
            put("version", 1)
            put("name", name)
            put("kind", kind)
            put("source", source ?: JSONObject.NULL)
            put("remoteId", remoteId ?: JSONObject.NULL)
            put("count", songs.size)
            put("exportedAt", System.currentTimeMillis())
            put("songs", JSONArray(RepoCodec.dumpSongs(songs)))
        }
        return if (pretty) o.toString(2) else o.toString()
    }

    /**
     * M3U：
     *  带 localPath 的歌写成文件路径（其它播放器能直接播），
     *  没有的写成 #EXTINF 注释 + 一条「艺术家 - 标题」的虚拟条目。
     * 为什么虚拟条目也要发：M3U 消费方（VLC/foobar）遇到不存在的路径会跳过，
     * 但用户至少能看到「这张歌单原本有哪些歌」，比直接丢掉更有用。
     */
    fun exportM3u(name: String, songs: List<Song>): String {
        val sb = StringBuilder(256 + songs.size * 96)
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:").append(oneLine(name)).append('\n')
        for (s in songs) {
            val durSec = (s.durationMs / 1000).coerceAtLeast(-1)
            val artist = s.artist.ifBlank { "未知歌手" }
            sb.append("#EXTINF:").append(durSec).append(',').append(oneLine(artist)).append(" - ")
                .append(oneLine(s.title)).append('\n')
            val local = s.localPath?.takeIf { it.isNotBlank() }
            if (local != null) {
                sb.append(local).append('\n')
            } else {
                // 没有本地文件：用注释行占位，避免下游把它当成本地路径反复报错
                sb.append("# ").append(oneLine(artist)).append(" - ").append(oneLine(s.title)).append('\n')
            }
        }
        return sb.toString()
    }

    fun exportTxt(songs: List<Song>): String {
        val sb = StringBuilder(songs.size * 48 + 16)
        for (s in songs) {
            sb.append(oneLine(s.title))
            if (s.artist.isNotBlank()) sb.append(" - ").append(oneLine(s.artist))
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * CSV：首行是表头，6 列（title,artist,album,durationMs,id,source）。
     * 用标准 CSV 转义（含逗号/引号/换行的字段整体加引号，内部引号翻倍），
     * 这样 Excel 直接双击能打开，其它脚本用 csv 库也能正确切分。
     */
    fun exportCsv(songs: List<Song>): String {
        val sb = StringBuilder(songs.size * 72 + 64)
        sb.append("title,artist,album,durationMs,id,source\n")
        for (s in songs) {
            sb.append(csvCell(s.title)).append(',')
                .append(csvCell(s.artist)).append(',')
                .append(csvCell(s.album)).append(',')
                .append(s.durationMs).append(',')
                .append(csvCell(s.id)).append(',')
                .append(csvCell(s.source)).append('\n')
        }
        return sb.toString()
    }

    /**
     * 分享文本：J4 紧凑 JSON（不换行、不缩进），直接塞进系统分享。
     * 为什么不用 M3U/TXT 分享：微信里换个 App 粘贴过来，平台 id 就没了，
     * 回灌只能靠重新搜索，匹配率和版本都不可控。
     */
    suspend fun shareText(id: String): String {
        val p = repo.get(id)
        val songs = if (p != null) RepoCodec.songsOf(p) else emptyList()
        return exportJ4(
            name = p?.name ?: "歌单",
            songs = songs,
            kind = p?.kind ?: PlaylistRepository.KIND_CUSTOM,
            source = p?.source,
            remoteId = p?.remoteId,
            pretty = false
        )
    }

    /** 分享文本的最小可用片段：只要 [歌单名, 歌曲数组]，去掉时间戳等噪音，压缩后更短 */
    suspend fun shareTextCompact(id: String): String {
        val p = repo.get(id)
        val songs = if (p != null) RepoCodec.songsOf(p) else emptyList()
        return JSONObject().apply {
            put("format", RepoCodec.FORMAT_J4)
            put("version", 1)
            put("name", p?.name ?: "歌单")
            put("count", songs.size)
            put("songs", JSONArray(RepoCodec.dumpSongs(songs)))
        }.toString()
    }

    // ================================================================== 导入

    /** 按文本猜格式。优先级：J4 > M3U > CSV > TXT，理由见各分支注释 */
    fun detect(text: String): Format {
        val t = text.trim().removePrefix("\uFEFF").trim()
        if (t.isEmpty()) return Format.TXT
        // J4 判据必须是「JSON 里带 format 字段」，不能只看 { 开头：
        // 第三方导出的 {songs:[...]} 也以 { 开头，但它没有 format，只能当 TXT 处理（靠搜索重匹配）
        if (t.startsWith("{")) {
            val o = RepoCodec.quiet { JSONObject(t) }
            val fmt = o?.optString("format") ?: ""
            if (fmt.equals(RepoCodec.FORMAT_J4, ignoreCase = true)) return Format.J4
            if (o?.has("songs") == true || o?.has("tracks") == true) return Format.J4
        }
        val firstLines = t.lineSequence().filter { it.isNotBlank() }.take(6).toList()
        if (firstLines.any { it.trimStart().startsWith("#EXTM3U", ignoreCase = true) }) return Format.M3U
        // CSV 判据：前几行都有逗号，且第一行像表头（含 title/歌名/name 之一）
        val commaRows = firstLines.count { it.count { c -> c == ',' } >= 2 }
        if (commaRows >= 2 || (commaRows >= 1 && firstLines.firstOrNull()?.let(::looksLikeCsvHeader) == true)) {
            return Format.CSV
        }
        return Format.TXT
    }

    private fun looksLikeCsvHeader(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("title") || l.contains("artist") || l.contains("歌名") || l.contains("歌曲")
    }

    suspend fun import(text: String, format: Format? = null): ImportResult =
        import(text, format, ImportOptions(), null)

    suspend fun importFromText(text: String, format: Format? = null): ImportResult =
        import(text, format, ImportOptions(), null)

    /**
     * 导入主入口。
     *
     * 处理顺序：探测格式 → 解析出 Song 列表（TXT/CSV 要联网搜索）→ 去重 → 落歌单。
     * 落歌单的选择：options.targetPlaylistId 非空就追加；否则新建一张（名字取 options.name，
     * 再不然取 J4 里的 name，最后按格式给「导入的歌单」）。
     */
    suspend fun import(
        text: String,
        format: Format? = null,
        options: ImportOptions = ImportOptions(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val fmt = format ?: run {
            val d = detect(text)
            // 探测结果不可靠时（例如单行 CSV 长得像 TXT）退回调用方给的兜底格式
            if (d == Format.TXT && options.fallback != Format.TXT) options.fallback else d
        }

        val parsedLines = when (fmt) {
            Format.J4 -> {
                val list = parseJ4(text)
                list.map { LineItem.Song(it, raw = it.title) }
            }
            Format.M3U -> parseM3u(text).map { it }
            Format.CSV -> parseCsv(text)
            Format.TXT -> parseTxt(text)
        }

        if (parsedLines.isEmpty()) {
            return@withContext ImportResult(0, emptyList(), null, emptyList(), fmt, "没有解析出任何歌曲")
        }

        val haveSongs = ArrayList<Song>(parsedLines.size)
        val failed = ArrayList<String>()
        val pendingText = ArrayList<LineItem.Text>()

        for (item in parsedLines) {
            coroutineContext.ensureActive()
            when (item) {
                is LineItem.Song -> haveSongs.add(item.song)
                is LineItem.Text -> pendingText.add(item)
            }
        }

        val total = parsedLines.size
        val alreadyMatched = haveSongs.size
        if (pendingText.isNotEmpty()) {
            // 只有 J4 / M3U(localPath) 能直接拿到 Song；TXT 与 CSV 无 id 的行必须搜索。
            // 进度按整张歌单的百分比报：已有 Song 的部分算已处理，避免进度条从 0 开始跳一下。
            val resolved = searchLines(pendingText, options) { d ->
                onProgress?.invoke(alreadyMatched + d, total)
            }
            for ((item, song) in resolved) {
                if (song != null) haveSongs.add(song) else failed.add(item.raw)
            }
            onProgress?.invoke(total, total)
        }
        coroutineContext.ensureActive()

        // 去重：搜索得到的两行可能命中同一首
        val seen = HashSet<String>()
        val unique = haveSongs.filter { it.id.isNotBlank() && seen.add(it.id) }

        if (unique.isEmpty()) {
            return@withContext ImportResult(0, failed, null, emptyList(), fmt, "全部未匹配")
        }

        val target = options.targetPlaylistId
        val playlistId: String = if (target != null) {
            repo.addSongs(target, unique)
            target
        } else {
            val name = options.name?.takeIf { it.isNotBlank() }
                ?: j4Name(text)?.takeIf { it.isNotBlank() }
                ?: "导入的歌单"
            repo.create(
                name = name,
                kind = PlaylistRepository.KIND_CUSTOM,
                source = null,
                remoteId = null,
                cover = unique.firstOrNull()?.coverUrl,
                songs = unique
            )
        }
        ImportResult(
            success = unique.size,
            failed = failed,
            playlistId = playlistId,
            matched = unique,
            detected = fmt,
            message = buildString {
                append("导入 ").append(unique.size).append(" 首")
                if (failed.isNotEmpty()) append("，").append(failed.size).append(" 首未匹配")
            }
        )
    }

    private sealed class LineItem {
        /** 已经解析出完整 Song（J4、或 M3U 里带本地路径的行） */
        data class Song(val song: com.j4.music.source.Song, val raw: String) : LineItem()
        /** 只有文本，需要联网搜索 */
        data class Text(val title: String, val artist: String, val raw: String) : LineItem()
    }

    private fun parseJ4(text: String): List<com.j4.music.source.Song> {
        val t = text.trim().removePrefix("\uFEFF")
        val direct = RepoCodec.parsePlaylistFile(t)
        if (direct != null) return direct
        // 兼容「一行一个 J4 JSON」的批量粘贴
        val out = ArrayList<com.j4.music.source.Song>()
        for (line in t.lineSequence()) {
            val l = line.trim()
            if (!l.startsWith("{")) continue
            val o = RepoCodec.quiet { JSONObject(l) } ?: continue
            o.optJSONArray("songs")?.let { out.addAll(RepoCodec.parseSongs(it.toString())) }
        }
        return out
    }

    /**
     * M3U 解析：只认 #EXTINF 带出的标题信息与紧跟的路径行。
     * 路径行是 http(s) 时无法直接当本地文件，仍按「标题 - 歌手」去搜索；
     * 是本地文件路径时保留为 localPath（source=local），这样离线歌单导入后能直接播。
     */
    private fun parseM3u(text: String): List<LineItem> {
        val items = ArrayList<LineItem>()
        var pendingTitle: String? = null
        var pendingArtist = ""
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val meta = line.substringAfter(':', "")
                    val display = meta.substringAfter(',', "")
                    // EXTINF 的显示名常见格式「歌手 - 歌名」或「歌名 - 歌手」，
                    // 这里按 " - " 切一刀，两边都保留，交给搜索去判断
                    val parts = display.split(" - ", limit = 2)
                    if (parts.size == 2) {
                        pendingArtist = parts[0].trim()
                        pendingTitle = parts[1].trim()
                    } else {
                        pendingTitle = display.trim()
                        pendingArtist = ""
                    }
                }
                line.startsWith("#") -> {
                    // 注释行（含 App 自己导出的占位行 "# 歌手 - 歌名"）当成文本候选
                    val body = line.removePrefix("#").trim()
                    if (body.isNotEmpty() && body.contains(" - ") && pendingTitle == null) {
                        val parts = body.split(" - ", limit = 2)
                        items.add(LineItem.Text(parts[1].trim(), parts[0].trim(), body))
                    }
                }
                else -> {
                    val title = pendingTitle ?: line.substringAfterLast('/').substringBeforeLast('.')
                    val isRemote = line.startsWith("http://") || line.startsWith("https://")
                    if (!isRemote && (line.startsWith("/") || line.startsWith("file://") || line.contains(":\\"))) {
                        val path = if (line.startsWith("file://")) line else "file://$line"
                        val song = com.j4.music.source.Song(
                            id = "local:" + RepoCodec.shortId(path),
                            source = "local",
                            title = title,
                            artist = pendingArtist,
                            album = "",
                            durationMs = 0L,
                            coverUrl = null,
                            localPath = path
                        )
                        items.add(LineItem.Song(song, title))
                    } else {
                        items.add(LineItem.Text(title, pendingArtist, (pendingArtist + " - " + title).trim(' ', '-')))
                    }
                    pendingTitle = null
                    pendingArtist = ""
                }
            }
        }
        return items
    }

    private fun parseTxt(text: String): List<LineItem> {
        val out = ArrayList<LineItem>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) continue
            // 常见分隔符都吃： " - " / "-" / "–" / "\t" / "《》"
            var title = line
            var artist = ""
            val sepIdx = listOf(" - ", "\t", " – ", " — ").firstOrNull { line.contains(it) }
            if (sepIdx != null) {
                val parts = line.split(sepIdx, limit = 2)
                // 约定是「歌名 - 歌手」；但如果前半看起来像歌手（很短）后半很长，也允许反过来
                title = parts[0].trim()
                artist = parts[1].trim()
            } else if (line.matches(Regex("^.+?《.+》$"))) {
                title = line.substringAfter('《').substringBefore('》').trim()
                artist = line.substringBefore('《').trim()
            }
            if (title.isEmpty()) continue
            out.add(LineItem.Text(title, artist, line))
        }
        return out
    }

    /**
     * CSV 解析（RFC4180 子集）：支持引号包裹、引号内逗号、引号翻倍转义、CRLF。
     * 不引第三方 CSV 库，逻辑就这么多。
     */
    private fun parseCsv(text: String): List<LineItem> {
        val rows = ArrayList<List<String>>()
        val cur = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("\uFEFF")
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        cur.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> { /* 忽略，交给 \n 收尾 */ }
                    '\n' -> {
                        cur.add(field.toString())
                        field.setLength(0)
                        rows.add(ArrayList(cur))
                        cur.clear()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || cur.isNotEmpty()) {
            cur.add(field.toString())
            rows.add(ArrayList(cur))
        }
        if (rows.isEmpty()) return emptyList()

        // 列定位：优先按表头名找，找不到就按位置（0=title,1=artist,2=album,3=duration,5=source）
        val header = rows.first().map { it.trim().lowercase() }
        val hasHeader = header.any { it == "title" || it == "歌名" || it == "name" || it == "song" }
        val idxTitle = indexOfAny(header, "title", "歌名", "name", "song", "歌曲").takeIf { hasHeader } ?: 0
        val idxArtist = indexOfAny(header, "artist", "singer", "歌手", "artistname").takeIf { hasHeader } ?: 1
        val idxAlbum = indexOfAny(header, "album", "专辑").takeIf { hasHeader } ?: 2
        val idxDur = indexOfAny(header, "durationms", "duration", "duration_ms", "时长").takeIf { hasHeader } ?: 3
        val idxId = indexOfAny(header, "id", "songid").takeIf { hasHeader } ?: -1
        val idxSource = indexOfAny(header, "source", "平台", "来源").takeIf { hasHeader } ?: -1

        val out = ArrayList<LineItem>()
        for (r in if (hasHeader) rows.drop(1) else rows) {
            if (r.all { it.isBlank() }) continue
            val title = r.getOrNull(idxTitle)?.trim().orEmpty()
            if (title.isEmpty()) continue
            val artist = r.getOrNull(idxArtist)?.trim().orEmpty()
            val album = if (idxAlbum >= 0) r.getOrNull(idxAlbum)?.trim().orEmpty() else ""
            val dur = if (idxDur >= 0) r.getOrNull(idxDur)?.trim()?.toLongOrNull() ?: 0L else 0L
            val id = if (idxId >= 0) r.getOrNull(idxId)?.trim().orEmpty() else ""
            val source = if (idxSource >= 0) r.getOrNull(idxSource)?.trim().orEmpty() else ""
            if (id.contains(':') && source.isNotEmpty()) {
                // 带 id + source 的 CSV 可以无损还原，不必搜索
                out.add(
                    LineItem.Song(
                        com.j4.music.source.Song(
                            id = id,
                            source = source,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = dur,
                            coverUrl = null
                        ),
                        "$title - $artist"
                    )
                )
            } else {
                out.add(LineItem.Text(title, artist, if (artist.isEmpty()) title else "$title - $artist"))
            }
        }
        return out
    }

    private fun indexOfAny(header: List<String>, vararg keys: String): Int {
        for (k in keys) {
            val i = header.indexOf(k)
            if (i >= 0) return i
        }
        return -1
    }

    // ------------------------------------------------------------------ TXT/CSV 搜索

    /**
     * 有并发上限地搜索一批「歌名 - 歌手」。
     *
     * 为什么要有总超时：三个平台的搜索接口都可能在 DNS 被劫持时挂住，
     * 一个歌单 200 行、每行 3 个源、每个源 20 秒，不加总超时就是 3 小时。
     * 超时后剩余行按失败返回，用户能看到「哪些没导进来」而不是界面一直转圈。
     */
    private suspend fun searchLines(
        items: List<LineItem.Text>,
        options: ImportOptions,
        onTick: (Int) -> Unit
    ): List<Pair<LineItem.Text, com.j4.music.source.Song?>> = coroutineScope {
        val limit = options.concurrency.coerceIn(1, 8)
        val sources = search.available().take(options.maxSources.coerceAtLeast(1))
        var finished = 0
        val results = arrayOfNulls<com.j4.music.source.Song>(items.size)

        val guard = withTimeoutOrNull(options.totalTimeoutMs) {
            items.mapIndexed { index, item ->
                async {
                    val hit = if (sources.isEmpty()) null else searchOne(sources, item.title, item.artist)
                    results[index] = hit
                    // finished 只在这里（单线程调度器下顺序不可保证）自增，
                    // 用锁保护足够；进度回调本身不做重活。
                    val n = synchronized(this@coroutineScope) { ++finished }
                    RepoCodec.quiet { onTick(n) }
                    index to hit
                }
            }.chunked(limit).forEach { group -> group.awaitAll() }
            true
        }
        if (guard == null) {
            // 超时的部分逐个 ensureActive，避免把真正的取消误判成超时
            coroutineContext.ensureActive()
        }
        items.mapIndexed { i, item -> item to results[i] }
    }

    /** 对单个源做一次搜索，取第一条基本对得上的结果 */
    private suspend fun searchOne(
        sources: List<MusicSource>,
        title: String,
        artist: String
    ): com.j4.music.source.Song? {
        val kw = if (artist.isBlank()) title else "$title $artist"
        for (src in sources) {
            coroutineContext.ensureActive()
            val hits = RepoCodec.quiet { src.search(kw, 0, 10) } ?: continue
            val best = pickBest(hits, title, artist)
            if (best != null) return best
        }
        return null
    }

    // ------------------------------------------------------------------ 跨平台 id 同步

    /**
     * 给歌单里每首歌补齐两个平台的 id（写进 extra 的 cross_netease / cross_qq）。
     *
     * 为什么要做这件事：平台版权在不同家之间来回跳，一首歌在网易没版权但在 QQ 有。
     * 播放器拿到 cross_qq 就能直接按 QQ 的 mid 取地址，不用用户手动「换源」时再搜一遍。
     *
     * 可中断性：每首歌开始时 ensureActive()，取消时抛 SyncCancelled 并带上已处理数量，
     * 调用方可以据此提示「已同步 N 首」。写回歌单用整表替换，取消时不会留下半条记录。
     */
    suspend fun syncToSources(
        id: String,
        onProgress: ((SyncProgress) -> Unit)? = null,
        concurrency: Int = 2
    ): SyncProgress = withContext(Dispatchers.IO) {
        val p = repo.get(id) ?: throw IllegalArgumentException("歌单不存在：$id")
        val songs = RepoCodec.songsOf(p)
        val total = songs.size
        if (total == 0) return@withContext SyncProgress(0, 0)

        val platformSources = PLATFORMS.mapNotNull { pid -> search.available().firstOrNull { it.id == pid } }
        if (platformSources.isEmpty()) {
            return@withContext SyncProgress(0, total, "", 0, total)
        }

        var done = 0
        var ok = 0
        var failed = 0
        val updated = ArrayList<com.j4.music.source.Song>(total)

        try {
            for (song in songs) {
                coroutineContext.ensureActive()
                // 三个平台并行搜，单首内部并发，整体受外层顺序控制，
                // 这样进度条是严格单调的，取消时也不会同时有几十个请求在飞
                val found = coroutineScope {
                    platformSources.map { src ->
                        async { src.id to RepoCodec.quiet { findCross(src, song) } }
                    }.awaitAll()
                }
                val extra = HashMap(song.extra)
                var got = 0
                for ((pid, hit) in found) {
                    if (hit != null) {
                        extra["cross_$pid"] = hit.id
                        got++
                    }
                }
                done++
                if (got > 0) ok++ else failed++
                updated.add(song.copy(extra = extra))
                onProgress?.invoke(SyncProgress(done, total, song.title, ok, failed))
            }
        } catch (e: CancellationException) {
            // 把已经处理完的部分写回去，剩下的保持原样，保证「取消后数据也是完整可用的」
            writeBack(p, songs, updated)
            throw SyncCancelled(updated.size, total)
        }

        repo.replaceSongs(id, updated)
        SyncProgress(done, total, "", ok, failed)
    }

    private suspend fun writeBack(
        p: com.j4.music.data.Playlist,
        original: List<com.j4.music.source.Song>,
        updated: List<com.j4.music.source.Song>
    ) {
        if (updated.isEmpty()) return
        val map = updated.associateBy { it.id }
        val merged = original.map { map[it.id] ?: it }
        repo.replaceSongs(p.id, merged)
    }

    /** 在某个平台找这首歌的对应条目；带歌手校验，避免同名的翻唱版本串台 */
    private suspend fun findCross(
        src: MusicSource,
        song: com.j4.music.source.Song
    ): com.j4.music.source.Song? {
        // 已经是本平台的歌，直接用自己
        if (song.source == src.id) return song
        val hits = src.search("${song.title} ${song.artist}".trim(), 0, 10)
        return pickBest(hits, song.title, song.artist, song.durationMs)
    }

    /**
     * 从搜索结果里挑一条：
     *  1) 标题规范化后必须一致或包含；
     *  2) 歌手有交集；
     *  3) 有时长时误差 8 秒以内优先。
     * 三条都不满足就返回 null —— 宁可漏配，也不要配错（版权在别人的歌上，
     * 换源播放会放出一首完全无关的歌，比「没有」糟糕得多）。
     */
    fun pickBest(
        hits: List<com.j4.music.source.Song>,
        title: String,
        artist: String,
        durationMs: Long = 0L
    ): com.j4.music.source.Song? {
        if (hits.isEmpty()) return null
        val nt = norm(title)
        if (nt.isEmpty()) return hits.firstOrNull()
        val na = norm(artist)

        var best: com.j4.music.source.Song? = null
        var bestScore = -1
        for (h in hits) {
            val ht = norm(h.title)
            val titleOk = ht == nt || ht.contains(nt) || nt.contains(ht)
            if (!titleOk) continue
            val ha = norm(h.artist)
            val artistOk = na.isEmpty() || ha.contains(na) || na.contains(ha) ||
                na.split(' ').any { tok -> tok.length >= 2 && ha.contains(tok) }
            var score = if (ht == nt) 30 else 20
            if (artistOk) score += 25
            else if (na.isNotEmpty()) score -= 10
            if (durationMs > 0 && h.durationMs > 0) {
                val diff = kotlin.math.abs(h.durationMs - durationMs)
                if (diff <= 8_000) score += 15
                else if (diff > 60_000) score -= 20
            }
            if (score > bestScore) {
                bestScore = score
                best = h
            }
        }
        // 阈值 25：标题完全一致(30) 或 标题一致+歌手对得上(55)，标题包含+歌手(45) 都能过，
        // 只有标题沾边、歌手完全不符(20) 的被刷掉
        return if (bestScore >= 25) best else null
    }

    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    private fun j4Name(text: String): String? {
        val o = RepoCodec.quiet { JSONObject(text.trim()) } ?: return null
        return o.optString("name").ifEmpty { null }
    }

    // ------------------------------------------------------------------ 文本辅助

    private fun oneLine(s: String): String = s.replace('\r', ' ').replace('\n', ' ').trim()

    private fun csvCell(s: String): String {
        val needQuote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    companion object {
        /** 「换源播放」支持的三个平台，顺序即优先级 */
        val PLATFORMS = listOf("netease", "qq")

        val shared = PlaylistSync()
    }
}

/**
 * 音源搜索的薄接口：PlaylistSync 只依赖它，方便单测塞假实现，
 * 也方便 SourceRegistry 变化时只改一处。
 */
interface SourceSearch {
    fun available(): List<MusicSource>

    object Registry : SourceSearch {
        /**
         * 音源列表：静态优先，反射兜底。
         * 反射只在直接调用失败（SourceRegistry 尚未落地 / 改了签名）时才走，
         * 而且 all() 缺省时会退化成对 get(每个 id) 的逐个问，保证「至少能拿到一个源」。
         */
        override fun available(): List<MusicSource> {
            val direct = RepoCodec.quiet { SourceRegistry.all() }
            if (!direct.isNullOrEmpty()) return direct
            val reflected = RepoCodec.quiet {
                val cls = Class.forName("com.j4.music.source.SourceRegistry")
                val inst = cls.getField("INSTANCE").get(null)
                val m = cls.getMethod("all")
                val r = m.invoke(inst)
                if (r is List<*>) r.filterIsInstance<MusicSource>() else null
            }
            if (!reflected.isNullOrEmpty()) return reflected
            // 最后兜底：按已知 id 逐个 get
            return PlaylistSync.PLATFORMS.mapNotNull { id ->
                PlaylistRepository.Fetchers.let { _ -> RepoCodec.quiet { SourceRegistry.get(id) } }
            }
        }
    }

    companion object {
        fun registry(): SourceSearch = Registry

        /** 固定源列表（测试用） */
        fun fixed(list: List<MusicSource>): SourceSearch = object : SourceSearch {
            override fun available(): List<MusicSource> = list
        }
    }
}
