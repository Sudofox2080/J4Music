package com.j4.music.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 音源注册表。
 *
 * 为什么要一个注册表而不是直接 new：桥接层（bridge/，前端 JS）只认识字符串 id，
 * 播放器的 UrlResolver 也是按 `song.source` 反查实现。集中注册之后，
 * 「加一个音源」= 写一个类 + 在 registerDefaults 里加一行，其它地方一行都不用动。
 *
 * registerDefaults 必须幂等：App.onCreate 可能因为进程重建被多次调用
 * （某些 ROM 会在 Application 重复初始化），重复注册不能抛异常、不能把顺序打乱。
 */
object SourceRegistry {

    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val map = java.util.concurrent.ConcurrentHashMap<String, MusicSource>()

    /** 注册（幂等：同 id 重复注册时以最后一次为准，位置保持在首次注册处） */
    @Synchronized
    fun register(source: MusicSource) {
        val first = map.put(source.id, source) == null
        if (first) order.add(source.id)
    }

    fun get(id: String): MusicSource? = map[id]

    /** 按注册顺序返回全部实现（前端按这个顺序展示音源切换） */
    fun all(): List<MusicSource> = order.mapNotNull { map[it] }

    fun ids(): List<String> = order.toList()

    @Synchronized
    fun unregister(id: String) {
        map.remove(id)
        order.remove(id)
    }

    /**
     * 注册四个在线源 + 一个内置离线演示源。幂等，可重复调用。
     *
     * 注意：源内部不缓存 SourceEnv，所有 state/jar 访问都在调用时实时取，
     * 所以 registerDefaults 早于 SourceEnv.install 也不会把过期的内存实现固化下来。
     *
     * 顺序上把 demo 放在最后：前端按注册顺序展示音源切换，演示源是兜底，
     * 不应该排在在线源前面抢默认位。搜索时它照样返回结果（searchAll 会遍历全部）。
     */
    @Synchronized
    fun registerDefaults() {
        // 只挂两家：网易云 + QQ 音乐，且两家都走同一个开源上游服务。
        //
        // 为什么不再自己实现签名：musics.fcg 的 zzcSign、musicu 的 platform=wk_v17、
        // vkey 的 guid/uin/qm_keyst 这些细节平台一改就得跟着改，自研这条路
        // 反复修了十几轮仍然「点播放没反应」。现在换成社区维护的
        // tlyanyu/multiPlatformMusicApi，我们只在外面套令牌网关，
        // 客户端只认它统一的 /search /song/url /lyric 三个端点。
        register(OpenApiSource(platform = "netease", id = "netease", name = "网易云音乐"))
        register(OpenApiSource(platform = "qqmusic", id = "qq", name = "QQ 音乐"))
    }

