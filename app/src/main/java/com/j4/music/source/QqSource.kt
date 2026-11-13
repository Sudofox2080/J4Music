package com.j4.music.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐。
 *
 * 这个源的复杂度不在解析，而在**四条静默失败的链**（全都是 HTTP 200、看着正常、没有数据）：
 *
 *  1) `musics.fcg` 的 `sign`：不是标准参数签名，而是一段固定打乱规则（见 [QqSign.zzcSign]）。
 *     算错返回 `{"code":2000}`，与音质无关。
 *  2) `comm` 必须是**精简版**：只留 `{cv, ct, uin}`（规格 2.1.2 实测三种稳定形态）。
 *     多塞 `format` / `platform` / `needNewCode`，服务端返回 `meta.query="??"` + 空 list。
 *  3) 播放地址：未登录时 vkey 一律 `result:104003`，但**同时**在 `midurlinfo[0].testfile2g`
 *     塞一个可播的 m4a 试听片段。绝不能把它当正片（表现为「起播、几十秒后没声音」）。
 *     判定唯一标准是 `purl` 是否为空。
 *  4) `filename` 拼法：有 `media_mid` 用它，没有则是 `<前缀><song_id><song_id><后缀>`
 *     （数字 id 重复两遍），拿 songmid 硬拼必然 104003。
 *
 * 两条网关的分工（规格 2 开头的说明）：`musics.fcg?sign=` 是签名网关，body 扁平、模块名当头 key；
 * `musicu.fcg` 不签名，body 是 `{comm, req_0, req_1}` 结构。搜索主用签名网关、备用 musicu；
 * 其余模块（详情/播放/歌词）规格给的都是 musicu 形态，所以直接打 musicu。
 */
