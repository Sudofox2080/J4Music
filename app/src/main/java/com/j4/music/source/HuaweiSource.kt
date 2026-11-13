package com.j4.music.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * 华为音乐。
 *
 * 这个源的接口设计跟前三家差别最大，几处必须记住的点：
 *
 *  1) **请求体是全字符串的 JSON**：`{"contentType":"1","limit":"20",...}`，
 *     数字也要带引号。传真数字服务端返回参数错误。
 *  2) **域名只有一个能用**：规格说明所有 `*.music.huawei.com` 都被 DNS 劫持，
 *     只有 `api-drcn.music.dbankcloud.cn` 可达。所以这里不再做「多域名 fallback」，
 *     而是同一域名下的多接口 / 多 qualityType fallback。
 *  3) **播放接口是「按音质码要地址」**：`bycontentcode` 的 `qualityType` 决定拿到什么。
 *     未登录时 type=1 只给 64K 试听（响应 type=2，URL 走 /preview/），
 *     type=2/3 直接 142400（要会员）。所以兜底链是 15 → 7 → 3 → 2 → 1。
 *  4) **没有独立歌词接口**：LRC 与 HMRC 逐字都塞在搜索结果里
 *     （`contentExInfo.lyricAddres` 少一个 s 是历史拼写，`lyrics.wordByWordLyric` 是 HMRC）。
 *
 * extra 编码：`content_id`、`quality_map`（`1:<code>|3:<code>|7:<code>`，
 * 键是 qualityType 音质码，值是 fileCode）、`lyric_url`、`vip_flag`。
 */
