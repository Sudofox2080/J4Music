package com.j4.music.source

/**
 * 音源层的共享小工具：状态存储、日志、JSON 容错、质量档位换算。
 *
 * 为什么全塞在一个文件：这些都是「一行逻辑」，单独拆十几个文件反而让每个源的实现
 * 读起来都是 import 噪音。这里只放无状态的纯函数 + 一个可替换的 KV 存储抽象。
 */

/**
 * 登录态/设备身份要落的 KV。
 *
 * 生产实现是 [DataStoreSourceState]（走 AppDatabase 的 kv 表），
 * 测试里可以塞一个内存 Map，避免音源层直接依赖 Android SQLite。
 */
interface SourceStateStore {
    fun get(key: String, def: String? = null): String?
    fun put(key: String, value: String)
    fun remove(key: String)

    fun getInt(key: String, def: Int = 0): Int = get(key)?.toIntOrNull() ?: def
    fun getLong(key: String, def: Long = 0L): Long = get(key)?.toLongOrNull() ?: def
    fun getOrPut(key: String, factory: () -> String): String {
        get(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        val v = factory()
        put(key, v)
        return v
    }
}

/** 内存实现：既给单元测试用，也作为 App.db 尚未初始化时的安全兜底 */
class MemorySourceState : SourceStateStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String, def: String?): String? = map[key] ?: def
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** 走 AppDatabase 的 kv 表；所有访问都在调用线程上同步发生，调用方保证在 IO 线程 */
class DataStoreSourceState(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit
) : SourceStateStore {
    override fun get(key: String, def: String?): String? = runCatching { read(key) ?: def }.getOrDefault(def)
    override fun put(key: String, value: String) { runCatching { write(key, value) } }
    override fun remove(key: String) { runCatching { write(key, "") } }
}

/**
 * 日志。
 *
 * 为什么不直接用 android.util.Log：音源的解析逻辑要在 JVM 单测里跑，
 * Log 会抛 "not mocked"。这里先把行攒在内存 ring buffer 里，
 * [logTag] 供问题排查，Debug 环境下再顺手打一条 Log。
 */
object SourceLog {

    private const val MAX = 200
    private val ring = ArrayDeque<String>(MAX)
    @Volatile var tag = "J4Source"
    @Volatile var echo = true

    fun w(source: String, message: String) = add("W", source, message)
    fun d(source: String, message: String) = add("D", source, message)
    fun e(source: String, message: String) = add("E", source, message)

    private fun add(lvl: String, source: String, message: String) {
        val line = "$lvl/$source: $message"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > MAX) ring.removeFirst()
        }
        if (echo) {
            runCatching {
                when (lvl) {
                    "E" -> android.util.Log.e(tag, line)
                    "W" -> android.util.Log.w(tag, line)
                    else -> android.util.Log.d(tag, line)
                }
            }
        }
    }

    /** 取最近的日志，调试面板用 */
    fun recent(n: Int = 50): List<String> = synchronized(ring) { ring.toList().takeLast(n) }

    fun clear() = synchronized(ring) { ring.clear() }
}

/**
 * org.json 的容错读取。
 *
 * 规格要求「解析函数必须容忍字段缺失」：各平台同一接口不同曲目返回的字段集合并不一致
 * （华为有的条目没有 picture），
 * 所以所有取值都走这里，字段缺失一律给默认值，绝不抛 JSONException。
 */
object Jsonx {

    fun parse(s: String?): org.json.JSONObject? {
        if (s.isNullOrBlank()) return null
        return runCatching { org.json.JSONObject(s) }.getOrNull()
    }

    fun parseArray(s: String?): org.json.JSONArray? {
        if (s.isNullOrBlank()) return null
        return runCatching { org.json.JSONArray(s) }.getOrNull()
    }

    /** 有些平台的「对象」字段其实是 JSON 字符串（华为的 contentExInfo），这里统一剥一层 */
    fun obj(o: org.json.JSONObject?, key: String): org.json.JSONObject? {
        if (o == null || !o.has(key)) return null
        val v = o.opt(key)
        return when (v) {
            is org.json.JSONObject -> v
            is String -> parse(v)
            else -> null
        }
    }

    fun arr(o: org.json.JSONObject?, key: String): org.json.JSONArray? = o?.optJSONArray(key)

    fun objAt(a: org.json.JSONArray?, i: Int): org.json.JSONObject? = a?.optJSONObject(i)

    fun str(o: org.json.JSONObject?, key: String, def: String = ""): String {
        if (o == null || !o.has(key)) return def
        val v = o.opt(key)
        return when (v) {
            null, org.json.JSONObject.NULL -> def
            is String -> if (v == "null") def else v
            is Number -> numToString(v)
            is Boolean -> v.toString()
            else -> v.toString()
        }
    }

