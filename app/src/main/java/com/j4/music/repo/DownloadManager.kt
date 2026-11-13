package com.j4.music.repo

import android.os.SystemClock
import com.j4.music.net.Http
import com.j4.music.source.MusicSource
import com.j4.music.source.PlayUrl
import com.j4.music.source.Song
import com.j4.music.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * 下载管理器。
 *
 * 结构上是一个「有界队列 + N 个常驻 worker」：
 *  - 队列用 Channel(UNLIMITED) 而不是自己维护列表，因为 enqueue 可以从任意线程来（JS 桥、播放页、扫描回调）；
 *  - worker 数量固定 2（MAX_CONCURRENT），平台对同一 IP 的并发下载有风控，开多了反而更容易被限速；
 *  - 任务状态集中在 StateFlow<Map<id, DownloadTask>>，前端订阅一次就能渲染整个下载列表。
 *
 * 为什么进度要节流：单曲 50MB 的流式下载，OkHttp 的 read 回调可能每几毫秒就触发一次，
 * 每次 update StateFlow 都会走一遍快照 + 重组，直接把 UI 线程打死。这里按
 * 「距上次 emit 超过 200ms」或「百分比变了」才推，两个条件都满足才推更省。
 *
 * 为什么写 .part + 改名：直接写目标文件时进程被杀死会留下半个文件，
 * 下次启动按「文件存在」判定就会认为已下载完成，播放时直接报解码错误。
 * 只有在 File.renameTo 成功之后才算 done。
 *
 * 元数据文件（同名 .j4meta 存 Song JSON）的作用：音频文件本身没有可靠的
 * 「平台 id / extra」信息（ID3 只在能写标签时才有，而本项目不引第三方库写标签），
 * 本地歌单恢复、离线播放、换源播放都依赖这几个字段，所以必须旁挂一份。
 */
object DownloadManager {

    const val MAX_CONCURRENT = 2
    const val MAX_RETRY = 2

    /** 进度推送的最小间隔 */
    private const val PROGRESS_INTERVAL_MS = 200L