class HuaweiSource(
    private val env: SourceEnv = SourceEnv.current,
    private val driverOverride: HttpDriver? = null
) : MusicSource, SourceRegistry.Warmable {

    override val id: String = "huawei"
    override val name: String = "华为音乐"
    override val supportsLossless: Boolean = true

    /** 未登录只能拿 64K 试听，但搜索与歌词都可用，所以不强制登录 */
    override val requiresLogin: Boolean = false

    private val state: SourceStateStore get() = SourceEnv.state()

    private fun http(headers: Map<String, String>): HttpDriver =
        driverOverride ?: env.driver(id, headers)

    companion object {
        const val SOURCE_ID = "huawei"

        /** 唯一可达域名（其余 *.music.huawei.com 全部 DNS 劫持） */
        const val BASE = "https://api-drcn.music.dbankcloud.cn"

        const val PATH_SEARCH = "/music-search-service/v10/service/fuzzysearch"
        const val PATH_PLAY = "/music-play-service/v3/service/file/bycontentcode"
        const val PATH_ALBUM = "/music-metacontent-service/v1/service/album/detail/byalbumcode"
        const val PATH_ARTIST = "/music-metacontent-service/v2/service/artist/detail/byartistcode"
        const val PATH_PLAYLIST = "/music-operation-service/v2/service/musiclist/detail/bymusiclistid"

        /** 成功码。其余都是错误码（142400 会员 / 142409 超钻 / 142411 超级会员） */
        const val CODE_OK = "000000"

        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "userId"

        /**
         * qualityType → 统一档位。
         * 1 = 标准（未登录时是 64K 试听）；2 = 较高；3 = 高品质；7 = 无损；15 = Hi-Res。
         * 30/96/999 是服务端「不认这个码」的出口（回 400），不放进矩阵。
         */
        private val QUALITY_TYPE = linkedMapOf(
            "hires" to "15",
            "lossless" to "7",
            "exhigh" to "3",
            "high" to "2",
            "standard" to "1"
        )

        /**
         * 音质码 → 统一档位。注意 contentExInfo.completeFileInfos 的键是
         * 1/2/3/15/17/5/6，其中 17/5/6 是环绕声等特殊格式，映射到 lossless 一档避免丢。
         */
        fun qualityOfType(code: String): String = when (code) {
            "15" -> "hires"
            "7", "17", "5", "6" -> "lossless"
            "3" -> "exhigh"
            "2" -> "high"
            else -> "standard"
        }

        /** 统一档位 → 首选音质码 */
        fun typeOfQuality(q: String): String = QUALITY_TYPE[Quality.normalize(q)] ?: "1"

        /** 试听要依次试的音质码：从高到低，1 放最后（1 永远能回，但只是试听） */
        fun typeLadder(want: String): List<String> {
            val out = linkedSetOf<String>()
            for (q in Quality.ladder(want)) {
                QUALITY_TYPE[q]?.let { out.add(it) }
            }
            out.add("1")
            return out.toList()
        }

        /** quality_map 编码：`1:<fileCode>|3:<fileCode>|7:<fileCode>|15:<fileCode>`，空值不写 */
        fun encodeQualityMap(m: Map<String, String>): String =
            m.entries.filter { it.value.isNotEmpty() }
                .sortedBy { it.key.toIntOrNull() ?: 999 }
                .joinToString("|") { "${it.key}:${it.value}" }

        fun decodeQualityMap(s: String?): Map<String, String> {
            if (s.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            s.split('|').forEach { seg ->
                val i = seg.indexOf(':')
                if (i > 0) {
                    val k = seg.substring(0, i).trim()
                    val v = seg.substring(i + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
                }
            }
            return out
        }

        fun contentIdOf(song: Song): String = song.extra["content_id"]?.takeIf { it.isNotEmpty() }
            ?: song.id.substringAfter(':', song.id)

        /**
         * 华为的时长字段 `times` 是毫秒，但实测有异常值（晴天给 476873198，
         * 相当于 132 小时）。规格说按 12 小时封顶，超过就当未知处理，
         * 否则进度条与「下一首」判断全乱。
         */
        fun sanitizeDuration(ms: Long): Long = if (ms in 1..43_200_000L) ms else 0L

        /** 请求体：所有值都是字符串（规格 4.1） */
        fun searchBody(keyword: String, offset: Int, limit: Int): String = JSONObject().apply {
            put("contentType", "1")
            put("correct", "1")
            put("limit", limit.toString())
            put("queryWord", keyword)
            put("start", offset.toString())
        }.toString()

        fun playBody(contentId: String, qualityType: String): String = JSONObject().apply {
            put("contentCode", contentId)
            put("contentType", "1")
            put("qualityType", qualityType)
        }.toString()
    }

    private fun jsonHeaders(): Map<String, String> {
        val h = mutableMapOf("Content-Type" to HttpDriver.JSON)
        val token = state.get(Keys.huawei(KEY_TOKEN)).orEmpty()
        if (token.isNotEmpty()) {
            h["Cookie"] = "userId=${state.get(Keys.huawei(KEY_USER_ID)).orEmpty()}; token=$token"
            h["Authorization"] = token
        }
        return h
    }

    private fun playHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://music.huawei.com/",
        "Range" to "bytes=0-"
    )

    override fun isLoggedIn(): Boolean = state.get(Keys.huawei(KEY_TOKEN)).orEmpty().isNotEmpty()

    // ==================== 搜索 ====================

    /**
     * fuzzysearch v10。三条 fallback：
     *  1) `correct=1`（带纠错，效果好）；
     *  2) `correct=0`（纠错关掉，能救回被改错的生僻歌名）；
     *  3) `contentType=2`（专辑搜索）—— 只取里面能映射成歌曲的条目，聊胜于无。
     *
     * 三次都空就返回空表；不必再试别的域名，因为规格里只有这一个域名可达。
     */
    override suspend fun search(keyword: String, offset: Int, limit: Int): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val size = limit.coerceIn(1, 50)
        val start = offset.coerceAtLeast(0)

        val primary = runCatching { fuzzySearch(keyword, start, size, correct = "1", contentType = "1") }
            .onFailure { SourceLog.w(id, "fuzzysearch 失败: ${it.message}") }
            .getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary

        SourceLog.w(id, "fuzzysearch 空，关掉纠错重试")
        val second = runCatching { fuzzySearch(keyword, start, size, correct = "0", contentType = "1") }
            .onFailure { SourceLog.w(id, "fuzzysearch(correct=0) 失败: ${it.message}") }
            .getOrDefault(emptyList())
        if (second.isNotEmpty()) return second

        SourceLog.w(id, "fuzzysearch 仍空，切 contentType=2 兜底")
        return runCatching { fuzzySearch(keyword, start, size, correct = "1", contentType = "2") }
            .onFailure { SourceLog.e(id, "fuzzysearch(contentType=2) 失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private suspend fun fuzzySearch(
        keyword: String,
        start: Int,
        limit: Int,
        correct: String,
        contentType: String
    ): List<Song> {
        val body = JSONObject().apply {
            put("contentType", contentType)
            put("correct", correct)
            put("limit", limit.toString())
            put("queryWord", keyword)
            put("start", start.toString())
        }.toString()
        val text = http(jsonHeaders()).postJson("$BASE$PATH_SEARCH", body, jsonHeaders())
        return parseSearch(text)
    }

    /** 解析 fuzzysearch：`{result:{resultCode, totalNum, hasNextPage, songSimpleInfos[]}}` */
    fun parseSearch(text: String?): List<Song> {
        val root = Jsonx.parse(text) ?: return emptyList()
        val result = Jsonx.obj(root, "result") ?: root
        val code = Jsonx.str(result, "resultCode")
        if (code.isNotEmpty() && code != CODE_OK) {
            SourceLog.w(id, "fuzzysearch resultCode=$code")
            return emptyList()
        }
        // contentType=2 时结果在 albumInfos 里，字段不同（没有 artistName），只做尽力映射
        val songs = Jsonx.arr(result, "songSimpleInfos") ?: Jsonx.arr(result, "albumInfos") ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            runCatching { parseSong(o) }
                .onFailure { SourceLog.d(id, "跳过脏条目: ${it.message}") }
                .getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * 单条映射（规格 4.1 映射表）。
     *
     * `contentExInfo` 与 `lyrics` 都是**字符串**（要二次解析），
     * 视频/有声书条目没有 contentID，直接丢掉。
     */
    fun parseSong(o: JSONObject): Song? {
        val contentId = Jsonx.firstStr(o, "contentID", "contentId", "contentCode")
        if (contentId.isEmpty()) return null

        val cover = imageUrl(Jsonx.obj(o, "picture"))
        val exInfo = Jsonx.obj(o, "contentExInfo") ?: Jsonx.obj(o, "contentExinfo")
        val lyricUrl = Jsonx.firstStr(o, "lyricAddress")
            .ifEmpty { Jsonx.str(exInfo, "lyricAddres") }
            .ifEmpty { Jsonx.str(exInfo, "lyricAddress") }

        val qualMap = parseFileInfos(exInfo)
        val best = qualMap.entries.minByOrNull { it.key.toIntOrNull() ?: 999 }
            ?.key?.let { qualityOfType(it) } ?: "standard"

        return Song(
            id = "$SOURCE_ID:$contentId",
            source = SOURCE_ID,
            title = Jsonx.firstStr(o, "contentName", "name", "subTitle"),
            artist = Jsonx.firstStr(o, "artistName", "singerName", "subTitle"),
            album = Jsonx.firstStr(o, "albumName"),
            // times 是毫秒，但有异常值，统一过一遍封顶
            durationMs = sanitizeDuration(Jsonx.long(o, "times", 0L)),
            coverUrl = Tiny.https(cover),
            vip = Jsonx.int(exInfo, "vip", Jsonx.int(o, "vip", 0)) == 1 ||
                Jsonx.str(o, "status") == "2",
            quality = best,
            extra = buildMap {
                put("content_id", contentId)
                put("quality_map", encodeQualityMap(qualMap))
                if (lyricUrl.isNotEmpty()) put("lyric_url", lyricUrl)
                put("vip_flag", Jsonx.int(exInfo, "vip", 0).toString())
                // 歌词随搜索结果一起下发，缓存下来省一次请求（值为原始字符串）
                Jsonx.str(o, "lyrics").takeIf { it.isNotEmpty() }?.let { put("lyrics", it) }
            }
        )
    }

    /** picture 有三种尺寸，取最大的；都没有返回空串 */
    private fun imageUrl(pic: JSONObject?): String =
        if (pic == null) "" else Jsonx.firstStr(pic, "bigImgURL", "middleImgURL", "smallImgURL", "bigImgUrl")

    /**
     * `contentExInfo.completeFileInfos` 的键是音质码（1/2/3/15/17/5/6），
     * 值是 `{fileCode, size, bitrate, format, ...}` 或直接就是 fileCode 字符串。
     * 两种形态都要吃。
     */
    fun parseFileInfos(exInfo: JSONObject?): Map<String, String> {
        if (exInfo == null) return emptyMap()
        val infos = Jsonx.obj(exInfo, "completeFileInfos") ?: exInfo
        val out = LinkedHashMap<String, String>()
        val keys = infos.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = infos.opt(k) ?: continue
            val code = when (v) {
                is JSONObject -> Jsonx.firstStr(v, "fileCode", "fileURL", "url")
                is String -> v
                else -> ""
            }
            if (code.isNotEmpty()) out[k] = code
        }
        return out
    }

    // ==================== 播放地址 ====================

    /**
     * 按音质码要地址，从高到低试（规格 4.2 的矩阵）。
     *
     * 一级失败的条件有三类，处理不同：
     *  - `142400/142409/142411`：会员墙，**这一档及以上都不行**，继续往低了试；
     *  - `type==2` 或 URL 含 `/preview/`：拿到的是试听，说明只能给 64K，
     *    这就是最终结果（标 preview=true），不再往下试；
     *  - `400`：音质码不认，往下试。
     */
    override suspend fun resolveUrl(song: Song, preferQuality: String): PlayUrl? {
        val cid = contentIdOf(song)
        if (cid.isEmpty()) return null
        val want = Quality.normalize(preferQuality)
        val types = typeLadder(want)

        var memberWall = false
        for (type in types) {
            val verdict = fetchByType(cid, type)
            if (verdict == null) continue
            if (verdict.ok && verdict.url != null) {
                // 试听片段：如实标记，前端提示「试听」
                return PlayUrl(
                    url = verdict.url,
                    quality = verdict.quality,
                    downgraded = Quality.lowerThan(verdict.quality, want),
                    preview = verdict.preview,
                    headers = playHeaders()
                )
            }
            if (verdict.failReason?.startsWith("需要") == true) {
                memberWall = true
                SourceLog.w(id, "type=$type 会员墙: ${verdict.failReason}")
            } else {
                SourceLog.w(id, "type=$type 失败: ${verdict.failReason}")
            }
        }
        if (memberWall) SourceLog.w(id, "cid=$cid 全档会员墙，最终无地址")
        return null
    }

    /** 打一次 bycontentcode 并判定 */
    private suspend fun fetchByType(contentId: String, qualityType: String): HuaweiUrlVerdict? {
        val body = playBody(contentId, qualityType)
        val text = runCatching { http(jsonHeaders()).postJson("$BASE$PATH_PLAY", body, jsonHeaders()) }
            .onFailure { SourceLog.w(id, "bycontentcode type=$qualityType 请求失败: ${it.message}") }
            .getOrNull() ?: return null
        val root = Jsonx.parse(text) ?: return null
        // 响应外层是 result 包裹（与搜索一致），判定器吃的是内层
        val node = Jsonx.obj(root, "result") ?: root
        return PlayUrlJudge.huawei(node, qualityOfType(qualityType))
    }

    // ==================== 歌词 ====================

    /**
     * 华为没有独立歌词接口，三条来源依次试：
     *  1) extra 里缓存过的 `lyrics`（搜索时就下发了，最常见，零请求）；
     *  2) `lyric_url` / `lyricAddress`（`lyric.tingmall.com` 的纯文本 LRC，可直接长用）；
     *  3) 重新查一次 fuzzysearch（用歌名+歌手），从结果里找同 contentID 的条目再取。
     *
     * HMRC 逐字在 `lyrics.wordByWordLyric` 里，是 `[0,2582]<0,287>晴<287,180>天` 形态，
     * 原样放进 Lyric.rich、kind="hmrc"，与网易 yrc 同构，前端同一套渲染能吃掉。
     */
    override suspend fun lyric(song: Song): Lyric {
        val cached = song.extra["lyrics"].orEmpty()
        if (cached.isNotEmpty()) {
            val lyric = parseLyricBlobs(cached, song.extra["lyric_url"].orEmpty())
            if (!lyric.isEmpty) return lyric
        }

        val lyricUrl = song.extra["lyric_url"].orEmpty()
        if (lyricUrl.isNotEmpty()) {
            val text = runCatching { http(jsonHeaders()).get(lyricUrl, emptyMap()) }.getOrNull()
            if (!text.isNullOrBlank() && Tiny.looksLikeLrc(text)) {
                return Lyric(lrc = Tiny.stripBom(text))
            }
        }

        // 最后再查一次搜索：歌词随内容一起下发，重查能补上
        val keyword = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (keyword.isBlank()) return Lyric()
        val cid = contentIdOf(song)
        val hits = runCatching { fuzzySearch(keyword, 0, 20, correct = "1", contentType = "1") }
            .getOrNull() ?: return Lyric()
        val match = hits.firstOrNull { contentIdOf(it) == cid } ?: hits.firstOrNull()
        val blob = match?.extra?.get("lyrics").orEmpty()
        if (blob.isEmpty()) return Lyric()
        return parseLyricBlobs(blob, match?.extra?.get("lyric_url").orEmpty())
    }

    /**
     * 解析歌词块。`lyrics` 字段是字符串，二次解析后形如
     * `{lyricUrl, wordByWordLyric, lyricFormat, ...}`；也可能直接就是 LRC 文本。
     * 纯函数，容忍任意残缺。
     */
    fun parseLyricBlobs(blob: String, fallbackUrl: String = ""): Lyric {
        if (blob.isBlank()) return Lyric()
        val parsed = Jsonx.parse(blob)
        if (parsed == null) {
            // 不是 JSON，那就是 LRC 正文
            return if (Tiny.looksLikeLrc(blob)) Lyric(lrc = Tiny.stripBom(blob)) else Lyric()
        }
        val lrcRaw = Jsonx.firstStr(parsed, "lyric", "lrc", "lyricText", "content")
        val hmrc = Jsonx.firstStr(parsed, "wordByWordLyric", "hmrc", "yrc")
        val lrc = when {
            Tiny.looksLikeLrc(lrcRaw) -> Tiny.stripBom(lrcRaw)
            hmrc.isNotEmpty() -> hmrcToLrc(hmrc)
            else -> ""
        }
        return Lyric(
            lrc = lrc,
            rich = hmrc,
            lyricRichKind = if (hmrc.isEmpty()) "" else "hmrc"
        )
    }

    /**
     * HMRC → LRC。格式 `[行起始,行时长]<字偏移,字时长>字<...>`，
     * 只保留行起始时间，丢掉字级信息。
     */
    fun hmrcToLrc(hmrc: String): String {
        val out = StringBuilder()
        val lineRegex = Regex("^\\[(\\d+),(\\d+)](.*)$")
        hmrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            val m = lineRegex.find(line) ?: return@forEach
            val startMs = m.groupValues[1].toLongOrNull() ?: return@forEach
            val content = m.groupValues[3].replace(Regex("<[^>]*>"), "").trim()
            if (content.isEmpty()) return@forEach
            out.append('[').append(formatMs(startMs)).append(']').append(content).append('\n')
        }
        return out.toString()
    }

    private fun formatMs(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        return "%02d:%02d.%02d".format(total / 60_000, (total % 60_000) / 1000, (total % 1000) / 10)
    }

    // ==================== 歌单 ====================

    /**
     * 歌单详情：`music-operation-service/v2/service/musiclist/detail/bymusiclistid`，
     * body 全字符串 JSON（同搜索风格）。规格只给了接口名，字段是推断的，
     * 所以下面所有取值都做了多别名兼容。
     */
    override suspend fun fetchPlaylist(remoteId: String): PlaylistDetail {
        val listId = remoteId.substringAfter(':').trim()
        if (listId.isEmpty()) return PlaylistDetail(remoteId, "", null, emptyList())
        val body = JSONObject().apply {
            put("musiclistId", listId)
            put("start", "0")
            put("limit", "500")
        }.toString()
        val text = runCatching { http(jsonHeaders()).postJson("$BASE$PATH_PLAYLIST", body, jsonHeaders()) }
            .onFailure { SourceLog.w(id, "歌单接口失败: ${it.message}") }
            .getOrNull()
        return parsePlaylist(text, listId)
    }

    fun parsePlaylist(text: String?, fallbackId: String): PlaylistDetail {
        val root = Jsonx.parse(text) ?: return PlaylistDetail(fallbackId, "", null, emptyList())
        val result = Jsonx.obj(root, "result") ?: root
        val code = Jsonx.str(result, "resultCode")
        if (code.isNotEmpty() && code != CODE_OK) {
            SourceLog.w(id, "歌单 resultCode=$code")
            return PlaylistDetail(fallbackId, "", null, emptyList())
        }
        val info = Jsonx.obj(result, "musiclistInfo") ?: Jsonx.obj(result, "playlistInfo") ?: result
        val arr = Jsonx.arr(result, "songSimpleInfos")
            ?: Jsonx.arr(result, "contentInfos")
            ?: Jsonx.arr(result, "songs")
            ?: Jsonx.arr(info, "songSimpleInfos")
        val songs = ArrayList<Song>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 歌单里可能是包了一层的 contentInfo
            val node = Jsonx.obj(o, "contentInfo") ?: Jsonx.obj(o, "songInfo") ?: o
            runCatching { parseSong(node) }.getOrNull()?.let { songs.add(it) }
        }
        return PlaylistDetail(
            Jsonx.firstStr(info, "musiclistId", "playlistId", "id").ifEmpty { fallbackId },
            Jsonx.firstStr(info, "musiclistName", "playlistName", "name"),
            Tiny.https(imageUrl(Jsonx.obj(info, "picture"))),
            songs
        )
    }

    /**
     * 用户歌单。规格没给接口（华为的「我的收藏」走账号中心，不在音乐 API 里），
     * 这里返回空表，让 UI 退化成「没有云端歌单」而不是报错。
     */
    override suspend fun userPlaylists(): List<RemotePlaylist> {
        if (!isLoggedIn()) return emptyList()
        SourceLog.d(id, "华为音乐没有用户歌单开放接口，返回空表")
        return emptyList()
    }

    // ==================== 登录 ====================

    /** 手动塞 token（华为账号体系在 HMS 里，第三方拿不到账号密码流） */
    fun loginByToken(userId: String, token: String) {
        if (token.isBlank()) return
        state.put(Keys.huawei(KEY_TOKEN), token)
        state.put(Keys.huawei(KEY_USER_ID), userId)
        env.putCookie(BASE, "userId=$userId; token=$token")
    }

    fun logout() {
        state.remove(Keys.huawei(KEY_TOKEN))
        state.remove(Keys.huawei(KEY_USER_ID))
        env.clearCookies()
    }

    override suspend fun warmUp() {
        // 只验一次域名可达性：华为域名被劫持时这里会立刻失败，不必等用户搜索才发现
        runCatching {
            fuzzySearch("test", 0, 1, correct = "1", contentType = "1")
        }.onFailure { SourceLog.w(id, "预热失败（域名可能不可达）: ${it.message}") }
    }
}
