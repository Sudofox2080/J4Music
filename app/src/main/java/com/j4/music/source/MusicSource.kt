package com.j4.music.source

/**
 * 一个音源（网易云 / QQ / 本地）必须实现的最小契约。
 *
 * 之所以把“搜索”和“取播放地址”分成两个接口调用，是因为平台侧的限制：
 * 搜索结果里的 id 只是索引，真实可播地址有时效（QQ vkey
 * 几分钟就过期），所以地址必须每次播放时单独取，不能缓存进歌单。
 */
interface MusicSource {

    /** 音源标识，和 Song.source 一致 */
    val id: String

    /** 展示名 */
    val name: String

    /** 是否支持无损 */
    val supportsLossless: Boolean get() = true

    /** 需要登录才能取到完整版权 */
    val requiresLogin: Boolean get() = false

    /**
     * 是否参与「全源搜索」。
     *
     * 云端聚合源置 false：它本来就是服务器上那几家的二次封装，
     * 让它一起参与搜索只会白等一次超时（8 秒），表现为「搜索转圈半天」。
     * 它在取播放地址时仍然会被用到（兜底 / 会员地址）。
     */
    val searchable: Boolean get() = true

    /** 是否已经登录（本地音源恒为 true） */
    fun isLoggedIn(): Boolean

    /** 搜索。offset 用于翻页，limit 建议 30 */
    suspend fun search(keyword: String, offset: Int = 0, limit: Int = 30): List<Song>

    /** 取歌单详情；不支持远端歌单的源返回空列表 */
    suspend fun fetchPlaylist(remoteId: String): PlaylistDetail

    /**
     * 取播放地址。
     * @param preferQuality standard/high/exhigh/lossless/hires
     * @return 可播 URL；拿不到返回 null（前端据此提示“版权受限”）
     */
    suspend fun resolveUrl(song: Song, preferQuality: String = "lossless"): PlayUrl?

    /** 取歌词；没有歌词返回带空字符串的对象，方便前端统一处理 */
    suspend fun lyric(song: Song): Lyric

    /** 取用户在该平台的歌单列表；未登录返回空 */
    suspend fun userPlaylists(): List<RemotePlaylist> = emptyList()
}

/** 解析出来的播放地址 */
data class PlayUrl(
    val url: String,
    /** 实际拿到的音质；平台会静默降级，必须以服务端返回为准 */
    val quality: String,
    /** 平台是否降级了（要提示用户） */
    val downgraded: Boolean = false,
    /** 试听片段（非 VIP 时的常见情况），时长很短 */
    val preview: Boolean = false,
    /** 额外请求头，QQ 对 Referer/UA 有要求 */
    val headers: Map<String, String> = emptyMap(),
    /**
     * 实际出地址的平台 id。只在「跨源兜底」时非空：
     * 用户点的歌在 A 平台要会员，服务端改从 B 平台取到了同一首的免费版本。
     * 必须告诉用户，否则他会以为自己听到的是 A 平台的版本。
     */
    val via: String = "",
    /** 兜底平台给出的歌名（可能是 Live/翻唱版，与用户点的那首不同） */
    val viaTitle: String = ""
)

/** 歌词。LRC 与逐字/增强格式同时给出，前端优先用 rich */
data class Lyric(
    val lrc: String = "",
    val translated: String = "",
    val romanized: String = "",
    /** yrc 这类逐字歌词，格式由 lyricRichKind 标注 */
    val rich: String = "",
    val lyricRichKind: String = ""
) {
    val isEmpty: Boolean get() = lrc.isEmpty() && rich.isEmpty()
}

/** 远端歌单详情 */
data class PlaylistDetail(
    val id: String,
    val name: String,
    val cover: String?,
    val songs: List<Song>
)

/** 用户账号下的歌单（同步用） */
data class RemotePlaylist(
    val id: String,
    val name: String,
    val cover: String?,
    val songCount: Int,
    val source: String
)