class QqSource(
    private val env: SourceEnv = SourceEnv.current,
    private val driverOverride: HttpDriver? = null
) : MusicSource, SourceRegistry.Warmable {

    override val id: String = "qq"
    override val name: String = "QQ音乐"
    override val supportsLossless: Boolean = true
    override val requiresLogin: Boolean = true

    private val state: SourceStateStore get() = SourceEnv.state()
    private fun http(): HttpDriver = driverOverride ?: env.driver(id, apiHeaders())

    companion object {
        const val SOURCE_ID = "qq"
        const val BASE_U = "https://u.y.qq.com"
        const val BASE_CGI = "https://c.y.qq.com"
        const val PATH_MUSICS = "/cgi-bin/musics.fcg"
        const val PATH_MUSICU = "/cgi-bin/musicu.fcg"

        /** `ptuiCB('0','0','<url>','0','<msg>','')` —— 轮询响应是 JS 调用而不是 JSON */
        private val PTUI_CB = Regex(
            "ptuiCB\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'"
        )

        /** 签名网关必须带的 URL 尾巴（规格 2.6 末段） */
        private const val SIGN_TAIL = "&format=json&inCharset=utf-8&outCharset=utf-8&platform=yqq.json&needNewCode=0"

        /** QR 登录用的第三方 appid（ptlogin 体系固定值） */
        private const val PT_APPID = "716027609"
        private const val PT_DAID = "383"
        private const val PT_3RD_AID = "100497308"

        private const val KEY_UID = "uin"
        private const val KEY_QMKEYST = "qm_keyst"
        private const val KEY_SKEY = "skey"
        private const val KEY_GUID = "guid"
        private const val KEY_WID = "wid"
        private const val KEY_PLOGIN_SIG = "pt_login_sig"
        private const val KEY_QRSIG = "qrsig"
        private const val KEY_NICK = "nickname"

        /**
         * 档位 → (文件名前缀, 扩展名)，取自附录 A。
         * `C400` 是 96k aac（m4a），比 `M500` 的 128k mp3 更省流量但码率更低，
         * 所以 standard 用 M500，high 用 C600（192k aac），与实际「较高」语义对齐。
         */
        private val QUALITY_PREFIX: Map<String, Pair<String, String>> = linkedMapOf(
            "hires" to ("Q000" to "flac"),
            "lossless" to ("F000" to "flac"),
            "exhigh" to ("M800" to "mp3"),
            "high" to ("C600" to "m4a"),
            "standard" to ("M500" to "mp3")
        )

        /**
         * 各档位在 vkey 请求里对应的 `filename` 前缀的**全部**候选。
         * QQ 同一音质有多种容器（320k 有 mp3 与 ogg），请求前按 quality_map 有的优先，
         * 没有 map 时按这里的顺序逐个试。
         */
        private val PREFIX_CANDIDATES: Map<String, List<Pair<String, String>>> = linkedMapOf(
            "hires" to listOf("Q000" to "flac", "Q001" to "flac", "AI00" to "flac"),
            "lossless" to listOf("F000" to "flac", "A000" to "ape"),
            "exhigh" to listOf("M800" to "mp3", "O800" to "ogg"),
            "high" to listOf("C600" to "m4a", "O600" to "ogg", "M500" to "mp3"),
            "standard" to listOf("M500" to "mp3", "C400" to "m4a", "O400" to "ogg")
        )

        /** 前缀 → 档位，用来把响应里的 filename 反推成实际音质 */
        private val PREFIX_QUALITY: Map<String, String> =
            PREFIX_CANDIDATES.entries.flatMap { (q, list) -> list.map { it.first to q } }.toMap()

        /**
         * quality_map 的编码，严格按规格 2.2 给的写法：`M500:4317292|M800:10792943|F000:55397039`
         * 键是 vkey 的**文件名前缀**，值是该音质的字节数，值为 0 的档位不写。
         * 用 `:` 与 `|` 两个分隔符，因为这段串会被 Song.toJson 写进 playlist.songs，不能带引号与逗号。
         */
        fun encodeQualityMap(m: Map<String, Long>): String =
            m.entries.filter { it.value > 0 }.joinToString("|") { "${it.key}:${it.value}" }

        fun decodeQualityMap(s: String?): Map<String, Long> {
            if (s.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, Long>()
            s.split('|').forEach { seg ->
                val i = seg.indexOf(':')
                if (i > 0) {
                    val k = seg.substring(0, i).trim()
                    val v = seg.substring(i + 1).trim().toLongOrNull() ?: 0L
                    if (k.isNotEmpty() && v > 0) out[k] = v
                }
            }
            return out
        }

        fun prefixOf(quality: String): String =
            QUALITY_PREFIX[Quality.normalize(quality)]?.first ?: "M500"

        /**
         * 拼 vkey 的 filename。规格 2.3：
         *  - 有 media_mid：`<前缀><media_mid><后缀>`
         *  - 无 media_mid：`<前缀><song_id><song_id><后缀>`（数字 id 重复两遍，不是 songmid）
         *
         * @param prefix 文件名前缀；null 时按档位取默认前缀
         */
        fun fileName(quality: String, mediaMid: String, songId: String, prefix: String? = null): String {
            val def = QUALITY_PREFIX[Quality.normalize(quality)] ?: ("M500" to "mp3")
            val p = prefix ?: def.first
            val ext = PREFIX_CANDIDATES.values.flatten().firstOrNull { it.first == p }?.second ?: def.second
            val body = if (mediaMid.isNotEmpty()) mediaMid else songId + songId
            if (body.isEmpty()) return ""
            return p + body + "." + ext
        }

        /** 从 quality_map 里挑出「从目标档位往下第一个真实有文件的档位」，mao 为空时返回 null 表示不预判 */
        fun pickAvailable(qualityMap: Map<String, Long>, want: String): String? {
            for (q in Quality.ladder(want)) {
                if (PREFIX_CANDIDATES[q].orEmpty().any { qualityMap.containsKey(it.first) }) return q
            }
            return null
        }

        /** 档位 → 依次要试的前缀（按 quality_map 里有的优先，其余按默认顺序） */
        fun prefixesFor(quality: String, qualityMap: Map<String, Long>): List<String> {
            val all = PREFIX_CANDIDATES[Quality.normalize(quality)] ?: listOf("M500" to "mp3")
            val have = all.map { it.first }.filter { qualityMap.containsKey(it) }
            val rest = all.map { it.first }.filter { !qualityMap.containsKey(it) }
            return (have + rest).ifEmpty { listOf(prefixOf(quality)) }
        }

        /** 从响应里的 filename 反推实际音质；认不出时返回 null 由调用方保留原档位 */
        fun qualityOfFileName(name: String?): String? {
            if (name.isNullOrBlank() || name.length < 4) return null
            return PREFIX_QUALITY[name.substring(0, 4)]
        }
    }

    // ==================== 通用请求 ====================

    private fun apiHeaders(): Map<String, String> {
        // 签名网关只要 UA（带 Referer 反而会让某些模块走 web 分支，见规格 2.6 末段）
        val h = mutableMapOf("User-Agent" to Tiny.UA_QQ_ANDROID)
        val cookie = cookieHeader()
        if (cookie.isNotEmpty()) h["Cookie"] = cookie
        return h
    }

    /** 老接口（c.y.qq.com 的 fcg-*）必须带 Referer，否则 403 */
    private fun cgiHeaders(): Map<String, String> = apiHeaders() + mapOf("Referer" to "https://y.qq.com/portal/player.html")

    /** 详情/播放这类老 CGI 吃 MSIE9 UA（规格 2.2 明确写了） */
    private fun msieHeaders(): Map<String, String> = apiHeaders() + mapOf("User-Agent" to Tiny.UA_QQ_MSIE)

    /**
     * 组装 Cookie 头。规格 2.5 末段：`uin`/`qm_keyst`/`qqmusic_key` 三者，
     * 其中 `qqmusic_key` 是老名字、`qm_keyst` 是新名字，值相同，同时带上最稳。
     */
    private fun cookieHeader(): String {
        val parts = mutableListOf<String>()
        val keyst = state.get(Keys.qq(KEY_QMKEYST)).orEmpty()
        val skey = state.get(Keys.qq(KEY_SKEY)).orEmpty()
        val uin = uinRaw()
        if (uin.isNotEmpty()) {
            parts.add("uin=$uin")
            parts.add("euin=" + uin.removePrefix("o"))
            parts.add("qqmusic_uin=" + uin.removePrefix("o"))
        }
        if (keyst.isNotEmpty()) {
            parts.add("qm_keyst=$keyst")
            parts.add("qqmusic_key=$keyst")
        }
        if (skey.isNotEmpty()) parts.add("skey=$skey")
        return parts.joinToString("; ")
    }

    override fun isLoggedIn(): Boolean = state.get(Keys.qq(KEY_QMKEYST)).orEmpty().isNotEmpty()

    /** cookie 里的 uin 形态（带 o 前缀） */
    private fun uinRaw(): String = state.get(Keys.qq(KEY_UID)).orEmpty()

    /** 请求体里要的 uin 是纯数字 */
    private fun uinDigits(): String {
        val d = uinRaw().removePrefix("o").filter { it.isDigit() }
        return d.ifEmpty { "0" }
    }

    /**
     * 设备身份：规格 2.5 明确「未登录时 u.y.qq.com 不下发任何 Set-Cookie，
     * 设备身份完全靠请求体里的 guid 与 wid」，所以必须自己持久化一对固定值，否则每次都是新设备。
     */
    private fun guid(): String = state.getOrPut(Keys.qq(KEY_GUID)) { Md5Sha1.randomHexUpper(32) }
    private fun wid(): String = state.getOrPut(Keys.qq(KEY_WID)) { Md5Sha1.randomHexUpper(16) }

    /** 已登录时 comm 要追加 {qq, authst, tmeLoginType}（规格 2.5 凭证段） */
    private fun loginCommFields(o: JSONObject): JSONObject {
        if (!isLoggedIn()) return o
        o.put("qq", uinDigits())
        o.put("authst", state.get(Keys.qq(KEY_QMKEYST)).orEmpty())
        o.put("tmeLoginType", 2)
        return o
    }

    /**
     * 发签名网关请求（musics.fcg?sign=）。
     *
     * @param flat 扁平 body：模块名当顶层 key，必须**不含** comm 之外的壳
     * @return 解析后的 JSON；签名失败（code==2000）返回 null
     */
    private suspend fun signed(flat: JSONObject): JSONObject? {
        val body = flat.toString()
        val sign = QqSign.zzcSign(body)
        val url = BASE_U + PATH_MUSICS + "?sign=" + sign + SIGN_TAIL + "&_=" + Tiny.nowMs()
        val text = runCatching { http().postJson(url, body, apiHeaders()) }
            .onFailure { SourceLog.w(id, "musics.fcg 失败: ${it.message}") }
            .getOrNull() ?: return null
        val root = Jsonx.parse(text)
        if (root == null) {
            SourceLog.w(id, "musics.fcg 响应不是 JSON: ${text.take(80)}")
            return null
        }
        val code = Jsonx.long(root, "code", 0)
        if (code == 2000L) {
            SourceLog.w(id, "musics.fcg 返回 2000（签名或 comm 被拒）")
            return null
        }
        return root
    }

    /** 发不签名的 musicu.fcg 请求（body 是 {comm, req_0} 结构） */
    private suspend fun musicu(req: JSONObject, msie: Boolean = false): JSONObject? {
        val body = req.toString()
        val text = runCatching { http().postJson(BASE_U + PATH_MUSICU, body, if (msie) msieHeaders() else apiHeaders()) }
            .onFailure { SourceLog.w(id, "musicu.fcg 失败: ${it.message}") }
            .getOrNull() ?: return null
        val root = Jsonx.parse(text)
        if (root == null) SourceLog.w(id, "musicu.fcg 响应不是 JSON: ${text.take(80)}")
        return root
    }

    /** musicu 的最小 comm：规格第一坑，只留三个字段 */
    private fun minimalComm(ct: Any = 24, cv: Any = 4747474): JSONObject =
        loginCommFields(JSONObject().apply {
            put("cv", cv)
            put("ct", ct)
            put("uin", uinDigits().toLongOrNull() ?: 0L)
        })

    private fun moduleReq(idx: String, module: String, method: String, param: JSONObject): JSONObject =
        JSONObject().apply {
            put(idx, JSONObject().apply {
                put("module", module)
                put("method", method)
                put("param", param)
            })
        }

    /** 从 musicu 响应里取某个 req 号的 data */
    private fun reqData(resp: JSONObject?, idx: String = "req_0"): JSONObject? =
        Jsonx.obj(Jsonx.obj(resp, idx), "data")
            ?: Jsonx.obj(Jsonx.obj(resp, "req"), "data")
            ?: Jsonx.obj(resp, "req_1")?.let { Jsonx.obj(it, "data") }

    // ==================== 搜索 ====================

    /**
     * 搜索。
     *
     * 顺序是被实测数据逼出来的（服务端 /opt/j4-upstream/lib/mpapi.js 用同一套结论）：
     *   1. musicu.fcg + DoSearchForQQMusicMobile。**comm 里必须带 format:'json' 与
     *      platform:'wk_v17'** —— 缺了 platform 这个方法会回 `req_1.code=2001`，
     *      看起来像风控，其实是少了一个字段。
     *   2. 签名网关 + DoSearchForQQMusicDesktop（老路，近年大量 2001，留作兜底）。
     *   3. musicu + Desktop 方法（不签名版本）。
     */
    override suspend fun search(keyword: String, offset: Int, limit: Int): List<Song> {
        if (keyword.isBlank()) return emptyList()
        val perPage = limit.coerceIn(1, 60)
        val page = 1 + (offset / perPage)

        // 1) 手机端方法（实测唯一稳定能出歌的路）
        val req2 = JSONObject().apply {
            put("comm", mobileComm())
            put("req_1", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicMobile")
                put("param", JSONObject().apply {
                    put("search_type", 0)
                    put("query", keyword)
                    put("page_num", page)
                    put("num_per_page", perPage)
                    put("searchid", searchId())
                    put("grp", 1)
                })
            })
        }
        val mobileResp = runCatching { musicu(req2) }
            .onFailure { SourceLog.w(id, "手机端搜索请求失败: ${it.message}") }
            .getOrNull()
        runCatching { Jsonx.long(Jsonx.obj(mobileResp, "req_1"), "code", 0) }.getOrNull()?.let { rc ->
            if (rc != 0L) SourceLog.w(id, "手机端搜索 req_1.code=$rc（2001 多为 comm 缺 platform 或按 IP 限流）")
        }
        val mobile = runCatching { parseMobileSearch(reqData(mobileResp, "req_1")) }
            .onFailure { SourceLog.w(id, "手机端搜索解析失败: ${it.message}") }
            .getOrDefault(emptyList())
        if (mobile.isNotEmpty()) return mobile

        // 2) 签名网关 + Desktop
        val flat = JSONObject().apply {
            put("comm", signedComm())
            put("music.search.SearchCgiService", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                put("param", JSONObject().apply {
                    put("grp", 1)
                    put("num_per_page", perPage)
                    put("page_num", page)
                    put("query", keyword)
                    put("remoteplace", "txt.newclient.top")
                    put("search_type", 0)
                    put("searchid", searchId())
                })
            })
        }
        val primary = runCatching { parseSearch(signedSearch(signed(flat))) }
            .onFailure { SourceLog.w(id, "签名搜索解析失败: ${it.message}") }
            .getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary

        // 3) musicu + Desktop（不签名）
        SourceLog.w(id, "签名搜索空结果，切 musicu.fcg")
        val req = JSONObject().apply {
            put("comm", minimalComm())
            put("req_1", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                put("param", JSONObject().apply {
                    put("num_per_page", perPage)
                    put("page_num", page)
                    put("query", keyword)
                    put("search_type", 0)
                    put("grp", 1)
                    put("remoteplace", "txt.newclient.top")
                })
            })
        }
        val resp = runCatching { musicu(req) }
            .onFailure { SourceLog.e(id, "musicu 搜索失败: ${it.message}") }
            .getOrNull()
        return parseSearch(musicuSearchNode(resp))
    }

    /**
     * 手机端 comm。四个字段一个都不能少：
     * ct/cv 是客户端版本，format=json 决定返回形状（否则是 protobuf 化的嵌套），
     * platform=wk_v17 是 2024 年后加的必填项，缺它一律 2001。
     */
    private fun mobileComm(): JSONObject = loginCommFields(JSONObject().apply {
        put("ct", "19")
        put("cv", "1859")
        put("uin", "0")
        put("format", "json")
        put("platform", "wk_v17")
    })

    /** 签名网关的 comm（规格 2.1.1 的完整形态，与 musicu 的最小集不是一回事） */
    private fun signedComm(): JSONObject = loginCommFields(JSONObject().apply {
        put("_channelid", "0")
        put("_os_version", "6.2.9200-2")
        put("ct", "19")
        put("cv", "2151")
        put("guid", guid())
        put("patch", "118")
        put("psrf_access_token_expiresAt", 0)
        put("psrf_qqaccess_token", "")
        put("psrf_qqopenid", "")
        put("psrf_qqunionid", "")
        put("tmeAppID", "qqmusic")
        put("tmeLoginType", if (isLoggedIn()) 2 else 0)
        put("uin", uinDigits())
        put("wid", wid())
    })

    /** searchid：32 位大写 hex + 5 位随机数字（规格 2.1.1 末段） */
    private fun searchId(): String = Md5Sha1.randomHexUpper(32) + (10000 + (Math.random() * 89999).toInt())

    private fun signedSearch(root: JSONObject?): JSONObject? {
        if (root == null) return null
        return Jsonx.obj(root, "music.search.SearchCgiService") ?: Jsonx.obj(root, "req")
    }

    private fun musicuSearchNode(root: JSONObject?): JSONObject? =
        Jsonx.obj(root, "req_1") ?: Jsonx.obj(root, "req_0") ?: Jsonx.obj(root, "req")

    /**
     * 解析 Desktop 搜索节点。规格 2.1.1：要求 `body.code == 0 && req.code == 0`，
     * 数据在 `data.body.song.list[]`；`meta.query` 与请求的 query 不一致说明 comm 被判异常。
     */
    fun parseSearch(node: JSONObject?): List<Song> {
        if (node == null) return emptyList()
        val code = Jsonx.long(node, "code", 0)
        if (code != 0L) {
            SourceLog.w(id, "搜索模块 code=$code")
            return emptyList()
        }
        val data = Jsonx.obj(node, "data") ?: return emptyList()
        val body = Jsonx.obj(data, "body") ?: data
        val songObj = Jsonx.obj(body, "song") ?: body
        val list = Jsonx.arr(songObj, "list") ?: Jsonx.arr(data, "list") ?: return emptyList()
        val out = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            runCatching { parseSong(o) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** Android 方法的返回是 `data.body.item_song[]`，字段名与 Desktop 有出入，单独一套 */
    fun parseMobileSearch(data: JSONObject?): List<Song> {
        if (data == null) return emptyList()
        val body = Jsonx.obj(data, "body") ?: data
        val list = Jsonx.arr(body, "item_song") ?: Jsonx.arr(body, "list") ?: return emptyList()
        val out = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            runCatching { parseSong(o) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * 单曲映射。所有字段都做缺失容忍：任何一个字段缺了都只是那一项为空，不能让整条搜索结果消失。
     * 时长用 `interval`（秒）× 1000 —— 附录 D 第 14 条点名的 1000 倍错误就在这。
     */
    fun parseSong(o: JSONObject): Song? {
        val mid = Jsonx.firstStr(o, "mid", "songmid", "songMid")
        val numId = Jsonx.firstStr(o, "id", "songid", "songId")
        if (mid.isEmpty() && numId.isEmpty()) return null

        val singerArr = Jsonx.arr(o, "singer")
        val artistNames = ArrayList<String>()
        if (singerArr != null) {
            for (i in 0 until singerArr.length()) {
                val s = Jsonx.objAt(singerArr, i) ?: continue
                val n = Jsonx.firstStr(s, "name", "title")
                if (n.isNotEmpty()) artistNames.add(n)
            }
        }
        if (artistNames.isEmpty()) {
            val single = Jsonx.firstStr(o, "singerName", "singername")
            if (single.isNotEmpty()) artistNames.add(single)
        }

        val album = Jsonx.obj(o, "album")
        val albumMid = Jsonx.str(album, "mid").ifEmpty { Jsonx.firstStr(o, "albummid", "albumMid") }
        val albumName = Jsonx.firstStr(album, "name", "title").ifEmpty { Jsonx.firstStr(o, "albumname", "albumName") }
        val albumId = Jsonx.firstStr(album, "id").ifEmpty { Jsonx.str(o, "albumid") }

        val intervalSec = Jsonx.long(o, "interval", 0L)
        val durationMs = if (intervalSec > 0) intervalSec * 1000L else Jsonx.long(o, "duration", 0L)

        val pay = Jsonx.obj(o, "pay")
        val payPlay = Jsonx.int(pay, "pay_play", Jsonx.int(o, "pay_play", 0))
        val payAlbum = Jsonx.int(pay, "pay_album", 0)
        val file = Jsonx.obj(o, "file")
        val mediaMid = Jsonx.str(file, "media_mid").ifEmpty { Jsonx.firstStr(o, "strMediaMid", "media_mid") }.ifEmpty { mid }

        val qualityMap = readQualityMap(file, o)

        val cover = when {
            albumMid.isNotEmpty() -> "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            singerArr != null && singerArr.length() > 0 ->
                Jsonx.objAt(singerArr, 0)?.let { Jsonx.str(it, "mid") }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T001R500x500M000$it.jpg" }
            else -> null
        }

        val bestQuality = Quality.ladder("hires").firstOrNull { qualityMap.keys.contains(prefixOf(it)) } ?: "standard"

        return Song(
            id = "$SOURCE_ID:${mid.ifEmpty { numId }}",
            source = SOURCE_ID,
            title = Jsonx.firstStr(o, "title", "name", "songname"),
            artist = Tiny.joinArtists(artistNames),
            album = albumName,
            durationMs = durationMs,
            coverUrl = cover,
            // 需要付费/VIP 才能完整播放：pay_play 或 pay_album 非 0
            vip = payPlay != 0 || payAlbum != 0,
            quality = bestQuality,
            extra = buildMap {
                put("songmid", mid)
                put("song_id", numId)
                put("album_mid", albumMid)
                makeMap("album_id", albumId)
                put("media_mid", mediaMid)
                put("quality_map", encodeQualityMap(qualityMap))
                put("pay_play", payPlay.toString())
            }
        )
    }

    /** 只放非空 extra，避免空串污染（Song.toJson 会原样写出） */
    private inline fun MutableMap<String, String>.makeMap(k: String, v: String) {
        if (v.isNotEmpty()) put(k, v)
    }

    /**
     * 从 `track_info.file` / 搜索结果的 `file` 里读各音质字节数。
     * 规格给的字段名：`size_128mp3` `size_320mp3` `size_flac` `size_hires` `size_ape` `size_dolby`；
     * 老字段 `size_new[0..9]` 里也藏了一部分音质，按附录 A 的顺序兜一遍。
     */
    fun readQualityMap(file: JSONObject?, fallback: JSONObject? = null): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val f = file ?: fallback
        if (f == null) return out
        fun put(key: String, prefix: String) {
            val v = Jsonx.long(f, key, 0L)
            if (v > 0) out[prefix] = v
        }
        put("size_128mp3", "M500")
        put("size_320mp3", "M800")
        put("size_192aac", "C600")
        put("size_96aac", "C400")
        put("size_48aac", "C200")
        put("size_192ogg", "O600")
        put("size_96ogg", "O400")
        put("size_flac", "F000")
        put("size_ape", "A000")
        put("size_hires", "Q000")
        put("size_dolby", "D004")
        // 老字段 size_new：索引语义见附录 A，0..2 是 hires，3 是 320k ogg，5 是 640k ogg，6/9 是 DTS
        val sizeNew = Jsonx.arr(f, "size_new")
        if (sizeNew != null) {
            val map = listOf(0 to "Q000", 1 to "Q001", 2 to "AI00", 3 to "O800", 5 to "O801", 6 to "DT03", 9 to "DT03")
            map.forEach { (idx, prefix) ->
                if (sizeNew.length() > idx && sizeNew.optLong(idx, 0L) > 0L) out.putIfAbsent(prefix, sizeNew.optLong(idx, 0L))
            }
        }
        return out
    }

    // ==================== 歌曲详情 ====================

    /**
     * `music.pf_song_detail_svr` / `get_song_detail_yqq`（规格 2.2）。
     * 搜索结果的 file 字段有时是空的（尤其 Android 方法），详情是唯一能补 quality_map 与 media_mid 的途径。
     */
    suspend fun songDetail(mid: String, songId: String = ""): Song? {
        if (mid.isEmpty() && songId.isEmpty()) return null
        val param = JSONObject()
        if (mid.isNotEmpty()) param.put("song_mid", mid) else param.put("song_id", songId.toLongOrNull() ?: 0L)
        param.put("song_type", 0)
        val req = JSONObject().apply {
            put("comm", minimalComm(ct = "19", cv = "1859"))
            put("req", JSONObject().apply {
                put("module", "music.pf_song_detail_svr")
                put("method", "get_song_detail_yqq")
                put("param", param)
            })
        }
        val resp = runCatching { musicu(req, msie = true) }.getOrNull()
        val track = Jsonx.obj(Jsonx.obj(Jsonx.obj(resp, "req"), "data"), "track_info")
            ?: Jsonx.obj(Jsonx.obj(resp, "req_0"), "data")?.let { Jsonx.obj(it, "track_info") }
            ?: return null
        return runCatching { parseSong(track) }.getOrNull()
    }

    /** 搜索没给 quality_map 时，用详情补一次（只补一次，失败就算了） */
    private suspend fun enrich(song: Song): Song {
        val map = decodeQualityMap(song.extra["quality_map"])
        if (map.isNotEmpty() && song.extra["media_mid"].orEmpty().isNotEmpty()) return song
        val mid = song.extra["songmid"].orEmpty().ifEmpty { song.id.substringAfter(':', song.id) }
        val detail = runCatching { songDetail(mid, song.extra["song_id"].orEmpty()) }.getOrNull() ?: return song
        return song.copy(
            extra = song.extra + detail.extra.filterValues { it.isNotEmpty() } + mapOf(
                "quality_map" to song.extra["quality_map"].orEmpty().ifEmpty { detail.extra["quality_map"].orEmpty() }
            )
        )
    }

    // ==================== 播放地址 ====================

    /**
     * `vkey.GetVkeyServer` / `CgiGetVkey`（规格 2.3）。
     *
     * 降级链：quality_map 里真实有文件的档位优先，逐档往下试；每档再按前缀候选依次试
     * （320k 的 mp3 拿不到时 ogg 可能还在）。未登录必然 104003，此时直接返回 null，
     * **不回退 testfile2g**——这正是规格第 3 条坑。
     */
    override suspend fun resolveUrl(song: Song, preferQuality: String): PlayUrl? {
        val mid = song.extra["songmid"].orEmpty().ifEmpty { song.id.substringAfter(':', song.id) }
        if (mid.isEmpty()) return null
        val enriched = runCatching { enrich(song) }.getOrDefault(song)
        val mediaMid = enriched.extra["media_mid"].orEmpty().ifEmpty { mid }
        val songId = enriched.extra["song_id"].orEmpty()
        val qMap = decodeQualityMap(enriched.extra["quality_map"])
        val want = Quality.normalize(preferQuality)

        for (level in Quality.ladder(want)) {
            for (prefix in prefixesFor(level, qMap)) {
                val fn = fileName(level, mediaMid, songId, prefix)
                if (fn.isEmpty()) continue
                val verdict = fetchVkey(fn, mid)
                if (verdict.ok && !verdict.url.isNullOrEmpty()) {
                    val actual = qualityOfFileName(fn) ?: level
                    return PlayUrl(
                        url = verdict.url,
                        quality = actual,
                        downgraded = Quality.lowerThan(actual, want),
                        preview = verdict.preview,
                        headers = playHeaders()
                    )
                }
                if (verdict.noPermission) {
                    SourceLog.w(id, "无播放权限 mid=$mid fn=$fn: ${verdict.failReason}")
                    return null
                }
                SourceLog.w(id, "vkey 未给地址 fn=$fn: ${verdict.failReason}")
            }
        }
        return null
    }

    /**
     * 一次 vkey 请求。filename / songmid 都传单元素数组，响应取 `midurlinfo[0]`。
     * 判定统一交给 [PlayUrlJudge.qq]：它按 purl 是否为空说话，这就自动绕开 testfile2g。
     */
    private suspend fun fetchVkey(fileName: String, mid: String): QqUrlVerdict {
        val param = JSONObject().apply {
            put("filename", JSONArray().put(fileName))
            put("guid", guid())
            put("songmid", JSONArray().put(mid))
            put("songtype", JSONArray().put(0))
            put("uin", uinDigits())
            put("loginflag", if (isLoggedIn()) 1 else 0)
            put("platform", "20")
        }
        val req = JSONObject().apply {
            put("req_1", JSONObject().apply {
                put("module", "vkey.GetVkeyServer")
                put("method", "CgiGetVkey")
                put("param", param)
            })
            put("loginUin", uinDigits())
            // 播放接口的 comm 用字符串 uin + 老版本号（规格 2.3 的 body 样例），不是搜索那份
            put("comm", loginCommFields(JSONObject().apply {
                put("uin", uinDigits())
                put("format", "json")
                put("ct", 24)
                put("cv", 0)
            }))
        }
        val resp = runCatching { musicu(req) }
            .onFailure { SourceLog.w(id, "vkey 请求失败: ${it.message}") }
            .getOrNull()
        val verdict = PlayUrlJudge.qq(resp, "standard")
        return if (verdict.ok) verdict.copy(quality = qualityOfFileName(fileName) ?: verdict.quality) else verdict
    }

    private fun playHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Tiny.UA_QQ_ANDROID,
        "Referer" to "https://y.qq.com/"
    )

    // ==================== 歌词 ====================

    /**
     * 主链 `music.musichallSong.PlayLyricInfo` / `GetPlayLyricInfo`（规格 2.4.1，`crypt:0` `qrc:0` 拿明文）；
     * 备用 `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg`（必须带 Referer，否则 403）。
     * QRC 逐字要非标准 DES + zlib，这里不做，rich 留空。
     */
    override suspend fun lyric(song: Song): Lyric {
        val mid = song.extra["songmid"].orEmpty().ifEmpty { song.id.substringAfter(':', song.id) }
        val numId = song.extra["song_id"].orEmpty().toLongOrNull() ?: 0L
        if (mid.isEmpty() && numId == 0L) return Lyric()

        val param = JSONObject().apply {
            put("format", "json")
            put("crypt", 0)
            put("ct", 19)
            put("cv", 1873)
            put("interval", 0)
            put("lrc_t", 0)
            put("qrc", 0)
            put("qrc_t", 0)
            put("roma", 1)
            put("roma_t", 0)
            put("songID", if (numId != 0L) numId else mid)
            put("trans", 1)
            put("trans_t", 0)
            put("type", -1)
        }
        if (mid.isNotEmpty()) param.put("songMid", mid)
        val req = JSONObject().apply {
            put("comm", minimalComm(ct = 19, cv = 1873))
            put("req", JSONObject().apply {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                put("param", param)
            })
        }
        val first = runCatching { parseLyricNode(reqData(musicu(req), "req")) }
            .onFailure { SourceLog.w(id, "歌词主链失败: ${it.message}") }
            .getOrNull()
        if (first != null && !first.isEmpty) return first

        if (mid.isEmpty()) return first ?: Lyric()
        SourceLog.w(id, "歌词主链空，切 fcg_query_lyric_new")
        val url = "$BASE_CGI/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&g_tk=5381" +
            "&format=json&inCharset=utf8&outCharset=utf-8&nobase64=0&loginUin=${uinDigits()}&hostUin=0" +
            "&platform=yqq.json&needNewCode=0"
        val text = runCatching { http().get(url, cgiHeaders()) }
            .onFailure { SourceLog.w(id, "歌词备用链失败: ${it.message}") }
            .getOrNull()
        return runCatching { parseLyricJson(text) }.getOrDefault(first ?: Lyric())
    }

    /** 新版歌词节点：data.lyric / trans / roma 都是 base64 */
    fun parseLyricNode(data: JSONObject?): Lyric? {
        if (data == null) return null
        val lrc = decodeMaybeBase64(Jsonx.str(data, "lyric"))
        if (lrc.isEmpty()) return null
        return Lyric(
            lrc = lrc,
            translated = decodeMaybeBase64(Jsonx.str(data, "trans")),
            romanized = decodeMaybeBase64(Jsonx.str(data, "roma"))
        )
    }

    /** 老接口：顶层 lyric/trans，retcode 非 0 时失败 */
    fun parseLyricJson(text: String?): Lyric {
        val root = Jsonx.parse(text) ?: return Lyric()
        val ret = Jsonx.long(root, "retcode", 0L)
        if (ret != 0L) return Lyric()
        val lrc = decodeMaybeBase64(Jsonx.str(root, "lyric"))
        return Lyric(
            lrc = lrc,
            translated = decodeMaybeBase64(Jsonx.str(root, "trans")),
            romanized = decodeMaybeBase64(Jsonx.str(root, "roma"))
        )
    }

    /** QQ 的歌词字段是 base64，但也遇到过明文，两种都吃 */
    internal fun decodeMaybeBase64(s: String): String {
        if (s.isEmpty()) return ""
        if (Tiny.looksLikeLrc(s)) return Tiny.stripBom(s)
        val decoded = Md5Sha1.unbase64String(s) ?: return ""
        return if (Tiny.looksLikeLrc(decoded)) Tiny.stripBom(decoded) else ""
    }

    // ==================== 歌单 ====================

    /**
     * 歌单详情，主链 `music.srfDissInfo.aiDissInfo` / `uniform_get_Dissinfo`（规格 2.7）。
     * 数据在 `req_1.data.dirinfo`（单元信息）与 `req_1.data.songlist[]`（歌曲），
     * songlist 的字段是 `songname/songmid/songid/singer/albumname/albummid/interval/filename`，
     * 与搜索结果的名字不同（`songmid` 而不是 `mid`），所以单独一个解析器。
     */
    override suspend fun fetchPlaylist(remoteId: String): PlaylistDetail {
        val disstid = remoteId.substringAfter(':').trim()
        if (disstid.isEmpty()) return PlaylistDetail(remoteId, "", null, emptyList())

        val param = JSONObject().apply {
            put("disstid", disstid)
            put("enc_host_uin", "")
            put("tag", 1)
            put("userinfo", 1)
            put("song_begin", 0)
            put("song_num", 500)
        }
        val req = JSONObject().apply {
            put("comm", minimalComm(ct = 24, cv = 4747474))
            put("req_1", JSONObject().apply {
                put("module", "music.srfDissInfo.aiDissInfo")
                put("method", "uniform_get_Dissinfo")
                put("param", param)
            })
        }
        val resp = runCatching { musicu(req) }
            .onFailure { SourceLog.w(id, "歌单主链失败: ${it.message}") }
            .getOrNull()
        val primary = runCatching { parsePlaylist(reqData(resp, "req_1"), disstid) }
            .onFailure { SourceLog.w(id, "歌单解析失败: ${it.message}") }
            .getOrDefault(PlaylistDetail(disstid, "", null, emptyList()))
        if (primary.songs.isNotEmpty()) return primary

        // 备用：老 fcgi（直接给 cdlist，字段与上面不同）
        SourceLog.w(id, "歌单主链空，切 fcg_ucc_getcdinfo_byids_cp")
        val url = "$BASE_CGI/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1" +
            "&onlysong=0&disstid=$disstid&format=json&inCharset=utf8&outCharset=utf-8" +
            "&notice=0&platform=yqq.json&needNewCode=0&g_tk=5381&loginUin=${uinDigits()}"
        val text = runCatching { http().get(url, cgiHeaders()) }
            .onFailure { SourceLog.w(id, "歌单备用链失败: ${it.message}") }
            .getOrNull()
        val second = runCatching { parseLegacyPlaylist(text, disstid) }.getOrDefault(primary)
        return if (second.songs.isNotEmpty()) second else primary
    }

    /** `uniform_get_Dissinfo` 的 data 节点 */
    fun parsePlaylist(data: JSONObject?, fallbackId: String): PlaylistDetail {
        if (data == null) return PlaylistDetail(fallbackId, "", null, emptyList())
        val dirinfo = Jsonx.obj(data, "dirinfo") ?: data
        val id = Jsonx.firstStr(dirinfo, "dissid", "disstid", "tid").ifEmpty { fallbackId }
        val nameText = Jsonx.firstStr(dirinfo, "dissname", "diss_name", "title")
        val cover = Tiny.https(Jsonx.firstStr(dirinfo, "logo", "diss_cover", "picurl", "cover"))
        val songs = ArrayList<Song>()
        val list = Jsonx.arr(data, "songlist") ?: Jsonx.arr(dirinfo, "songlist")
        if (list != null) for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            runCatching { parsePlaylistSong(o) }.getOrNull()?.let { songs.add(it) }
        }
        return PlaylistDetail(id, nameText, cover, songs)
    }

    /** songlist 里的单曲：字段名与搜索不同（songmid/songid/songname/singer/albummid/filename） */
    fun parsePlaylistSong(o: JSONObject): Song? {
        val mid = Jsonx.firstStr(o, "songmid", "mid", "songMid")
        val numId = Jsonx.firstStr(o, "songid", "id")
        if (mid.isEmpty() && numId.isEmpty()) return null
        val singerArr = Jsonx.arr(o, "singer")
        val names = ArrayList<String>()
        if (singerArr != null) for (i in 0 until singerArr.length()) {
            val s = Jsonx.objAt(singerArr, i) ?: continue
            if (s.has("name")) names.add(Jsonx.str(s, "name")) else names.add(s.toString())
        }
        val fileName = Jsonx.str(o, "filename")
        val prefix = fileName.take(4).ifEmpty { "M500" }
        val intervalSec = Jsonx.long(o, "interval", 0L)
        val albumMid = Jsonx.firstStr(o, "albummid", "album_mid")
        // filename 里带的实际上就是 media_mid（`M500<media_mid>.mp3`）
        val mediaMid = fileName.removePrefix(prefix).substringBeforeLast('.').ifEmpty { mid }
        val qMap = LinkedHashMap<String, Long>()
        listOf(
            "size128" to "M500", "size320" to "M800", "sizeflac" to "F000",
            "sizeape" to "A000", "sizeogg" to "O600"
        ).forEach { (k, p) ->
            val v = Jsonx.long(o, k, 0L)
            if (v > 0) qMap[p] = v
        }
        return Song(
            id = "$SOURCE_ID:${mid.ifEmpty { numId }}",
            source = SOURCE_ID,
            title = Jsonx.firstStr(o, "songname", "name", "title"),
            artist = Tiny.joinArtists(names).ifEmpty { Jsonx.str(o, "singername") },
            album = Jsonx.firstStr(o, "albumname", "album_name"),
            durationMs = if (intervalSec > 0) intervalSec * 1000L else 0L,
            coverUrl = albumMid.takeIf { it.isNotEmpty() }?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" },
            vip = Jsonx.int(o, "pay", 0) != 0 || Jsonx.int(Jsonx.obj(o, "pay"), "pay_play", 0) != 0,
            quality = Quality.ladder("hires").firstOrNull { qMap.keys.contains(prefixOf(it)) } ?: "standard",
            extra = buildMap {
                put("songmid", mid)
                put("song_id", numId)
                put("album_mid", albumMid)
                put("media_mid", mediaMid)
                put("quality_map", encodeQualityMap(qMap))
                put("pay_play", Jsonx.int(Jsonx.obj(o, "pay"), "pay_play", 0).toString())
            }
        )
    }

    /** 老 fcgi 的 `cdlist[0]` */
    fun parseLegacyPlaylist(text: String?, fallbackId: String): PlaylistDetail {
        val root = Jsonx.parse(text) ?: return PlaylistDetail(fallbackId, "", null, emptyList())
        val cd = Jsonx.arr(root, "cdlist")?.let { Jsonx.objAt(it, 0) } ?: Jsonx.obj(root, "cdlist") ?: return PlaylistDetail(fallbackId, "", null, emptyList())
        val id = Jsonx.firstStr(cd, "disstid", "dissid").ifEmpty { fallbackId }
        val songs = ArrayList<Song>()
        Jsonx.arr(cd, "songlist")?.let { list ->
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val s = runCatching { parsePlaylistSong(o) }.getOrNull()
                    ?: runCatching { parseSong(o) }.getOrNull()
                s?.let { songs.add(it) }
            }
        }
        return PlaylistDetail(id, Jsonx.firstStr(cd, "dissname", "diss_name"), Tiny.https(Jsonx.str(cd, "logo")), songs)
    }

    /**
     * 用户自建歌单。规格 2.7 没给这个接口的实测，用 `fcg_user_created_diss`（老 web 接口，
     * 需要 hostuin）打底，登录态才有结果；失败返回空表而不是抛异常。
     */
    override suspend fun userPlaylists(): List<RemotePlaylist> {
        if (!isLoggedIn()) return emptyList()
        val url = "$BASE_CGI/fcgi-bin/fcg_user_created_diss?hostuin=${uinDigits()}&format=json" +
            "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&g_tk=" +
            QqSign.gtk(state.get(Keys.qq(KEY_SKEY)).orEmpty())
        val text = runCatching { http().get(url, cgiHeaders()) }
            .onFailure { SourceLog.w(id, "用户歌单请求失败: ${it.message}") }
            .getOrNull()
        return runCatching { parseUserPlaylists(text) }
            .onFailure { SourceLog.w(id, "用户歌单解析失败: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun parseUserPlaylists(text: String?): List<RemotePlaylist> {
        val root = Jsonx.parse(text) ?: return emptyList()
        val data = Jsonx.obj(root, "data") ?: root
        val arr = Jsonx.arr(data, "disslist") ?: Jsonx.arr(data, "list") ?: Jsonx.arr(root, "disslist") ?: return emptyList()
        val out = ArrayList<RemotePlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = Jsonx.firstStr(o, "tid", "dissid", "disstid", "dirid")
            if (id.isEmpty()) continue
            out.add(
                RemotePlaylist(
                    id = id,
                    name = Jsonx.firstStr(o, "dissname", "diss_name", "name", "title"),
                    cover = (Tiny.https(Jsonx.firstStr(o, "diss_cover", "logo", "cover", "picurl")) ?: "").ifEmpty { null },
                    songCount = Jsonx.int(o, "song_cnt", Jsonx.int(o, "songnum", Jsonx.int(o, "song_count", 0))),
                    source = SOURCE_ID
                )
            )
        }
        return out
    }

    // ==================== 登录 ====================

    /** 手动塞 cookie：从浏览器控制台复制 `uin=o0123; qm_keyst=...; skey=...` 的场景 */
    fun loginByCookie(cookie: String) {
        if (cookie.isBlank()) return
        cookie.split(';').map { it.trim() }.forEach { seg ->
            val i = seg.indexOf('=')
            if (i <= 0) return@forEach
            val k = seg.substring(0, i).trim()
            val v = seg.substring(i + 1).trim()
            if (v.isEmpty()) return@forEach
            when (k) {
                "uin", "wxuin", "qqmusic_uin" -> state.put(Keys.qq(KEY_UID), normalizeUin(v))
                "qm_keyst", "qqmusic_key", "str_music_key" -> state.put(Keys.qq(KEY_QMKEYST), v)
                "skey" -> state.put(Keys.qq(KEY_SKEY), v)
            }
        }
        env.putCookie(BASE_CGI, cookie)
        SourceLog.d(id, "cookie 已写入，loggedIn=${isLoggedIn()}")
    }

    private fun normalizeUin(v: String): String {
        val digits = v.removePrefix("o").filter { it.isDigit() }
        if (digits.isEmpty()) return v
        return if (v.startsWith("o") || v.length > 10) "o$digits" else digits
    }

    fun logout() {
        state.remove(Keys.qq(KEY_QMKEYST))
        state.remove(Keys.qq(KEY_SKEY))
        state.remove(Keys.qq(KEY_UID))
        state.remove(Keys.qq(KEY_QRSIG))
        state.remove(Keys.qq(KEY_PLOGIN_SIG))
        env.clearCookies()
    }

    // ---------- QR 登录链（规格 2.5 四段） ----------

    /**
     * 第一步：申请二维码。
     * `ptqrshow` 的响应体是 PNG，`qrsig` 在 `Set-Cookie` 里，由 CookieJar 收下；
     * 这里同时把 qrsig 从 jar 里抄一份进 KV，因为 [loginQrPoll] 要拿它算 ptqrtoken。
     * `imageUrl` 给 UI 直接当图片源（App 已开 cleartext，PNG 带 Cookie 请求也能出图）。
     */
    suspend fun loginQrCreate(): QrInfo {
        val t = Math.random()
        val imgUrl = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=$PT_APPID&e=2&l=M&s=3&d=72&v=4" +
            "&t=$t&daid=$PT_DAID&pt_3rd_aid=$PT_3RD_AID"
        // 先访问一次 xlogin 拿 pt_login_sig（ptqrlogin 要带，缺了会被判风控）
        runCatching { http().get("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=$PT_APPID&daid=$PT_DAID&style=33", ptHeaders()) }
            .onFailure { SourceLog.w(id, "xlogin 失败: ${it.message}") }
        val sig = extractCookie(env.cookieString("ptlogin2.qq.com"), "pt_login_sig")
            .ifEmpty { extractCookie(env.cookieString("xui.ptlogin2.qq.com"), "pt_login_sig") }
        if (sig.isNotEmpty()) state.put(Keys.qq(KEY_PLOGIN_SIG), sig)

        // ptqrshow 返回二进制 PNG，OkHttpDriver 按文本读会抛「空 body」，所以这里只关心 cookie 有没有到
        runCatching { http().get(imgUrl, ptHeaders()) }
            .onFailure { SourceLog.w(id, "ptqrshow 文本读取失败（属预期，PNG 不是文本）: ${it.message}") }
        val qrsig = extractCookie(env.cookieString("ptlogin2.qq.com"), "qrsig")
        if (qrsig.isNotEmpty()) state.put(Keys.qq(KEY_QRSIG), qrsig)
        else SourceLog.w(id, "没拿到 qrsig，轮询会失败")

        return QrInfo(
            url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=$PT_APPID&daid=$PT_DAID&pt_3rd_aid=$PT_3RD_AID",
            key = qrsig,
            imageUrl = imgUrl,
            pollIntervalMs = 2000L,
            expireAt = Tiny.nowMs() + 120_000L
        )
    }

    private fun extractCookie(cookie: String?, name: String): String {
        if (cookie.isNullOrEmpty()) return ""
        return cookie.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()
    }

    /**
     * 第二步：轮询。`ptqrtoken = hash33(qrsig)`。
     * 响应是 `ptuiCB('67','0','','0','已扫描','')` 这种 JS 调用，不是 JSON。
     * 状态码：0 成功 / 65 过期 / 66 未扫 / 67 待确认。
     * 成功后 URL 里带 `uin=` 与 `ptsigx=`，存进 KV 供 [finishQqLogin] 用。
     */
    suspend fun loginQrPoll(key: String): QrState {
        val qrsig = key.ifEmpty { state.get(Keys.qq(KEY_QRSIG)).orEmpty() }
        if (qrsig.isEmpty()) return QrState.Error("缺少 qrsig，请重新出码")
        val token = QqSign.hash33(qrsig, 0)
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=" + Tiny.enc("https://graph.qq.com/oauth2.0/login_jump") +
            "&ptqrtoken=$token&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-${Tiny.nowMs()}" +
            "&js_ver=20102616&js_type=1&login_sig=${state.get(Keys.qq(KEY_PLOGIN_SIG)).orEmpty()}" +
            "&pt_uistyle=40&aid=$PT_APPID&daid=$PT_DAID&pt_3rd_aid=$PT_3RD_AID&has_onekey=1"
        val text = runCatching { http().get(url, ptHeaders() + mapOf("Cookie" to "qrsig=$qrsig")) }
            .getOrElse { return QrState.Error("轮询失败: ${it.message}") }
        return parseQrPoll(text)
    }

    /** 纯函数：`ptuiCB('67','0','','0','已扫描','')` → QrState */
    fun parseQrPoll(text: String?): QrState {
        if (text.isNullOrBlank()) return QrState.Error("空响应")
        val m = PTUI_CB.find(text) ?: return QrState.Error("非 ptuiCB 响应: ${text.take(80)}")
        val code = m.groupValues[1]
        val redirect = m.groupValues[3]
        val msg = m.groupValues[5].trim()
        return when (code) {
            "0" -> {
                val q = parseQuery(redirect)
                q["uin"]?.let { state.put(Keys.qq(KEY_UID), normalizeUin(it)) }
                q["ptsigx"]?.let { state.put(Keys.qq("pt_sigx"), it) }
                if (msg.isNotEmpty()) state.put(Keys.qq(KEY_NICK), msg)
                QrState.Scanned(msg, "")
            }
            "66" -> QrState.Waiting
            "67" -> QrState.Scanned(msg, "")
            "65" -> QrState.Expired
            else -> QrState.Error("未知状态码 $code")
        }
    }

    private fun parseQuery(url: String): Map<String, String> {
        val q = runCatching { java.net.URI(url).rawQuery ?: url.substringAfter('?', "") }.getOrDefault("")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { seg ->
            val i = seg.indexOf('=')
            if (i <= 0) null else seg.substring(0, i) to runCatching {
                java.net.URLDecoder.decode(seg.substring(i + 1), "UTF-8")
            }.getOrDefault(seg.substring(i + 1))
        }.toMap()
    }

    /**
     * 第三步与第四步：check_sig → oauth2/authorize → `QQConnectLogin.LoginServer` / `QQLogin`。
     *
     * - `check_sig` **必须禁止跟随 302**（规格 2.5 第 4 点），p_skey 在 302 的 Set-Cookie 里，
     *   所以这一步单独用一个 followRedirects=false 的 driver；
     * - `authorize` 的 `Location` 里带 `code=`；
     * - `QQLogin` 的 param 只有 `{code}`，comm 带 `tmeLoginType:2`（QQ 登录；微信是 1）；
     * - 凭证里 `musicid` 是 QQ 号、`musickey` 是音乐 key，二者分别落进 uin 与 qm_keyst。
     */
    suspend fun finishQqLogin(): QrState {
        val sigx = state.get(Keys.qq("pt_sigx")).orEmpty()
        val uin = uinDigits()
        if (sigx.isEmpty()) return QrState.Error("缺少 ptsigx，请重新扫码")

        val checkUrl = "https://ssl.ptlogin2.graph.qq.com/check_sig?uin=$uin&pttype=1&service=ptqrlogin&nodirect=0" +
            "&ptsigx=" + Tiny.enc(sigx) + "&s_url=" + Tiny.enc("https://graph.qq.com/oauth2.0/login_jump") +
            "&ptlang=2052&ptredirect=100&aid=$PT_APPID&daid=$PT_DAID&j_later=0&low_login_hour=0&regmaster=0" +
            "&pt_login_type=3&pt_aid=0&pt_aaid=16&pt_light=0&pt_3rd_aid=$PT_3RD_AID"
        val noRedirect = driverOverride
            ?: env.driver(id, ptHeaders(), followRedirects = false)
        runCatching { noRedirect.get(checkUrl, ptHeaders() + mapOf("Referer" to "https://xui.ptlogin2.qq.com/")) }
            .onFailure { SourceLog.w(id, "check_sig 失败: ${it.message}") }

        val pskey = extractCookie(env.cookieString("qq.com"), "p_skey")
            .ifEmpty { extractCookie(env.cookieString("graph.qq.com"), "p_skey") }
            .ifEmpty { state.get(Keys.qq("p_skey")).orEmpty() }
        if (pskey.isEmpty()) SourceLog.w(id, "check_sig 没拿到 p_skey，g_tk 会是 hash33(\"\",5381)")

        val authUrl = "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=$PT_3RD_AID" +
            "&redirect_uri=" + Tiny.enc("https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/") +
            "&scope=get_user_info,get_app_friends&state=state&from_ptlogin=1&src=1&update_auth=1" +
            "&openapi=1010_1030&g_tk=" + QqSign.gtk(pskey) + "&auth_time=" + Tiny.nowMs() +
            "&ui=" + Md5Sha1.randomHexUpper(32)
        val loc = runCatching { http().get(authUrl, ptHeaders()) }.getOrNull().orEmpty()
        val code = Regex("[?&]code=([^&\"'\\s]+)").find(loc)?.groupValues?.getOrNull(1)
            ?: Regex("[?&]code=([^&\"'\\s]+)").find(env.cookieString("y.qq.com") ?: "")?.groupValues?.getOrNull(1)

        if (code.isNullOrEmpty()) {
            // 有时 authorize 直接把 key 写进 Cookie
            val direct = extractCookie(env.cookieString("y.qq.com"), "qm_keyst")
            if (direct.isNotEmpty()) {
                state.put(Keys.qq(KEY_QMKEYST), direct)
                return QrState.Success("uin=$uin; qm_keyst=$direct")
            }
            return QrState.Error("未从 authorize 拿到 code")
        }

        val req = JSONObject().apply {
            put("module", "QQConnectLogin.LoginServer")
            put("method", "QQLogin")
            put("param", JSONObject().put("code", code))
            put("comm", JSONObject().put("tmeLoginType", 2))
        }
        val resp = runCatching { musicu(req) }
            .getOrElse { return QrState.Error("QQLogin 请求失败: ${it.message}") }
            ?: return QrState.Error("QQLogin 空响应")

        return parseLoginCredential(resp, uin)
    }

    /** 纯函数：解析 QQLogin 凭证并落库。字段见规格 2.5「凭证字段」 */
    fun parseLoginCredential(resp: JSONObject, fallbackUin: String): QrState {
        val root = Jsonx.obj(resp, "req") ?: Jsonx.obj(resp, "req_0") ?: resp
        val code = Jsonx.long(root, "code", 0L)
        if (code != 0L) return QrState.Error("QQLogin code=$code ${Jsonx.str(root, "msg")}")
        val data = Jsonx.obj(root, "data") ?: root
        val musicid = Jsonx.firstStr(data, "musicid", "uin", "encryptUin")
        val key = Jsonx.firstStr(data, "musickey", "musicKey", "qm_keyst")
        if (key.isEmpty()) return QrState.Error("QQLogin 未返回 musickey")
        val finalUin = normalizeUin(musicid.ifEmpty { fallbackUin })
        state.put(Keys.qq(KEY_QMKEYST), key)
        state.put(Keys.qq(KEY_UID), finalUin)
        val cookie = "uin=$finalUin; qm_keyst=$key; qqmusic_key=$key"
        env.putCookie(BASE_CGI, cookie)
        SourceLog.d(id, "QQ 登录成功 uin=$finalUin")
        return QrState.Success(cookie)
    }

    private fun ptHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Tiny.UA_QQ_MSIE,
        "Referer" to "https://xui.ptlogin2.qq.com/"
    )

    override suspend fun warmUp() {
        guid()
        wid()
        if (isLoggedIn()) runCatching { search("晴天", 0, 1) }
    }
}
