package com.j4.music.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云音乐。
 *
 * 链路选择（规格 1.4.3 的结论）：**明文 api 接口优先**，因为它是实测最全、实现最简单的一条；
 * weapi 只在登录态校验（/weapi/w/nuser/account/get）与二维码登录兜底时用；eapi 专门用来拿
 * yrc 逐字歌词 —— 那是明文 /api/song/lyric 拿不到的唯一东西。
 *
 * 三个必须记住的坑：
 *  1) /api/song/enhance/player/url/v1 **漏 encodeType 直接 400**；
 *  2) 未登录要 lossless 会被**静默降级**成 exhigh 且不报错，实际音质只能读响应里的 level；
 *  3) weapi/eapi 出错可能是 HTTP 200 + 0 字节，必须在 HttpDriver 层就判空。
 *
 * 解析逻辑都写成「JSONObject → 模型」的纯函数（下面对外的 parse* 系列），
 * 这样接口字段变了只改这里，不用动调用链。
 */
class NeteaseSource(
    private val env: SourceEnv = SourceEnv.current,
    /** 允许测试注入驱动；生产用 env.driver("netease") */
    private val driverOverride: HttpDriver? = null
) : MusicSource, SourceRegistry.Warmable {

    override val id: String = "netease"
    override val name: String = "网易云音乐"
    override val supportsLossless: Boolean = true
    override val requiresLogin: Boolean = true

    private val state: SourceStateStore get() = SourceEnv.state()
    private fun http(): HttpDriver = driverOverride ?: env.driver(id, apiHeaders())

    // ==================== 基础设施 ====================

    companion object {
        const val SOURCE_ID = "netease"
        const val BASE = "https://music.163.com"
        const val BASE_INTERFACE3 = "https://interface3.music.163.com"

        private const val PATH_SEARCH = "/api/cloudsearch/pc"
        private const val PATH_SEARCH_BAK = "/api/search/get/web"
        private const val PATH_DETAIL = "/api/v3/song/detail"
        private const val PATH_PLAY = "/api/song/enhance/player/url/v1"
        private const val PATH_LYRIC = "/api/song/lyric"
        private const val PATH_PLAYLIST = "/api/v6/playlist/detail"
        private const val PATH_USER_PLAYLIST = "/api/user/playlist"
        private const val PATH_QR_UNIKEY = "/api/login/qrcode/unikey"
        private const val PATH_QR_LOGIN = "/api/login/qrcode/client/login"
        private const val PATH_ACCOUNT = "/weapi/w/nuser/account/get"

        private const val KEY_COOKIE = "cookie"

        /** 二维码内容前缀，规格 1.7：本地拼字符串，不用请求服务端 */
        private const val QR_CONTENT_PREFIX = "https://music.163.com/login?codekey="

        /** 桌面客户端伪装头：缺 appver 会让部分接口降级到老逻辑 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 把 fee 映射成 Song.vip：0 免费 / 1 VIP / 4 付费专辑 / 8 低音质免费 */
        fun feeToVip(fee: Int): Boolean = fee == 1 || fee == 4

        fun songIdOf(song: Song): String = song.extra["song_id"]?.takeIf { it.isNotEmpty() }
            ?: song.id.substringAfter(':', song.id)

        /**
         * eapi 的 apiPath：规格 1.6 明确 digest 里用 /api/ 拼（请求打到 /eapi/）。
         * 这个函数单独暴露出来，是为了让 eapi 的拼接与 digest 保持一致、可单测。
         */
        fun eapiPath(path: String): String = path
    }

    private fun apiHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://music.163.com",
            "Origin" to "https://music.163.com",
            "Content-Type" to "application/x-www-form-urlencoded"
        )
        val cookie = cookieHeader()
        if (cookie.isNotEmpty()) h["Cookie"] = cookie
        return h
    }

    /** 组装 Cookie 头：MUSIC_U（或匿名 MUSIC_A）+ 设备伪装字段 */
    private fun cookieHeader(): String {
        val parts = mutableListOf<String>()
        val saved = state.get(Keys.netease(KEY_COOKIE)).orEmpty()
        if (saved.isNotEmpty()) parts.add(saved)
        val nmtid = state.getOrPut(Keys.netease("nmtid")) { NeteaseCrypto.randomHex32() }
        // 这几项是「本人份」的伪装，服务端核对不严但带上更稳（规格 1.1）
        listOf(
            "os=pc", "appver=8.9.70", "osver=10.15.7",
            "deviceId=j4music", "channel=netease",
            "NMTID=$nmtid", "_ntes_nnid=$nmtid,${Tiny.nowMs()}",
            "__remember_me=true"
        ).forEach { seg ->
            if (parts.none { it.substringBefore('=') == seg.substringBefore('=') }) parts.add(seg)
        }
        return parts.joinToString("; ")
    }

    /** 提取登录 cookie 里真正重要的 MUSIC_U / __csrf，存进 KV */
    private fun persistCookie(raw: String) {
        if (raw.isBlank()) return
        val keep = raw.split(";")
            .map { it.trim() }
            .filter { it.startsWith("MUSIC_U=") || it.startsWith("__csrf=") || it.startsWith("MUSIC_A=") || it.startsWith("NMTID=") }
            .joinToString("; ")
        if (keep.isNotEmpty()) state.put(Keys.netease(KEY_COOKIE), keep)
        // CookieJar 同步一份，eapi/weapi 走 OkHttp 时用它
        env.putCookie(BASE, keep)
    }

    private fun musicU(): String? {
        val saved = state.get(Keys.netease(KEY_COOKIE)).orEmpty()
        return saved.split(";").map { it.trim() }.firstOrNull { it.startsWith("MUSIC_U=") }?.substringAfter('=')
    }

    override fun isLoggedIn(): Boolean = !musicU().isNullOrEmpty()

    /**
     * 登录态校验：cookie 里有没有 MUSIC_U 只是一半，
     * 规格 1.7 说伪造 MUSIC_U 也不会报错、只会静默降级，所以这里以「本地有 MUSIC_U」为准，
     * 真正的有效性在 accountCheck() 里用 weapi 验一次（UI 的「刷新登录状态」按钮调它）。
     */
    suspend fun accountCheck(): Boolean {
        if (!isLoggedIn()) return false
        val body = JSONObject().toString()
        val url = "$BASE$PATH_ACCOUNT"
        return try {
            val resp = http().post(url, NeteaseCrypto.weapiBody(body, secretForWeapi()), apiHeaders())
            val o = Jsonx.parse(resp) ?: return false
            Jsonx.long(o, "code", 0) == 200L && o.optJSONObject("account") != null
        } catch (e: Throwable) {
            SourceLog.w(id, "accountCheck failed: ${e.message}")
            false
        }
    }

    /**
     * weapi 的 secret 需要与 encSecKey 成对，且这里用了纯 Kotlin 实现，
     * 所以直接把 secret 记在 KV 里当临时值（一次请求内一致即可）。
     */
    private fun secretForWeapi(): String = NeteaseCrypto.randomSecret()

    // ==================== 搜索 ====================

    override suspend fun search(keyword: String, offset: Int, limit: Int): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val pageNo = offset.coerceAtLeast(0)

        val primary = runCatching {
            val body = Tiny.form(
                "s" to keyword,
                "type" to "1",
                "limit" to limit.toString(),
                "offset" to pageNo.toString(),
                "total" to "true"
            )
            val text = http().post("$BASE$PATH_SEARCH", body, apiHeaders())
            parseCloudSearch(text)
        }.onFailure { SourceLog.w(id, "cloudsearch/pc failed: ${it.message}") }.getOrNull()

        if (!primary.isNullOrEmpty()) return primary

        // 备用：老接口字段名不同（artists/album/duration），必须用另一套解析器
        SourceLog.w(id, "cloudsearch 空或失败，切 search/get/web")
        return runCatching {
            val body = Tiny.form(
                "s" to keyword, "type" to "1", "limit" to limit.toString(), "offset" to pageNo.toString()
            )
            parseSearchWeb(http().post("$BASE$PATH_SEARCH_BAK", body, apiHeaders()))
        }.onFailure { SourceLog.e(id, "search/get/web failed: ${it.message}") }.getOrDefault(emptyList())
    }

    /** /api/cloudsearch/pc —— result.songs[]，字段 ar/al/dt/fee，带 privilege 时取 maxbr */
    fun parseCloudSearch(text: String?): List<Song> {
        val root = Jsonx.parse(text) ?: return emptyList()
        if (Jsonx.long(root, "code", 0) != 200L) return emptyList()
        val result = Jsonx.obj(root, "result") ?: return emptyList()
        val songs = Jsonx.arr(result, "songs") ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            parseCloudSong(o)?.let { out.add(it) }
        }
        return out
    }

    /** 单曲映射（cloudsearch 与 v3/detail 结构相同，共用） */
    fun parseCloudSong(o: JSONObject): Song? {
        val sid = Jsonx.str(o, "id")
        if (sid.isEmpty()) return null
        val name = Jsonx.firstStr(o, "name", "title")
        val ar = Jsonx.arr(o, "ar") ?: Jsonx.arr(o, "artists")
        val artists = ArrayList<String>()
        if (ar != null) for (i in 0 until ar.length()) {
            Jsonx.objAt(ar, i)?.let { artists.add(Jsonx.str(it, "name")) }
        }
        val al = Jsonx.obj(o, "al") ?: Jsonx.obj(o, "album")
        val albumId = Jsonx.str(al, "id")
        val albumName = Jsonx.str(al, "name")
        val cover = Tiny.https(Jsonx.str(al, "picUrl").ifEmpty { Jsonx.str(al, "blurPicUrl") })
        val dt = Jsonx.long(o, "dt", 0L).let { if (it > 0) it else Jsonx.long(o, "duration", 0L) }
        val fee = Jsonx.int(o, "fee", 0)
        val privilege = Jsonx.obj(o, "privilege")
        val maxBr = if (privilege != null) Jsonx.str(privilege, "maxbr") else ""

        return Song(
            id = "$SOURCE_ID:$sid",
            source = SOURCE_ID,
            title = name,
            artist = Tiny.joinArtists(artists),
            album = albumName,
            durationMs = dt,
            coverUrl = cover,
            vip = feeToVip(fee),
            quality = "standard",
            extra = buildMap {
                put("song_id", sid)
                put("album_id", albumId)
                put("fee", fee.toString())
                put("max_br", maxBr.ifEmpty { "0" })
            }
        )
    }

    /** /api/search/get/web —— result.songs[]，字段 artists[]/album/duration */
    fun parseSearchWeb(text: String?): List<Song> {
        val root = Jsonx.parse(text) ?: return emptyList()
        if (Jsonx.long(root, "code", 0) != 200L) return emptyList()
        val result = Jsonx.obj(root, "result") ?: return emptyList()
        val songs = Jsonx.arr(result, "songs") ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val sid = Jsonx.str(o, "id")
            if (sid.isEmpty()) continue
            val artists = Jsonx.arr(o, "artists")?.let { a ->
                (0 until a.length()).mapNotNull { Jsonx.objAt(a, it)?.let { x -> Jsonx.str(x, "name") } }
            } ?: listOf(Jsonx.str(o, "artistName"))
            val album = Jsonx.obj(o, "album")
            val fee = Jsonx.int(o, "fee", 0)
            out.add(
                Song(
                    id = "$SOURCE_ID:$sid",
                    source = SOURCE_ID,
                    title = Jsonx.firstStr(o, "name", "title"),
                    artist = Tiny.joinArtists(artists),
                    album = Jsonx.str(album, "name"),
                    durationMs = Jsonx.long(o, "duration", 0L),
                    coverUrl = Tiny.https(Jsonx.str(album, "picUrl")),
                    vip = feeToVip(fee),
                    extra = mapOf(
                        "song_id" to sid,
                        "album_id" to Jsonx.str(album, "id"),
                        "fee" to fee.toString(),
                        "max_br" to "0"
                    )
                )
            )
        }
        return out
    }

    // ==================== 详情 ====================

    /**
     * /api/v3/song/detail：c 必须是 JSON 数组字符串，元素只带 id。
     * 批量上限 1000（NeteaseMusic-API-main 注释），这里按 300 切，免得 URL 太长。
     */
    suspend fun songDetail(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Song>(ids.size)
        ids.chunked(300).forEach { chunk ->
            val c = JSONArray()
            chunk.forEach { c.put(JSONObject().put("id", it.toLongOrNull() ?: 0L)) }
            val body = "c=" + Tiny.enc(c.toString()) + "&ids=" + Tiny.enc(JSONArray(chunk.map { it.toLongOrNull() ?: 0L }).toString())
            val text = runCatching { http().post("$BASE$PATH_DETAIL", body, apiHeaders()) }
                .onFailure { SourceLog.w(id, "detail failed: ${it.message}") }
                .getOrNull()
            out.addAll(parseDetail(text))
        }
        return out
    }

    fun parseDetail(text: String?): List<Song> {
        val root = Jsonx.parse(text) ?: return emptyList()
        val songs = Jsonx.arr(root, "songs") ?: return emptyList()
        val priv = Jsonx.arr(root, "privileges")
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val song = parseCloudSong(o) ?: continue
            val p = priv?.optJSONObject(i)
            if (p != null) {
                val maxBr = Jsonx.str(p, "maxbr")
                val fee = Jsonx.int(p, "fee", song.extra["fee"]?.toIntOrNull() ?: 0)
                val st = Jsonx.int(p, "st", 0)
                val pl = Jsonx.int(p, "pl", 1)
                val vip = feeToVip(fee) || (maxBr.toLongOrNull() ?: 0L) < 320_000L
                out.add(
                    song.copy(
                        vip = vip,
                        extra = song.extra + mapOf(
                            "max_br" to maxBr.ifEmpty { "0" },
                            "fee" to fee.toString(),
                            "st" to st.toString(),
                            "pl" to pl.toString()
                        )
                    )
                )
            } else {
                out.add(song)
            }
        }
        return out
    }

    // ==================== 播放地址 ====================

    override suspend fun resolveUrl(song: Song, preferQuality: String): PlayUrl? {
        val sid = songIdOf(song)
        if (sid.isEmpty()) return null
        val want = Quality.normalize(preferQuality)

        // 逐档下降，第一档就成功就返回；每一档都读响应的 level 判断真实音质
        for (level in Quality.ladder(want)) {
            val verdict = fetchUrlLevel(sid, level)
            if (verdict.ok && verdict.url != null) {
                // 试听片段即使有 url 也要如实标记，前端会提示「试听」
                SourceLog.d(id, "url ok sid=$sid req=$level got=${verdict.quality} preview=${verdict.preview}")
                return PlayUrl(
                    url = verdict.url,
                    quality = verdict.quality,
                    downgraded = verdict.downgraded,
                    preview = verdict.preview,
                    headers = playHeaders()
                )
            }
            SourceLog.w(id, "level=$level 失败: ${verdict.failReason}")
            // -110 这种是硬失败，直接放弃后面更低的档位也不会有
            if (verdict.failReason?.contains("-110") == true) return null
        }
        return null
    }

    /**
     * 打 /api/song/enhance/player/url/v1。
     *
     * 参数关系（规格 1.4.1）：level 决定要哪一档，encodeType 只在 lossless/hires 才有意义，
     * 但**三个参数缺一不可**，漏 encodeType 直接 {"msg":"参数错误","code":400}。
     */
    private suspend fun fetchUrlLevel(sid: String, level: String): NeteaseUrlVerdict {
        val form = Tiny.form(
            "ids" to "[" + sid + "]",
            "level" to toApiLevel(level),
            // exhigh 及以下其实忽略它，但漏了就 400，所以一律带上
            "encodeType" to if (level == "lossless" || level == "hires") "flac" else "mp3"
        )
        val text = runCatching { http().post("$BASE$PATH_PLAY", form, apiHeaders()) }
            .onFailure { SourceLog.w(id, "player/url/v1 failed: ${it.message}") }
            .getOrNull()
        return PlayUrlJudge.netease(Jsonx.parse(text), level)
    }

    /** 内部档位名 → 网易 level 字符串 */
    fun toApiLevel(q: String): String = when (Quality.normalize(q)) {
        "hires" -> "hires"
        "lossless" -> "lossless"
        "exhigh" -> "exhigh"
        "high" -> "higher"
        else -> "standard"
    }

    /** 播放地址也要伪装的桌面头，否则部分 CDN 会 403 */
    private fun playHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "https://music.163.com/"
    )

    // ==================== 歌词 ====================

    override suspend fun lyric(song: Song): Lyric {
        val sid = songIdOf(song)
        if (sid.isEmpty()) return Lyric()

        // 1) 明文接口：lrc + tlyric + romalrc
        val plain = runCatching {
            val form = Tiny.form("id" to sid, "lv" to "-1", "tv" to "-1", "rv" to "-1", "kv" to "-1")
            parseLyricPlain(http().post("$BASE$PATH_LYRIC", form, apiHeaders()))
        }.onFailure { SourceLog.w(id, "lyric plain failed: ${it.message}") }.getOrNull()

        // 2) eapi 补 yrc 逐字：明文接口不返回 yrc，这是唯一的来源
        val yrc = runCatching { fetchYrcEapi(sid) }
            .onFailure { SourceLog.w(id, "lyric eapi failed: ${it.message}") }
            .getOrNull()

        if (plain == null && yrc == null) return Lyric()
        val base = plain ?: Lyric()
        return if (!yrc.isNullOrEmpty()) base.copy(rich = yrc, lyricRichKind = "yrc") else base
    }

    fun parseLyricPlain(text: String?): Lyric {
        val root = Jsonx.parse(text) ?: return Lyric()
        val lrc = Jsonx.str(Jsonx.obj(root, "lrc"), "lyric")
        val t = Jsonx.str(Jsonx.obj(root, "tlyric"), "lyric")
        val r = Jsonx.str(Jsonx.obj(root, "romalrc"), "lyric")
        val k = Jsonx.str(Jsonx.obj(root, "klyric"), "lyric")
        // tlyric/romalrc 缺失时是 {"version":0,"lyric":null} 而不是整个对象缺失，Jsonx.str 已把 null 归一成 ""
        return if (k.isNotEmpty() && lrc.isEmpty()) {
            Lyric(lrc = "", translated = t, romanized = r, rich = k, lyricRichKind = "klyric")
        } else {
            Lyric(lrc = lrc, translated = t, romanized = r)
        }
    }

    /**
     * eapi 逐字歌词。参数按规格 1.5.2 全给 "0"（注意不是 -1），yv=0 才会下发 yrc。
     * apiPath 用 /api/song/lyric/v1 算 digest（规格 1.6），请求打到 /eapi/song/lyric/v1。
     */
    private suspend fun fetchYrcEapi(sid: String): String {
        val apiPath = "/api/song/lyric/v1"
        val payload = JSONObject().apply {
            put("id", sid)
            put("cp", "false")
            put("tv", "0"); put("lv", "0"); put("rv", "0"); put("kv", "0")
            put("yv", "0"); put("ytv", "0"); put("yrv", "0")
        }.toString()
        val body = NeteaseCrypto.eapiBody(apiPath, payload)
        val headers = apiHeaders() + mapOf(
            "Cookie" to (cookieHeader() + "; os=android; appver=9.0.50")
        )
        val text = http().post("$BASE_INTERFACE3/eapi/song/lyric/v1", body, headers)
        val root = Jsonx.parse(text) ?: return ""
        return Jsonx.str(Jsonx.obj(root, "yrc"), "lyric")
    }

    // ==================== 歌单 ====================

    /**
     * /api/v6/playlist/detail。远端 id 支持 "id" 或纯数字两种写法：
     * 数据库里存的是 "netease:123"，前端也可能直接传 "123"，统一剥前缀。
     */
    override suspend fun fetchPlaylist(remoteId: String): PlaylistDetail {
        val pid = remoteId.substringAfter(':').trim()
        if (pid.isEmpty()) return PlaylistDetail(remoteId, "", null, emptyList())

        val text = runCatching {
            http().post("$BASE$PATH_PLAYLIST", Tiny.form("id" to pid, "n" to "1000", "s" to "0"), apiHeaders())
        }.onFailure { SourceLog.w(id, "playlist detail failed: ${it.message}") }.getOrNull()

        val root = Jsonx.parse(text)
            ?: return runCatching { fetchPlaylistWeapi(pid) }.getOrElse { PlaylistDetail(pid, "", null, emptyList()) }

        return parsePlaylistDetail(root, pid).let { detail ->
            if (detail.songs.isNotEmpty()) detail
            else runCatching { fetchPlaylistWeapi(pid) }.getOrDefault(detail)
        }
    }

    /** 明文接口掉线时的 weapi 备用（/weapi/v6/playlist/detail） */
    private suspend fun fetchPlaylistWeapi(pid: String): PlaylistDetail {
        val payload = JSONObject().apply {
            put("id", pid)
            put("n", 1000)
            put("s", 0)
        }.toString()
        val text = http().post("$BASE/weapi/v6/playlist/detail", NeteaseCrypto.weapiBody(payload), apiHeaders())
        val root = Jsonx.parse(text) ?: return PlaylistDetail(pid, "", null, emptyList())
        return parsePlaylistDetail(root, pid)
    }

    fun parsePlaylistDetail(root: JSONObject, requestedId: String): PlaylistDetail {
        val pl = Jsonx.obj(root, "playlist")
            ?: return PlaylistDetail(requestedId, "", null, emptyList())
        val id = Jsonx.str(pl, "id").ifEmpty { requestedId }
        val name = Jsonx.str(pl, "name")
        val cover = Tiny.https(Jsonx.str(pl, "coverImgUrl"))
        val tracks = Jsonx.arr(pl, "tracks") ?: Jsonx.arr(pl, "songs")
        val songs = ArrayList<Song>()
        if (tracks != null) for (i in 0 until tracks.length()) {
            val o = tracks.optJSONObject(i) ?: continue
            parseCloudSong(o)?.let { songs.add(it) }
        }
        return PlaylistDetail(id, name, cover, songs)
    }

    override suspend fun userPlaylists(): List<RemotePlaylist> {
        val uid = state.get(Keys.netease("uid")).orEmpty()
        if (uid.isEmpty()) {
            // 没存 uid 就先用 account 查一次，避免「登录了但用户歌单空」
            val fetched = runCatching { fetchUid() }.getOrNull()
            if (fetched.isNullOrEmpty()) return emptyList()
            state.put(Keys.netease("uid"), fetched)
            return userPlaylistsByUid(fetched)
        }
        return userPlaylistsByUid(uid)
    }

    private suspend fun fetchUid(): String? {
        if (!isLoggedIn()) return null
        val text = http().post("$BASE$PATH_ACCOUNT", NeteaseCrypto.weapiBody("{}"), apiHeaders())
        val root = Jsonx.parse(text) ?: return null
        return Jsonx.str(Jsonx.obj(root, "account"), "id").ifEmpty { null }
    }

    private suspend fun userPlaylistsByUid(uid: String): List<RemotePlaylist> {
        val text = runCatching {
            http().post(
                "$BASE$PATH_USER_PLAYLIST",
                Tiny.form("uid" to uid, "limit" to "1000", "offset" to "0"),
                apiHeaders()
            )
        }.onFailure { SourceLog.w(id, "user/playlist failed: ${it.message}") }.getOrNull()
        return parseUserPlaylists(text)
    }

    fun parseUserPlaylists(text: String?): List<RemotePlaylist> {
        val root = Jsonx.parse(text) ?: return emptyList()
        val pl = Jsonx.arr(root, "playlist") ?: return emptyList()
        val out = ArrayList<RemotePlaylist>(pl.length())
        for (i in 0 until pl.length()) {
            val o = pl.optJSONObject(i) ?: continue
            val pid = Jsonx.str(o, "id")
            if (pid.isEmpty()) continue
            out.add(
                RemotePlaylist(
                    id = pid,
                    name = Jsonx.str(o, "name"),
                    cover = Tiny.https(Jsonx.str(o, "coverImgUrl")),
                    songCount = Jsonx.int(o, "trackCount", 0),
                    source = SOURCE_ID
                )
            )
        }
        return out
    }

    // ==================== 登录 ====================

    /** 直接塞 cookie（用户从别处复制 MUSIC_U 的场景） */
    fun loginByCookie(cookie: String) {
        persistCookie(cookie)
        // 顺带把 uid 清掉，下次 userPlaylists 会重新取
        state.remove(Keys.netease("uid"))
        SourceLog.d(id, "cookie 已写入，含 MUSIC_U=${!musicU().isNullOrEmpty()}")
    }

    fun logout() {
        state.remove(Keys.netease(KEY_COOKIE))
        state.remove(Keys.netease("uid"))
        env.clearCookies()
    }

    /**
     * 第一步：/api/login/qrcode/unikey（form type=3）拿 unikey，
     * 第二步：本地拼 https://music.163.com/login?codekey=<unikey> 当二维码内容。
     */
    suspend fun loginQrCreate(): QrInfo {
        val text = http().post("$BASE$PATH_QR_UNIKEY", Tiny.form("type" to "3"), apiHeaders())
        val root = Jsonx.parse(text) ?: throw SourceHttpException("$BASE$PATH_QR_UNIKEY", 200, "unikey 响应不是 JSON")
        val code = Jsonx.long(root, "code", 0)
        val key = Jsonx.str(root, "unikey")
        if (code != 200L || key.isEmpty()) {
            throw SourceHttpException("$BASE$PATH_QR_UNIKEY", 200, "unikey 失败 code=$code")
        }
        return QrInfo(
            url = QR_CONTENT_PREFIX + key,
            key = key,
            pollIntervalMs = 2000L,
            expireAt = Tiny.nowMs() + 3 * 60_000L
        )
    }

    /**
     * 第三步：轮询 /api/login/qrcode/client/login（form key=&type=3）。
     * 800 过期 / 801 等待 / 802 已扫 / 803 成功（响应带整串 cookie）。
     */
    suspend fun loginQrPoll(key: String): QrState {
        val text = runCatching {
            http().post("$BASE$PATH_QR_LOGIN", Tiny.form("key" to key, "type" to "3"), apiHeaders())
        }.getOrElse { return QrState.Error("轮询失败: ${it.message}") }
        val root = Jsonx.parse(text) ?: return QrState.Error("轮询响应不是 JSON")
        return parseQrState(root)
    }

    fun parseQrState(root: JSONObject): QrState = when (Jsonx.long(root, "code", -1)) {
        800L -> QrState.Expired
        801L -> QrState.Waiting
        802L -> QrState.Scanned(Jsonx.str(root, "nickname"), Tiny.https(Jsonx.str(root, "avatarUrl")) ?: "")
        803L -> {
            val cookie = Jsonx.str(root, "cookie")
            if (cookie.isEmpty()) QrState.Error("登录成功但未下发 cookie") else {
                persistCookie(cookie)
                QrState.Success(cookie)
            }
        }
        else -> QrState.Error("未知状态 code=${Jsonx.long(root, "code", -1)}")
    }

    // ==================== 预热 ====================

    /** 预热：把 NMTID 建出来（第一次请求顺手拿到服务端下发的匿名 cookie） */
    override suspend fun warmUp() {
        state.getOrPut(Keys.netease("nmtid")) { NeteaseCrypto.randomHex32() }
        runCatching { search("test", 0, 1) }
    }
}
