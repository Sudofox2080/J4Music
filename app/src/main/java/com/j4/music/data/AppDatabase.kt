package com.j4.music.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简本地存储：一张 KV 表和一张歌单表。
 *
 * 为什么不引 Room：这个应用的数据结构几乎全是 JSON（歌单就是歌曲数组），
 * 用 Room 反而要再写一堆 Entity/DAO/Converter；这里直接 KV + JSON 更省。
 *
 * 所有方法都是同步 SQLite 访问，调用方必须放在 IO 线程（Dispatchers.IO）。
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, "j4music.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL(
            """CREATE TABLE playlist (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                cover TEXT,
                kind TEXT NOT NULL,          -- custom / local / remote
                source TEXT,                  -- 远端歌单来源平台
                remote_id TEXT,               -- 远端歌单 id
                songs TEXT NOT NULL,          -- JSON 数组
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, song TEXT NOT NULL, played_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前只有 v1，预留升级路径
    }

    // ---------------- KV ----------------

    fun get(k: String, def: String? = null): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else def
        }

    fun put(k: String, v: String) {
        val cv = ContentValues().apply {
            put("k", k)
            put("v", v)
        }
        writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------- 歌单 ----------------

    fun listPlaylists(): List<Playlist> =
        readableDatabase.rawQuery(
            "SELECT id,name,cover,kind,source,remote_id,songs,created_at,updated_at FROM playlist ORDER BY updated_at DESC",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Playlist.fromCursor(c))
            }
        }

    fun getPlaylist(id: String): Playlist? =
        readableDatabase.rawQuery(
            "SELECT id,name,cover,kind,source,remote_id,songs,created_at,updated_at FROM playlist WHERE id=?",
            arrayOf(id)
        ).use { c -> if (c.moveToFirst()) Playlist.fromCursor(c) else null }

    fun upsertPlaylist(p: Playlist) {
        val cv = ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("cover", p.cover)
            put("kind", p.kind)
            put("source", p.source)
            put("remote_id", p.remoteId)
            put("songs", p.songsJson)
            put("created_at", p.createdAt)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("playlist", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePlaylist(id: String) {
        writableDatabase.delete("playlist", "id=?", arrayOf(id))
    }

    fun addHistory(songId: String) {
        writableDatabase.insert("history", null, ContentValues().apply {
            put("song", songId)
            put("played_at", System.currentTimeMillis())
        })
    }
}

data class Playlist(
    val id: String,
    val name: String,
    val cover: String?,
    val kind: String,
    val source: String?,
    val remoteId: String?,
    val songsJson: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        fun fromCursor(c: android.database.Cursor) = Playlist(
            id = c.getString(0),
            name = c.getString(1),
            cover = c.getString(2),
            kind = c.getString(3),
            source = c.getString(4),
            remoteId = c.getString(5),
            songsJson = c.getString(6),
            createdAt = c.getLong(7),
            updatedAt = c.getLong(8)
        )

        fun fromJson(o: JSONObject) = Playlist(
            id = o.optString("id"),
            name = o.optString("name"),
            cover = o.optString("cover").ifEmpty { null },
            kind = o.optString("kind", "custom"),
            source = o.optString("source").ifEmpty { null },
            remoteId = o.optString("remoteId").ifEmpty { null },
            songsJson = o.optJSONArray("songs")?.toString() ?: JSONArray().toString(),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("cover", cover ?: JSONObject.NULL)
        put("kind", kind)
        put("source", source ?: JSONObject.NULL)
        put("remoteId", remoteId ?: JSONObject.NULL)
        put("songs", JSONArray(songsJson))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }
}
