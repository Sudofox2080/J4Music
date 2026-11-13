package com.j4.music.source

import android.util.Base64
import com.j4.music.App
import com.j4.music.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 开源上游音源：tlyanyu/multiPlatformMusicApi（跑在 J4 网关上）。
 *
 * 为什么换成它：网易与 QQ 两家的签名/风控（musics.fcg 的 zzcSign、musicu 的
 * platform=wk_v17、vkey 的 guid/uin/qm_keyst）一直要跟着平台改，自己维护一套
 * 只是在重复社区已经做过的事。这个开源项目把两家的 84 + 69 个端点都封装好了，
 * 路由沿用 NeteaseCloudMusicApi 的风格（/search、/song/url、/lyric），
 * 我们只在外面套一层带令牌的网关，并保留一个自己的小补丁（QQ 搜索的 comm）。
 *
 * 一个类同时服务两个平台，靠 [platform] 区分：
 *  - 网易云：platform=netease，歌曲 id 是数字
 *  - QQ 音乐：platform=qqmusic，歌曲 id 用 songmid（取地址/歌词都认它）
 */
class OpenApiSource(
    /** 上行接口里的平台名：netease / qqmusic */
    val platform: String,
    override val id: String,
    override val name: String,
    private val baseUrl: () -> String = { OpenApiConfig.baseUrl() },
    private val token: () -> String = { OpenApiConfig.token() },
    private val cookie: () -> String = { OpenApiConfig.cookie(platform) }
) : MusicSource {

    override val requiresLogin: Boolean = false

    override fun isLoggedIn(): Boolean = cookie().isNotEmpty() || serverLoggedIn

    /**
     * 服务器上是否有这家的登录态（网页控制台扫码后存在网关里的那份）。
     * 用 @Volatile 缓存，`isLoggedIn()` 是同步接口、不能在里面发网络请求，
     * 所以由 [refreshServerLogin] 在打开账号页/启动时刷新。
     */
    @Volatile
    private var serverLoggedIn: Boolean = false

    suspend fun refreshServerLogin(): Boolean {
        val st = runCatching { serverState() }.getOrNull()
        val plat = st?.optJSONObject("cookies")?.optJSONObject(platform)
        val ck = plat?.optString("cookie").orEmpty()
        serverLoggedIn = ck.isNotEmpty() && ck != "null"
        val uid = plat?.optString("uid").orEmpty()
        if (uid.isNotEmpty() && OpenApiConfig.uid(platform).isEmpty()) OpenApiConfig.saveUid(platform, uid)
        return serverLoggedIn
    }

    /* ---------------------------------------------------------------- */
    /* HTTP                                                              */
    /* ---------------------------------------------------------------- */

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            val base = baseUrl().trimEnd('/')
            if (base.isEmpty()) return@withContext null
            val url = StringBuilder(base).append(path)
            val q = StringBuilder()
            fun add(k: String, v: String) {
                if (v.isEmpty()) return
                if (q.isNotEmpty()) q.append('&')
                q.append(java.net.URLEncoder.encode(k, "UTF-8"))
                    .append('=')
                    .append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
            add("platform", platform)
            params.forEach { (k, v) -> add(k, v) }
            val ck = cookie()
            if (ck.isNotEmpty()) add("cookie", ck)
            if (q.isNotEmpty()) url.append('?').append(q)

            val req = Request.Builder()
                .url(url.toString())
                .header("X-J4-Token", token())
                .header("User-Agent", Http.UA_MOBILE)
                .get()
                .build()
            runCatching {
                Http.raw.newBuilder().callTimeout(java.time.Duration.ofSeconds(25)).build()
                    .newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            SourceLog.w(id, "HTTP ${resp.code} $path ${body.take(80)}")
                            return@use null
                        }
                        runCatching { JSONObject(body) }.getOrNull()
                    }
            }.onFailure { SourceLog.w(id, "${it.javaClass.simpleName} $path: ${it.message}") }
                .getOrNull()
        }

    /* ---------------------------------------------------------------- */
    /* 服务端登录态写入                                                  */
    /* ---------------------------------------------------------------- */

    /**
     * 把登录态写进网关（`/api/login/save`）。
     * 需求里的「分发会员」就是这个意思：号存在服务器上，下面所有设备共用，
     * 用户不必在每台手机上各登一遍。
     */
    suspend fun saveServerCookie(raw: String): Boolean = postJson("/api/login/save") {
        JSONObject().put("platform", platform).put("cookie", raw)
    }

    suspend fun clearServerCookie(): Boolean = postJson("/api/login/clear") {
        JSONObject().put("platform", platform)
    }

    private suspend fun postJson(path: String, build: () -> JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            val base = baseUrl().trimEnd('/')
            if (base.isEmpty()) return@withContext false
            val req = Request.Builder()
                .url(base + path)
                .header("X-J4-Token", token())
                .header("User-Agent", Http.UA_MOBILE)
                .post(build().toString().toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                Http.raw.newBuilder().callTimeout(java.time.Duration.ofSeconds(15)).build()
                    .newCall(req).execute().use { it.isSuccessful }
            }.onFailure { SourceLog.w(id, "POST $path: ${it.message}") }.getOrDefault(false)
        }

    /* ---------------------------------------------------------------- */
    /* 搜索                                                              */
    /* ---------------------------------------------------------------- */

    override suspend fun search(keyword: String, offset: Int, limit: Int): List<Song> {
        if (keyword.isBlank()) return emptyList()
        // 开源实现里 QQ 搜索偶发空结果（上游按 IP 突发限流，表现为 req_1.code=2001），
        // 重试一次就能过，别把一次抖动变成「搜不到歌」。
        repeat(2) { attempt ->
            val r = get(
                "/search",
                mapOf(
                    "keywords" to keyword,
                    "type" to "1",
                    "limit" to limit.coerceIn(1, 30).toString(),
                    "offset" to offset.toString()
                )
            )
            val songs = parseSearch(r)
            if (songs.isNotEmpty()) return backfillCovers(songs)
            if (attempt == 0) kotlinx.coroutines.delay(600)
        }
        return emptyList()
    }

    /**
     * 网易搜索的返回里 `album` 只有 `picId`（数字），**没有 picUrl**，
     * 于是列表页所有网易曲目都没有封面、播放页只能用「取首字」的占位图。
     * `/song/detail` 的 `al.picUrl` 是全的，所以缺封面时批量补一次。
     *
     * 只在确有缺失时才发请求，拿不到就原样返回 —— 封面是锦上添花，不该拖慢搜索。
     */
    private suspend fun backfillCovers(songs: List<Song>): List<Song> {
        // coverUrl 在 Song 里是可空的（解析拿不到就是 null），统一用 isNullOrEmpty 判
        val missing = songs.filter { it.coverUrl.isNullOrEmpty() }
        if (missing.isEmpty()) return songs
        val ids = missing.mapNotNull { s ->
            (s.extra["songid"] ?: s.extra["mid"])?.takeIf { it.isNotEmpty() }
                ?: s.id.substringAfter(':', "").takeIf { it.isNotEmpty() }
        }.distinct()
        if (ids.isEmpty()) return songs
        val detail = get("/song/detail", mapOf("ids" to ids.joinToString(","))) ?: return songs
        val arr = detail.optJSONArray("songs") ?: return songs
        val byId = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pic = o.optJSONObject("al")?.optString("picUrl").orEmpty()
                .ifEmpty { o.optJSONObject("album")?.optString("picUrl").orEmpty() }
            if (pic.isEmpty() || pic == "null") continue
            byId[o.optString("id")] = pic
        }
        if (byId.isEmpty()) return songs
        return songs.map { s ->
            if (!s.coverUrl.isNullOrEmpty()) s
            else {
                val key = s.extra["songid"] ?: s.extra["mid"] ?: s.id.substringAfter(':', "")
                val pic = byId[key]
                if (pic.isNullOrEmpty()) s else s.copy(coverUrl = pic)
            }
        }
    }

    private fun parseSearch(root: JSONObject?): List<Song> {
        val result = root?.optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { parseSong(o) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * 歌曲映射。两家的字段已经被开源层对齐成网易的形状：
     * `{ id, mid, name, ar:[{name}], al:{name,picUrl}, dt, fee }`。
     * QQ 额外给 `mid`，取地址与歌词都用 mid（数字 id 也能用，但要多打一次 song_detail）。
     */
    private fun parseSong(o: JSONObject): Song? {
        val title = o.optString("name").ifEmpty { o.optString("title") }
        val mid = o.optString("mid")
        val numId = o.optString("id")
        val rawId = when {
            platform == "qqmusic" && mid.isNotEmpty() -> mid
            numId.isNotEmpty() -> numId
            else -> mid
        }
        if (rawId.isEmpty()) return null

        val artists = StringBuilder()
        o.optJSONArray("ar")?.let { a ->
            for (i in 0 until a.length()) {
                val n = a.optJSONObject(i)?.optString("name").orEmpty()
                if (n.isNotEmpty()) {
                    if (artists.isNotEmpty()) artists.append(" / ")
                    artists.append(n)
                }
            }
        }
        if (artists.isEmpty()) {
            o.optJSONArray("artists")?.let { a ->
                for (i in 0 until a.length()) {
                    val n = a.optJSONObject(i)?.optString("name").orEmpty()
                    if (n.isNotEmpty()) {
                        if (artists.isNotEmpty()) artists.append(" / ")
                        artists.append(n)
                    }
                }
            }
        }

        val albumObj = o.optJSONObject("al") ?: o.optJSONObject("album")
        val album = albumObj?.optString("name").orEmpty()
        val cover = albumObj?.optString("picUrl")
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?: o.optString("pic").takeIf { it.isNotEmpty() && it != "null" }

        // dt 是毫秒；有些老接口给 duration（毫秒）或 interval（秒）
        var dur = o.optLong("dt", 0L)
        if (dur <= 0L) dur = o.optLong("duration", 0L)
        if (dur in 1..1000) dur *= 1000   // 防御：给成秒的情况

        val fee = o.optInt("fee", 0)

        val extra = LinkedHashMap<String, String>()
        if (mid.isNotEmpty()) extra["mid"] = mid
        if (numId.isNotEmpty()) extra["songid"] = numId
        extra["platform"] = platform

        return Song(
            id = id + ":" + rawId,
            source = id,
            title = title,
            artist = artists.toString(),
            album = album,
            durationMs = dur,
            coverUrl = cover,
            vip = fee == 1 || fee == 4,
            extra = extra
        )
    }

    /* ---------------------------------------------------------------- */
    /* 播放地址                                                          */
    /* ---------------------------------------------------------------- */

    override suspend fun resolveUrl(song: Song, preferQuality: String): PlayUrl? {
        val raw = rawId(song) ?: return null
        val level = LEVELS[preferQuality] ?: "exhigh"

        // 优先 /song/url/v1（按 level 说话），失败再退回老接口（按 br 说话）
        val fromV1 = get("/song/url/v1", mapOf("id" to raw, "level" to level))
        pickUrl(fromV1, preferQuality, level)?.let { return it }

        val br = BR[level] ?: 320000
        val fromBr = get("/song/url", mapOf("id" to raw, "br" to br.toString()))
        return pickUrl(fromBr, preferQuality, level)
    }

    /**
     * 从 `{data:[{url, br, size, level, fee, freeTrialInfo}]}` 里取地址。
     *
     * 「看起来成功但放不出来」的两种情况必须当失败：
     *  - url 是 null/空串（无版权、需要会员）；
     *  - url 只有域名、没有路径（QQ 未登录时只回 sip，路径是空的）。
     *
     * **不能拿 size == 0 当失败判据**：QQ 这个开源实现回的会员曲地址是完整可播的
     * `M800…mp3?guid=10000&vkey=…`（实测 206 + content-range 12.3MB），但 size 一栏
     * 常年是 0。以前按 size 判失败，结果把会员曲的正式地址全丢掉，症状就是
     * 「明明开了会员还是放不了」。
     */
    private fun pickUrl(root: JSONObject?, wantQuality: String, wantLevel: String): PlayUrl? {
        val arr = root?.optJSONArray("data") ?: return null
        if (arr.length() == 0) return null
        val o = arr.optJSONObject(0) ?: return null
        val url = o.optString("url")
        val size = o.optLong("size", 0L)
        if (url.isEmpty() || url == "null") return null
        val trimmed = url.substringAfter("://", "")
        if (!trimmed.contains('/')) return null          // 只有域名，没有实体文件
        val hasTicket = url.contains("vkey=") || url.contains("guid=") || url.contains("auth_info=")
        if (size == 0L && !hasTicket) return null        // 既没大小又没票据，才是真的空壳
        val br = o.optLong("br", 0L)
        // freeTrialInfo 出现就说明只给了试听片段（30 秒），与 br 无关
        val preview = o.optJSONObject("freeTrialInfo") != null || o.optLong("freeTrialInfo", 0L) > 0
        return PlayUrl(
            url = url,
            quality = wantQuality,
            downgraded = o.optString("level", wantLevel) != wantLevel,
            preview = preview
        )
    }

    /* ---------------------------------------------------------------- */
    /* 歌词                                                              */
    /* ---------------------------------------------------------------- */

    override suspend fun lyric(song: Song): Lyric {
        // QQ 的 /lyric 只认数字 songid：传 mid 会回 songID=0、lyric 为空，
        // 或者（更糟）回成另一首歌的歌词 —— 这正是「歌词和歌名对不上」在 QQ 源上的样子。
        val numId = song.extra["songid"]?.takeIf { it.isNotEmpty() && it != "null" }
            ?: if (platform == "qqmusic") qqNumericId(song) else null
        val raw = (if (platform == "qqmusic") numId else null) ?: rawId(song) ?: return Lyric()
        // lyric/new 是增强版：网易给 yrc（逐字）+ tlyric（翻译）
        val rich = get("/lyric/new", mapOf("id" to raw))
        parseLyric(rich)?.takeIf { !it.isEmpty }?.let { return it }
        val plain = get("/lyric", mapOf("id" to raw))
        return parseLyric(plain) ?: Lyric()
    }

    /**
     * QQ 的歌词接口要数字 songid。搜索出来的曲子本身带着这个字段，但「按 id 重建」的曲子
     * （比如从歌单/队列里恢复的 `qq:0039MnYb0qxYhV`）只有 mid，这时回搜一次把数字 id 找回来，
     * 否则歌词必然为空。找不到就返回 null，让调用方走别的路。
     */
    private suspend fun qqNumericId(song: Song): String? {
        val mine = rawId(song) ?: return null
        val kw = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (kw.isBlank()) return null
        val res = get("/search", mapOf("keywords" to kw, "type" to "1", "limit" to "20")) ?: return null
        val arr = res.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("mid") == mine) {
                val num = o.optString("id")
                if (num.isNotEmpty() && num != "0") return num
            }
        }
        return null
    }

    /**
     * 两种返回形状都要吃：
     *  - 网易/新接口：`{lrc:{lyric}, tlyric:{lyric}, yrc:{lyric}, romalrc:{lyric}}`
     *  - QQ 老接口：`{status, body:{lyric: base64, trans: base64}}`
     */
    private fun parseLyric(root: JSONObject?): Lyric? {
        if (root == null) return null
        val lrcObj = root.optJSONObject("lrc")
        if (lrcObj != null) {
            val lrc = lrcObj.optString("lyric")
            val trans = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
            val roma = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
            val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val klyric = root.optJSONObject("klyric")?.optString("lyric").orEmpty()
            val richText = yrc.ifEmpty { klyric }
            return Lyric(
                lrc = lrc,
                translated = trans,
                romanized = roma,
                rich = richText,
                lyricRichKind = when {
                    yrc.isNotEmpty() -> "yrc"
                    klyric.isNotEmpty() -> "krc"
                    else -> ""
                }
            )
        }
        val body = root.optJSONObject("body") ?: return null
        val lrc = decodeMaybeBase64(body.optString("lyric"))
        val trans = decodeMaybeBase64(body.optString("trans"))
        return Lyric(lrc = lrc, translated = trans)
    }

    /** QQ 的歌词字段是 base64（明文时也常见），两种都要对 */
    private fun decodeMaybeBase64(s: String): String {
        if (s.isEmpty()) return ""
        if (s.contains('[') && s.contains(':') && s.contains(']')) return s
        return runCatching {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(s)
    }

    /* ---------------------------------------------------------------- */
    /* 歌单                                                              */
    /* ---------------------------------------------------------------- */

    override suspend fun fetchPlaylist(remoteId: String): PlaylistDetail {
        val r = get("/playlist/detail", mapOf("id" to remoteId))
        val p = r?.optJSONObject("playlist") ?: r?.optJSONObject("result") ?: return PlaylistDetail(remoteId, "", null, emptyList())
        val name = p.optString("name")
        val cover = p.optString("coverImgUrl").takeIf { it.isNotEmpty() && it != "null" }
        val tracks = p.optJSONArray("tracks")
        val songs = ArrayList<Song>()
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val o = tracks.optJSONObject(i) ?: continue
                runCatching { parseSong(o) }.getOrNull()?.let { songs.add(it) }
            }
        }
        return PlaylistDetail(remoteId, name, cover, songs)
    }

    override suspend fun userPlaylists(): List<RemotePlaylist> {
        val uid = OpenApiConfig.uid(platform)
        if (uid.isEmpty()) return emptyList()
        val r = get("/user/playlist", mapOf("uid" to uid)) ?: return emptyList()
        val arr = r.optJSONArray("playlist") ?: return emptyList()
        val out = ArrayList<RemotePlaylist>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RemotePlaylist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    cover = o.optString("coverImgUrl").takeIf { it.isNotEmpty() && it != "null" },
                    songCount = o.optInt("trackCount"),
                    source = id
                )
            )
        }
        return out
    }

    /* ---------------------------------------------------------------- */
    /* 登录（扫码 / 粘 Cookie）                                          */
    /* ---------------------------------------------------------------- */

    /**
     * 开源上游的登录接口把真正的内容包在 `body` 里（`{status,body:{code,data...},cookie}`），
     * 而搜索之类的接口是平铺的。这里统一拆一层，避免每个调用点写 optJSONObject("body") ?: …
     */
    private fun unwrap(o: JSONObject?): JSONObject {
        if (o == null) return JSONObject()
        return o.optJSONObject("body") ?: o
    }

    /** 申请一个二维码：返回 (key, 二维码图片 dataURL) */
    suspend fun qrCreate(): Pair<String, String> {
        val keyRaw = get("/login/qr/key") ?: return "" to ""
        val data = unwrap(keyRaw).optJSONObject("data")
        val key = data?.optString("unikey").orEmpty()
        if (key.isEmpty()) return "" to ""
        // QQ 的 key 接口顺带把图给全了，直接用
        val direct = data?.optString("qrImg").orEmpty()
        if (direct.startsWith("data:image")) return key to direct
        // 网易只给 unikey，二维码内容要自己拼，图片由网关的 /qr 画（服务端装了 qrcode）
        val text = "https://music.163.com/login?codekey=$key"
        val img = unwrap(get("/qr", mapOf("text" to text)))
        return key to img.optString("dataUri")
    }

    /**
     * 轮询扫码状态。返回三态：
     *  - 800 过期 / 801 待扫 / 802 待确认 / 803 成功（不同平台编号不完全一样，只认 803）
     * 拿到 803 时把 cookie 写进本地，后续取地址自动带上。
     */
    suspend fun qrPoll(key: String): Triple<Boolean, String, String> {
        val raw = get("/login/qr/check", mapOf("key" to key)) ?: return Triple(false, "网络错误", "")
        val r = unwrap(raw)
        val code = r.optInt("code")
        val msg = r.optString("message").ifEmpty { r.optString("msg") }
        if (code == 803) {
            // cookie 可能在外层（网易）也可能在 body 里（QQ 的合并 cookie），
            // 而且**形状可能是对象**：开源实现回的是 `{MUSIC_U: '...', __csrf: '...'}`。
            // 早先直接 optString 读，对象会被读成空串 —— 于是扫码成功、却仍然显示未登录。
            val ck = cookieString(raw).ifEmpty { cookieString(r) }
            if (ck.isNotEmpty()) {
                OpenApiConfig.saveCookie(platform, ck)
                val uin = Regex("(?:^|;\\s*)uin=o?(\\d{4,20})").find(ck)?.groupValues?.get(1)
                if (!uin.isNullOrEmpty()) OpenApiConfig.saveUid(platform, uin)
            }
            return Triple(true, msg.ifEmpty { "登录成功" }, ck)
        }
        return Triple(false, msg.ifEmpty { "等待扫码（$code）" }, "")
    }

    /**
     * 把「cookie」字段摊平成 `a=1; b=2`。
     * 上游既可能给字符串，也可能给对象（`{MUSIC_U: '...'}`），
     * 对象直接用 optString 读会得到空串，登录态就这么丢了。
     */
    private fun cookieString(o: JSONObject?, key: String = "cookie"): String {
        val v = o?.opt(key) ?: return ""
        return when (v) {
            is JSONObject -> flattenCookie(v)
            is org.json.JSONArray -> (0 until v.length()).mapNotNull { i ->
                when (val item = v.opt(i)) {
                    is JSONObject -> flattenCookie(item)
                    null -> null
                    else -> item.toString()
                }
            }.filter { it.isNotEmpty() }.joinToString("; ")
            else -> v.toString().trim().let { if (it == "null") "" else it }
        }
    }

    private fun flattenCookie(o: JSONObject): String {
        val sb = StringBuilder()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)?.toString()?.trim().orEmpty()
            if (v.isEmpty() || v == "null" || v == "[object Object]") continue
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(k).append('=').append(v)
        }
        return sb.toString()
    }

    /**
     * 服务器上的登录态（网关的 /api/state）。
     * 「服务器登录一次、下面所有设备都能用」是需求里说的分发会员 ——
     * 所以界面判断「有没有登录」不能只看本机 cookie，还要看服务器这份。
     */
    suspend fun serverState(): JSONObject? = get("/api/state")

    fun loginByCookie(raw: String) {        val ck = raw.trim()
        if (ck.isEmpty()) return
        OpenApiConfig.saveCookie(platform, ck)
        // uin / userid 从 cookie 里抠，用户歌单要用
        val uin = Regex("(?:^|;\\s*)(?:MUSIC_U|uin|userid|wxuin)=([^;]+)").find(ck)?.groupValues?.get(1)
        if (!uin.isNullOrEmpty() && uin.length > 20) {
            // MUSIC_U 是长串票据，不是 uin，别拿它当 uid
        } else if (!uin.isNullOrEmpty()) {
            OpenApiConfig.saveUid(platform, uin)
        }
    }

    fun logout() {
        OpenApiConfig.clearCookie(platform)
    }

    private fun rawId(song: Song): String? {
        song.extra["mid"]?.takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        song.extra["songid"]?.takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        val bare = song.id.substringAfter(':', "")
        return bare.ifEmpty { null }
    }

    companion object {
        /** 我们内部的音质名 → 开源接口的 level */
        val LEVELS = mapOf(
            "hires" to "hires",
            "lossless" to "lossless",
            "exhigh" to "exhigh",
            "high" to "higher",
            "standard" to "standard"
        )

        /** 老接口的 br 参数 */
        val BR = mapOf(
            "hires" to 1900000,
            "lossless" to 999000,
            "exhigh" to 320000,
            "higher" to 192000,
            "high" to 192000,
            "standard" to 128000
        )
    }
}

