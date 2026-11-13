package com.j4.music.repo

import com.j4.music.App
import com.j4.music.data.Playlist
import com.j4.music.source.MusicSource
import com.j4.music.source.PlaylistDetail
import com.j4.music.source.Song
import com.j4.music.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 歌单唯一入口。
 *
 * 设计取舍：
 *  1) 只有这一层碰 AppDatabase 的 playlist 表，其它模块（下载、扫描、转译、同步）都通过它落歌单，
 *     这样「歌单变了」只需要在一个地方 emit，前端订阅一次就够，不会出现某个入口改了库但列表不刷新。
 *  2) 读写都是 suspend + Dispatchers.IO + 单锁串行：SQLite 虽然是线程安全的，但「读-改-写」
 *     （addSongs、reorder、refreshRemote）必须串起来，否则并发加歌会丢更新。
 *  3) 状态用 StateFlow<List<PlaylistSummary>> 而不是回调，原因：列表页、播放页、JS 桥三处都要用，
 *     每次变更主动 push 比让每个调用点注册回调简单，也不会漏掉自己的写操作。
 *  4) 远端歌单镜像（kind="remote"）保留 source + remoteId，refreshRemote 时做差量合并：
 *     Song.id 在结果里已存在就跳过，不在结果里的「用户自己加的」保留在列表尾部。
 *     为什么不做「以远端为准全量替换」：用户对镜像歌单做过的编辑会被一次刷新全部抹掉，体验上不可接受。
 */
class PlaylistRepository {