    /** 取第一个非空的字符串字段，用于「name 或 title 都行」这种二选一 */
    fun firstStr(o: org.json.JSONObject?, vararg keys: String): String {
        for (k in keys) {
            val v = str(o, k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    fun long(o: org.json.JSONObject?, key: String, def: Long = 0L): Long {
        if (o == null || !o.has(key)) return def
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong()
                ?: v.trim().toLongOrNull()
                ?: def
            is Boolean -> if (v) 1L else 0L
            else -> def
        }
    }

    fun int(o: org.json.JSONObject?, key: String, def: Int = 0): Int = long(o, key, def.toLong()).toInt()

    fun bool(o: org.json.JSONObject?, key: String, def: Boolean = false): Boolean {
        if (o == null || !o.has(key)) return def
        val v = o.opt(key)
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "true" || v == "1"
            else -> def
        }
    }

    /** 平台的时间戳有时是 "1700102194000"、有时是 1.700102194E12，统一处理 */
    private fun numToString(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            d.toLong().toString()
        } else {
            n.toString()
        }
    }

    /** 把 JSONArray 转成 List<String>，元素是字符串或数字都能吃 */
    fun strList(a: org.json.JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            when (v) {
                null, org.json.JSONObject.NULL -> {}
                is String -> if (v.isNotEmpty()) out.add(v)
                else -> out.add(v.toString())
            }
        }
        return out
    }
}

/**
 * 音质档位换算。四个平台各有自己的档位命名，统一成 MusicSource 里那五个：
 * standard / high / exhigh / lossless / hires。
 */
object Quality {

    val ORDER = listOf("hires", "lossless", "exhigh", "high", "standard")

    /** 用户偏好的小写归一：前端可能传 "Hi-Res"、"flac"、"320k" 这种别名 */
    fun normalize(q: String?): String = when (q?.trim()?.lowercase()) {
        null, "" -> "lossless"
        "hires", "hi-res", "hi_res", "hires_lossless", "flac24", "24bit" -> "hires"
        "lossless", "flac", "sq" -> "lossless"
        "exhigh", "320", "320k", "hq" -> "exhigh"
        "high", "192", "192k" -> "high"
        "standard", "128", "128k", "std" -> "standard"
        else -> "lossless"
    }

    /** 从 wanted 开始往下降的档位阶梯（含 wanted 本身），用于 fallback */
    fun ladder(wanted: String): List<String> {
        val w = normalize(wanted)
        val idx = ORDER.indexOf(w)
        return if (idx < 0) ORDER else ORDER.drop(idx)
    }

    fun rank(q: String): Int = ORDER.indexOf(normalize(q)).let { if (it < 0) ORDER.size else it }

    /** a 是否比 b 低（数字越大越低） */
    fun lowerThan(a: String, b: String): Boolean = rank(a) > rank(b)

    /** 按扩展名猜一下文件真实音质，有些接口给的 q 字段是音质码，扩展名更可靠 */
    fun fromAudioType(extName: String, bitRate: Long): String = when (extName.lowercase()) {
        "flac", "ape", "wav", "dsf", "dff" -> if (bitRate >= 900_000) "hires" else "lossless"
        "mp3" -> when {
            bitRate >= 300_000 -> "exhigh"
            bitRate >= 180_000 -> "high"
            else -> "standard"
        }
        "m4a", "mp4", "aac" -> when {
            bitRate >= 300_000 -> "exhigh"
            bitRate >= 180_000 -> "high"
            else -> "standard"
        }
        else -> if (bitRate >= 300_000) "exhigh" else "standard"
    }
}

/** 一些小到不值得单开文件的东西 */
object Tiny {

    const val UA_QQ_ANDROID = "QQMusic 14090508(android 12)"
    const val UA_QQ_MSIE = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)"
    const val UA_NETEASE_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** form 编码，参数按传入顺序拼，值统一 encode */
    fun form(vararg kv: Pair<String, String>): String =
        kv.filter { it.first.isNotEmpty() }
            .joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun nowMs(): Long = System.currentTimeMillis()

    /** 多歌手统一用 " / " 连接（规格给的是这个分隔符） */
    fun joinArtists(names: List<String>): String = names.filter { it.isNotBlank() }.joinToString(" / ")

    /** 网易/华为的封面 http 换 https，App 虽然开了 cleartext，但 https 能走 CDN 压缩 */
    fun https(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            url.startsWith("//") -> "https:" + url
            else -> url
        }
    }

    /** LRC 常见的 BOM 头，华为的 LRC 就带，不剥掉第一行时间轴会解析错 */
    fun stripBom(s: String?): String = s?.removePrefix("\uFEFF") ?: ""

    /** 文本看起来像 LRC（含 [mm:ss.xx] 时间轴） */
    fun looksLikeLrc(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        return Regex("\\[\\d{1,3}:\\d{1,2}[\\.:]\\d{1,3}\\]").containsMatchIn(s)
    }
}
