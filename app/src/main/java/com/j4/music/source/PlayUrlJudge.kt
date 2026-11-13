package com.j4.music.source

/**
 * 各平台的「播放地址判定」纯函数集合。
 *
 * 为什么单独抽出来：附录 B 那套判定是四个源里最容易写错、又最不好在线调试的部分
 * （网易静默降级、QQ testfile2g 陷阱、华为 /preview/ 路径），
 * 抽成「输入响应 JSON、输出判定结果」的纯函数之后，可以直接拿实测响应粘贴进来对拍。
 * 四个源里的 resolveUrl 只负责「发请求 + 按判定结果拼 PlayUrl」。
 *
 * 所有函数都不抛异常，字段缺失一律按「拿不到」处理。
 */

/** 网易播放地址的判定结果 */
data class NeteaseUrlVerdict(
    val url: String?,
    val quality: String,
    val downgraded: Boolean,
    val preview: Boolean,
    val expireSec: Long,
    val failReason: String?
) {
    val ok: Boolean get() = !url.isNullOrEmpty()
}

/** QQ 播放地址的判定结果 */
data class QqUrlVerdict(
    val url: String?,
    val quality: String,
    val downgraded: Boolean,
    val preview: Boolean,
    val failReason: String?,
    /** 104003 表示该曲目对该账号无权限，UI 可以提前标 VIP */
    val noPermission: Boolean
) {
    val ok: Boolean get() = !url.isNullOrEmpty()
}

data class HuaweiUrlVerdict(
    val url: String?,
    val quality: String,
    val preview: Boolean,
    val auditionMs: Long,
    val failReason: String?
) {
    val ok: Boolean get() = !url.isNullOrEmpty()
}

object PlayUrlJudge {

    // ======================= 网易 =======================

    /**
     * 判定 /api/song/enhance/player/url/v1 的响应。
     *
     * 规格附录 B 的五条信号逐条落实：
     *  1. data[0].url == null → 失败；
     *  2. 请求 lossless/hires 但响应 level 变成了别的 → 静默降级，必须置 downgraded；
     *  3. freeTrialInfo != null → 只给了试听；
     *  4. data[0].code == 404 → 无可用地址；
     *  5. 整体 code == -110 → 地区/会员限制。
     *
     * 第 2 条是重点：服务端**不会报错**，只会把响应里的 level 改写成 exhigh，
     * 所以「实际音质」永远读响应的 level，不能信自己请求时传的值。
     */
    fun netease(resp: org.json.JSONObject?, requestedLevel: String): NeteaseUrlVerdict {
        val want = Quality.normalize(requestedLevel)
        if (resp == null) return NeteaseUrlVerdict(null, want, false, false, 0, "响应不是 JSON")

        val code = Jsonx.long(resp, "code", 0)
        if (code == -110L) return NeteaseUrlVerdict(null, want, false, false, 0, "地区或会员限制(-110)")
        if (code != 200L && code != 0L) {
            return NeteaseUrlVerdict(null, want, false, false, 0, "接口错误 code=$code")
        }

        val d = Jsonx.objAt(Jsonx.arr(resp, "data"), 0)
            ?: return NeteaseUrlVerdict(null, want, false, false, 0, "data 为空")

        val url = Jsonx.str(d, "url").ifEmpty { null }
        val itemCode = Jsonx.long(d, "code", 0)
        if (url == null) {
            val reason = when {
                itemCode == 404L -> "该曲目无可用地址(404)"
                itemCode == -110L -> "地区或会员限制(-110)"
                itemCode != 0L -> "单曲错误 code=$itemCode"
                else -> "url 为 null"
            }
            return NeteaseUrlVerdict(null, want, false, false, 0, reason)
        }

        // 服务端实际给的档位；缺字段时退回 br 推断
        val levelRaw = Jsonx.str(d, "level")
        val type = Jsonx.str(d, "type").lowercase()
        val br = Jsonx.long(d, "br", 0)
        val actual = if (levelRaw.isNotEmpty()) {
            Quality.normalize(levelRaw)
        } else when {
            type == "flac" || type == "ape" -> if (br >= 900_000) "hires" else "lossless"
            br >= 300_000 -> "exhigh"
            br >= 180_000 -> "high"
            else -> "standard"
        }

        val downgraded = Quality.lowerThan(actual, want)
        val trial = Jsonx.obj(d, "freeTrialInfo")
        val preview = trial != null && trial !== org.json.JSONObject.NULL
        val expi = Jsonx.long(d, "expi", 0)

        return NeteaseUrlVerdict(
            url = url,
            quality = actual,
            downgraded = downgraded,
            preview = preview,
            expireSec = expi,
            failReason = null
        )
    }

    // ======================= QQ =======================

