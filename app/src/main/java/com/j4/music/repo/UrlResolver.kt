package com.j4.music.repo

import com.j4.music.source.MusicSource
import com.j4.music.source.PlayUrl

/**
 * 播放地址解析。
 *
 * 为什么要单独一层：Media3 的 Player 在「播放器准备好之前」就要拿到 URI，
 * 而地址要从网络现取（有时效、需要登录态）。这里做三件事：
 *  1) 按用户偏好音质逐级降级（lossless → exhigh → high → standard）；
 *  2) 本地文件优先，完全不联网（离线播放的核心路径）；
 *  3) 结果带短 TTL 缓存，避免 seek 或连续切歌时反复打接口。
 */
class UrlResolver(
    private val sourceProvider: (String) -> MusicSource?,
    private val qualityOf: () -> String = { "lossless" },
    /**
     * 跨源兜底：直连拿不到正式地址时，去别家找同一首的免费版本。
     *
     * 为什么需要：会员曲目在不带 cookie 时只给 null 或试听壳，
     * 而同一首歌在另一家往往免费。没有这一步，用户点热门歌就是
     * 「转圈然后没声音」。实现放在调用方（SourceRegistry.crossResolve），
     * 这里只留一个函数口，避免 repo 层反过来依赖音源注册表。
     */
    private val fallbackResolver: (suspend (com.j4.music.source.Song, String) -> PlayUrl?)? = null
) {

    private data class CacheEntry(val url: PlayUrl, val expireAt: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 64
    }

    /** 拿不到地址时抛出，供上层给出"版权受限"提示 */
    class NoUrlException(val songId: String, message: String) : Exception(message)

    fun resolve(song: com.j4.music.source.Song): PlayUrl {
        // 本地文件最高优先级：离线播放
        song.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            return PlayUrl(url = path, quality = "local", headers = emptyMap())
        }

        val key = song.id + "|" + qualityOf()
        synchronized(cache) {
            cache[key]?.let { if (it.expireAt > System.currentTimeMillis()) return it.url }
        }

        val src = sourceProvider(song.source)
            ?: throw NoUrlException(song.id, "未注册的音源 ${song.source}")

        val want = qualityOf()

        // 有一类音源自带固定音质（演示源只有 wav），它们的 resolveUrl 不认
        // preferQuality。硬塞进 5 档降级链会让同一首歌被解析五次：既多打四次
        // 网络请求，也把"解包 assets"这种一次性工作重复做四遍（日志里会出现
        // 四条相同的解包记录）。这里先问一次本音质的地址，只要拿到的质量
        // 和请求的不一样就说明这个源不吃这套，直接用它，不再降级。
        val first = runCatching { runBlockingCall(src, song, want) }.getOrNull()
        if (first != null && first.quality != want) {
            val ttl0 = if (first.preview) 30_000L else 5 * 60_000L
            synchronized(cache) { cache[key] = CacheEntry(first, System.currentTimeMillis() + ttl0) }
            return first
        }

        // 逐级降级链：宁可放到的音质低一点，也不要直接失败
        val ladder = buildList {
            add(want)
            QUALITY_ORDER.dropWhile { it != want }.drop(1).forEach { add(it) }
            if (want !in QUALITY_ORDER) QUALITY_ORDER.forEach { add(it) }
        }

        var last: PlayUrl? = first
        for (q in ladder) {
            val got = runCatching { runBlockingCall(src, song, q) }.getOrElse { e ->
                if (e is NoUrlException) null else null
            }
            if (got != null) {
                last = got
                // 只有拿到想要的质量才停；否则继续降级，但记住已有结果兜底
                if (got.quality == q) break
            }
        }

        val result = last ?: run {
            // 直连彻底失败时同样给上游一次机会，能播出一首别家的免费版本，
            // 也好过直接弹「版权受限」。
            upstreamAttempt(song, want)?.let { better ->
                val ttl = if (better.preview) 30_000L else 5 * 60_000L
                synchronized(cache) { cache[key] = CacheEntry(better, System.currentTimeMillis() + ttl) }
                return better
            }
            throw NoUrlException(song.id, "该曲目在 ${song.source} 上暂无可用音源（可能版权受限或需要会员）")
        }

        // 直连只拿到试听片段等于没拿到：会员曲目在未登录时就是这个待遇。
        // 交给上游再试一次（它会跨平台找免费版本），成功就用它的地址。
        if (result.preview) {
            upstreamAttempt(song, want)?.let { better ->
                val ttl = if (better.preview) 30_000L else 5 * 60_000L
                synchronized(cache) { cache[key] = CacheEntry(better, System.currentTimeMillis() + ttl) }
                return better
            }
        }

        // 试听片段只缓存 30 秒，正式地址缓存 5 分钟
        val ttl = if (result.preview) 30_000L else 5 * 60_000L
        synchronized(cache) { cache[key] = CacheEntry(result, System.currentTimeMillis() + ttl) }
        return result
    }

    private fun runBlockingCall(src: MusicSource, song: com.j4.music.source.Song, quality: String): PlayUrl? =
        kotlinx.coroutines.runBlocking { src.resolveUrl(song, quality) }

    /**
     * 让别的平台代理解析同一首歌。
     * 注意 song 保持原样：兜底实现会用 title/artist 去别的平台重新搜一遍，
     * 所以这里不需要改写 id。
     */
    private fun upstreamAttempt(song: com.j4.music.source.Song, quality: String): PlayUrl? {
        val fn = fallbackResolver ?: return null
        val got = runCatching { kotlinx.coroutines.runBlocking { fn(song, quality) } }.getOrNull() ?: return null
        return got.takeIf { it.url.isNotEmpty() }
    }

    fun invalidate(songId: String) {
        synchronized(cache) { cache.keys.removeAll { it.startsWith("$songId|") } }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    companion object {
        /** 从高到低的音质档位，命名与网易云接口的 level 对齐 */
        val QUALITY_ORDER = listOf("hires", "lossless", "exhigh", "high", "standard")
    }
}