    /** 缓冲区 64KB：与转译一致，内存占用与文件大小无关 */
    private const val BUF_SIZE = 64 * 1024

    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class DownloadTask(
        val id: String,
        val song: Song,
        val state: State,
        val progress: Int = 0,
        val total: Long = -1L,
        val done: Long = 0L,
        val error: String? = null,
        val file: String? = null,
        val quality: String = "",
        val attempt: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("song", song.toJson())
            put("state", state.name.lowercase())
            put("progress", progress)
            put("total", total)
            put("done", done)
            put("error", error ?: JSONObject.NULL)
            put("file", file ?: JSONObject.NULL)
            put("quality", quality)
            put("attempt", attempt)
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(o: JSONObject): DownloadTask? = runCatching {
                DownloadTask(
                    id = o.getString("id"),
                    song = Song.fromJson(o.getJSONObject("song")),
                    state = State.valueOf(o.optString("state", "queued").uppercase()),
                    progress = o.optInt("progress"),
                    total = o.optLong("total", -1L),
                    done = o.optLong("done"),
                    error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                    file = o.optString("file").takeIf { it.isNotEmpty() && it != "null" },
                    quality = o.optString("quality"),
                    attempt = o.optInt("attempt"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }.getOrNull()
        }
    }

    /** 排队项：任务 id + 待下载歌曲 */
    private data class Job_(val taskId: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val queue = Channel<String>(Channel.UNLIMITED)

    /** 每个任务一个 Job，用于 cancel */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    @Volatile
    private var started = false

    /** 解析播放地址的源查找，便于测试替换；默认走 SourceRegistry */
    @Volatile
    var sourceProvider: (String) -> MusicSource? = { SourceRegistry.get(it) }

    // ------------------------------------------------------------------ 对外 API

    /** 全部任务，按创建时间倒序（新的在上面） */
    fun snapshot(): List<DownloadTask> {
        // 首次访问时把磁盘上的任务表读回来，否则下载页在冷启动后是空的
        if (!started || _tasks.value.isEmpty()) ensureStarted()
        return _tasks.value.values.sortedByDescending { it.createdAt }
    }

    fun task(id: String): DownloadTask? = _tasks.value[id]

    /**
     * 入队一首歌。
     *
     * @return 任务 id（即使已经下载完成也会返回一个 done 状态的任务，方便前端统一处理）
     */
    suspend fun enqueue(song: Song, quality: String? = null): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        ensureStarted()
        val taskId = "dl_" + RepoCodec.shortId(song.id + "|" + (quality ?: "") + "|" + System.currentTimeMillis())

        // 1) 本地已有：直接 done，不排队
        val existing = findLocal(song)
        if (existing != null) {
            val t = DownloadTask(
                id = taskId,
                song = song.copy(localPath = existing.absolutePath),
                state = State.DONE,
                progress = 100,
                total = existing.length(),
                done = existing.length(),
                file = existing.absolutePath,
                quality = "local"
            )
            put(t)
            // 顺手把 localPath 回写进歌单，让其它页面立刻能离线播
            writeBackLocalPath(song.id, existing.absolutePath)
            return@withContext taskId
        }

        // 2) 同 id 已有排队/进行中的任务：返回已有 id，避免重复下载
        val dup = _tasks.value.values.firstOrNull {
            it.song.id == song.id && (it.state == State.QUEUED || it.state == State.RUNNING)
        }
        if (dup != null) return@withContext dup.id
        put(
            DownloadTask(
                id = taskId,
                song = song,
                state = State.QUEUED,
                quality = quality ?: "",
                attempt = 0
            )
        )
        queue.send(taskId)
        taskId
    }

    fun cancel(id: String) {
        val t = _tasks.value[id] ?: return
        jobs.remove(id)?.cancel()
        if (t.state == State.QUEUED || t.state == State.RUNNING) {
            put(t.copy(state = State.CANCELLED, error = "已取消"))
            // 清掉半截文件
            t.file?.let { f -> RepoCodec.quiet { File("$f.part").delete() } }
        }
    }

    /** 重试：失败/取消的任务重置为排队；其它状态忽略 */
    fun retry(id: String): Boolean {
        val t = _tasks.value[id] ?: return false
        if (t.state == State.DONE) return false
        put(t.copy(state = State.QUEUED, error = null, progress = 0, done = 0, total = -1L))
        queue.trySend(id)
        return true
    }

    /** 移除一条任务记录（不删文件）；进行中的先取消 */
    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        _tasks.update { m -> m - id }
        persist(force = true)
    }

    /** 清掉所有已完成/失败/取消的任务记录 */
    fun clearFinished() {
        _tasks.update { m -> m.filterValues { it.state == State.QUEUED || it.state == State.RUNNING } }
        persist(force = true)
    }

    /** 把整张歌单排队下载，返回入队的任务 id 列表 */
    suspend fun downloadPlaylist(id: String, quality: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val songs = PlaylistRepository.shared.songs(id)
            val ids = ArrayList<String>(songs.size)
            for (s in songs) {
                coroutineContext.ensureActive()
                ids.add(enqueue(s, quality))
            }
            ids
        }

    // ------------------------------------------------------------------ 队列 / worker

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            loadPersisted()
            repeat(MAX_CONCURRENT) { idx ->
                scope.launch { worker(idx) }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 任务持久化                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 任务表落盘。
     *
     * 为什么必须持久化：原来只放在内存 StateFlow 里，进程一被系统回收（后台听歌时很常见），
     * 下载页就变成空的，用户以为文件没了 —— 其实音频还在 filesDir/Music 下。
     * 落盘之后「下载页」才是可靠的离线曲库入口。
     */
    private val storeFile: File? by lazy {
        runCatching { File(com.j4.music.App.instance.filesDir, "downloads.json") }.getOrNull()
    }

    @Volatile
    private var lastPersistAt = 0L

    /** 读取上次的任务表；进行中的任务一律标成「已中断」，文件还在就按完成处理 */
    private fun loadPersisted() {
        if (_tasks.value.isNotEmpty()) return
        val f = storeFile ?: return
        if (!f.exists()) return
        val arr = runCatching { org.json.JSONArray(f.readText()) }.getOrNull() ?: return
        val map = LinkedHashMap<String, DownloadTask>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = DownloadTask.fromJson(o) ?: continue
            val recovered = when {
                t.state == State.DONE -> {
                    val exists = t.file?.let { File(it).exists() } ?: false
                    if (exists) t else t.copy(state = State.FAILED, error = "文件已被删除")
                }
                t.state == State.QUEUED || t.state == State.RUNNING ->
                    t.copy(state = State.FAILED, error = "上次未下完就退出，点重试继续")
                else -> t
            }
            map[recovered.id] = recovered
        }
        if (map.isNotEmpty()) _tasks.value = map
    }

