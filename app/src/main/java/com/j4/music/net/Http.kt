package com.j4.music.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 全局网络层。
 *
 * 两个平台对请求头的要求各不相同并且经常被风控拿来判别：
 *  - 网易云：必须带 Referer: https://music.163.com，Cookie 里放 MUSIC_U；
 *  - QQ：Referer 必须是 y.qq.com，UA 要像手机浏览器；
 *    用它自带的 "kwplayer" 之类反而拿不到 flac。
 *
 * 所以这里不用单一 client，而是提供按源取 client 的方法，每个源一套 UA/Referer，
 * 同时共享连接池与超时，避免频繁 TLS 握手。
 */
object Http {

    /** 移动端 UA，网易云与 QQ 通用 */
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    /** 通用 client：不注入任何平台头的裸 client */
    val raw: OkHttpClient get() = base

    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    /** 按源取带默认头的 client；headers 为空则返回裸 client */
    fun clientFor(source: String, headers: Map<String, String> = defaultHeaders(source)): OkHttpClient {
        if (headers.isEmpty()) return base
        val key = source + headers.entries.joinToString("|") { "${it.key}=${it.value}" }
        return clients.getOrPut(key) {
            base.newBuilder().addInterceptor(HeaderInterceptor(headers)).build()
        }
    }

    /** 各源的默认请求头 */
    fun defaultHeaders(source: String): Map<String, String> = when (source) {
        "netease" -> mapOf(
            "User-Agent" to UA_MOBILE,
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com"
        )
        "qq" -> mapOf(
            "User-Agent" to UA_MOBILE,
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com"
        )
        "huawei" -> mapOf(
            "User-Agent" to UA_MOBILE,
            "Referer" to "https://music.huawei.com/"
        )
        else -> emptyMap()
    }

    private class HeaderInterceptor(private val headers: Map<String, String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val b: Request.Builder = chain.request().newBuilder()
            headers.forEach { (k, v) ->
                // 调用方显式给的头优先，不覆盖
                if (chain.request().header(k) == null) b.header(k, v)
            }
            return chain.proceed(b.build())
        }
    }
}

/**
 * 轻量联网探测。
 *
 * 为什么要它：四个在线源的失败形态不同 —— DNS 被劫持时是连接超时（慢），
 * 飞行模式时是 NetworkUnavailable（快），而部分 ROM 在「已连 Wi-Fi 但 Captive
 * Portal 登录页」状态下 TCP 能连上却不回数据。UI 上「刷新失败」和「你现在没网」
 * 是两种完全不同的提示，得先分得清。
 *
 * 两个手段，按成本从低到高，任一为真即返回：
 *  1) ConnectivityManager：查默认网络的 NET_CAPABILITY_VALIDATED。它由系统做
 *     captive portal 探针（http://connectivitycheck.gstatic.com/generate_204）
 *     之后打标，结果是**缓存**的，所以这个调用几乎零成本，也不产生流量；
 *     没有这个 capability 但仍有网络（比如某些国内 ROM / 代理）时不会直接判死，
 *     交给第 2 步确认。
 *  2) 短超时 socket 探测：对 1.1.1.1:443 / 223.5.5.5:53 依次 connect，
 *     每个 400ms 超时。不做任何握手，只建立 TCP 连接就立刻关闭，
 *     代价是一次 SYN 往返。选 IP 而不是域名是为了绕过 DNS，
 *     否则 DNS 失败时无法区分「没网」和「DNS 挂了」。
 *
 * 为什么是 object 而不是 class：调用方（桥层 / 搜索页）只需要一问一答，
 * 没有必要持有实例；内部只缓存很小的结果（2 秒），用来避免列表滚动时
 * 每个 item 各探一次。
 */
object NetProbe {

    private const val PROBE_HOST_1 = "1.1.1.1"
    private const val PROBE_PORT_1 = 443
    private const val PROBE_HOST_2 = "223.5.5.5"
    private const val PROBE_PORT_2 = 53
    private const val CONNECT_TIMEOUT_MS = 400

    /** 结果缓存时长。2 秒足够挡住「同一帧里 N 个组件同时问」的抖动，又不至于误导 */
    private const val CACHE_MS = 2_000L

    private const val CAP_INTERNET = 12            // android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
    private const val CAP_VALIDATED = 16           // NET_CAPABILITY_VALIDATED
    private const val TRANSPORT_WIFI = 1
    private const val TRANSPORT_CELLULAR = 0
    private const val TRANSPORT_ETHERNET = 3

    @Volatile
    private var lastResult: Boolean = false

    @Volatile
    private var lastAt: Long = 0L

    /**
     * 是否在线。
     *
     * 注意"在线"的语义是「看起来有可用网络」，不是「某个具体域名可达」：
     * 后者永远只能对一个 URL 有结论，而这个函数要给的是 UI 的全局开关。
     * 真要判断某个接口能否访问，调用方应该直接发请求并看异常。
     */
    fun isOnline(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAt < CACHE_MS) return lastResult
        val ok = runCatching { quickCheck() }.getOrDefault(false) || runCatching { socketProbe() }.getOrDefault(false)
        lastResult = ok
        lastAt = now
        return ok
    }

    /** 连通性管理器报告「默认网络已通过系统验证」 */
    private fun quickCheck(): Boolean {
        val ctx = AppContextHolder.get() ?: return false
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // 这个网络必须是 Internet 类型（而不是 VPN 内部 / 本地热点）
        if (!caps.hasCapability(CAP_INTERNET)) return false
        // 有 WiFi / 蜂窝 / 有线任一传输方式（排除纯 loopback 之类）
        val hasTransport = caps.hasTransport(TRANSPORT_WIFI) ||
            caps.hasTransport(TRANSPORT_CELLULAR) ||
            caps.hasTransport(TRANSPORT_ETHERNET)
        if (!hasTransport) return false
        // VALIDATED 有就直接信；没有也不判死（国内 ROM 常不打这个标），交给 socket 探测
        return caps.hasCapability(CAP_VALIDATED)
    }

    /** 对固定 IP 做短超时 TCP connect；连上即认为通，立刻关闭 */
    private fun socketProbe(): Boolean {
        if (probe(PROBE_HOST_1, PROBE_PORT_1)) return true
        return probe(PROBE_HOST_2, PROBE_PORT_2)
    }

    private fun probe(host: String, port: Int): Boolean = try {
        java.net.Socket().use { s ->
            val addr = java.net.InetSocketAddress(host, port)
            s.connect(addr, CONNECT_TIMEOUT_MS)
        }
        true
    } catch (e: Throwable) {
        // SocketTimeoutException / UnknownHost / NetworkOnMainThread 全部算「不通」，
        // 不区分原因：这个函数只回答「现在能不能指望网络」。
        false
    }

    /** 强制刷新缓存（用户点了"重试"时用） */
    fun invalidate() {
        lastAt = 0L
    }
}

/**
 * 给 NetProbe 拿 Context 的极薄间接层。
 *
 * 为什么不直接引 App.instance：net 包被音源层大量引用，而音源层要能在纯 JVM
 * 单测里跑（没有 Android 运行时，App 是 lateinit 会抛异常）。
 * 这里用 runCatching 收住，拿不到就返回 null，NetProbe 自然退化成 socket 探测。
 */
internal object AppContextHolder {
    fun get(): android.content.Context? = runCatching { com.j4.music.App.instance }.getOrNull()
}
