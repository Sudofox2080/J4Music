package com.j4.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.j4.music.source.Song

/**
 * Song ↔ MediaItem 的双向映射。
 *
 * 为什么把整首歌塞进 MediaItem 而不是只放 id：Media3 的 MediaSession 要往系统
 * 通知栏、蓝牙、车机送元数据，如果只剩 id，播放服务每次切换都要回查数据库，
 * 在无网/后台受限时容易拿不到封面和标题。这里把可序列化的字段全部写进 Bundle。
 *
 * URL 不放进来：播放地址有时效（QQ vkey 几分钟就过期），
 * 每次真正开播时由 resolver 现取，这就是 extra 存在的意义。
 */
object SongMapper {

    private const val KEY_JSON = "j4.song.json"

    fun toMediaItem(song: Song, url: String? = null, mimeHint: String? = null, headers: Map<String, String> = emptyMap()): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.coverUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(Bundle().apply { putString(KEY_JSON, song.toJson().toString()) })
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(song.id)
            .setMediaMetadata(meta)

        if (!url.isNullOrEmpty()) {
            builder.setUri(url)
            if (!mimeHint.isNullOrEmpty()) builder.setMimeType(mimeHint)
            if (headers.isNotEmpty()) {
                val bundle = Bundle()
                headers.forEach { (k, v) -> bundle.putString(k, v) }
                // Media3 的 http 头约定：Bundle 键名可以是 "User-Agent" 这种裸键
                builder.setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setExtras(bundle)
                        .build()
                )
            }
        }
        return builder.build()
    }

    /** 从 MediaItem 还原 Song；拿不到就返回 null，调用方应跳过该曲目 */
    fun fromMediaItem(item: MediaItem): Song? {
        val json = item.mediaMetadata.extras?.getString(KEY_JSON) ?: return null
        return runCatching { Song.fromJson(org.json.JSONObject(json)) }.getOrNull()
    }

    /** 根据 URL 或本地文件名猜 MIME，Media3 在 progressive 播放时对 mime 有要求（尤其 ogg） */
    fun guessMime(url: String?, localPath: String? = null): String? {
        val s = (localPath ?: url ?: return null).lowercase()
        val path = s.substringBefore('?')
        return when {
            path.endsWith(".flac") -> "audio/flac"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac") -> "audio/mp4"
            path.endsWith(".ogg") || path.endsWith(".oga") -> "audio/ogg"
            path.endsWith(".opus") -> "audio/opus"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".mka") -> "audio/x-matroska"
            else -> null
        }
    }
}
