package com.j4.music.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.j4.music.App
import com.j4.music.repo.DownloadManager
import com.j4.music.repo.LocalLibrary
import com.j4.music.repo.PlaylistRepository
import com.j4.music.repo.PlaylistSync
import com.j4.music.repo.RepoCodec
import com.j4.music.repo.Transcode
import com.j4.music.repo.UrlResolver
import com.j4.music.playback.LoopMode
import com.j4.music.playback.PlayerService
import com.j4.music.source.MusicSource
import com.j4.music.source.Song
import com.j4.music.source.SourceRegistry
import com.j4.music.source.SourceEnv
import com.j4.music.source.QrState
import com.j4.music.source.NeteaseSource
import com.j4.music.source.QqSource
import com.j4.music.source.HuaweiSource
import com.j4.music.source.Md5Sha1
import com.j4.music.source.Quality
import com.j4.music.source.PlayUrl
import com.j4.music.source.OpenApiConfig
import com.j4.music.source.OpenApiSource
import com.j4.music.repo.AudioSniff
import com.j4.music.repo.CloudPlaylists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.webkit.JavascriptInterface

/**
 * WebView ↔ Kotlin 的唯一桥。
 *
 * 设计约定（前端 js/bridge.js 严格依赖这些）：
 *   1. `J4Native.call(method, argsJson)` 永远**同步**返回一个 JSON 字符串，
 *      形如 {"ok":true,"data":...} 或 {"ok":false,"error":"..."}。
 *      同步是为了让 JS 侧不用维护回调 id 表；会阻塞超过 16ms 的活儿一律进异步分支。
 *   2. 异步方法立刻返回 {"ok":true,"data":null}，真正结果通过 `emit` 事件推给前端：
 *      {"type":"search","req":<回显的 req>,"ok":true,"data":...}
 *      前端用 `req` 匹配自己发出的请求（同一个 search 可能并发多个）。
 *   3. 所有 suspend 调用都在 IO 线程池，主线程只碰 emit 的 WebView.evaluateJavascript。
 *   4. 任何异常都吞掉转成 {"ok":false,"error":...}，绝不让异常穿透到 JS 层 ——
 *      @JavascriptInterface 抛异常会直接崩 WebView 渲染进程。
 */