    /**
     * 判定 vkey.GetVkeyServer/CgiGetVkey 的响应。
     *
     * 规格 2.3 的第二大坑：未登录时所有组合都返回 result:104003 且 purl 空，
     * 但接口**同时**会在 midurlinfo[i].testfile2g 里塞一个 C400 的 m4a 试听片段，
     * 而且这个链接实测能播（200 + audio/mp4）。绝对不能把它当正式地址返回，
     * 否则表现为「能起播但几十秒后无声」。
     *
     * 所以判定顺序固定为：purl 空 → 失败；purl 非空才算拿到。
     * testfile2g 只在「purl 空」的失败返回里作为说明信息出现。
     */
    fun qq(resp: org.json.JSONObject?, requestedQuality: String): QqUrlVerdict {
        val want = Quality.normalize(requestedQuality)
        if (resp == null) return QqUrlVerdict(null, want, false, false, "响应不是 JSON", false)

        val topCode = Jsonx.long(resp, "code", 0)
        if (topCode == 2000L) {
            // 签名缺失/comm 不合法，与音质无关
            return QqUrlVerdict(null, want, false, false, "签名或 comm 非法(2000)", false)
        }

        val req = Jsonx.obj(resp, "req_1") ?: Jsonx.obj(resp, "req")
        if (req == null) return QqUrlVerdict(null, want, false, false, "响应缺 req_1", false)
        val reqCode = Jsonx.long(req, "code", 0)
        if (reqCode != 0L) return QqUrlVerdict(null, want, false, false, "模块错误 code=$reqCode", false)

        val data = Jsonx.obj(req, "data")
            ?: return QqUrlVerdict(null, want, false, false, "响应缺 data", false)
        val info = Jsonx.objAt(Jsonx.arr(data, "midurlinfo"), 0)
            ?: return QqUrlVerdict(null, want, false, false, "midurlinfo 为空", false)

        val result = Jsonx.long(info, "result", 0)
        val purl = Jsonx.str(info, "purl")
        val hasTest = Jsonx.str(info, "testfile2g").isNotEmpty()
        if (purl.isEmpty()) {
            val reason = when {
                result == 104003L && hasTest -> "无播放权限(104003)，接口只给了试听片段"
                result == 104003L -> "无播放权限(104003)"
                result != 0L -> "无播放权限($result)"
                else -> "purl 为空"
            }
            return QqUrlVerdict(null, want, false, false, reason, result != 0L && result != -1L)
        }

        val sip = Jsonx.strList(Jsonx.arr(data, "sip"))
        val base = sip.firstOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_QQ_SIP
        val host = info.optString("filename", "")
        val ext = when {
            host.endsWith(".flac") -> "flac"
            host.endsWith(".m4a") -> "m4a"
            host.endsWith(".ogg") -> "ogg"
            else -> "mp3"
        }
        // purl 有时已经是绝对地址，有时是 /M500xxx.mp3 这样的相对路径
        val full = when {
            purl.startsWith("http://") || purl.startsWith("https://") -> purl
            else -> base.trimEnd('/') + "/" + purl.trimStart('/')
        }
        return QqUrlVerdict(full, want, false, false, null, false)
    }

    const val DEFAULT_QQ_SIP = "http://aqqmusic.tc.qq.com/"

    // ======================= 华为 =======================

    /**
     * 判定 bycontentcode 的响应。
     *
     * 规格 4.2：resultCode=000000 才算成功；type=2 是试听；
     * URL 路径含 /preview/ 或文件名含 -64K-FTD-P 一定是 64k 试听片段（最可靠信号）。
     * 142400/142409/142411 分别是 VIP / 超钻 / 超级音乐会员，分开给文案。
     */
    fun huawei(resp: org.json.JSONObject?, requestedQuality: String): HuaweiUrlVerdict {
        val want = Quality.normalize(requestedQuality)
        if (resp == null) return HuaweiUrlVerdict(null, want, false, 0, "响应不是 JSON")

        val resultCode = Jsonx.str(resp, "resultCode").ifEmpty { Jsonx.str(resp, "result_code") }
        if (resultCode != "000000" && resultCode.isNotEmpty()) {
            val msg = when (resultCode) {
                "142400" -> "需要音乐会员(142400)"
                "142409" -> "需要超级音乐会员(142409)"
                "142411" -> "需要超级钻石会员(142411)"
                else -> "接口错误 $resultCode"
            }
            return HuaweiUrlVerdict(null, want, false, 0, msg)
        }

        val url = Jsonx.str(resp, "fileURL").ifEmpty { Jsonx.str(resp, "fileUrl") }
        if (url.isEmpty()) {
            // 有时主地址空但 bakFileURls 有，取第一个
            val bak = Jsonx.strList(Jsonx.arr(resp, "bakFileURls"))
                .ifEmpty { Jsonx.strList(Jsonx.arr(resp, "bakFileUrls")) }
                .firstOrNull { it.startsWith("http") }
            if (bak == null) return HuaweiUrlVerdict(null, want, false, 0, "fileURL 为空")
            return HuaweiUrlVerdict(bak, "standard", isPreviewUrl(bak) || Jsonx.str(resp, "type") == "2",
                Jsonx.long(resp, "auditionTime", 0), null)
        }

        val type = Jsonx.str(resp, "type")
        val preview = type == "2" || isPreviewUrl(url)
        val auditionMs = Jsonx.long(resp, "auditionTime", 0)
        // 未登录/vip=0 只能拿 64k；playQuality 有时给 "2"/"3"，直接用它更准
        val playQuality = Jsonx.str(resp, "playQuality")
        val quality = when {
            preview -> "standard"
            playQuality == "3" -> "lossless"
            playQuality == "2" -> "high"
            else -> "standard"
        }
        return HuaweiUrlVerdict(url, quality, preview, auditionMs, null)
    }

    fun isPreviewUrl(url: String): Boolean =
        url.contains("/preview/") || url.contains("-64K-FTD-P") || url.contains("-64K-FTD")

    // ======================= 通用 =======================

    /** 把判定结果转成 PlayUrl；失败返回 null（前端据此提示版权受限） */
    fun toPlayUrl(v: NeteaseUrlVerdict, headers: Map<String, String>): PlayUrl? {
        val u = v.url ?: return null
        return PlayUrl(u, v.quality, v.downgraded, v.preview, headers)
    }

    fun toPlayUrl(v: QqUrlVerdict, headers: Map<String, String>): PlayUrl? {
        val u = v.url ?: return null
        return PlayUrl(u, v.quality, v.downgraded, v.preview, headers)
    }

    fun toPlayUrl(v: HuaweiUrlVerdict, headers: Map<String, String>): PlayUrl? {
        val u = v.url ?: return null
        return PlayUrl(u, v.quality, false, v.preview, headers)
    }
}
