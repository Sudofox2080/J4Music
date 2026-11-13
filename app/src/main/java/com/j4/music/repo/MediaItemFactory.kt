package com.j4.music.repo

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import com.j4.music.crypto.CipherFactory
import com.j4.music.source.Song
import com.j4.music.source.PlayUrl
import com.j4.music.playback.SongMapper
import java.io.File

/**
 * MediaItem 工厂：把「一首歌」变成「可直接播的 MediaItem」。
 *
 * 关键分支：
 *  1) 本地文件 + 加密后缀（.ncm/.mflac/...）→ 走解密 DataSource，边读边解，可 seek；
 *  2) 本地文件 + 明文 → 直接用 file URI；
 *  3) 网络地址 → 用带 Referer/UA 的 OkHttp DataSource（QQ 对 Referer 有要求）；
 *  4) 拿不到地址 → 抛异常，由调用方提示版权受限。
 */
class MediaItemFactory(
    private val context: Context,
    private val resolver: UrlResolver,
    /**
     * 跨源兜底回调：用户点的歌在原平台要会员、由别的平台出了地址时调用。
     * 放在这里而不是 UrlResolver 里，是因为只有组装 MediaItem 这一层
     * 同时握着「原始 song」和「最终 PlayUrl」，能说清到底换了什么。
     */
    private val onSourceSwitched: ((Song, PlayUrl) -> Unit)? = null
) {

    class ResolveError(val song: Song, message: String) : Exception(message)

    fun build(song: Song): MediaItem {
        // 1) 本地优先
        val local = song.localPath
        if (!local.isNullOrBlank()) {
            val f = localToFile(local)
            if (f != null && f.exists() && f.length() > 0) {
                return if (isEncrypted(f)) {
                    // 加密文件：起一个自定义 dataSource 的 media source 不方便，
                    // 这里用虚拟 URI scheme，由 ExoPlayer 的工厂按 scheme 分发
                    SongMapper.toMediaItem(
                        song,
                        url = "$SCHEME_ENCRYPTED://${Uri.encode(f.absolutePath)}",
                        mimeHint = mimeForCipher(f),
                    )
                } else {
                    SongMapper.toMediaItem(song, url = f.toURI().toString(), mimeHint = SongMapper.guessMime(null, f.name))
                }
            }
        }

        // 2) 音源解析
        val play = runCatching { resolver.resolve(song) }.getOrNull()
            ?: throw ResolveError(song, "暂无可用音源：${song.title}")

        // 服务端换了源（A 平台要会员 → 用 B 平台的免费版本）：界面必须说一声，
        // 否则用户听到的是别的版本却以为是原版。只在真正换了平台时回调。
        if (play.via.isNotEmpty()) {
            runCatching { onSourceSwitched?.invoke(song, play) }
        }

        // 音源也可能给出本地地址（演示音源把 assets 里的 wav 复制到 filesDir 后返回
        // file:// 绝对路径）。这类地址必须走 FileDataSource，塞进 OkHttpDataSource 会
        // 直接报 Source error —— OkHttp 不认 file scheme。
        val resolvedLocal = localToFile(play.url)
        if (resolvedLocal != null && resolvedLocal.exists() && resolvedLocal.length() > 0) {
            return if (isEncrypted(resolvedLocal)) {
                SongMapper.toMediaItem(
                    song,
                    url = "$SCHEME_ENCRYPTED://${Uri.encode(resolvedLocal.absolutePath)}",
                    mimeHint = mimeForCipher(resolvedLocal),
                )
            } else {
                SongMapper.toMediaItem(
                    song,
                    url = resolvedLocal.toURI().toString(),
                    mimeHint = SongMapper.guessMime(null, resolvedLocal.name),
                )
            }
        }

        val mime = SongMapper.guessMime(play.url, null) ?: mimeFromQuality(play.quality)
        // 把「实际拿到的音质」写回这首歌：界面据此显示 SQ/HQ 徽标，
        // 用户才看得见「会员到底买到了什么」——否则一律显示 standard，
        // 开了会员和没开在屏幕上完全一样。
        val played = song.copy(quality = play.quality)
        return SongMapper.toMediaItem(played, url = play.url, mimeHint = mime, headers = play.headers)
    }

    private fun localToFile(path: String): File? {
        return try {
            when {
                path.startsWith("file://") -> Uri.parse(path).path?.let { File(it) }
                path.startsWith("content://") -> null // content URI 由 DataSource 直接处理
                // 任何带 scheme 的（http/https/j4enc/blob/...）都不是本地路径
                path.contains("://") -> null
                path.isBlank() -> null
                else -> File(path)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isEncrypted(f: File): Boolean = runCatching { CipherFactory.isEncrypted(f) }.getOrDefault(false)

    private fun mimeForCipher(f: File): String {
        val ext = runCatching { CipherFactory.outputExtension(f) }.getOrDefault("")
        return SongMapper.guessMime(null, "x.$ext") ?: "audio/mpeg"
    }

    private fun mimeFromQuality(quality: String): String? = when (quality) {
        "lossless", "hires" -> "audio/flac"
        "exhigh", "high", "standard" -> null // 交给 Media3 自己嗅探
        else -> null
    }

    companion object {
        /** 加密文件的虚拟 scheme，ExoPlayer 侧按这个分发到 DecryptingDataSource */
        const val SCHEME_ENCRYPTED = "j4enc"
    }
}
