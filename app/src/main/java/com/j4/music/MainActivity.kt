package com.j4.music

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.j4.music.bridge.JsBridge
import com.j4.music.playback.PlayerService
import java.io.ByteArrayInputStream

/**
 * 唯一的 Activity：一个全屏 WebView 承载界面。
 *
 * 为什么用 WebView 而不是原生控件：用户要求的就是 WebView；而且播放器的
 * 动画（歌词流光、封面漂移、列表手势）用 CSS 写成本只有原生的十分之一。
 *
 * 几个容易翻车的地方：
 *  - file:// 加载 assets 时，XHR/fetch 会被 CORS 拦，必须把 assets 走
 *    WebViewAssetLoader 或自己实现 shouldInterceptRequest；这里用手写拦截，
 *    少一个依赖，行为也更可控。
 *  - JS 接口必须挂在 "J4Native" 这个名字上，前端 bridge.js 按这个名字找。
 *  - 状态栏透明 + 深色图标要与前端主题一致，所以设成 enableEdgeToEdge 手写。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: JsBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 文件选择器必须在 STARTED 之前注册。挂在 MainActivity 上而不是各个调用点，
        // 是因为 Activity 重建（转屏/深色模式切换）时也要能继续用同一个 launcher。
        val picker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> FilePick.deliver(uris ?: emptyList()) }
        FilePick.bind(picker)

        // 状态栏/导航栏透明与系统栏外观必须放在 setContentView 之后。
        // 原因：window.insetsController 内部会去拿 DecorView，而 DecorView 是在
        // setContentView 里才 attach 到 PhoneWindow 的。提前访问会拿到 null，
        // 然后 getWindowInsetsController() 抛 NPE —— 表现为一打开就闪退，
        // 且栈里只有一行 MainActivity.onCreate，看着像无关的窗口问题。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        web = WebView(this)
        setContentView(web)
        applySystemBarAppearance()

        web.setBackgroundColor(Color.parseColor("#07070C"))

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadsImagesAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = true
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        bridge = JsBridge(
            context = this,
            emit = { json -> runOnUiThread { web.evaluateJavascript(json, null) } },
            toast = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        )
        bridge.host = object : JsBridge.MainActivityHost {
            override fun runJs(js: String) {
                runOnUiThread { runCatching { web.evaluateJavascript(js, null) } }
            }
        }
        web.addJavascriptInterface(bridge, "J4Native")

        // WebView 全屏时 Chromium 不上报 env(safe-area-inset-*)，前端拿到的永远是 0，
        // 结果标题被状态栏压住。这里把真实的系统栏高度以 px 注进 CSS 变量。
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    v.windowInsetsController?.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                }
            }
            pushSafeInsets(insets)
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        // 首帧可能还没有 insets 回调，先用资源里的值兜底
        web.postDelayed({ injectResourceInsets() }, 300)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return serveAsset(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                android.util.Log.e("J4Web", "resource error ${request?.url} ${error?.errorCode} ${error?.description}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面一加载完就把系统栏高度推给 CSS 变量：insets 监听器可能在
                // 文档还是 about:blank 时就触发过了，那时写进去的变量随旧文档一起丢了。
                // 这里直接读 ViewCompat.getRootWindowInsets 再写一次，保证首帧就对。
                val wi = ViewCompat.getRootWindowInsets(window.decorView)
                if (wi != null) {
                    pushSafeInsets(wi)
                } else {
                    injectResourceInsets()
                }
                // 页面加载完先探一下 JS 是否活着，黑屏时能直接在 logcat 看出哪一层挂了
                view?.evaluateJavascript(
                    "(function(){try{return JSON.stringify({ok:1,app:!!window.J4App,bridge:!!window.J4Bridge,router:!!window.J4Router,body:document.body.innerHTML.length})}catch(e){return 'probe:'+e.message}})()"
                ) { r -> android.util.Log.i("J4Web", "page ready $url -> $r") }
                // 黑屏诊断：把 #app 下的结构与关键计算样式打出来
                view?.postDelayed({
                    view.evaluateJavascript(
                        "(function(){try{var r={native:typeof window.J4Native,mock:!!(window.J4Bridge&&window.J4Bridge.mock)};" +
                            "try{r.ping=window.J4Native?String(window.J4Native.call('ping','{}')).slice(0,300):'no-native';}catch(e){r.ping='err:'+e.message;}" +
                            "r.sources='';try{r.sources=String(window.J4Native.call('sourceList','{}')).slice(0,60);}catch(e2){r.sources='e:'+e2.message;}" +
                            "var de=document.documentElement,st=getComputedStyle(de);" +
                            "r.safeT=st.getPropertyValue('--safe-t');" +
                            "r.inline=de.style.getPropertyValue('--safe-t');" +
                            "var g=document.querySelector('.greeting');" +
                            "r.greetingTop=g?g.getBoundingClientRect().top:null;" +
                            "var hd=document.querySelector('.page__header')||document.querySelector('.greeting')?.parentElement;" +
                            "r.hdrPad=hd?getComputedStyle(hd).paddingTop:null;" +
                            "return JSON.stringify(r);}catch(e){return 'probe:'+e.message}})()"
                    ) { r2 -> android.util.Log.i("J4Web", "bridge -> $r2") }
                }, 1500)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                // 应用自身的资源（含 assets/web/legal 下的协议页）留在 WebView 里加载。
                if (u.scheme == "file" || u.scheme == "about" || u.scheme == "data") return false
                // 站外链接交给系统浏览器。只有真的能跳转成功才拦截 ——
                // 以前不管成功与否都 return true，设备上没有浏览器或 scheme 没人处理时，
                // 结果就是「点了毫无反应」，用户只能以为按钮坏了。
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
                if (!opened) {
                    Toast.makeText(this@MainActivity, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show()
                }
                return opened
            }
        }

        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val m = msg ?: return true
                android.util.Log.i("J4Web", "[${m.messageLevel()}] ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                return true
            }
        }

        web.loadUrl("file:///android_asset/web/index.html")

        startPlayerService()
    }

    /** 把 assets/web 下的文件按 MIME 直接吐给 WebView，绕开 file:// 的同源限制 */
    private fun serveAsset(url: String): WebResourceResponse? {
        val prefix = "file:///android_asset/"
        if (!url.startsWith(prefix)) {
            val local = "https://j4.local/"
            if (url.startsWith(local)) {
                return assetResponse(url.removePrefix(local))
            }
            return null
        }
        return assetResponse(url.removePrefix(prefix))
    }

    private fun assetResponse(path: String): WebResourceResponse? {
        val clean = path.substringBefore('?').substringBefore('#').ifEmpty { "web/index.html" }
        val mime = when {
            clean.endsWith(".html") -> "text/html"
            clean.endsWith(".css") -> "text/css"
            clean.endsWith(".js") -> "application/javascript"
            clean.endsWith(".json") -> "application/json"
            clean.endsWith(".svg") -> "image/svg+xml"
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".webp") -> "image/webp"
            clean.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
        val bytes = runCatching { assets.open(clean).use { it.readBytes() } }.getOrNull() ?: return null
        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
        }
    }

    /**
     * 把系统栏 inset 写进文档的 CSS 变量。
     * 用 px 而不是 env()，是因为 WebView 全屏时 env() 恒为 0。
     */
    private fun pushSafeInsets(insets: androidx.core.view.WindowInsetsCompat) {
        val bars = insets.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        )
        val d = resources.displayMetrics.density
        applyInsetCss(
            (bars.top / d).toInt(),
            (bars.bottom / d).toInt(),
            (bars.left / d).toInt(),
            (bars.right / d).toInt()
        )
    }

    private fun applyInsetCss(topDp: Int, bottomDp: Int, leftDp: Int, rightDp: Int) {
        val js = "(function(){var s=document.documentElement.style;" +
            "s.setProperty('--safe-t','${topDp}px');" +
            "s.setProperty('--safe-b','${bottomDp}px');" +
            "s.setProperty('--safe-l','${leftDp}px');" +
            "s.setProperty('--safe-r','${rightDp}px');})()"
        runCatching { web.evaluateJavascript(js, null) }
    }

    /**
     * insets 拿不到时的兜底：直接读系统资源里的状态栏/导航栏高度。
     * px 已在资源里，不需要再乘 density。
     */
    private fun injectResourceInsets() {
        val res = resources
        val top = res.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { res.getDimensionPixelSize(it) } ?: 0
        val nav = res.getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { res.getDimensionPixelSize(it) } ?: 0
        val d = resources.displayMetrics.density
        applyInsetCss((top / d).toInt(), (nav / d).toInt(), 0, 0)
    }

    private fun startPlayerService() {
        val intent = Intent(this, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    /**
     * 让状态栏/导航栏的图标与 App 的深色主题一致。
     *
     * 必须在 DecorView 已 attach 之后调用；即使那样也可能拿到 null（部分 ROM 的
     * 启动窗口阶段），所以这里用 ViewCompat 的 insets 监听兜底：DecorView 真正
     * 拿到 WindowInsets 的那一刻再设一次，两次都拿到 null 就放弃，绝不抛异常。
     * 闪退优先于不一致 —— 系统栏图标颜色再难看，也比打不开强。
     */
    private fun applySystemBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
        // 注意：真正的 insets 监听器在 onCreate 里设（要把高度推给 CSS 变量）。
        // 这里只补一次 requestApplyInsets 触发那个监听器，不要再设第二个监听器 ——
        // 后设的会覆盖先设的，两个都留着会导致回调丢失。
        ViewCompat.requestApplyInsets(window.decorView)
    }

    override fun onStart() {
        super.onStart()
        // 服务可能慢一拍起来，轮询挂上回调（最多等 3 秒）
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val bind = object : Runnable {
            override fun run() {
                val svc = PlayerService.instance
                if (svc != null) bridge.attachPlayer(svc)
                else if (tries++ < 15) handler.postDelayed(this, 200)
            }
        }
        handler.post(bind)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 别的 App 分享 "歌名 - 歌手" 到本 App 时，直接跳搜索 */
    private fun handleShareIntent(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString ?: return
        if (text.isBlank()) return
        runOnUiThread {
            web.evaluateJavascript("window.__J4Share && window.__J4Share(${quote(text)})", null)
        }
    }

    override fun onBackPressed() {
        // 先让前端处理（返回上一页 / 收起面板），前端不认再说
        dispatchBackToWeb()
    }

    /**
     * 返回键统一入口。
     *
     * 两个坑，都踩过：
     *  1. 不能用 super.onBackPressed() —— AppCompatActivity 会绕 BackPressedDispatcher，
     *     被库里注册的 enabled 回调吞掉后就永远不 finish。
     *  2. **不能用 evaluateJavascript 的异步回调来决定退不退**。onBackPressed 返回时如果
     *     还没调用 finish()，系统就按「未处理」继续往下走，异步回调回来时 Activity 已经没了。
     *     所以这里用 addJavascriptInterface 暴露的同步通道取结果。
     */
    private fun dispatchBackToWeb() {
        if (bridge.consumeBack()) {
            // 前端认领了这次返回：让它自己退栈/关面板，异步发即可，不需要等结果。
            web.evaluateJavascript("window.J4 && window.J4.onBack && window.J4.onBack()", null)
            return
        }
        handleBackFallback()
    }

    /** 前端不认领这次返回：正在播就退到后台继续放，否则真关掉。 */
    private fun handleBackFallback() {
        if (isFinishing) return
        val playing = runCatching { PlayerService.isPlayingNow() }.getOrDefault(false)
        if (playing) moveTaskToBack(true) else finish()
    }

    override fun onDestroy() {
        runCatching { web.removeJavascriptInterface("J4Native") }
        runCatching { web.destroy() }
        super.onDestroy()
    }

    /** 把字符串安全地嵌进 JS 字面量 */
    private fun quote(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        return sb.append("'").toString()
    }
}
