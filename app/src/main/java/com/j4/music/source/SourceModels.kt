package com.j4.music.source

/**
 * 二维码登录的共享类型。
 *
 * 为什么放在 source 包而不是 net 包：登录是「音源的能力」，不同平台的状态机完全一样
 * （出码 → 待扫 → 已扫 → 成功/过期），前端只想要一套状态，不想认四套。
 * 放在这里，MusicSource 的子类型直接暴露 loginQrCreate/loginQrPoll 即可。
 */

/**
 * 一条待扫码的二维码。
 *
 * @param url  二维码**内容字符串**，直接丢给二维码库渲染（网易是 music.163.com 的登录链接）
 * @param key  轮询时回传的票据：网易是 unikey，QQ 是 qrsig
 * @param imageUrl 少数平台直接给一张码图（QQ 的 ptqrshow 返回 PNG），此时 url 可能为空
 */
data class QrInfo(
    val url: String,
    val key: String,
    val imageUrl: String? = null,
    /** 建议轮询间隔（毫秒），各平台风控不同，由音源自己给 */
    val pollIntervalMs: Long = 2000L,
    /** 过期时间戳；0 表示未知 */
    val expireAt: Long = 0L
)

/** 扫码登录状态机。Success 里直接带可以塞进 CookieJar 的整串 cookie。 */
sealed class QrState {
    /** 等待扫码（801 / 66 / 未扫） */
    object Waiting : QrState() {
        override fun toString() = "Waiting"
    }

    /** 已扫码待确认（802 / 67），nickname/avatar 可选，用于「xxx 已扫码」提示 */
    data class Scanned(val nickname: String = "", val avatar: String = "") : QrState()

    /**
     * 登录成功。
     * @param cookie 形如 "MUSIC_U=xxx; __csrf=yyy" 的整串，调用方直接 putCookieString 即可
     */
    data class Success(val cookie: String) : QrState()

    /** 二维码过期（800 / 65），需要重新 loginQrCreate */
    object Expired : QrState() {
        override fun toString() = "Expired"
    }

    data class Error(val msg: String) : QrState()

    val isTerminal: Boolean
        get() = this is Success || this is Expired || this is Error
}
