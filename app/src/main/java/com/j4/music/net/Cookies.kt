package com.j4.music.net

import android.content.Context
import com.j4.music.App
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 PersistentCookieJar 的 Cookie 落到 KV 表。
 *
 * 存储格式：k = "ck:<host>"，v = JSON 数组，每项 {n:name, v:value, d:domain, p:path, e:expiresAt, s:secure, h:hostOnly}。
 * 不用 Cookie.toString() 解析回来，因为 okhttp 的 toString 丢 expiresAt 精度且
 * 在 value 里含 ';' 时会解析错，直接用 JSON 最省心。
 */
class KvCookieStore(private val context: Context) : PersistentCookieJar.CookieStore {

    private val db get() = App.db

    override fun load(): Map<String, List<Cookie>> {
        val out = mutableMapOf<String, List<Cookie>>()
        val raw = db.get("ck:index") ?: return out
        val hosts = runCatching { JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until hosts.length()) {
            val host = hosts.optString(i)
            if (host.isEmpty()) continue
            val json = db.get("ck:$host") ?: continue
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: continue
            val list = mutableListOf<Cookie>()
            for (j in 0 until arr.length()) {
                val o = arr.optJSONObject(j) ?: continue
                val c = runCatching {
                    Cookie.Builder()
                        .name(o.optString("n"))
                        .value(o.optString("v"))
                        .domain(o.optString("d"))
                        .path(o.optString("p", "/"))
                        .expiresAt(o.optLong("e", Long.MAX_VALUE))
                        .apply { if (o.optBoolean("s")) secure() }
                        .build()
                }.getOrNull()
                if (c != null) list.add(c)
            }
            if (list.isNotEmpty()) out[host] = list
        }
        return out
    }

    override fun save(data: Map<String, List<Cookie>>) {
        val hosts = JSONArray()
        data.forEach { (host, list) ->
            if (list.isEmpty()) return@forEach
            hosts.put(host)
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("n", c.name)
                        put("v", c.value)
                        put("d", c.domain)
                        put("p", c.path)
                        put("e", c.expiresAt)
                        put("s", c.secure)
                        put("h", c.hostOnly)
                    }
                )
            }
            db.put("ck:$host", arr.toString())
        }
        db.put("ck:index", hosts.toString())
    }
}

/** 全局唯一的 CookieJar；各音源共用，因为域名不同不会互相污染 */
object Cookies {
    private var jar: PersistentCookieJar? = null

    fun get(context: Context): PersistentCookieJar =
        jar ?: synchronized(this) {
            jar ?: PersistentCookieJar(context, KvCookieStore(context)).also { jar = it }
        }

    /** 便捷：把 "k=v; k2=v2" 注入某个站点 */
    fun put(context: Context, url: String, cookieString: String) {
        get(context).putCookieString(url, cookieString)
    }

    fun string(context: Context, host: String): String = get(context).cookieString(host)

    fun clear(context: Context) = get(context).clear()

    /** 让 okhttp 的冒烟测试能构造 URL */
    fun host(url: String): String? = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull()
}