    /** 状态变化后写盘（200ms 节流，避免每个进度回调都写一次） */
    private fun persist(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersistAt < 200) return
        lastPersistAt = now
        val f = storeFile ?: return
        val arr = org.json.JSONArray()
        for (t in snapshot()) arr.put(t.toJson())
        scope.launch {
            runCatching {
                val tmp = File(f.absolutePath + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
            }
        }
    }

    /**
     * worker：一直从 queue 取任务，取一个做完再取下一个。
     * 用 Channel 而不是轮询 Map 的原因：轮询要么空转烧 CPU，要么加锁复杂；
     * Channel 天然是线程安全的阻塞队列，取消 worker 也不会丢已入队的 id。
     *
     * 注意 receive() 是匿名 Job（scope 的子协程），不能把整个 worker 的 `this` 当 job 交出去，
     * 所以这里把 runTask 提交到 scope 上拿 Job，再 join。
     */
    private suspend fun worker(index: Int) {
        while (scope.isActive) {
            val taskId = try {
                queue.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            // 已被取消的任务不再执行
            val cur = _tasks.value[taskId]
            if (cur == null || cur.state == State.CANCELLED) {
                jobs.remove(taskId)
                continue
            }
            val job = scope.launch {
                try {
                    runTask(taskId)
                } finally {
                    jobs.remove(taskId)
                }
            }
            jobs[taskId] = job
            job.join()
        }
    }

    private suspend fun runTask(taskId: String) {
        var attempt = 0
        var lastError = ""
        while (attempt <= MAX_RETRY) {
            coroutineContext.ensureActive()
            val task = _tasks.value[taskId] ?: return
            if (task.state == State.CANCELLED) return
            put(task.copy(state = State.RUNNING, attempt = attempt, error = null, progress = 0, done = 0L))
            try {
                downloadOnce(task)
                return
            } catch (e: CancellationException) {
                val cur = _tasks.value[taskId]
                if (cur != null && cur.state != State.CANCELLED) {
                    put(cur.copy(state = State.CANCELLED, error = "已取消"))
                }
                return
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                if (e is NoUrl) lastError = e.message ?: "无可用音源"
            }
            attempt++
            if (attempt <= MAX_RETRY) {
                // 指数退避：1s、3s。为什么不用固定间隔：平台限流通常按窗口算，
                // 固定 1 秒重试等于稳定打满窗口，退避能跨过窗口。
                val wait = 1000L * (1L shl (attempt - 1)) + 500L
                try {
                    delay(wait)
                } catch (e: CancellationException) {
                    val cur = _tasks.value[taskId]
                    if (cur != null && cur.state != State.CANCELLED) {
                        put(cur.copy(state = State.CANCELLED, error = "已取消"))
                    }
                    return
                }
            }
        }
        val cur = _tasks.value[taskId]
        if (cur != null) put(cur.copy(state = State.FAILED, error = lastError.ifBlank { "下载失败" }))
    }

    private class NoUrl(msg: String) : Exception(msg)

    /** 单次下载尝试：取地址 → 流式读到 .part → 改名 → 写 .j4meta */
    private suspend fun downloadOnce(task: DownloadTask) {
        val song = task.song
        val src = sourceProvider(song.source)
            ?: throw NoUrl("未注册的音源 ${song.source}")
        val want = task.quality.ifBlank { song.quality.ifBlank { "standard" } }
        var play: PlayUrl? = src.resolveUrl(song, want)
        // 会员曲直连只会拿到 30 秒试听片段（网易 br 128k、QQ 的 testfile2g），
        // 直接存下来就是「下载成功但只有半分钟」的假象 —— 必须当作失败去跨源找完整版。
        if (play == null || play.preview) {
            val cross = runCatching { SourceRegistry.crossResolve(song, want) }.getOrNull()
            if (cross != null && !cross.preview && cross.url.isNotEmpty()) play = cross
        }
        val chosen = play ?: throw NoUrl("版权受限，暂无可用音源")
        if (chosen.preview) throw NoUrl("只拿到试听片段，未下载（该曲需要会员）")
        coroutineContext.ensureActive()

        val dir = RepoCodec.musicDir()
        val ext = extensionFor(play, song)
        val base = RepoCodec.sanitizeFileName(
            buildString {
                append(song.title.ifBlank { "untitled" })
                if (song.artist.isNotBlank()) append(" - ").append(song.artist)
            }
        )
        // 目标文件名带 song.id 短哈希：同名不同歌（翻唱/现场版）不会互相覆盖
        val audio = File(dir, "$base [${RepoCodec.shortId(song.id)}].$ext")
        val part = File(dir, audio.name + ".part")
        if (audio.exists() && audio.length() > 0) {
            // 另一个 worker/上一次已经下好了
            finish(task.id, song, audio, play.quality)
            return
        }

        val headers = HashMap(play.headers)
        val client = Http.clientFor(song.source, Http.defaultHeaders(song.source) + headers)
        val reqBuilder = Request.Builder().url(play.url).get()
        for ((k, v) in headers) reqBuilder.header(k, v)
        val req = reqBuilder.build()

        var total = -1L
        var written = 0L
        var lastEmitAt = 0L
        var lastPct = -1

        client.newCall(req).execute().use { resp: Response ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应体")
            total = body.contentLength()
            put(task.copy(state = State.RUNNING, total = total, quality = play.quality))

            body.byteStream().use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(0)
                    val buf = ByteArray(BUF_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        val now = SystemClock.elapsedRealtime()
                        val pct = if (total > 0) ((written * 100) / total).toInt() else -1
                        val timeUp = now - lastEmitAt >= PROGRESS_INTERVAL_MS
                        val pctUp = pct >= 0 && pct != lastPct
                        if (timeUp || pctUp) {
                            lastEmitAt = now
                            lastPct = pct
                            writeProgress(task.id, written, total, pct)
                        }
                    }
                }
            }
        }
        coroutineContext.ensureActive()

