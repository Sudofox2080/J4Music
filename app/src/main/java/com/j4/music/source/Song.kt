package com.j4.music.source

import org.json.JSONObject

/**
 * 统一歌曲模型。所有平台的搜索结果都要先落到这个结构再交给前端，
 * 前端因此只需要认识一套字段。
 *
 * 关键设计：
 *  - id 统一写成 "source:平台内id"，前端拿它做列表 key；
 *  - extra 存各平台原始 id（song_id / songmid / hash / content_id 等），
 *    取播放地址时必须靠它，因为同一个歌名在同一平台可能有几十个版本；
 *  - localPath 非空表示本地已有文件，播放器会直接读文件，完全不联网。
 */
data class Song(
    val id: String,
    val source: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val vip: Boolean = false,
    val localPath: String? = null,
    val quality: String = "standard",
    val extra: Map<String, String> = emptyMap()
) {
    /** 是否可以直接离线播放 */
    val playableOffline: Boolean get() = !localPath.isNullOrEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("source", source)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("coverUrl", coverUrl ?: JSONObject.NULL)
        put("vip", vip)
        put("localPath", localPath ?: JSONObject.NULL)
        put("quality", quality)
        val e = JSONObject()
        extra.forEach { (k, v) -> e.put(k, v) }
        put("extra", e)
    }

    companion object {
        fun fromJson(o: JSONObject): Song {
            val extraObj = o.optJSONObject("extra")
            val extra = mutableMapOf<String, String>()
            extraObj?.keys()?.forEach { k -> extra[k] = extraObj.optString(k) }
            return Song(
                id = o.optString("id"),
                source = o.optString("source"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                durationMs = o.optLong("durationMs"),
                coverUrl = nullIfBlank(o.optString("coverUrl")),
                vip = o.optBoolean("vip"),
                localPath = nullIfBlank(o.optString("localPath")),
                quality = o.optString("quality", "standard"),
                extra = extra
            )
        }

        /**
         * 把 JSON 里的空值统一成 Kotlin 的 null。
         *
         * 为什么不能只用 ifEmpty：前端（以及 JS 侧 JSON.stringify）经常把
         * null 写成一个四字符的字符串 "null"，org.json 的 optString 遇到
         * JSONObject.NULL 也会返回字面量 "null"。一旦这个值被当成
         * localPath，MediaItemFactory 就会误判成"本地文件优先"，
         * 最后 FileDataSource 去 open 一个叫 null 的路径，报
         * FileNotFoundException: null —— 和真实原因（没有本地文件）相差十万八千里。
         */
        private fun nullIfBlank(s: String?): String? {
            if (s.isNullOrEmpty()) return null
            val t = s.trim()
            if (t.isEmpty() || t.equals("null", ignoreCase = true) ||
                t.equals("undefined", ignoreCase = true)
            ) return null
            return t
        }
    }
}