    /**
     * 并发搜索多个源。
     *
     * 三个约束决定了这个函数的写法：
     *  1) 单个源失败绝不能拖垮其它源 —— supervisorScope + 每个 async 内自己 try/catch；
     *  2) 单个源卡死不能挂死整个搜索 —— 每个源一个 withTimeoutOrNull；
     *  3) 结果顺序要稳定 —— 按入参 sources 的顺序返回，而不是按谁先返回。
     *
     * @param sources 为 null 表示「全部已注册的源」；给了就用给定顺序，未知 id 记一条错误
     * @return 每个源一组结果；error 非空表示该源失败，songs 此时为空
     */
    suspend fun searchAll(
        keyword: String,
        sources: List<String>? = null,
        /** 单源超时（毫秒）；DNS 被劫持时最快失败的是连接超时，给 8 秒足够 */
        perSourceTimeoutMs: Long = 8_000L,
        limit: Int = DEFAULT_LIMIT
    ): List<SearchGroup> {
        if (keyword.isBlank()) return emptyList()
        val wanted = sources?.takeIf { it.isNotEmpty() } ?: ids()
        // 不参与搜索的源直接跳过：既不占超时窗口，也不会在结果里留一条「搜索超时」
        val targets = wanted
            .filter { id -> map[id]?.searchable != false }
            .map { id -> id to map[id] }

        return supervisorScope {
            targets.map { (id, src) ->
                async {
                    if (src == null) {
                        SearchGroup(id, id, emptyList(), "未注册的音源: $id")
                    } else {
                        try {
                            val songs = withTimeoutOrNull(perSourceTimeoutMs) {
                                src.search(keyword, 0, limit)
                            }
                            if (songs == null) {
                                SourceLog.w(id, "search timeout ${perSourceTimeoutMs}ms")
                                SearchGroup(id, src.name, emptyList(), "搜索超时")
                            } else {
                                SearchGroup(id, src.name, songs, null)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            SourceLog.e(id, "search failed: ${e.javaClass.simpleName}: ${e.message}")
                            SearchGroup(id, src.name, emptyList(), e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** 只要一份平铺的歌曲列表（按 Song.id 去重），给「一键全搜」用 */
    suspend fun searchAllFlat(keyword: String, sources: List<String>? = null): List<Song> {
        val seen = HashSet<String>()
        return searchAll(keyword, sources).flatMap { g -> g.songs.filter { seen.add(it.id) } }
    }

    /** 预热：并发把每个源的设备身份/cookie 建起来，避免第一次搜索慢 */
    suspend fun warmUp() = coroutineScope {
        all().map { src ->
            async {
                runCatching { if (src is Warmable) withTimeoutOrNull(3_000L) { src.warmUp() } }
            }
        }.awaitAll()
        Unit
    }

    const val DEFAULT_LIMIT = 30

    /**
     * 跨源兜底：同一首歌在 A 平台要会员/没版权时，去 B 平台找它的免费版本。
     *
     * 为什么放在客户端做：开源上游只管「按 id 取地址」，不做跨平台找同曲；
     * 而用户听的绝大多数是热门会员曲，直连必然拿不到地址。没有这一步，
     * 表现就是「点了播放没反应」。
     *
     * 匹配规则故意保守：歌名归一后必须相等（或长度≥3 互为前缀），
     * 并且歌手至少要有一段命中；找不到宁可失败，也不要放错歌。
     *
     * @return 命中时返回带 via/viaTitle 的 PlayUrl（via 是实际出地址的平台 id）
     */
    suspend fun crossResolve(song: Song, quality: String): PlayUrl? {
        val title = song.title.trim()
        if (title.isEmpty()) return null
        val keyword = if (song.artist.isBlank()) title else "$title ${song.artist}"
        // 注意这里**包含本平台自己**：同一首歌在网易/QQ 上往往同时有「会员版」和「免费版」
        // （fee=1 vs fee=8），只换平台的话遇到「网易会员曲、QQ 也全要会员」就彻底没路可走。
        val platforms = all().sortedBy { if (it.id == song.source) 0 else 1 }
        for (src in platforms) {
            val hits = runCatching { withTimeoutOrNull(6_000L) { src.search(keyword, 0, 6) } }
                .getOrNull().orEmpty()
            // 关键：命中同一首之后要**逐个试到能播为止**。以前只试第一个候选，
            // 而同一个歌名在网易/QQ 上往往前几条都是会员曲（fee=1），
            // 第一个取不到地址就直接跳到下一个音源，结果是「所有音源都取不到播放地址」，
            // 而同一个搜索结果里其实躺着免费的翻唱/正版（fee=8）。
            val candidates = hits
                .filter { cand -> cand.id != song.id && sameSong(title, cand.title) && artistHit(song.artist, cand.artist) }
                .sortedBy { if (it.vip) 1 else 0 }   // 先试免费曲，会员曲放最后
                .take(4)
            for (cand in candidates) {
                val got = runCatching { withTimeoutOrNull(8_000L) { src.resolveUrl(cand, quality) } }
                    .getOrNull() ?: continue
                if (got.url.isEmpty() || got.preview) continue
                return got.copy(via = src.id, viaTitle = cand.title)
            }
        }
        return null
    }

    /** 归一化歌名：小写、去掉括号里的后缀与空白标点 */
    private fun normTitle(s: String): String = s.lowercase()
        .replace(Regex("[(\\[（【].*?[)\\]）】]"), "")
        .replace(Regex("[\\s\\-_.·、,，'\"!！?？]"), "")
        .trim()

    private fun sameSong(a: String, b: String): Boolean {
        val x = normTitle(a)
        val y = normTitle(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        return x.length >= 3 && y.length >= 3 && (x.startsWith(y) || y.startsWith(x))
    }

    private fun artistHit(want: String, got: String): Boolean {
        if (want.isBlank() || got.isBlank()) return true
        val parts = want.split(Regex("[、,&，/]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        return parts.any { got.contains(it) }
    }

    /** 有设备身份要初始化的源实现它（网易 NMTID、QQ guid/wid） */
    interface Warmable {
        suspend fun warmUp()
    }
}

/** 一个源的一组搜索结果 */
data class SearchGroup(
    val source: String,
    val name: String,
    val songs: List<Song>,
    val error: String?
) {
    val ok: Boolean get() = error == null
    val count: Int get() = songs.size
}