/**
 * 开源上游的地址与鉴权。
 *
 * 存在 KV 表里而不是写死在代码：换服务器/换令牌不用重新打包。
 * 这里刻意留空：仓库里不写任何真实服务器地址，用户第一次进「设置 → 云端 · 分发会员」
 * 填自己的地址与口令即可；没填时 isConfigured() 为 false，界面会提示去配置。
 */
object OpenApiConfig {
    const val DEFAULT_BASE = ""
    const val DEFAULT_TOKEN = ""

    private fun db() = App.db

    fun baseUrl(): String = db().get(KEY_BASE)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
    fun token(): String = db().get(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN

    fun isConfigured(): Boolean = baseUrl().isNotEmpty() && token().isNotEmpty()

    fun set(base: String, token: String) {
        if (base.isNotBlank()) db().put(KEY_BASE, base.trim().trimEnd('/'))
        if (token.isNotBlank()) db().put(KEY_TOKEN, token.trim())
    }

    fun cookie(platform: String): String = db().get("open.cookie.$platform").orEmpty()
    fun saveCookie(platform: String, cookie: String) = db().put("open.cookie.$platform", cookie.trim())
    fun clearCookie(platform: String) = db().put("open.cookie.$platform", "")
    fun uid(platform: String): String = db().get("open.uid.$platform").orEmpty()
    fun saveUid(platform: String, uid: String) = db().put("open.uid.$platform", uid.trim())

    private const val KEY_BASE = "open.baseUrl"
    private const val KEY_TOKEN = "open.token"
}