        if (written <= 0) {
            part.delete()
            throw IOException("下载到 0 字节")
        }
        // 完整性：服务端给了长度就必须对得上，否则当失败重试
        if (total > 0 && written != total) {
            part.delete()
            throw IOException("长度不符：$written/$total")
        }
        if (part.length() == 0L) {
            part.delete()
            throw IOException("临时文件为空")
        }
        if (audio.exists()) audio.delete()
        if (!part.renameTo(audio)) {
            // 少数文件系统跨目录 rename 会失败，兜底用复制
            try {
                part.inputStream().use { i -> audio.outputStream().use { o -> i.copyTo(o, BUF_SIZE) } }
            } catch (e: Exception) {
                part.delete()
                audio.delete()
                throw IOException("落盘失败：${e.message}")
            }
            part.delete()
        }
        finish(task.id, song, audio, play.quality)
    }

    private suspend fun finish(taskId: String, song: Song, audio: File, quality: String) {
        // 元数据旁挂：失败不影响下载本身
        RepoCodec.quiet {
            val meta = JSONObject().apply {
                put("format", "j4meta")
                put("version", 1)
                put("savedAt", System.currentTimeMillis())
                put("quality", quality)
                put("song", song.copy(localPath = audio.absolutePath).toJson())
            }
            RepoCodec.metaFileFor(audio).writeText(meta.toString(), Charsets.UTF_8)
        }
        val cur = _tasks.value[taskId]
        if (cur != null) {
            put(
                cur.copy(
                    state = State.DONE,
                    progress = 100,
                    total = audio.length(),
                    done = audio.length(),
                    file = audio.absolutePath,
                    quality = quality,
                    error = null,
                    song = song.copy(localPath = audio.absolutePath, quality = quality)
                )
            )
        }
        writeBackLocalPath(song.id, audio.absolutePath)
    }

    /** 把一个已经存在的任务置为成功（用于「本地已有」或「已经被别人下好」） */
    private suspend fun finishExisting(taskId: String, song: Song, audio: File, quality: String) {
        finish(taskId, song, audio, quality)
    }

    // ------------------------------------------------------------------ 本地查找 / 回写

    /** 按 Song.id 与文件名两种方式判断这首是否已经下过 */
    fun findLocal(song: Song): File? {
        val dir = RepoCodec.quiet { RepoCodec.musicDir() } ?: return null
        // 1) Song.localPath 指向的实际文件
        song.localPath?.let { p ->
            val f = RepoCodec.toFile(p)
            if (f != null && f.isFile && f.length() > 0) return f
        }
        // 2) 文件名里带 song.id 短哈希（下载时的命名规则）
        val tag = "[${RepoCodec.shortId(song.id)}]"
        val hit = RepoCodec.quiet {
            dir.listFiles { f -> f.isFile && f.name.contains(tag) && RepoCodec.isPlainAudio(f.name) }
                ?.maxByOrNull { it.lastModified() }
        }
        if (hit != null && hit.length() > 0) return hit
        // 3) 老版本按「歌名 - 歌手」命名、没有哈希的文件：只做保守匹配（同名同后缀）
        val base = RepoCodec.sanitizeFileName(
            buildString {
                append(song.title.ifBlank { "untitled" })
                if (song.artist.isNotBlank()) append(" - ").append(song.artist)
            }
        )
        return RepoCodec.quiet {
            dir.listFiles { f -> f.isFile && f.name.substringBeforeLast('.') == base && RepoCodec.isPlainAudio(f.name) }
                ?.firstOrNull { it.length() > 0 }
        }
    }

    /** 把 localPath 回写到所有包含这首歌的歌单里（离线播放入口） */
    private suspend fun writeBackLocalPath(songId: String, path: String) {
        // 这里刻意不用 quiet{} 包整体：ensureActive() 抛的 CancellationException 必须穿透到上层，
        // 而 quiet 只吞普通异常；逐个歌单的读写各自 quiet 兜底即可。
        val repo = PlaylistRepository.shared
        val sums = RepoCodec.quiet { repo.playlists.value } ?: return
        if (sums.isEmpty()) return
        for (sum in sums) {
            coroutineContext.ensureActive()
            val songs = RepoCodec.quiet { repo.songs(sum.id) } ?: continue
            if (songs.none { it.id == songId && it.localPath != path }) continue
            val next = songs.map { if (it.id == songId && it.localPath != path) it.copy(localPath = path) else it }
            RepoCodec.quiet { repo.replaceSongsQuiet(sum.id, next) }
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun put(t: DownloadTask) {
        _tasks.update { m -> m + (t.id to t) }
        // 状态跃迁（排队/完成/失败/取消）必须立刻写盘，进度按节流写
        persist(force = t.state != State.RUNNING)
    }

    private fun writeProgress(taskId: String, done: Long, total: Long, pct: Int) {
        _tasks.update { m ->
            val cur = m[taskId] ?: return@update m
            m + (taskId to cur.copy(done = done, total = total, progress = if (pct >= 0) pct else 0))
        }
    }

    /**
     * 从 PlayUrl 推断后缀：
     *  1) URL 路径里带明确后缀就用它；
     *  2) 否则按 quality 猜（hires/lossless → flac，其它 → mp3）；
     *  3) 网易/QQ 的地址常常是 "/xxx.mp3?auth=..." 或干脆没有后缀，猜错也能播放（Media3 靠魔数）。
     */
    private fun extensionFor(play: PlayUrl, song: Song): String {
        val fromUrl = RepoCodec.extOf(play.url.substringBefore('?'))
        if (fromUrl.isNotEmpty() && (RepoCodec.isPlainAudio("x.$fromUrl") || fromUrl == "flac" || fromUrl == "m4a")) {
            return fromUrl
        }
        val q = play.quality.ifBlank { song.quality }
        return when (q) {
            "hires", "lossless" -> "flac"
            "exhigh", "high", "standard" -> "mp3"
            else -> "mp3"
        }
    }
}
