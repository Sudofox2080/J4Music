package com.j4.music.repo

import com.j4.music.net.Http
import com.j4.music.source.OpenApiConfig
import com.j4.music.source.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云端歌单：把歌单整份 JSON 推到网关的 /api/playlists 上，换设备/重装也能取回来。
 *
 * 为什么不让浏览器存：需求要的是「一处登录、多端可用」——歌单跟着服务器走，
 * 而不是跟着某台手机的 WebView localStorage 走。
 *
 * 服务端只是存文件（/opt/j4-gateway/playlists/<id>.json），不做合并，
 * 冲突策略很简单：谁最后点「上传」，云上就是谁的样子。
 */
object CloudPlaylists {

    private const val TAG = "CloudPlaylists"
    private const val MAPPING_PREFIX = "cloud.map."

    fun enabled(): Boolean = OpenApiConfig.isConfigured()

    private fun client() = Http.raw.newBuilder()
        .callTimeout(40, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String = OpenApiConfig.baseUrl().trimEnd('/') + path

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject? =
        runCatching {
            val builder = okhttp3.Request.Builder()
                .url(url(path))
                .header("X-J4-Token", OpenApiConfig.token())
                .header("Accept", "application/json")
            when (method) {
                "PUT" -> builder.put(
                    (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            client().newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    com.j4.music.source.SourceLog.w(TAG, "$method $path -> HTTP ${resp.code} ${text.take(160)}")
                    null
                } else {
                    runCatching { JSONObject(text) }.getOrNull()
                }
            }
        }.onFailure {
            com.j4.music.source.SourceLog.w(TAG, "$method $path 失败：${it.message}")
        }.getOrNull()

    /** 把本地歌单整份上传（覆盖云端同名 id） */
    suspend fun push(repo: PlaylistRepository, id: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext null
        val detail = repo.get(id) ?: return@withContext null
        val songs = repo.songs(id)
        val body = JSONObject().apply {
            put("id", id)
            put("name", detail.name)
            put("cover", detail.cover ?: "")
            put("kind", "cloud")
            put("source", detail.source ?: "")
            put("songs", JSONArray().also { ja -> for (s in songs) ja.put(s.toJson()) })
        }
        call("PUT", "/api/playlists", body)?.also {
            com.j4.music.source.SourceLog.d(TAG, "已上传「${detail.name}」${songs.size} 首")
            rememberMap(id, id)
        }
    }

    /** 上传但失败也不打扰用户（新建/加歌之后自动跑） */
    suspend fun pushQuietly(repo: PlaylistRepository, id: String) {
        runCatching { push(repo, id) }
    }

    suspend fun remoteList(): List<JSONObject> = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext emptyList()
        val d = call("GET", "/api/playlists") ?: return@withContext emptyList()
        val arr = d.optJSONArray("playlists") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * 从云端取一张歌单写回本地。
     * 本地已经映射过就整份替换（保留本地文件路径这类私人信息由 PlaylistRepository 处理不了，
     * 所以这里直接 replaceSongs，宁可丢本地位图信息也不要把歌单搞成两半）。
     */
    suspend fun pull(repo: PlaylistRepository, remoteId: String): String? = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext null
        val d = call("GET", "/api/playlists/$remoteId") ?: return@withContext null
        val pl = d.optJSONObject("playlist") ?: return@withContext null
        val name = pl.optString("name").ifEmpty { "云端歌单" }
        val songs = parseSongs(pl.optJSONArray("songs"))
        val localId = mappedLocal(remoteId)?.takeIf { repo.get(it) != null }
        if (localId != null) {
            repo.rename(localId, name)
            repo.replaceSongs(localId, songs)
            rememberMap(remoteId, localId)
            localId
        } else {
            val newId = repo.create(name, PlaylistRepository.KIND_CUSTOM, null, null, pl.optString("cover").ifEmpty { null }, songs)
            rememberMap(remoteId, newId)
            newId
        }
    }

    /** 一次把云上所有歌单同步下来（已有的就地更新，没有的新建） */
    suspend fun pullAll(repo: PlaylistRepository): JSONObject = withContext(Dispatchers.IO) {
        val list = remoteList()
        var added = 0
        var updated = 0
        var failed = 0
        for (item in list) {
            val rid = item.optString("id")
            if (rid.isEmpty()) { failed++; continue }
            val had = mappedLocal(rid)
            val ok = runCatching { pull(repo, rid) }.getOrNull()
            if (ok == null) failed++ else if (had == null) added++ else updated++
        }
        JSONObject().apply {
            put("total", list.size)
            put("added", added)
            put("updated", updated)
            put("failed", failed)
        }
    }

    suspend fun remove(remoteId: String): Boolean = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext false
        call("DELETE", "/api/playlists/$remoteId") != null
    }

    fun mappedLocal(remoteId: String): String? =
        com.j4.music.App.db.get(MAPPING_PREFIX + remoteId)?.takeIf { it.isNotEmpty() }

    private fun rememberMap(remoteId: String, localId: String) {
        runCatching { com.j4.music.App.db.put(MAPPING_PREFIX + remoteId, localId) }
    }

    private fun parseSongs(arr: JSONArray?): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { out.add(Song.fromJson(o)) }
        }
        return out
    }
}
