package com.j4.music.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 持久化 CookieJar。
 *
 * 为什么要自己做：三平台的登录态全靠 Cookie（网易 MUSIC_U、QQ qm_keyst/uin、
 * 网易 MUSIC_U 之类），OkHttp 默认的内存 CookieJar 一进程结束就丢，
 * 用户每次开 App 都要重新扫码。这里把每个域名下的 Cookie 序列化进 KV 表。
 */
class PersistentCookieJar(context: Context, private val store: CookieStore?) : CookieJar {

    private val mem = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        store?.load()?.forEach { (host, list) -> mem[host] = list.toMutableList() }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val list = mem.getOrPut(host) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { nc ->
                list.removeAll { it.name == nc.name && it.path == nc.path }
                if (nc.expiresAt > System.currentTimeMillis()) {
                    list.add(nc)
                }
            }
        }
        flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Cookie>()
        // 精确域名 + 父域（.163.com 这类）都要带上
        val hosts = buildList {
            add(url.host)
            val parts = url.host.split('.')
            for (i in 1 until parts.size - 1) add("." + parts.drop(i).joinToString("."))
        }
        hosts.forEach { h ->
            mem[h]?.let { list ->
                synchronized(list) {
                    list.removeAll { it.expiresAt <= now }
                    out.addAll(list.filter { url.pathSegments.joinToString("/").startsWith(it.path.trimStart('/')) || it.path == "/" })
                }
            }
        }
        return out
    }

    /** 直接注入一串 "k=v; k2=v2" 形式的 Cookie（登录成功后平台返回的那种） */
    fun putCookieString(url: String, cookieString: String) {
        val u = url.toHttpUrlOrNull() ?: return
        val cookies = cookieString.split(";").mapNotNull { seg ->
            val i = seg.indexOf('=')
            if (i <= 0) null else Cookie.parse(u, seg.trim())
        }
        saveFromResponse(u, cookies)
    }

    /** 取某域名下的 Cookie 串，给需要手动拼 Cookie 头的接口用 */
    fun cookieString(host: String): String {
        val list = mem[host] ?: mem[".$host"] ?: return ""
        return synchronized(list) { list.joinToString("; ") { "${it.name}=${it.value}" } }
    }

    fun clear() {
        mem.clear()
        flush()
    }

    private fun flush() {
        store?.save(mem.mapValues { it.value.toList() })
    }

    /** 由调用方实现持久化（项目里接 AppDatabase 的 kv 表） */
    interface CookieStore {
        fun load(): Map<String, List<Cookie>>
        fun save(data: Map<String, List<Cookie>>)
    }
}
