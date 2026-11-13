package com.j4.music

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import java.io.ByteArrayInputStream

/**
 * 协议 / 隐私政策 / 开源许可的阅读页。
 *
 * 为什么不塞进主界面的路由栈：这些页面是独立文档，带自己的排版，退回时不该
 * 参与应用内的「push/back」动画与栈深（那会让返回键判断变复杂）。用一个很小的
 * Activity 承载，物理返回键天然就是「关掉这一页」。
 *
 * 加载方式与 MainActivity 一致：手写 shouldInterceptRequest 把 assets 直接吐给
 * WebView，避免 file:// 里跨目录的子资源（../css/legal.css）被同源限制挡住。
 */
class WebPageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra.url"
        const val EXTRA_TITLE = "extra.title"
    }

    private lateinit var web: WebView
    private lateinit var titleView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "J4Music"

        window.statusBarColor = android.graphics.Color.parseColor("#07070C")
        window.navigationBarColor = android.graphics.Color.parseColor("#07070C")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#07070C"))
        }

        // 顶部标题条：左侧返回箭头 + 标题。高度由内容撑开，下方留出安全区。
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
        }
        val back = ImageButton(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setImageDrawable(drawBack())
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor("#F2F2F7"))
            textSize = 16f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#07070C"))
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.loadsImagesAutomatically = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                val prefix = "file:///android_asset/"
                if (!u.startsWith(prefix)) return null
                return assetResponse(u.removePrefix(prefix))
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "file" || u.scheme == "about" || u.scheme == "data") return false
                val opened = runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u))
                    true
                }.getOrDefault(false)
                if (!opened) {
                    android.widget.Toast.makeText(this@WebPageActivity, "没有可以打开该链接的应用", android.widget.Toast.LENGTH_SHORT).show()
                }
                return opened
            }
        }
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val content = root
        setContentView(content)

        // 安全区：标题条压在状态栏下面要补 padding；WebView 底部补导航栏高度。
        val basePad = bar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            bar.setPadding(dp(8), basePad + bars.top, dp(16), 0)
            web.setPadding(0, 0, 0, bars.bottom)
            ViewCompat.onApplyWindowInsets(v, insets)
        }

        if (url.isNotEmpty()) web.loadUrl(url) else finish()
    }

    override fun onDestroy() {
        runCatching { web.destroy() }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 一个极简的返回箭头，避免为一行图标再引一个 drawable 资源。 */
    private fun drawBack(): android.graphics.drawable.Drawable {
        val path = android.graphics.Path().apply {
            moveTo(dp(15).toFloat(), dp(18).toFloat())
            lineTo(dp(7).toFloat(), dp(24).toFloat())
            lineTo(dp(15).toFloat(), dp(30).toFloat())
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#F2F2F7")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val d = object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.save()
                canvas.translate((bounds.width() - dp(48)) / 2f, (bounds.height() - dp(48)) / 2f)
                canvas.drawPath(path, paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(cf: android.graphics.ColorFilter?) {
                paint.colorFilter = cf
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
        return d
    }

    private fun assetResponse(path: String): WebResourceResponse? {
        val clean = path.substringBefore('?').substringBefore('#').ifEmpty { return null }
        val mime = when {
            clean.endsWith(".html") -> "text/html"
            clean.endsWith(".css") -> "text/css"
            clean.endsWith(".js") -> "application/javascript"
            clean.endsWith(".json") -> "application/json"
            clean.endsWith(".svg") -> "image/svg+xml"
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".webp") -> "image/webp"
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
}