    /** 列表页用的轻量模型：不含歌曲数组，避免一次把几十张歌单的全部歌曲读进内存 */
    data class PlaylistSummary(
        val id: String,
        val name: String,
        val cover: String?,
        val kind: String,
        val source: String?,
        val remoteId: String?,
        val songCount: Int,
        val updatedAt: Long
    ) {
        constructor(p: Playlist, n: Int) : this(
            id = p.id,
            name = p.name,
            cover = p.cover,
            kind = p.kind,
            source = p.source,
            remoteId = p.remoteId,
            songCount = n,
            updatedAt = p.updatedAt
        )

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("cover", cover ?: JSONObject.NULL)
            put("kind", kind)
            put("source", source ?: JSONObject.NULL)
            put("remoteId", remoteId ?: JSONObject.NULL)
            put("songCount", songCount)
            put("updatedAt", updatedAt)
        }
    }

    /** 一次写操作的统计结果，供 JS 桥回显「新增 N 首、跳过 M 首」 */
    data class MutationStats(val added: Int, val skipped: Int, val total: Int)

    private val lock = Any()

    private val _playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val playlists: StateFlow<List<PlaylistSummary>> = _playlists.asStateFlow()

    @Volatile
    private var loaded: Boolean = false

    // ------------------------------------------------------------------ 读

    /** 重新从库里加载一次并 emit；线程安全，可并发调用 */
    suspend fun reload(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        val list = loadNow()
        loaded = true
        list
    }

    private fun loadNow(): List<PlaylistSummary> {
        val rows = synchronized(lock) { App.db.listPlaylists() }
        val out = ArrayList<PlaylistSummary>(rows.size)
        for (p in rows) out.add(PlaylistSummary(p, RepoCodec.parseSongs(p.songsJson).size))
        _playlists.value = out
        return out
    }

    suspend fun summary(id: String): PlaylistSummary? {
        if (!loaded) reload()
        return _playlists.value.firstOrNull { it.id == id }
    }

    suspend fun get(id: String): Playlist? = withContext(Dispatchers.IO) {
        synchronized(lock) { App.db.getPlaylist(id) }
    }

    suspend fun songs(id: String): List<Song> = withContext(Dispatchers.IO) {
        val p = synchronized(lock) { App.db.getPlaylist(id) } ?: return@withContext emptyList()
        RepoCodec.songsOf(p)
    }

    suspend fun song(id: String, songId: String): Song? = songs(id).firstOrNull { it.id == songId }

    /** 全库搜索：按歌单名或歌曲 title/artist 模糊匹配；不传 id 时搜所有歌单 */
    suspend fun search(keyword: String, playlistId: String? = null): Map<String, List<Song>> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyMap()
            val rows = if (playlistId != null) {
                listOfNotNull(synchronized(lock) { App.db.getPlaylist(playlistId) })
            } else {
                synchronized(lock) { App.db.listPlaylists() }
            }
            val out = LinkedHashMap<String, List<Song>>()
            for (p in rows) {
                val hit = RepoCodec.songsOf(p).filter {
                    it.title.contains(kw, ignoreCase = true) ||
                        it.artist.contains(kw, ignoreCase = true) ||
                        it.album.contains(kw, ignoreCase = true)
                }
                if (hit.isNotEmpty()) out[p.id] = hit
            }
            out
        }

    // ------------------------------------------------------------------ 建 / 改 / 删

    /** 新建自定义歌单；名字为空时自动命名「我的歌单 N」 */
    suspend fun createCustom(name: String): String = create(
        name = name,
        kind = KIND_CUSTOM,
        source = null,
        remoteId = null,
        cover = null,
        songs = emptyList()
    )

    /**
     * 建一个任意类型的歌单并返回 id。
     * kind 语义与 AppDatabase 一致：custom / local / remote。
     * 远端歌单走 importRemote，本地歌单走 LocalLibrary.buildLocalPlaylist，这里只是共用实现。
     */
    suspend fun create(
        name: String,
        kind: String,
        source: String?,
        remoteId: String?,
        cover: String?,
        songs: List<Song>
    ): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifEmpty { "未命名歌单" }
        val idSeed = buildString {
            append(kind).append('|').append(source ?: "-").append('|')
            append(remoteId ?: "-").append('|').append(finalName).append('|').append(now)
        }
        // 同一毫秒内建两张同名歌单会撞 id（shortId 只用 16 位 hex），加一个自增尾巴避开
        val id = uniqueId("pl_" + RepoCodec.shortId(idSeed))
        val p = Playlist(
            id = id,
            name = finalName,
            cover = cover,
            kind = kind,
            source = source,
            remoteId = remoteId,
            songsJson = RepoCodec.dumpSongs(songs),
            createdAt = now,
            updatedAt = now
        )
        write(p)
        id
    }

    /**
     * 批量加歌：按 Song.id 去重（keepOrder=true 时保持传入顺序，已有歌曲位置不变，新歌追加到尾部）。
     */
    suspend fun addSongs(id: String, songs: List<Song>, at: Int = -1): MutationStats =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val p = readOrNull(id) ?: return@withContext MutationStats(0, songs.size, 0)
            val cur = RepoCodec.songsOf(p).toMutableList()
            val have = cur.mapTo(HashSet()) { it.id }
            var added = 0
            var skipped = 0
            val toAdd = ArrayList<Song>()
            for (s in songs) {
                if (s.id.isBlank() || !have.add(s.id)) {
                    skipped++
                } else {
                    toAdd.add(s)
                    added++
                }
            }
            if (toAdd.isNotEmpty()) {
                val pos = if (at < 0 || at > cur.size) cur.size else at
                cur.addAll(pos, toAdd)
                write(p.copy(songsJson = RepoCodec.dumpSongs(cur)))
            }
            MutationStats(added, skipped, cur.size)
        }

    /** 删除一首歌（按 Song.id）。返回是否真的删掉了 */
    suspend fun removeSong(id: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext false
        val cur = RepoCodec.songsOf(p)
        val next = cur.filterNot { it.id == songId }
        if (next.size == cur.size) return@withContext false
        write(p.copy(songsJson = RepoCodec.dumpSongs(next)))
        true
    }

    suspend fun removeSongs(id: String, songIds: Collection<String>): Int = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext 0
        val drop = songIds.toHashSet()
        val cur = RepoCodec.songsOf(p)
        val next = cur.filterNot { drop.contains(it.id) }
        if (next.size == cur.size) return@withContext 0
        write(p.copy(songsJson = RepoCodec.dumpSongs(next)))
        cur.size - next.size
    }

    /** 拖动排序：把第 from 首挪到第 to 首的位置；越界返回 false */
    suspend fun reorder(id: String, from: Int, to: Int): Boolean = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext false
        val cur = RepoCodec.songsOf(p).toMutableList()
        if (from !in cur.indices || to !in 0 until cur.size || from == to) return@withContext false
        val s = cur.removeAt(from)
        cur.add(to, s)
        write(p.copy(songsJson = RepoCodec.dumpSongs(cur)))
        true
    }

    suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext false
        val n = name.trim()
        if (n.isEmpty()) return@withContext false
        write(p.copy(name = n))
        true
    }

    /** 换封面（网络地址或本地 file://）；传空串表示清空 */
    suspend fun setCover(id: String, cover: String?): Boolean = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext false
        write(p.copy(cover = cover?.takeIf { it.isNotBlank() }))
        true
    }

    /** 整组替换歌曲（导入到既有歌单、合并歌单时用） */
    suspend fun replaceSongs(id: String, songs: List<Song>): Boolean = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext false
        val seen = HashSet<String>()
        val clean = songs.filter { it.id.isNotBlank() && seen.add(it.id) }
        write(p.copy(songsJson = RepoCodec.dumpSongs(clean)))
        true
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { App.db.deletePlaylist(id) }
        loadNow()
        true
    }

    /** 静默写入歌曲（不回写 localPath 等内部用途，失败吞掉；取消照常抛出） */
    suspend fun replaceSongsQuiet(id: String, songs: List<Song>) {
        RepoCodec.quiet { replaceSongs(id, songs) }
    }

    /** 是否已经至少加载过一次列表（避免调用方在 App 冷启动时读到空列表就当真） */
    fun ready(): Boolean = loaded

    /** 保证 id 在库里唯一：撞了就加 "_1"、"_2"…（只在极少数同毫秒创建时触发） */
    private fun uniqueId(base: String): String {
        var candidate = base
        var n = 0
        while (synchronized(lock) { App.db.getPlaylist(candidate) } != null) {
            n++
            candidate = "${base}_$n"
            if (n > 100) return base + "_" + System.nanoTime()
        }
        return candidate
    }

    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val all = synchronized(lock) { App.db.listPlaylists() }
        for (p in all) synchronized(lock) { App.db.deletePlaylist(p.id) }
        loadNow()
        all.size
    }

    /** 按名字取第一张歌单；没有就建。给「转译」「最近下载」这类固定用途的容器用 */
    suspend fun ensureByName(name: String, kind: String = KIND_CUSTOM): String {
        if (!loaded) reload()
        val hit = _playlists.value.firstOrNull { it.name == name && it.kind == kind }
        if (hit != null) return hit.id
        return create(name = name, kind = kind, source = null, remoteId = null, cover = null, songs = emptyList())
    }

    // ------------------------------------------------------------------ 远端镜像

    /**
     * 把远端歌单拉下来存成本地镜像。
     *
     * 返回新歌单 id。同一 (source, remoteId) 已经镜像过时不重复建，
     * 直接对已有歌单做一次刷新并返回它的 id —— 否则用户每点一次「导入」就多一张重复歌单。
     */
    suspend fun importRemote(source: String, remoteId: String): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val fetcher = Fetchers.of(source)
            ?: throw IllegalStateException("音源 $source 未注册，无法获取远端歌单")
        val detail = fetcher.fetchPlaylist(remoteId)
        coroutineContext.ensureActive()

        if (!loaded) reload()
        val existing = _playlists.value.firstOrNull {
            it.kind == KIND_REMOTE && it.source == source && it.remoteId == remoteId
        }
        if (existing != null) {
            mergeRemote(existing.id, detail)
            return@withContext existing.id
        }
        val id = create(
            name = detail.name.ifBlank { "远端歌单 $remoteId" },
            kind = KIND_REMOTE,
            source = source,
            remoteId = remoteId,
            cover = detail.cover,
            songs = detail.songs
        )
        id
    }

    /**
     * 刷新镜像：拉最新 + 差量合并。
     *  - 远端仍有的歌：保留原位置（不打乱用户的排序）；
     *  - 远端新增的歌：按远端顺序追加到尾部；
     *  - 远端已删、但不在上次远端快照里的歌：视为用户手动加入，保留；
     *  - 远端已删、且在上次快照里的歌：删掉（这是远端真的删了）。
     * 「上次远端快照」存在 AppDatabase 的 kv 表里，key = remote_snapshot_<歌单id>，
     * 值是远端歌曲 id 的 JSON 数组。为什么需要它：只比对当前列表无法区分
     * 「用户手动加的」和「远端删掉的」，两者都表现为「本地有、远端没有」。
     */
    suspend fun refreshRemote(id: String): Boolean = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val p = readOrNull(id) ?: return@withContext false
        val source = p.source ?: return@withContext false
        val remoteId = p.remoteId ?: return@withContext false
        val fetcher = Fetchers.of(source) ?: return@withContext false
        val detail = fetcher.fetchPlaylist(remoteId)
        coroutineContext.ensureActive()
        mergeRemote(id, detail)
        true
    }

    /** 刷新所有镜像歌单，返回成功的数量；单张失败不中断其余 */
    suspend fun refreshAllRemote(): Int {
        if (!loaded) reload()
        val targets = _playlists.value.filter { it.kind == KIND_REMOTE && it.source != null && it.remoteId != null }
        var ok = 0
        for (t in targets) {
            try {
                if (refreshRemote(t.id)) ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单张失败继续下一张，调用方从返回值看出成功数
            }
        }
        return ok
    }

    private suspend fun mergeRemote(id: String, detail: PlaylistDetail) = withContext(Dispatchers.IO) {
        val p = readOrNull(id) ?: return@withContext
        val local = RepoCodec.songsOf(p)
        val localById = local.associateBy { it.id }
        val remoteIds = LinkedHashSet<String>()
        detail.songs.forEach { if (it.id.isNotBlank()) remoteIds.add(it.id) }

        val previous = readRemoteSnapshot(id)
        val merged = ArrayList<Song>(local.size + detail.songs.size)

        // 1) 远端顺序优先：远端有的用远端最新元数据，位置沿用本地位置
        val remoteSeq = ArrayList<Song>()
        for (s in detail.songs) {
            if (s.id.isBlank()) continue
            val seen = remoteSeq.any { it.id == s.id }
            if (seen) continue
            remoteSeq.add(localById[s.id]?.let { keepLocalBits(it, s) } ?: s)
        }

        // 2) 本地有、远端没有：只有「不在上次远端快照里」的才是用户手动加的
        val keepLocal = local.filter { s ->
            !remoteIds.contains(s.id) && (previous.isEmpty() || !previous.contains(s.id))
        }

        merged.addAll(remoteSeq)
        // 用户手动加入的歌保持它们在本地列表里的原顺序，追加到尾部
        merged.addAll(keepLocal)

        val cover = detail.cover ?: p.cover
        write(
            p.copy(
                name = detail.name.ifBlank { p.name },
                cover = cover,
                songsJson = RepoCodec.dumpSongs(merged)
            )
        )
        writeRemoteSnapshot(id, remoteIds)
    }

    /**
     * 远端元数据更新、但本地已经下载过的歌不要丢 localPath：
     * 没有这个合并，刷新一次远端歌单就会让所有离线歌曲变成「需要联网」。
     */
    private fun keepLocalBits(local: Song, remote: Song): Song = remote.copy(
        localPath = remote.localPath ?: local.localPath,
        extra = if (local.extra.isEmpty()) remote.extra else remote.extra + local.extra.filterKeys {
            // crossIds 与本地私有字段以本地为准（它们不来自远端）
            it.startsWith("cross_") || it == EXTRA_LOCAL_HASH
        }
    )

    private fun readRemoteSnapshot(playlistId: String): Set<String> {
        val raw = RepoCodec.quiet { App.db.get("remote_snapshot_$playlistId") } ?: return emptySet()
        return RepoCodec.quiet {
            val arr = org.json.JSONArray(raw)
            val set = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) set.add(arr.optString(i))
            set
        } ?: emptySet()
    }

    private fun writeRemoteSnapshot(playlistId: String, ids: Set<String>) {
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        RepoCodec.quiet { App.db.put("remote_snapshot_$playlistId", arr.toString()) }
    }

    // ------------------------------------------------------------------ 内部写

    private suspend fun readOrNull(id: String): Playlist? = withContext(Dispatchers.IO) {
        synchronized(lock) { App.db.getPlaylist(id) }
    }

    /** 写一行并刷新 StateFlow；写失败直接抛（调用方需要知道，否则界面会静默丢改动） */
    private suspend fun write(p: Playlist) = withContext(Dispatchers.IO + NonCancellable) {
        synchronized(lock) { App.db.upsertPlaylist(p) }
        loadNow()
        Unit
    }

    /**
     * 音源取歌单的适配层。
     *
     * 这里是对 SourceRegistry 的**唯一**引用点：其它代码一律走 Fetchers.of(source)。
     * 为什么先试反射再退到静态引用：SourceRegistry 由同事并行实现，早期版本里
     * get(id) 可能叫 byId / find，或者整个类还没落地；反射能让我们在源码层面先跑起来。
     * 现在 source/SourceRegistry.kt 已经落地（object，`get(id: String): MusicSource?`），
     * 所以静态引用是正常路径，反射只是给「类被移走/改签名」留的活口。
     */
    interface PlaylistFetcher {
        suspend fun fetchPlaylist(remoteId: String): PlaylistDetail
    }

    object Fetchers {
        fun of(source: String): PlaylistFetcher? {
            val src: MusicSource = resolve(source) ?: return null
            return object : PlaylistFetcher {
                override suspend fun fetchPlaylist(remoteId: String): PlaylistDetail = src.fetchPlaylist(remoteId)
            }
        }

        /** 取一个 MusicSource：静态优先，失败再反射（反射只覆盖 get/byId/find 三种候选名） */
        private fun resolve(source: String): MusicSource? {
            val direct = RepoCodec.quiet { SourceRegistry.get(source) }
            if (direct != null) return direct
            val reflected = RepoCodec.quiet {
                val cls = Class.forName("com.j4.music.source.SourceRegistry")
                val inst = cls.getField("INSTANCE").get(null)
                for (name in arrayOf("get", "byId", "find")) {
                    val m = RepoCodec.quiet { cls.getMethod(name, String::class.java) } ?: continue
                    val r = RepoCodec.quiet { m.invoke(inst, source) }
                    if (r is MusicSource) return@quiet r
                }
                null
            }
            return reflected
        }
    }

    companion object {
        const val KIND_CUSTOM = "custom"
        const val KIND_LOCAL = "local"
        const val KIND_REMOTE = "remote"

        /** 已下载文件的短哈希，写在 Song.extra 里，LocalLibrary 用来在文件名被改过时找回 */
        const val EXTRA_LOCAL_HASH = "j4_local_hash"

        val shared = PlaylistRepository()
    }
}