class JsBridge(
    private val context: Context,
    private val emit: (String) -> Unit,
    private val toast: (String) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val playlistRepo = PlaylistRepository.shared
    private val sync = PlaylistSync.shared
    private val local = LocalLibrary(context)

    private var player: PlayerService? = null

    /**
     * 扫码 key 的本地缓存。前端 loginQrPoll 只传 {source}，不带 key
     * （见 assets/web/js/ui/login.js:161），所以 login 时拿到的 key 必须存在这里。
     */
    private val qrKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var resolver: UrlResolver? = null

    // ================================================================ 播放器挂载

    fun attachPlayer(service: PlayerService) {
        player = service
        resolver = UrlResolver(
            sourceProvider = { id -> SourceRegistry.get(id) },
            qualityOf = { App.db.get("audio.quality") ?: "lossless" },
            // 直连平台取不到正式地址时，去别家找同一首的免费版本（客户端兜底）
            fallbackResolver = { song, quality -> SourceRegistry.crossResolve(song, quality) }
        )
        service.onStateChanged = { snap ->
            // 一定要用 stateJson()（含 queue/playlistId），否则前端每收到一次 state 就把
            // 队列清空重画，列表会闪 —— 这是 player.js 里 setQueue 的目标一致性问题。
            emitEvent("state", stateJsonOf(snap), null)
        }
        service.onError = { msg ->
            emitEvent("error", JSONObject().put("message", msg), null)
        }
        service.onTrackResolved = { index, song ->
            emitEvent("track", JSONObject().put("index", index).put("song", song.toJson()), null)
        }
        // 后台队列变化（自动下一首等）时把整个队列快照推一次，前端不必自己维护
        service.onQueueChanged = { songs ->
            emitEvent("queue", JSONObject().put("queue", songsToJson(songs))
                .put("index", service.snapshot().index), null)
        }
        // 跨源兜底提示：前端收到这一条弹一次 toast，说明「听到的不是原平台版本」。
        service.onSourceSwitched = { song, play ->
            emitEvent("sourceSwitch", JSONObject().apply {
                put("songId", song.id)
                put("title", song.title)
                put("origin", song.extra["origin"] ?: song.source)
                put("via", play.via)
                put("viaTitle", play.viaTitle)
                put("quality", play.quality)
                put("preview", play.preview)
            }, null)
        }
    }

    fun emitEvent(type: String, data: Any?, req: String?) {
        val payload = JSONObject().apply {
            put("type", type)
            if (req != null) put("req", req)
            put("data", data ?: JSONObject.NULL)
            put("ts", System.currentTimeMillis())
        }
        val line = "window.__J4NativeEvent && window.__J4NativeEvent(${quote(type)}, ${quote(payload.toString())});"
        main.post {
            runCatching { host?.runJs(line) }
        }
    }

    /** 宿主（MainActivity）注入，用来 evaluateJavascript */
    @Volatile
    var host: MainActivityHost? = null

    interface MainActivityHost {
        fun runJs(js: String)
    }

    /**
     * 「这次返回前端认不认」的缓存位。
     *
     * 为什么要有这么一个字段：onBackPressed 必须**当场**决定退不退，否则系统会按
     * 「未处理」继续往下走，直接 finish。而从 Kotlin 同步取走一个 JS 返回值是不可能的
     * （evaluateJavascript 天生异步，主线程用锁等它必死锁）。
     * 所以反过来做：JS 侧每当路由栈 / 面板状态变化，就用同步桥推一个布尔过来，这里只缓存。
     */
    @Volatile
    var backClaimable: Boolean = false
        private set

    /**
     * 消费一次返回：前端能处理就返回 true，并把「可返回」位清掉（等前端推新的状态）。
     * 只应由 MainActivity 在主线程调用。
     */
    fun consumeBack(): Boolean {
        if (!backClaimable) return false
        backClaimable = false
        return true
    }

    // ================================================================ 入口

    @JavascriptInterface
    fun call(method: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return try {
            route(method, args)
        } catch (e: Throwable) {
            err("${method}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @JavascriptInterface
    fun log(level: String, msg: String) {
        when (level) {
            "e" -> android.util.Log.e("J4JS", msg)
            "w" -> android.util.Log.w("J4JS", msg)
            else -> android.util.Log.d("J4JS", msg)
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        main.post { toast(msg) }
    }

    // ================================================================ 路由

    private fun route(method: String, a: JSONObject): String = when (method) {

        // ---------------------------------------------------------- 基础
        "ping" -> ok(JSONObject().put("pong", true).put("version", BuildInfo.VERSION)
            .put("appVersion", BuildInfo.VERSION)
            .put("native", true).put("mock", false))
        // JS 侧路由栈/面板变化时推过来，供 onBackPressed 同步判断。
        // 必须同步返回：前端在 backState 之后立刻要决定按钮会不会关 App。
        "backState" -> {
            backClaimable = a.optBoolean("claimable", false)
            ok(JSONObject().put("claimable", backClaimable))
        }
        // 协议 / 隐私 / 许可；也用来把任意站外链接丢给系统浏览器。
        "openUrl" -> {
            openUrl(a.optString("url"), a.optString("title", "J4Music"))
            ok(JSONObject().put("opened", true))
        }
        /**
         * 音源列表。这里顺带问一次服务器上的登录态：
         * 网页控制台扫码登录的号存在网关里，不刷新的话手机这边会一直显示「未登录」，
         * 与「服务器登一次、全端可用」自相矛盾。
         */
        "sourceList" -> async(a) {
            for (id in SourceRegistry.ids()) {
                runCatching { (SourceRegistry.get(id) as? OpenApiSource)?.refreshServerLogin() }
            }
            JSONObject().put("list", sourcesJson())
        }
        "settingsGet" -> ok(settingsJson())
        /**
         * 设置写入。前端的 key 是短名（theme / quality / downloadDir / cacheClear …），
         * 落到 kv 表时统一映射成带命名空间的 j4.* 键 —— 否则会和 repo 层的
         * play.loopMode、remote_snapshot_*、ck:* 等既有键撞车。
         */
        "settingsSet" -> sync(a) {
            val k = a.optString("key")
            val v = a.opt("value")
            val s = if (v == null || v == JSONObject.NULL) "" else v.toString()
            when (k) {
                "theme" -> App.db.put("ui.theme", s)
                "quality" -> App.db.put("audio.quality", s)
                "downloadDir" -> App.db.put("j4.downloadDir", s)
                "cacheClear" -> clearCache()
                // 上游地址与令牌成对写：改了地址但令牌还是旧的等于连不上
                "upstreamBase" -> OpenApiConfig.set(s.trim().trimEnd('/'), OpenApiConfig.token())
                "upstreamToken" -> OpenApiConfig.set(OpenApiConfig.baseUrl(), s.trim())
                else -> if (k.isNotBlank()) App.db.put(k, s)
            }
            settingsJson()
        }
        "app.dbStats" -> ok(JSONObject()
            .put("playlists", App.db.listPlaylists().size)
            .put("db", runCatching { File(App.instance.getDatabasePath("j4music.db").path).length() }.getOrDefault(0L)))

        // ---------------------------------------------------------- 播放控制（同步返回）
        "getStatus" -> ok(onMain { stateJson() })
        "state" -> ok(onMain { stateJson() })
        "queue" -> ok(onMain {
            JSONObject().apply {
                put("queue", songsToJson(player?.queueSongs() ?: emptyList()))
                put("index", player?.snapshot()?.index ?: 0)
                put("playlistId", App.db.get("play.list.playlistId") ?: "")
            }
        })
        // 这批全部走 onMain：JavaBridge 线程碰 ExoPlayer 会抛 wrong thread
        "pause" -> sync(a) {
            onMain { player?.pause() }
            // 回给前端最新的完整状态：前端 B.call('pause') 直接用返回值刷 UI
            onMain { stateJson() }
        }
        "resume" -> sync(a) {
            onMain { player?.play() }
            onMain { stateJson() }
        }
        "toggle" -> sync(a) {
            onMain { player?.toggle() }
            onMain { stateJson() }
        }
        "next" -> sync(a) {
            onMain { player?.next() }
            onMain { stateJson() }
        }
        "prev" -> sync(a) {
            onMain { player?.prev() }
            onMain { stateJson() }
        }
        "seek" -> sync(a) {
            val ms = a.optLong("ms", 0L)
            onMain { player?.seekTo(ms) }
            onMain { stateJson() }
        }
        "loopMode" -> sync(a) {
            val next = LoopMode.of(a.optString("mode"))
            onMain { player?.setLoopMode(next) }
            App.db.put("play.loopMode", next.key)
            JSONObject().apply { put("mode", next.key) }
        }
        /** 前端 play({index}) 语义：没在播就开播；若给了 index 且与当前不同则跳过去 */
        "play" -> sync(a) {
            val idx = a.optInt("index", -1)
            onMain {
                val svc = player ?: throw IllegalStateException("播放器未就绪")
                if (idx >= 0 && idx != svc.snapshot().index) svc.playIndex(idx) else svc.play()
                stateJson()
            }
        }

        // ---------------------------------------------------------- 队列（异步，要解析 URL）
        "setQueue" -> asyncUnit(a) { req ->
            val songs = parseSongs(a.optJSONArray("songs"))
            val start = a.optInt("index", 0)
            val autoplay = a.optBoolean("autoplay", true)
            val playlistId = a.optString("playlistId").ifEmpty { null }
            App.db.put("play.list.playlistId", playlistId ?: "")
            onMain {
                val svc = player ?: throw IllegalStateException("播放器未就绪")
                svc.setQueue(songs, start, autoplay)
            }
            emitEvent("queue", JSONObject().apply {
                put("queue", songsToJson(songs))
                put("index", start)
                put("playlistId", playlistId ?: JSONObject.NULL)
            }, req)
        }
        "playSong" -> asyncUnit(a) { req ->
            val song = Song.fromJson(a.getJSONObject("song"))
            onMain {
                val svc = player ?: throw IllegalStateException("播放器未就绪")
                svc.setQueue(listOf(song), 0, true)
            }
            emitEvent("queue", JSONObject().apply {
                put("queue", songsToJson(listOf(song)))
                put("index", 0)
            }, req)
        }

        // ---------------------------------------------------------- 搜索 / 歌词 / 取址
        /**
         * 搜索：前端期望 { list:[Song], total, groups:[{source,name,count,ok,error}] }。
         * 多音源并发后按「音源注册顺序 × 各自排名」做交错合并 —— 直接拼接会让
         * 某个源的 30 首把列表前半页全占满，用户看不到别的平台的结果。
         */
        "search" -> async(a) {
            val kw = a.optString("keyword").trim()
            val srcArg = a.optString("source", "all")
            val offset = a.optInt("offset", 0)
            val limit = a.optInt("limit", 30).coerceIn(1, 200)
            val ids = if (srcArg.isEmpty() || srcArg == "all") null else listOf(srcArg)
            val t0 = System.currentTimeMillis()
            val groups = if (kw.isEmpty()) emptyList() else SourceRegistry.searchAll(kw, ids)
            val merged = interleave(groups.map { it.songs })
            val page = if (offset >= merged.size) emptyList() else
                merged.subList(offset, minOf(merged.size, offset + limit))
            JSONObject().apply {
                put("keyword", kw)
                put("costMs", System.currentTimeMillis() - t0)
                put("list", songsToJson(page))
                put("total", merged.size)
                put("offset", offset)
                put("groups", JSONArray().also { ja ->
                    for (g in groups) ja.put(JSONObject().apply {
                        put("source", g.source)
                        put("name", g.name)
                        put("ok", g.ok)
                        put("error", g.error ?: JSONObject.NULL)
                        put("count", g.count)
                    })
                })
            }
        }
        /**
         * 歌词：前端接受 rich = [{ms,text,tr,chars:[{c,t}]}]（逐字）或 lrc 文本。
         * 原生的 rich 是各平台原始格式（yrc / krc 文本），必须在这里解析成前端结构，
         * 否则前端会把它当「已经是逐字数组」直接渲染失败。
         */
        "lyric" -> async(a) {
            // 三种入参来源，优先级从高到低：
            //   1) 直接给 song 完整对象；
            //   2) 只给 songId（lyric.js:282 就是这么调的）—— 去当前队列里找，找不到再问音源；
            //   3) 什么都不给，取当前播放曲。
            // 注意：async 的 body 跑在 IO 协程里，而 ExoPlayer 的状态只能主线程读，
            // 所以这里必须用 onMain 包住 —— 否则直接撞 "Player is accessed on the wrong thread"。
            val song = when {
                a.has("song") && a.optJSONObject("song") != null -> Song.fromJson(a.getJSONObject("song"))
                a.optString("songId").isNotEmpty() -> {
                    val wantId = a.optString("songId")
                    val inQueue = onMain { player?.queueSongs()?.firstOrNull { it.id == wantId } }
                    // 队列里没这首歌时必须按 id 重建，绝不能退回「当前播放曲」：
                    // 那样会把 A 的歌词当成 B 的歌词返回，正是「歌词和歌名对不上」。
                    inQueue ?: songFromUpstreamId(wantId)
                    ?: throw IllegalStateException("找不到这首歌：$wantId")
                }
                else -> onMain { player?.currentSong() } ?: throw IllegalStateException("没有正在播放的歌曲")
            }
            val src = SourceRegistry.get(song.source) ?: throw IllegalStateException("未知音源 ${song.source}")
            val ly = runCatching { src.lyric(song) }.getOrDefault(com.j4.music.source.Lyric())
            val richLines = RichLyric.mergeTranslation(RichLyric.parse(ly), ly.translated)
            JSONObject().apply {
                put("songId", song.id)
                put("lrc", ly.lrc)
                put("translated", ly.translated)
                put("romanized", ly.romanized)
                put("richKind", ly.lyricRichKind)
                put("rich", richLines)
            }
        }

        "switchSource" -> async(a) {
            // 换源：同名的歌去另一个平台找一条能播的
            val song = Song.fromJson(a.getJSONObject("song"))
            val exclude = a.optString("exclude").ifEmpty { song.source }
            val hits = SourceRegistry.searchAll("${song.title} ${song.artist}".trim(),
                SourceRegistry.ids().filter { it != exclude })
            val cands = hits.flatMap { it.songs }
            val best = sync.pickBest(cands, song.title, song.artist, song.durationMs)
                ?: throw IllegalStateException("其它音源也没找到")
            JSONObject().apply { put("song", best.toJson()) }
        }

        // ---------------------------------------------------------- 登录 / 会员
        /**
         * 前端语义（login.js）：
         *   login({source})            → 已登录直接回 {cookie}；未登录且有二维码能力回 {qr:{...}}
         *   login({source, cookie})    → 用 cookie 登录
         *   login({source, user, pwd}) → 账密登录（当前没有音源支持，会直接报错）
         *   login({source, userId, token}) → 华为 token
         * 登录态本身由各 Source 用 SourceEnv.putCookie/state 落库，这里只回状态。
         */
        "login" -> async(a) { req ->
            val srcId = a.getString("source")
            val src = SourceRegistry.get(srcId) ?: throw IllegalStateException("未知音源 $srcId")
            val cookie = a.optString("cookie").ifEmpty { null }
            val user = a.optString("user").ifEmpty { null }
            val pwd = a.optString("pwd").ifEmpty { null }
            val token = a.optString("token").ifEmpty { null }
            val userId = a.optString("userId").ifEmpty { null }

            var result: JSONObject = JSONObject()
            val done = { extra: JSONObject ->
                result = JSONObject().apply {
                    put("source", srcId)
                    put("ok", src.isLoggedIn())
                    put("loggedIn", src.isLoggedIn())
                    put("sources", sourcesJson())
                    for (k in extra.keys()) put(k, extra.get(k))
                }
                emitEvent("login", result, req)
            }

            when {
                cookie != null -> {
                    when (src) {
                        is OpenApiSource -> src.loginByCookie(cookie)
                        is NeteaseSource -> src.loginByCookie(cookie)
                        is QqSource -> src.loginByCookie(cookie)
                        else -> throw IllegalStateException("${src.name} 不支持 Cookie 登录")
                    }
                    done(JSONObject())
                }
                user != null && pwd != null -> {
                    // 现在只剩网易云 / QQ，两家都只走扫码或 Cookie，没有账密登录
                    throw IllegalStateException("${src.name} 只支持扫码或 Cookie 登录")
                }
                token != null && userId != null -> {
                    val hw = src as? HuaweiSource ?: throw IllegalStateException("${src.name} 不支持 Token 登录")
                    hw.loginByToken(userId, token)
                    done(JSONObject())
                }
                src.isLoggedIn() -> done(JSONObject().put("cookie", currentCookie(src)))
                // 开源上游音源：二维码由上游的 /login/qr/* 端点生成（网易、QQ 都支持），
                // 前端只认 {key,image,intervalMs}，所以这里把 dataURL 直接塞进 image。
                src is OpenApiSource -> {
                    val (key, img) = src.qrCreate()
                    if (key.isEmpty()) throw IllegalStateException("二维码获取失败，请检查上游配置")
                    qrKeys[srcId] = key
                    done(JSONObject().put("qr", JSONObject().apply {
                        put("key", key)
                        put("url", img)
                        put("image", img)
                        put("intervalMs", 2_500L)
                        put("expireAt", System.currentTimeMillis() + 180_000L)
                    }))
                }
                src is NeteaseSource || src is QqSource -> {
                    val info = if (src is NeteaseSource) src.loginQrCreate() else (src as QqSource).loginQrCreate()
                    done(JSONObject().put("qr", JSONObject().apply {
                        put("key", info.key)
                        put("url", info.url)
                        put("image", info.imageUrl ?: JSONObject.NULL)
                        put("intervalMs", info.pollIntervalMs)
                        put("expireAt", info.expireAt)
                    }))
                }
                else -> throw IllegalStateException("${src.name} 需要手动填 cookie / token")
            }
            // 作为返回值兑现 Promise：login.js 是 await 取结果的，只发事件会拿到 null
            result
        }
        /**
         * 轮询扫码结果。前端会按 intervalMs 反复调，所以每次都必须快（单次网络往返，
         * 不做任何等待循环），否则 WebView 的主线程 bridge 队列会被拖住。
         */
        "loginQrPoll" -> async(a) { req ->
            val srcId = a.getString("source")
            val src = SourceRegistry.get(srcId) ?: throw IllegalStateException("未知音源 $srcId")
            val key = a.optString("key").ifEmpty { qrKeys[srcId].orEmpty() }
            if (key.isEmpty()) throw IllegalStateException("二维码已失效，请重新点击登录")
            // 开源上游的扫码：只回 code（803 成功），没有「已扫待确认」这档昵称信息，
            // 所以这里自己翻译成前端那套 status，别让 login.js 走不到成功分支。
            if (src is OpenApiSource) {
                val (ok, msg, ck) = src.qrPoll(key)
                val status = when {
                    ok -> "success"
                    msg.contains("过期") || msg.contains("失效") || msg.contains("800") -> "expired"
                    msg.contains("已扫") || msg.contains("确认") -> "scanned"
                    else -> "waiting"
                }
                val payload = JSONObject().apply {
                    put("source", srcId)
                    put("status", status)
                    put("ok", ok)
                    put("scanned", status == "scanned" || ok)
                    put("message", msg)
                    if (ok && ck.isNotEmpty()) put("cookie", ck)
                    put("sources", sourcesJson())
                }
                emitEvent("loginQrPoll", payload, req)
                return@async payload
            }
            val r: QrState = when (src) {
                is NeteaseSource -> src.loginQrPoll(key)
                is QqSource -> src.loginQrPoll(key)
                else -> throw IllegalStateException("该音源不支持扫码")
            }
            // QrState 的子类都是 object/data class，when 分支不能用 is 对象匹配
            val status = when (r) {
                QrState.Waiting -> "waiting"
                QrState.Expired -> "expired"
                is QrState.Scanned -> "scanned"
                is QrState.Success -> "success"
                is QrState.Error -> "error"
            }
            emitEvent("loginQrPoll", JSONObject().apply {
                put("source", srcId)
                put("status", status)
                put("ok", r is QrState.Success)
                put("scanned", r is QrState.Scanned || r is QrState.Success)
                if (r is QrState.Scanned) {
                    put("nickname", r.nickname)
                    put("avatar", r.avatar)
                }
                if (r is QrState.Success) {
                    put("cookie", r.cookie)
                    put("sources", sourcesJson())
                }
                if (r is QrState.Error) put("message", r.msg)
            }, req)
            // 同时作为返回值兑现 Promise：前端 login.js 是 await B.call('loginQrPoll') 取结果的，
            // 只发事件不返回会让 await 拿到 null。
            JSONObject().apply {
                put("source", srcId)
                put("status", status)
                put("ok", r is QrState.Success)
                put("scanned", r is QrState.Scanned || r is QrState.Success)
                if (r is QrState.Scanned) {
                    put("nickname", r.nickname)
                    put("avatar", r.avatar)
                }
                if (r is QrState.Success) {
                    put("cookie", r.cookie)
                    put("sources", sourcesJson())
                }
                if (r is QrState.Error) put("message", r.msg)
            }
        }
        "logout" -> async(a) { req ->
            val srcId = a.optString("source")
            val src = SourceRegistry.get(srcId) ?: throw IllegalStateException("未知音源 $srcId")
            when (src) {
                is OpenApiSource -> src.logout()
                is NeteaseSource -> src.logout()
                is QqSource -> src.logout()
                is HuaweiSource -> src.logout()
            }
            val out = JSONObject().apply {
                put("source", srcId)
                put("ok", true)
                put("loggedIn", src.isLoggedIn())
                put("sources", sourcesJson())
            }
            emitEvent("logout", out, req)
            out
        }
        // ---------------------------------------------------------- 会员分发
        /**
         * 「会员分发」：上游账号（用户在 设置→登录 里自己的 VIP）登录后，
         * 本机所有播放请求都走同一个 CookieJar，所以 VIP 曲目取址会自动带上会员态 ——
         * 这里只负责把结果如实告诉前端：是原曲、是试听，还是被降级。
         */
        "vipStatus" -> ok(JSONObject().also { o ->
            for (s in SourceRegistry.all()) {
                o.put(s.id, JSONObject().apply {
                    put("loggedIn", runCatching { s.isLoggedIn() }.getOrDefault(false))
                    put("vip", isVip(s))
                    put("requiresLogin", s.requiresLogin)
                })
            }
        })
        // ------------------------------------------------ 上游账号（分发会员）
        /**
         * 上游账号状态。
         *
         * 语义已经变了但方法名保留（cloud.js 还在调）：现在「上游」就是开源服务，
         * 每个平台一个 Cookie 存在本地 KV 里，用它取 VIP 地址。所以这里
         * 回的是「本地存了哪家的 cookie」而不是「服务器登录了谁」。
         */
        "upstreamAccount" -> async(a) {
            // 先问一下服务器：网页控制台上扫码登录的号存在网关里，
            // 只有把这份也算进来，「服务器登录一次、全端可用」才说得通。
            val server = runCatching {
                (SourceRegistry.get("netease") as? OpenApiSource)?.serverState()
            }.getOrNull()
            val serverPlats = server?.optJSONObject("cookies")
            JSONObject().apply {
                put("base", OpenApiConfig.baseUrl())
                put("enabled", OpenApiConfig.isConfigured())
                put("serverOk", server?.optBoolean("ok") == true)
                val platforms = JSONObject()
                for (p in listOf("netease", "qqmusic")) {
                    val ck = OpenApiConfig.cookie(p)
                    val srv = serverPlats?.optJSONObject(p)
                    val srvCookie = srv?.optString("cookie").orEmpty()
                    val srvLogged = srvCookie.isNotEmpty() && srvCookie != "null"
                    platforms.put(p, JSONObject().apply {
                        // effective = 本机或服务器任一处有登录态
                        put("loggedIn", ck.isNotEmpty() || srvLogged)
                        put("localLoggedIn", ck.isNotEmpty())
                        put("serverLoggedIn", srvLogged)
                        put("cookieLen", ck.length)
                        put("serverCookieLen", srvCookie.length)
                        put("uid", OpenApiConfig.uid(p).ifEmpty { srv?.optString("uid").orEmpty() })
                    })
                }
                put("platforms", platforms)
                // 前端也有按数组渲染的地方（云端账号页），两种形状都给，省得再对一次字段
                put("list", JSONArray().also { ja ->
                    for (p in listOf("netease", "qqmusic")) {
                        val one = platforms.optJSONObject(p) ?: continue
                        ja.put(JSONObject().apply {
                            put("source", if (p == "qqmusic") "qq" else p)
                            put("platform", p)
                            put("loggedIn", one.optBoolean("loggedIn"))
                            put("serverLoggedIn", one.optBoolean("serverLoggedIn"))
                            put("uid", one.optString("uid"))
                        })
                    }
                })
                put("ok", true)
            }
        }
        "upstreamLoginQr" -> async(a) { req ->
            val src = openApiOf(a.optString("source"))
            val (key, img) = src.qrCreate()
            val info = JSONObject().apply {
                put("source", src.platform)
                put("key", key)
                put("image", img)
                put("url", img)
                put("status", if (key.isEmpty()) "error" else "waiting")
                put("intervalMs", 2_500L)
            }
            emitEvent("upstreamQr", info, req)
            info
        }
        "upstreamLoginPoll" -> async(a) {
            val src = openApiOf(a.optString("source"))
            val key = a.optString("key").ifEmpty { qrKeys[src.id].orEmpty() }
            if (key.isEmpty()) {
                val (fresh, img) = src.qrCreate()
                if (fresh.isNotEmpty()) qrKeys[src.id] = fresh
                JSONObject().put("source", src.platform).put("status", "waiting")
                    .put("key", fresh).put("image", img).put("refreshed", true)
                    .put("message", "二维码已重新生成")
            } else {
                val (ok, msg, ck) = src.qrPoll(key)
                JSONObject().put("source", src.platform)
                    .put("status", if (ok) "success" else "waiting")
                    .put("key", key).put("message", msg)
                    .put("cookie", if (ok) ck else "")
            }
        }
        "upstreamLoginCookie" -> async(a) {
            val src = openApiOf(a.optString("source"))
            val ck = a.optString("cookie")
            src.loginByCookie(ck)
            // 同时写一份到网关：这样别的设备/以后重装都不用再登一次
            runCatching { src.saveServerCookie(ck) }
            JSONObject().put("source", src.platform).put("ok", src.isLoggedIn())
                .put("sources", sourcesJson())
        }
        "upstreamLogout" -> async(a) {
            val src = openApiOf(a.optString("source"))
            src.logout()
            runCatching { src.clearServerCookie() }
            JSONObject().put("source", src.platform).put("ok", true)
                .put("sources", sourcesJson())
        }
        /** 测试上游连通性：ping 网关 + 各平台搜索一次，如实报告谁通谁不通 */
        "upstreamHealth" -> async(a) {
            val base = OpenApiConfig.baseUrl()
            val t0 = System.currentTimeMillis()
            val netease = runCatching { SourceRegistry.get("netease")?.search("周杰伦", 0, 1)?.size ?: 0 }.getOrDefault(0)
            val qq = runCatching { SourceRegistry.get("qq")?.search("周杰伦", 0, 1)?.size ?: 0 }.getOrDefault(0)
            val ms = System.currentTimeMillis() - t0
            val text = "上游 $base：网易 ${if (netease > 0) "可用" else "无结果"}，QQ ${if (qq > 0) "可用" else "无结果"}，${ms}ms"
            JSONObject().put("text", text).put("base", base)
                .put("enabled", OpenApiConfig.isConfigured())
                .put("netease", netease).put("qq", qq).put("ms", ms)
        }

        /**
         * 打开上游网页控制台。
         *
         * 保留这个方法是因为设置页有入口；上游换成开源服务之后它没有登录页了，
         * 所以直接把基址当文档页打开（开源项目根路径就是 API 文档）。
         */
        "authWebLogin" -> sync(a) {
            val url = OpenApiConfig.baseUrl()
            if (url.isEmpty()) throw IllegalStateException("请先在设置里填写上游地址")
            var opened = false
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                opened = true
            }.onFailure { toast("没有可以打开网页的应用") }
            JSONObject().put("url", url).put("opened", opened).put("userId", "")
        }

        /** 歌曲版权/会员标记：vip=true 表示需要会员或单曲付费 */        "songRights" -> sync(a) {
            val song = if (a.has("song")) Song.fromJson(a.getJSONObject("song"))
            else throw IllegalArgumentException("缺少 song")
            JSONObject().apply {
                put("songId", song.id)
                put("source", song.source)
                put("vip", song.vip)
                put("playable", !song.vip || (SourceRegistry.get(song.source)?.isLoggedIn() == true))
                put("offline", song.playableOffline)
            }
        }
        // ---------------------------------------------------------- 取址（跨音源兜底）
        /**
         * 单独取一首歌的可播地址。主要用于「换源」与调试：
         * 依次尝试 song.source 与本地的其它音源，找到第一个非试听、非降级的链接。
         * 走 async + 事件回推，因为要联网（前端 B.call 有 8s 超时）。
         */
        "songUrl" -> async(a) { req ->
            // 同 lyric：读当前曲必须回主线程
            // 入参三种：song 完整对象 / songId（队列里找）/ 什么都不给取当前曲
            val song = when {
                a.has("song") && a.optJSONObject("song") != null -> Song.fromJson(a.getJSONObject("song"))
                a.optString("songId").isNotEmpty() -> {
                    val wantId = a.optString("songId")
                    val inQueue = onMain { player?.queueSongs()?.firstOrNull { it.id == wantId } }
                    // 不在队列里时，只要 id 带上游前缀就能直接问上游 —— 搜索结果点「试听」就是这条路径
                    inQueue ?: songFromUpstreamId(wantId)
                        ?: throw IllegalStateException("找不到这首歌：$wantId")
                }
                else -> onMain { player?.currentSong() } ?: throw IllegalStateException("没有指定歌曲")
            }
            val prefer = Quality.normalize(a.optString("quality").ifEmpty { null })
            // 本地优先：已经下载/扫描到文件的歌直接回文件路径，
            // 否则断网时「下载好的歌」也会被当成在线曲去取地址而失败。
            val localPath = song.localPath
            if (!localPath.isNullOrEmpty()) {
                val out = JSONObject().apply {
                    put("ok", true)
                    put("songId", song.id)
                    put("source", "local")
                    put("url", localPath)
                    put("quality", "local")
                    put("downgraded", false)
                    put("preview", false)
                    put("via", "")
                    put("viaTitle", "")
                    put("mime", com.j4.music.playback.SongMapper.guessMime(localPath, localPath))
                }
                emitEvent("songUrl", out, req)
                return@async out
            }
            // 顺序：本平台 → 其它平台（按标题重新搜一遍，见 SourceRegistry.crossResolve）。
            // 不同平台的 id 体系不通，所以对别的平台不能拿同一个 id 去问，
            // 一定要走 crossResolve 那种「按歌名+歌手重搜」的路径。
            var best: Pair<String, PlayUrl>? = null
            var lastErr: String? = null
            val own = SourceRegistry.get(song.source)
            if (own != null) {
                val r = runCatching { own.resolveUrl(song, prefer) }.getOrElse {
                    lastErr = it.message; null
                }
                if (r != null && r.url.isNotEmpty() && !r.preview) best = song.source to r
                else if (r != null && r.url.isNotEmpty()) best = song.source to r
            }
            if (best == null) {
                val cross = runCatching { SourceRegistry.crossResolve(song, prefer) }.getOrNull()
                if (cross != null) best = cross.via to cross
            }
            val hit = best
            val out = if (hit == null) {
                JSONObject().put("ok", false).put("songId", song.id)
                    .put("message", lastErr ?: "所有音源都取不到播放地址")
            } else JSONObject().apply {
                put("ok", true)
                put("songId", song.id)
                put("source", hit.first)
                put("url", hit.second.url)
                put("quality", hit.second.quality)
                put("downgraded", hit.second.downgraded)
                put("preview", hit.second.preview)
                put("via", hit.second.via)
                put("viaTitle", hit.second.viaTitle)
                put("mime", com.j4.music.playback.SongMapper.guessMime(hit.second.url, null))
            }
            emitEvent("songUrl", out, req)
            // 取不到地址不视为调用失败（前端要拿 ok=false 自己决定显示什么），照常返回
            out
        }
        // ---------------------------------------------------------- 收藏 / 下载（都当歌单用）
        "favoritesGet" -> ok(JSONObject().apply {
            val list = favSongs()
            put("songs", JSONArray().also { ja -> for (s in list) ja.put(s.toJson()) })
            put("count", list.size)
        })
        "favoritesToggle" -> async(a) {
            val song = Song.fromJson(a.getJSONObject("song"))
            val pair = synchronized(favLock) {
                val cur = favSongs().toMutableList()
                val at = cur.indexOfFirst { it.id == song.id }
                val liked: Boolean
                if (at >= 0) {
                    cur.removeAt(at)
                    liked = false
                } else {
                    cur.add(0, song) // 新收藏放最前，和「最近收藏」的直觉一致
                    liked = true
                }
                favSave(cur)
                liked to cur
            }
            JSONObject().apply { put("liked", pair.first); put("count", pair.second.size) }
        }
        "favoritesClear" -> async(a) {
            synchronized(favLock) { favSave(emptyList()) }
            JSONObject().apply { put("ok", true) }
        }
        "downloadSongs" -> async(a) {
            // 把「已下载完成」的任务当成一张歌单的曲目（localPath 指到落盘文件）
            val list = DownloadManager.snapshot()
                .filter { it.state == DownloadManager.State.DONE && !it.file.isNullOrEmpty() }
                .mapNotNull { t -> runCatching { t.song.copy(localPath = t.file) }.getOrNull() }
                .distinctBy { it.id }
            JSONObject().apply {
                put("songs", JSONArray().also { ja -> for (s in list) ja.put(s.toJson()) })
                put("count", list.size)
            }
        }
        // ---------------------------------------------------------- 歌单
        "playlistList" -> async(a) {
            val list = playlistRepo.reload()
            JSONObject().apply {
                put("list", JSONArray().also { ja -> for (s in list) ja.put(s.toJson()) })
            }
        }
        "playlistGet" -> async(a) {
            val id = a.getString("id")
            val detail = playlistRepo.get(id) ?: throw IllegalStateException("歌单不存在")
            val songs = playlistRepo.songs(id)
            JSONObject().apply {
                put("id", id)
                put("playlist", JSONObject().apply {
                    put("id", detail.id)
                    put("name", detail.name)
                    put("cover", detail.cover ?: JSONObject.NULL)
                    put("kind", detail.kind)
                    put("source", detail.source ?: JSONObject.NULL)
                    put("remoteId", detail.remoteId ?: JSONObject.NULL)
                    put("songCount", songs.size)
                    put("updatedAt", detail.updatedAt)
                })
                put("songs", songsToJson(songs))
            }
        }
        "playlistCreate" -> async(a) {
            val name = a.optString("name").ifEmpty { "新歌单" }
            // 需求：默认新建的都是云歌单（能跨设备取回）。想只要本地就传 kind="local"。
            val kind = a.optString("kind").ifEmpty { KIND_CLOUD }
            val id = playlistRepo.create(name, kind, null, null, null, emptyList())
            cloudPushAsync(id)
            JSONObject().apply { put("id", id); put("kind", kind) }
        }
        "playlistCreateFromSongs" -> async(a) {
            val name = a.optString("name").ifEmpty { "新歌单" }
            val songs = parseSongs(a.optJSONArray("songs"))
            val kind = a.optString("kind").ifEmpty { KIND_CLOUD }
            val id = playlistRepo.create(name, kind, null, null, null, songs)
            cloudPushAsync(id)
            JSONObject().apply { put("id", id); put("count", songs.size); put("kind", kind) }
        }
        "playlistAddSongs" -> async(a) {
            val id = a.getString("id")
            val songs = parseSongs(a.optJSONArray("songs"))
            val at = a.optInt("at", -1)
            val st = playlistRepo.addSongs(id, songs, at)
            cloudPushAsync(id)
            JSONObject().apply { put("added", st.added); put("skipped", st.skipped); put("total", st.total) }
        }
        "playlistRemoveSong" -> async(a) {
            val id = a.getString("id")
            val songIds = a.optJSONArray("songIds").let { arr ->
                (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
            }
            val n = if (songIds.isEmpty()) {
                if (playlistRepo.removeSong(id, a.optString("songId"))) 1 else 0
            } else playlistRepo.removeSongs(id, songIds)
            cloudPushAsync(id)
            JSONObject().apply { put("removed", n) }
        }
        "playlistReorder" -> async(a) {
            val id = a.getString("id")
            val ok = playlistRepo.reorder(id, a.getInt("from"), a.getInt("to"))
            cloudPushAsync(id)
            JSONObject().apply { put("ok", ok) }
        }
        "playlistRename" -> async(a) {
            val id = a.getString("id")
            val ok = playlistRepo.rename(id, a.getString("name"))
            cloudPushAsync(id)
            JSONObject().apply { put("ok", ok) }
        }
        "playlistSetCover" -> async(a) {
            val cover = a.optString("cover").ifEmpty { null }
            val id = a.getString("id")
            val ok = playlistRepo.setCover(id, cover)
            cloudPushAsync(id)
            JSONObject().apply { put("ok", ok) }
        }
        "playlistDelete" -> async(a) {
            val id = a.getString("id")
            val ok = playlistRepo.delete(id)
            // 云端那份的 id 和本地一样（上传时就写的是本地 id），顺手删掉，
            // 否则下次「拉取云歌单」又把它变回来
            if (CloudPlaylists.enabled()) scope.launch { runCatching { CloudPlaylists.remove(id) } }
            JSONObject().apply { put("ok", ok) }
        }
        // ---------------------------------------------------------- 云端歌单
        "cloudPlaylistPush" -> async(a) {
            val id = a.getString("id")
            if (!CloudPlaylists.enabled()) throw IllegalStateException("云端未配置，先在设置里填上游地址和口令")
            val r = CloudPlaylists.push(playlistRepo, id) ?: throw IllegalStateException("上传失败，检查网络或上游服务")
            // 网关回的是 {ok, playlist:{...}}，别把 summary 当成顶层字段读
            val pl = r.optJSONObject("playlist") ?: r
            JSONObject().apply {
                put("ok", true)
                put("remoteId", pl.optString("id"))
                put("songCount", pl.optInt("songCount"))
            }
        }
        "cloudPlaylistList" -> async(a) {
            if (!CloudPlaylists.enabled()) throw IllegalStateException("云端未配置，先在设置里填上游地址和口令")
            val list = CloudPlaylists.remoteList()
            JSONObject().apply {
                put("ok", true)
                put("list", JSONArray().also { ja ->
                    for (o in list) ja.put(JSONObject().apply {
                        put("id", o.optString("id"))
                        put("name", o.optString("name"))
                        put("cover", o.optString("cover"))
                        put("songCount", o.optInt("songCount"))
                        put("updatedAt", o.optLong("updatedAt"))
                        put("localId", CloudPlaylists.mappedLocal(o.optString("id")) ?: JSONObject.NULL)
                    })
                })
            }
        }
        "cloudPlaylistPull" -> async(a) {
            val remoteId = a.getString("id")
            if (!CloudPlaylists.enabled()) throw IllegalStateException("云端未配置，先在设置里填上游地址和口令")
            val localId = CloudPlaylists.pull(playlistRepo, remoteId) ?: throw IllegalStateException("没有取到云端的这张歌单")
            JSONObject().apply { put("ok", true); put("id", localId) }
        }
        "cloudPlaylistPullAll" -> async(a) {
            if (!CloudPlaylists.enabled()) throw IllegalStateException("云端未配置，先在设置里填上游地址和口令")
            CloudPlaylists.pullAll(playlistRepo)
        }
        "cloudPlaylistDelete" -> async(a) {
            val id = a.getString("id")
            JSONObject().apply { put("ok", CloudPlaylists.remove(id)) }
        }
        "cloudPlaylistStatus" -> ok(JSONObject().apply {
            put("enabled", CloudPlaylists.enabled())
            put("base", OpenApiConfig.baseUrl())
        })
        "playlistImportRemote" -> async(a) {
            val id = playlistRepo.importRemote(a.getString("source"), a.getString("remoteId"))
            JSONObject().apply { put("id", id) }
        }
        "playlistRefresh" -> async(a) {
            JSONObject().apply { put("ok", playlistRepo.refreshRemote(a.getString("id"))) }
        }
        "playlistUserRemote" -> async(a) {
            val src = a.getString("source")
            val s = SourceRegistry.get(src) ?: throw IllegalStateException("未知音源 $src")
            val pls = s.userPlaylists()
            JSONObject().apply {
                put("playlists", JSONArray().also { ja ->
                    for (p in pls) ja.put(JSONObject().apply {
                        put("id", p.id); put("name", p.name); put("cover", p.cover)
                        put("songCount", p.songCount); put("source", p.source)
                    })
                })
            }
        }
        // ---------------------------------------------------------- 歌单同步 / 分享 / 导入
        "playlistExport" -> async(a) {
            val id = a.getString("id")
            val fmt = PlaylistSync.Format.values().firstOrNull {
                it.name.equals(a.optString("format", "J4"), true) || it.ext == a.optString("format")
            } ?: PlaylistSync.Format.J4
            val text = sync.export(id, fmt)
            val path = writeExportFile(id, text, fmt.ext)
            JSONObject().apply { put("text", text); put("path", path); put("format", fmt.name.lowercase()) }
        }
        "playlistShare" -> async(a) {
            val id = a.getString("id")
            val text = if (a.optBoolean("compact")) sync.shareTextCompact(id) else sync.shareText(id)
            // 导出文件写一份，分享正文里附上路径：文本走 IM，文件留给「跨软件同步」用
            val fmt = a.optString("format", "J4")
            val f = PlaylistSync.Format.values().firstOrNull {
                it.name.equals(fmt, true) || it.ext == fmt
            } ?: PlaylistSync.Format.J4
            val path = writeExportFile(id, text, f.ext)
            shareToSystem(text + shareFileHint(path))
            JSONObject().apply { put("text", text); put("path", path) }
        }
        "playlistDetect" -> ok(JSONObject().put("format", sync.detect(a.optString("text")).name.lowercase()))
        "playlistImport" -> async(a) {
            doImport(
                text = a.optString("text"),
                name = a.optString("name").ifEmpty { null },
                targetPlaylistId = a.optString("playlistId").ifEmpty { null },
                formatName = a.optString("format").ifEmpty { "auto" }
            )
        }
        "playlistCrossSources" -> async(a) {
            val pr = sync.syncToSources(a.getString("id"), onProgress = { p: PlaylistSync.SyncProgress ->
                emitEvent("syncProgress", JSONObject().apply {
                    put("done", p.done); put("total", p.total)
                    put("title", p.title); put("ok", p.ok); put("failed", p.failed)
                }, a.optString("req").ifEmpty { null })
            })
            JSONObject().apply { put("done", pr.done); put("total", pr.total); put("ok", pr.ok); put("failed", pr.failed) }
        }

        // ---------------------------------------------------------- 下载
        "download" -> async(a) {
            val song = Song.fromJson(a.getJSONObject("song"))
            val q = a.optString("quality").ifEmpty { null }
            val id = DownloadManager.enqueue(song, q)
            JSONObject().apply { put("id", id); put("tasks", tasksJson()) }
        }
        "downloadPlaylist" -> async(a) {
            val ids = DownloadManager.downloadPlaylist(a.getString("id"), a.optString("quality").ifEmpty { null })
            JSONObject().apply { put("ids", JSONArray(ids)) }
        }
        "downloadCancel" -> ok(JSONObject().also {
            DownloadManager.cancel(a.getString("taskId").ifEmpty { a.getString("id") })
            it.put("list", tasksJson())
        })
        "downloadRetry" -> ok(JSONObject().put("ok", DownloadManager.retry(a.getString("id"))))
        "downloadRemove" -> ok(JSONObject().also { DownloadManager.remove(a.getString("id")); it.put("tasks", tasksJson()) })
        "downloadClearFinished" -> ok(JSONObject().also { DownloadManager.clearFinished(); it.put("tasks", tasksJson()) })
        "downloadList" -> ok(JSONObject().put("list", tasksJson()))

        // ---------------------------------------------------------- 本地
        "localScan" -> async(a) {
            playlistRepo.reload()
            val r = local.scanWithStatus()
            JSONObject().apply {
                put("status", r.status.name.lowercase())
                put("message", r.message)
                put("songs", songsToJson(r.songs))
            }
        }
        "localAppDir" -> async(a) {
            JSONObject().apply { put("songs", songsToJson(local.scanAppDir())) }
        }
        /**
         * 前端用 <input type=file multiple> 选文件，传过来的是浏览器文件名（不是绝对路径）。
         * WebView 里拿不到真实路径，所以这里按「文件名 + 媒体库」反查：
         * 先扫一次本地库做 name → uri 索引，命中就导入；全都不命中也照常返回，
         * 并把未匹配的名字回给前端提示，而不是静默丢弃。
         */
        "localImport" -> async(a) {
            val names = a.optJSONArray("files").let { arr ->
                (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
            }
            val uris = a.optJSONArray("uris").let { arr ->
                (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
            }
            val all = ArrayList<String>(uris)
            val missing = ArrayList<String>()
            if (names.isNotEmpty()) {
                val index = local.scan().associateBy { (it.localPath ?: "").substringAfterLast('/') }
                for (n in names) {
                    val hit = index[n] ?: index[n.replace(Regex("^.*[/\\\\]"), "")]
                    if (hit?.localPath != null) all.add(hit.localPath) else missing.add(n)
                }
            }
            val n = if (all.isEmpty()) 0 else local.importFiles(all)
            JSONObject().apply {
                put("count", n)
                put("missing", JSONArray(missing))
                put("songs", songsToJson(
                    if (missing.isEmpty()) emptyList() else local.scan().filter {
                        (it.localPath ?: "").substringAfterLast('/') in missing
                    }
                ))
            }
        }
        "localBuildPlaylist" -> async(a) {
            val songs = parseSongs(a.optJSONArray("songs"))
            val name = a.optString("name").ifEmpty { "本地音乐" }
            val id = local.buildLocalPlaylist(name, songs)
            JSONObject().apply { put("id", id) }
        }
        "localScanToPlaylist" -> async(a) {
            val id = local.buildPlaylistFromScan(a.optString("name").ifEmpty { "本地音乐" })
            JSONObject().apply { put("id", id) }
        }
        "localPermission" -> ok(JSONObject()
            .put("granted", local.hasPermission())
            .put("perms", JSONArray(local.requiredPermissions().toList()))
            .put("hint", local.permissionHint()))

        // ---------------------------------------------------------- 转译
        "cryptoAvailable" -> ok(JSONObject().put("available", Transcode.isAvailable()))
        /**
         * 解码器自检：NCM/MFLAC(QMC v2)/QMC v1/TEA 的往返断言。
         *
         * 为什么要在设备上跑：这些都是「抄错一个下标就只产出沙沙声、永远不报错」的算法，
         * 只有在真正会跑的那台机器上（同一份 JVM/ART 实现）逐项比对字节，才能证明没抄错。
         */
        "cryptoSelfTest" -> {
            val lines = runCatching { com.j4.music.crypto.CryptoProbe.selfTest() }.getOrElse {
                listOf("FAIL: 自检本身抛异常 —— ${it.message}")
            }
            val pass = lines.count { it.startsWith("PASS") }
            ok(JSONObject()
                .put("total", lines.size)
                .put("pass", pass)
                .put("fail", lines.size - pass)
                .put("list", JSONArray().also { ja -> lines.forEach { ja.put(it) } }))
        }
        /**
         * 造一个可解的自检样本 .ncm，用来端到端验证「转译」。
         * 音频体取设备上第一个真实音频文件（下载目录里的 mp3/flac），没有就退回合成字节。
         */
        "cryptoMakeSample" -> async(a) {
            val name = a.optString("name").ifEmpty { "j4_transcode_sample" }
            val fmt = a.optString("format").ifEmpty { "mp3" }
            val src = findAnyAudio() ?: throw IllegalStateException("设备上找不到任何音频文件可当素材")
            val audio = src.readBytes()
            val ncm = com.j4.music.crypto.CryptoProbe.buildNcmSample(audio, name, fmt)
            val dir = File(App.instance.filesDir, "transcode_test").apply { mkdirs() }
            val out = File(dir, "$name.ncm")
            out.writeBytes(ncm)
            JSONObject()
                .put("path", out.absolutePath)
                .put("bytes", out.length())
                .put("sourceAudio", src.absolutePath)
                .put("sourceBytes", audio.size)
        }
        "cryptoProbe" -> {
            val p = a.optString("path")
            val f = File(p)
            ok(JSONObject()
                .put("exists", f.isFile)
                .put("encrypted", Transcode.looksEncrypted(f))
                .put("format", runCatching {
                    f.inputStream().use { ins ->
                        val head = ByteArray(64)
                        val n = ins.read(head)
                        AudioSniff.format(head.copyOf(maxOf(n, 0)))
                    }
                }.getOrNull()))
        }
        "translate" -> async(a) {
            val arr = a.optJSONArray("paths")
            val paths = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
            doTranslate(
                paths = paths,
                req = a.optString("req").ifEmpty { null },
                deleteSource = a.optBoolean("deleteSource", false),
                addToPlaylist = a.optBoolean("addToPlaylist", true)
            )
        }
        /**
         * 原生文件选择器 + 转译。
         *
         * 网页里的 <input type=file> 在 WebView 中拿不到真实路径（File.path 恒 undefined），
         * 所以「选择文件」这件事必须由原生发起；选中后把 content:// 复制成缓存文件再转译。
         */
        "pickTranslate" -> async(a) {
            val uris = awaitPick(arrayOf("application/octet-stream", "audio/*", "*/*"))
            if (uris.isEmpty()) return@async JSONObject()
                .put("total", 0).put("okCount", 0).put("cancelled", true).put("list", JSONArray())
            val copied = copyPicked(uris, "pick_translate")
            if (copied.isEmpty()) throw IllegalStateException("选中的文件读不出来")
            doTranslate(
                paths = copied.map { it.absolutePath },
                req = a.optString("req").ifEmpty { null },
                deleteSource = false,
                addToPlaylist = true
            )
        }
        /**
         * 原生文件选择器 + 导入歌单。直接吃 .json（J4/裸数组）、.m3u、.csv、.txt，
         * 大文件也不用整段粘到输入框里。
         */
        "pickPlaylist" -> async(a) {
            val uris = awaitPick(arrayOf("application/json", "text/*", "*/*"))
            if (uris.isEmpty()) return@async JSONObject()
                .put("cancelled", true).put("success", 0).put("songCount", 0).put("id", "")
            val text = readPickedText(uris.first())
                ?: throw IllegalStateException("这个文件读不出来（可能是二进制或权限不足）")
            doImport(text, a.optString("name").ifEmpty { null }, a.optString("playlistId").ifEmpty { null })
        }

        else -> err("未知方法 $method")
    }

    // ================================================================ 工具

    /**
     * 导入歌单的公共实现（粘贴文本与选文件两条路都走这里）。
     * 返回值形状就是前端认的那一套：{id, name, success, songCount, failed, detected, message}。
     */
    suspend fun doImport(
        text: String,
        name: String?,
        targetPlaylistId: String?,
        formatName: String = "auto"
    ): JSONObject {
        val fmt = if (formatName == "auto") null else PlaylistSync.Format.values()
            .firstOrNull { it.name.equals(formatName, true) || it.ext == formatName }
        val opts = PlaylistSync.ImportOptions(targetPlaylistId = targetPlaylistId, name = name)
        val r = sync.import(text, fmt, opts)
        val pl = r.playlistId?.let { runCatching { playlistRepo.summary(it) }.getOrNull() }
        return JSONObject().apply {
            put("id", r.playlistId ?: "")
            put("name", pl?.name ?: name ?: "导入的歌单")
            put("success", r.success)
            put("songCount", pl?.songCount ?: r.success)
            put("failed", JSONArray(r.failed))
            put("playlistId", r.playlistId ?: "")
            put("detected", r.detected.name.lowercase())
            put("message", r.message)
        }
    }

    /** 转译的公共实现：路径必须已经存在（选文件的路径会先复制到缓存） */
    suspend fun doTranslate(
        paths: List<String>,
        req: String?,
        deleteSource: Boolean,
        addToPlaylist: Boolean
    ): JSONObject {
        // 传了路径但一个都不存在时必须报错：以前这里静默返回 total:0，
        // 前端只能显示「转译 0 个」，用户看不出是自己路径写错还是格式不支持。
        val missing = paths.filter { it.isNotBlank() && !java.io.File(it).exists() }
        if (paths.isEmpty()) throw IllegalStateException("没有选择文件")
        val results = Transcode.translate(
            paths,
            onProgress = { d: Int, t: Int ->
                emitEvent("transcodeProgress", JSONObject().put("done", d).put("total", t), req)
            },
            deleteSource = deleteSource,
            addToPlaylist = addToPlaylist
        )
        return JSONObject().apply {
            put("total", results.size)
            put("okCount", results.count { it.ok })
            put("missing", JSONArray().also { ja -> missing.forEach { ja.put(it) } })
            put("notice", if (missing.isEmpty()) "" else "有 ${missing.size} 个文件找不到：${missing.first()}")
            put("list", JSONArray().also { ja ->
                for (r in results) ja.put(JSONObject().apply {
                    put("in", r.input)
                    put("out", r.output ?: "")
                    put("ok", r.ok)
                    put("error", r.error ?: "")
                    put("bytes", r.bytes)
                    put("ext", r.extension ?: "")
                    put("sourceDeleted", r.sourceDeleted)
                })
            })
        }
    }

    /**
     * 拉起原生文件选择器并等结果。
     *
     * 用 suspendCancellableCoroutine 把「用户选完」这个异步事件接回协程，调用方就能像
     * 读普通返回值一样写代码；用户点返回取消时给空列表，由调用方决定怎么提示。
     */
    private suspend fun awaitPick(mime: Array<String>): List<android.net.Uri> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val ok = com.j4.music.FilePick.request(mime) { uris -> cont.resumeWith(Result.success(uris)) }
            if (!ok) cont.resumeWith(Result.success(emptyList()))
        }

    /** 把 content:// 复制到缓存目录，返回真实文件（转译/导入都需要能按路径打开的 File） */
    private suspend fun copyPicked(uris: List<android.net.Uri>, subDir: String): List<File> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(context.cacheDir, subDir).apply { mkdirs() }
            uris.mapNotNull { uri ->
                runCatching {
                    val name = com.j4.music.repo.RepoCodec.sanitizeFileName(queryDisplayName(uri) ?: "picked.bin")
                    val out = File(dir, name)
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        out.outputStream().use { os -> ins.copyTo(os) }
                    } ?: return@runCatching null
                    out
                }.getOrNull()
            }
        }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } else {
            uri.lastPathSegment
        }
    }.getOrNull()

    /** 读选中的文本文件（歌单 JSON/M3U/CSV/TXT），超过 8MB 直接拒绝，免得把内存吃穿 */
    private suspend fun readPickedText(uri: android.net.Uri): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = ins.readBytes()
                    if (buf.size > 8 * 1024 * 1024) throw IllegalStateException("文件太大（超过 8MB）")
                    String(buf, Charsets.UTF_8)
                } ?: return@runCatching null
                text.removePrefix("\uFEFF")
            }.getOrNull()
        }

    /** 同步方法：必须在主线程无关的短任务上跑，失败统一 catch */
    private inline fun sync(a: JSONObject, body: () -> JSONObject): String =
        try { ok(body()) } catch (e: Throwable) { err(e.message ?: e.javaClass.simpleName) }

    /**
     * 异步方法的结果回填通道。
     *
     * 为什么需要它：`call()` 是 @JavascriptInterface，跑在 WebView 的 JavaBridge 线程上，
     * 且必须同步返回一个字符串。搜索、取播放地址、下载、扫描这些动辄几百毫秒到两分钟，
     * 在这条线程上阻塞会把整个界面卡死。所以这里立刻返回一个「占位 id」：
     *
     *     {"ok":true,"data":null,"__async":true,"id":"a17"}
     *
     * 前端的 bridge.js 认得这个形状，会返回一个挂起中的 Promise 并把它登记进 pendingMap。
     * 协程真正跑完后，我们主动执行一次
     *
     *     window.__J4BridgeResolve('a17', '{"ok":true,"data":{...}}')
     *
     * 由前端把那个 Promise resolve 掉。于是业务代码可以正常写 `await B.call('search', …)`，
     * 而不必自己订阅事件。
     *
     * 事件通道（emitEvent）仍然保留：它负责「没有人在等」的推送，比如播放状态变化、
     * 下载进度、扫码轮的中间态。两条通道互不冲突——一次调用会两条都发，
     * 前端只会用到其中一条，另一条留给监听者。
     */
    /**
     * 异步路由的两种入口。
     *
     * 返回值型：body 的最后一个表达式就是回给前端的数据，前端 `await B.call(...)` 直接拿到。
     * 动作型：body 不产生数据（setQueue / 各类进度型操作），前端拿到 null，靠事件感知结果。
     */
    private fun async(a: JSONObject, body: suspend (String?) -> Any?): String =
        launch(a, body)

    private fun asyncUnit(a: JSONObject, body: suspend (String?) -> Unit): String =
        launch(a) { req -> body(req); null }

    private fun launch(a: JSONObject, body: suspend (String?) -> Any?): String {
        val req = a.optString("req").ifEmpty { null }
        val ticket = "j4a" + asyncSeq.incrementAndGet() + System.nanoTime().toString(36)
        scope.launch {
            val reply = try {
                val data = body(req)
                JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 失败也要兑现 Promise，否则前端的 await 永远悬挂
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            resolveTicket(ticket, reply.toString())
        }
        return JSONObject().put("ok", true).put("data", JSONObject.NULL)
            .put("__async", true).put("id", ticket).toString()
    }

    /** 执行 `window.__J4BridgeResolve(id, json)`，把对应的 Promise 兑现 */
    private fun resolveTicket(id: String, json: String) {
        val line = "window.__J4BridgeResolve && window.__J4BridgeResolve(${quote(id)}, ${quote(json)});"
        main.post { host?.runJs(line) }
    }

    private val asyncSeq = java.util.concurrent.atomic.AtomicLong()

    private fun ok(data: Any?): String = JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL).toString()
    private fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    /**
     * 歌单改动后把整份推到云端。
     *
     * 为什么不 await：用户点「加歌」时不该等一次 40s 超时的网络往返；
     * 失败只在日志里留一条，本地数据已经落盘，下次任何一次操作会重传。
     */
    private fun cloudPushAsync(id: String) {
        if (!CloudPlaylists.enabled()) return
        scope.launch { runCatching { CloudPlaylists.pushQuietly(playlistRepo, id) } }
    }


    private fun songsToJson(list: List<Song>): JSONArray = JSONArray().also { ja ->
        for (s in list) ja.put(s.toJson())
    }

    private fun parseSongs(arr: JSONArray?): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Song.fromJson(o))
        }
        return out
    }

    /** 各音源 cookie 落盘的 host，登录态读回时要用同一个 host 查 */
    /** 当前登录态对应的 cookie 串（网易要 MUSIC_U，QQ 要 qm_keyst，各源 host 不同） */
    /** 会员态：各源登录流程成功时会把 vipLevel 落到 src.<id>.vip，这里只读不推 */
    private fun isVip(src: MusicSource): Boolean {
        val v = App.db.get("src.${src.id}.vip") ?: return false
        return v != "0" && v != "false" && v != ""
    }

    private fun currentCookie(src: MusicSource): String = runCatching {
        when (src) {
            // 开源上游音源：cookie 存在本地 KV 里（不是 CookieJar），原样回给前端
            is OpenApiSource -> OpenApiConfig.cookie(src.platform)
            // SourceEnv.jar() 是全局 CookieJar，按 host 取回整串登录 cookie
            is NeteaseSource -> SourceEnv.jar()?.cookieString("music.163.com").orEmpty()
            is QqSource -> SourceEnv.jar()?.cookieString("y.qq.com").orEmpty()
            is HuaweiSource -> SourceEnv.jar()?.cookieString("music.huawei.com").orEmpty()
            else -> ""
        }
    }.getOrDefault("")

    private fun hostOf(srcId: String): String = when (srcId) {
        "netease" -> "music.163.com"
        "qq" -> "y.qq.com"
        "huawei" -> "music.huawei.com"
        else -> srcId
    }    /** 把「音源顺序 × 组内排名」交错合并：A1 B1 C1 A2 B2 C2 …  */
    private fun interleave(lists: List<List<Song>>): List<Song> {
        if (lists.isEmpty()) return emptyList()
        if (lists.size == 1) return lists[0]
        val out = ArrayList<Song>(lists.sumOf { it.size })
        val seen = HashSet<String>()
        var i = 0
        var any = true
        while (any) {
            any = false
            for (l in lists) {
                if (i < l.size) {
                    any = true
                    val s = l[i]
                    if (seen.add(s.id)) out.add(s)
                }
            }
            i++
        }
        return out
    }
    private fun snapshotJson(snap: PlayerService.PlayerSnapshot): JSONObject = stateJsonOf(snap)

    /**
     * 在主线程上同步执行一段播放器操作并取回结果。
     *
     * 为什么必须这样：`@JavascriptInterface` 的方法跑在 WebView 的 JavaBridge
     * 线程，而 ExoPlayer 强制「只能在创建它的线程上访问」，否则直接抛
     * `Player is accessed on the wrong thread. thread: 'JavaBridge' Expected
     * thread: 'main'`，表现为点播放就失败、界面弹红条。
     * 用 CountDownLatch 等主线程做完再回来，前端依然是同步拿到结果的。
     * 已经在主线程时直接执行，避免自己等自己死锁。
     */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = main.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!posted) throw IllegalStateException("播放器线程已退出")
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        val r = result ?: throw IllegalStateException("播放器操作超时")
        return r.getOrThrow()
    }

    /**
     * 用回调里那一帧的快照拼状态。
     *
     * 为什么不直接调 stateJson()：state 回调跑在播放器线程上，此刻再去问
     * service.snapshot() 会拿到下一帧的数据，和事件里的 playing/position 对不上。
     * queue 一定要带上：前端每次收到 state 都会用它重画队列，缺了就会把列表清空。
     */
    private fun stateJsonOf(snap: PlayerService.PlayerSnapshot): JSONObject = JSONObject().apply {
        put("ready", true)
        put("playing", snap.playing)
        put("index", snap.index)
        put("positionMs", snap.positionMs)
        put("durationMs", snap.durationMs)
        put("bufferedMs", snap.bufferedMs)
        put("loopMode", snap.loopMode)
        put("song", player?.currentSong()?.toJson() ?: JSONObject.NULL)
        put("queue", songsToJson(player?.queueSongs().orEmpty()))
        put("playlistId", App.db.get("play.list.playlistId") ?: "")
    }

    /**
     * 前端超时保护：桥接层所有「要联网/要解压」的方法都必须走 async + 事件回推，
     * 只有纯内存读写的短任务允许同步返回。
     */
    private fun stateJson(): JSONObject = onMain { readStateJson() }

    /**
     * 真正读播放器的部分。只能在主线程调用 —— ExoPlayer 的 getCurrentMediaItem 等
     * 访问器同样带线程断言，stateJson/stateJsonOf 都会碰它。
     * 回调线程里已经拿到的快照用 stateJsonOf(快照) 拼，不要走这里。
     */
    private fun readStateJson(): JSONObject {
        val p = player ?: return JSONObject().put("ready", false)
        val snap = p.snapshot()
        return JSONObject().apply {
            put("ready", true)
            put("playing", snap.playing)
            put("index", snap.index)
            put("positionMs", snap.positionMs)
            put("durationMs", snap.durationMs)
            put("bufferedMs", snap.bufferedMs)
            put("loopMode", snap.loopMode)
            put("song", p.currentSong()?.toJson() ?: JSONObject.NULL)
            put("queue", songsToJson(p.queueSongs()))
            put("playlistId", App.db.get("play.list.playlistId") ?: "")
        }
    }

    /** 统一的状态广播：播放器任何状态变化都走这里，避免各处拼 JSON 漂移 */
    private fun broadcastState() {
        emitEvent("state", stateJson(), null)
    }

    /**
     * 下载任务的对外形状。DownloadTask 给的是内部结构（id/song/progress 0..1），
     * 前端要的是 taskId/title/songId/progress(0..100)/state ∈ waiting|downloading|done|failed|canceled，
     * 所以这里单独摊一次，别直接透传。
     */
    private fun tasksJson(): JSONArray = JSONArray().also { ja ->
        for (t in DownloadManager.snapshot()) ja.put(JSONObject().apply {
            put("taskId", t.id)
            put("songId", t.song.id)
            put("title", t.song.title)
            put("artist", t.song.artist)
            put("cover", t.song.coverUrl ?: JSONObject.NULL)
            put("state", when (t.state) {
                DownloadManager.State.QUEUED -> "waiting"
                DownloadManager.State.RUNNING -> "downloading"
                DownloadManager.State.DONE -> "done"
                DownloadManager.State.FAILED -> "failed"
                DownloadManager.State.CANCELLED -> "canceled"
            })
            put("progress", t.progress * 100.0)
            put("done", t.done)
            put("total", t.total)
            put("quality", t.quality)
            put("error", t.error ?: JSONObject.NULL)
            put("file", t.file ?: JSONObject.NULL)
            // download.js:101 读的是 path，file 是历史命名，两个都发避免又一处不一致
            put("path", t.file ?: JSONObject.NULL)
            put("song", t.song.toJson())
        })
    }

    /** 把导出的歌单文本落到 App 私有目录 exports/ 下，返回绝对路径 */
    private fun writeExportFile(playlistId: String, text: String, ext: String): String = runCatching {
        val dir = RepoCodec.ensureDir(File(RepoCodec.musicDir(), "exports"))
        val base = RepoCodec.sanitizeFileName("playlist_" + playlistId)
        val f = RepoCodec.uniqueFile(dir, base + "-" + System.currentTimeMillis(), "." + ext)
        f.writeText(text, Charsets.UTF_8)
        f.absolutePath
    }.getOrDefault("")

    private fun sourcesJson(): JSONArray = JSONArray().also { ja ->
        for (s in SourceRegistry.all()) ja.put(sourceJson(s))
    }

    private fun sourceJson(s: MusicSource): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("lossless", s.supportsLossless)
        put("requiresLogin", s.requiresLogin)
        put("loggedIn", runCatching { s.isLoggedIn() }.getOrDefault(false))
    }

    private fun settingsJson(): JSONObject = JSONObject().apply {
        put("quality", App.db.get("audio.quality") ?: "lossless")
        put("theme", App.db.get("ui.theme") ?: "aurora")
        put("accent", App.db.get("ui.accent") ?: "#7C5CFF")
        put("downloadQuality", App.db.get("download.quality") ?: "lossless")
        put("downloadDir", App.db.get("j4.downloadDir") ?: RepoCodec.musicDir().absolutePath)
        put("crossSource", App.db.get("play.crossSource") != "0")
        put("autoNext", App.db.get("play.autoNext") != "0")
        put("lyricOffset", App.db.get("play.lyricOffset")?.toIntOrNull() ?: 0)
        put("transcodeOnImport", App.db.get("crypto.autoTranscode") == "1")
        put("loopMode", App.db.get("play.loopMode") ?: "list")
        // 缓存 = 下载目录里音频文件的总字节；上限给个固定配额让前端画进度条
        put("cacheSize", dirSize(RepoCodec.musicDir()))
        put("cacheLimit", 1024L * 1024 * 1024)
        put("version", BuildInfo.VERSION)
        // 上游聚合服务：设置页那两个输入框直接读写这三个键
        put("upstreamBase", OpenApiConfig.baseUrl())
        put("upstreamToken", OpenApiConfig.token())
        put("upstreamEnabled", OpenApiConfig.isConfigured())
    }

    /**
     * 把前端传来的平台名映射到具体的音源实现。
     * 前端历史上用过 `netease` / `qq` / `qqmusic` 三种写法，这里全部收口。
     */
    private fun openApiOf(name: String): OpenApiSource {
        val want = when (name) {
            "qqmusic", "qq" -> "qq"
            "netease", "" -> "netease"
            else -> name
        }
        return SourceRegistry.get(want) as? OpenApiSource
            ?: throw IllegalStateException("音源 $name 不支持登录（只支持网易云与 QQ 音乐）")
    }

    /**
     * 从 `"netease:12345"` / `"qq:0039MnYb0qxYhV"` 这样的 id 还原出一首最少信息的 Song。
     *
     * 用途：搜索结果里的歌还没进播放队列时点「播放」，前端只能给出 id。
     * 取地址需要的原始 id 就在 `source:rawId` 里，但 QQ 需要它落在 extra["mid"]，
     * 所以这里按平台把 rawId 摆到对应位置，避免取址时拿错字段。
     */
    private fun songFromUpstreamId(id: String): Song? {
        val source = SourceRegistry.ids().firstOrNull { id.startsWith("$it:") } ?: return null
        val rawId = id.removePrefix("$source:")
        if (rawId.isEmpty()) return null
        val extra = when (source) {
            "qq" -> mapOf("mid" to rawId, "platform" to "qqmusic")
            "netease" -> mapOf("songid" to rawId, "platform" to "netease")
            else -> emptyMap()
        }
        return Song(
            id = id,
            source = source,
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            coverUrl = null,
            vip = false,
            localPath = null,
            quality = "standard",
            extra = extra
        )
    }

    /**
     * 找一个真实音频文件当转译素材：先看下载目录，再看私有的 Music 目录，
     * 最后退回外部下载目录（用户手动放进来的也行）。
     */
    private fun findAnyAudio(): File? {
        val dirs = listOfNotNull(
            runCatching { com.j4.music.repo.RepoCodec.musicDir() }.getOrNull(),
            File(App.instance.getExternalFilesDir(null) ?: App.instance.filesDir, "Music"),
            File(App.instance.filesDir, "Music"),
            File(android.os.Environment.getExternalStorageDirectory(), "Download")
        )
        for (d in dirs) {
            if (!d.isDirectory) continue
            val hit = d.listFiles()?.firstOrNull {
                it.isFile && it.length() > 64 * 1024 &&
                    it.name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "ogg", "wav")
            }
            if (hit != null) return hit
        }
        return null
    }

    /** 递归算目录占用，单个文件出错就跳过（权限/被删都可能） */    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    /** 清缓存：只删音频与临时文件，导出与歌词元数据保留 */
    private fun clearCache() {
        val music = RepoCodec.musicDir()
        music.walkTopDown().filter { it.isFile }.forEach { f ->
            val n = f.name.lowercase()
            if (n.endsWith(".part") || n.endsWith(".tmp") || RepoCodec.isPlainAudio(n) || RepoCodec.isEncryptedName(n)) {
                runCatching { f.delete() }
            }
        }
    }

    private fun shareToSystem(text: String) {
        main.post {
            runCatching {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "J4Music 歌单")
                }
                val chooser = android.content.Intent.createChooser(i, "分享歌单")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        }
    }

    /**
     * 打开一个 URL。
     *
     * file:///android_asset/ 下的应用内页面（用户协议、隐私政策、开源许可）走
     * WebView 内部的独立浏览页 —— 它们不该进路由栈，用一个很轻的 Activity 承载，
     * 返回键直接退出那一页。
     * 其他 scheme（http/https/mailto…）交给系统浏览器。
     *
     * 失败时抛异常而不是静默吞掉：以前整条链都是 runCatching 且不看结果，
     * 用户点到没反应，也拿不到任何提示。
     */
    private fun openUrl(url: String, title: String) {
        val u = url.trim()
        if (u.isEmpty()) throw IllegalArgumentException("空的链接")
        main.post {
            runCatching {
                val intent = if (u.startsWith("file:///android_asset/")) {
                    android.content.Intent(context, com.j4.music.WebPageActivity::class.java)
                        .putExtra(com.j4.music.WebPageActivity.EXTRA_URL, u)
                        .putExtra(com.j4.music.WebPageActivity.EXTRA_TITLE, title)
                } else {
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                }
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure { e ->
                main.post { toast("打不开：${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /**
     * 把导出的歌单文件也发给系统：文本分享能过 IM，但「跨软件同步」往往需要
     * 一个真实的文件（.j4 / .m3u8 / .txt）。没有 FileProvider 时 file:// 会被
     * 系统的 StrictMode 拦掉，所以这里只发文本，文件路径作为附加字段给出，
     * 让用户在导出目录里自己取（设置里能看到 exports 目录）。
     */
    private fun shareFileHint(path: String): String =
        if (path.isEmpty()) "" else "\n（文件已导出到：$path）"

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    object BuildInfo {
        const val VERSION = "1.0.0"
    }

    private companion object {
        /** 歌单类型：云端歌单（新建时的默认值，改动后自动整份上传） */
        const val KIND_CLOUD = "cloud"

        /** 收藏表存在 KV 里（一个 JSON 数组），不建表也不占歌单列表位置 */
        const val KEY_FAV = "j4.fav.songs"
    }

    /** 收藏的读写要走同一把锁：加歌/取消可能连着点，读时写会写出半截 JSON */
    private val favLock = Any()

    private fun favSongs(): List<Song> = runCatching {
        val raw = App.db.get(KEY_FAV) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { Song.fromJson(o) }.getOrNull() }
        }
    }.getOrDefault(emptyList())

    private fun favSave(list: List<Song>) {
        val arr = JSONArray()
        for (s in list) arr.put(s.toJson())
        runCatching { App.db.put(KEY_FAV, arr.toString()) }
    }
}
