package com.j4.music.playback

import androidx.media3.common.Player

/**
 * 循环模式的三种形态。
 *
 * 用户要的是「歌单循环 / 随机播放 / 单曲循环」三态。Media3 原生有
 * REPEAT_MODE_OFF/ALL/ONE 与 shuffle 开关两个维度，这里把二者压成一个三态，
 * 免得前端要处理 3×2 的组合，也免得暂停在最后一首时行为不一致。
 */
enum class LoopMode(val key: String) {
    /** 列表循环：播完最后一首回到第一首 */
    LIST("list"),

    /** 单曲循环：当前曲目无限重播 */
    ONE("one"),

    /** 随机播放：打乱队列顺序（shuffle 开启，同时开 repeat all 防止播完就停） */
    SHUFFLE("shuffle");

    fun applyTo(player: Player) {
        when (this) {
            LIST -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            ONE -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            SHUFFLE -> {
                player.shuffleModeEnabled = true
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
    }

    fun next(): LoopMode = when (this) {
        LIST -> SHUFFLE
        SHUFFLE -> ONE
        ONE -> LIST
    }

    companion object {
        fun of(key: String?): LoopMode =
            entries.firstOrNull { it.key == key } ?: LIST

        /** 从播放器当前状态反推（比如系统媒体控制改了 shuffle） */
        fun from(player: Player): LoopMode = when {
            player.shuffleModeEnabled -> SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> ONE
            else -> LIST
        }
    }
}
