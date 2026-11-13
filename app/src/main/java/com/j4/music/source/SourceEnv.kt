package com.j4.music.source

import com.j4.music.data.AppDatabase
import com.j4.music.net.Cookies
import com.j4.music.net.PersistentCookieJar

/**
 * 音源层的「运行时 glue」：把 cookie jar、KV 存储、HTTP 驱动这三样东西
 * 从 App 单例那里取出来，切成可以替换的形式。
 *
 * 为什么要可替换：四个平台的解析逻辑要能在没有网络、没有 Android 运行时的
 * 单测里跑。生产环境走 [install]（App 启动时调用一次），测试里直接 new 一个
 * [SourceEnv] 塞假 driver 和内存 store。
 */
class SourceEnv(
    val state: SourceStateStore,
    private val driverFactory: ((String) -> HttpDriver)? = null,
    val cookieJar: PersistentCookieJar? = null
) {
    /** 给某个源造一个 HTTP 驱动；driverFactory 为 null 时走默认 OkHttp 实现 */
    fun driver(source: String, headers: Map<String, String>? = null, followRedirects: Boolean = true): HttpDriver {
        val f = driverFactory
        if (f != null) return f(source)
        return OkHttpDriver(
            source = source,
            jar = cookieJar,
            headers = headers ?: com.j4.music.net.Http.defaultHeaders(source),
            followRedirects = followRedirects
        )
    }

    /** 把 "k=v; k2=v2" 注入某域名；没有 jar 时（单测）静默丢弃 */
    fun putCookie(url: String, cookie: String) {
        cookieJar?.putCookieString(url, cookie)
    }

    fun cookieString(host: String): String = cookieJar?.cookieString(host) ?: ""

    fun clearCookies() {
        cookieJar?.clear()
    }

    companion object {

        @Volatile
        var current: SourceEnv = SourceEnv(MemorySourceState())

        /**
         * App.kt 已经在 onCreate 里调了 SourceRegistry.registerDefaults()，
         * 但那是「注册」（把所有源放进表），[install] 是「装配」（把 App 的 DB 与 CookieJar 接上）。
         * 两个动作分开做，是为了 registerDefaults 能在测试里跑而不碰 Android。
         */
        @Synchronized
        fun install(context: android.content.Context, db: AppDatabase) {
            val jar = Cookies.get(context)
            val store = DataStoreSourceState(
                read = { k -> db.get(k) },
                write = { k, v -> if (v.isEmpty()) Unit else db.put(k, v) }
            )
            current = SourceEnv(store, null, jar)
        }

        /** 每个源注册时都来问一次；没 install 过就返回内存实现，不会 NPE */
        fun state(): SourceStateStore = current.state

        fun jar(): PersistentCookieJar? = current.cookieJar
    }
}

/** 各源共享的 KV 键名前缀，避免和前端/其它模块撞 key */
object Keys {
    fun netease(k: String) = "src.netease.$k"
    fun qq(k: String) = "src.qq.$k"
    fun huawei(k: String) = "src.huawei.$k"
}
