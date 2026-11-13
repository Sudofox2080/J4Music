package com.j4.music

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher

/**
 * 系统文件选择器的中转站。
 *
 * 为什么不能直接在网页里用 `<input type="file">`：WebView 里的 `File` 对象没有 `path`
 * 字段（永远 undefined），只有 `name`。于是前端把 `f.path || f.name` 传给原生时，
 * 拿到的只是一个文件名 —— 转译/导入这类「必须真读文件」的功能就永远找不到东西。
 * 所以选择动作由原生发起（SAF），拿到的 `content://` Uri 直接交给下游处理。
 */
object FilePick {

    private var launcher: ActivityResultLauncher<Array<String>>? = null

    @Volatile
    private var pending: ((List<Uri>) -> Unit)? = null

    /** 由 MainActivity 在 onCreate 里注册（必须在 STARTED 之前完成注册） */
    fun bind(l: ActivityResultLauncher<Array<String>>) {
        launcher = l
    }

    /**
     * 拉起选择器。返回 false 表示宿主还没准备好（例如 Activity 还没创建），
     * 调用方据此给前端一个明确的报错，而不是让它一直等。
     */
    @Synchronized
    fun request(mime: Array<String>, cb: (List<Uri>) -> Unit): Boolean {
        val l = launcher ?: return false
        pending = cb
        return runCatching { l.launch(mime) }.isSuccess
    }

    /** 选择结果回填；用户直接返回（取消）时给空列表 */
    @Synchronized
    fun deliver(uris: List<Uri>) {
        val cb = pending
        pending = null
        cb?.invoke(uris)
    }
}
