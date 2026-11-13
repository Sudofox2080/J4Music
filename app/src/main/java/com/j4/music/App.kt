package com.j4.music

import android.app.Application
import com.j4.music.data.AppDatabase
import com.j4.music.source.SourceEnv
import com.j4.music.source.SourceRegistry

/**
 * 全局入口。
 *
 * 初始化顺序有讲究：
 *  1) 先建 db —— 后面所有模块（cookie、播放状态、歌单）都依赖它；
 *  2) 再 install SourceEnv —— 它要把 App.db 与 CookieJar 接上，音源对象内部
 *     是「用到时才取」的，所以两者顺序不能颠倒，否则第一次登录会写进内存实现；
 *  3) 最后注册音源 —— 音源的构造只依赖 SourceEnv.current，此时已经就绪。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase(this)
        SourceEnv.install(this, db)
        SourceRegistry.registerDefaults()
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var db: AppDatabase
            private set
    }
}
