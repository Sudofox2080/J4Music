package com.j4.music.source

import com.j4.music.net.Http
import com.j4.music.net.PersistentCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 所有音源的 HTTP 出口都收敛到这一个接口。
 *
 * 为什么要多这一层：本机外网被 DNS 劫持，四个平台的接口一行都没法真打，
 * 所以「解析逻辑」必须能在完全不联网的情况下被验证。把请求抽成接口之后，
 * 测试里塞一个假实现返回手写的响应体，就能逐字段验证解析函数，
 * 等哪天接口字段变了，只需要改解析层，不用动调用链。
 *
 * 约定：
 *  - get/post 失败（网络错误、非 2xx、空 body）直接抛 [SourceHttpException]，
 *    调用方据此走 fallback 链，不在这里吞掉；
 *  - post 默认按 form（application/x-www-form-urlencoded）发，
 *    需要 JSON body 的调 postJson；
 *  - 空 body 视为失败，因为网易 weapi/eapi 出错时就是 HTTP 200 + 0 字节，
 *    这时连 code 都读不到（规格 1.6）。
 */
interface HttpDriver {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = FORM
    ): String

    /** JSON body 的便捷方法 */
    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        post(url, body, headers, JSON)

    companion object {
        const val FORM = "application/x-www-form-urlencoded"
        const val JSON = "application/json"
    }
}

class SourceHttpException(
    val url: String,
    val httpCode: Int,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause) {
    /** 不是网络悲剧而是服务端明确拒绝（4xx/5xx），fallback 时值得换接口重试 */
    val isServerRejection: Boolean get() = httpCode >= 400
}

/**
 * 默认实现：OkHttp。
 *
 * 每个源一个 client（不同 UA/Referer），但**共用一个 CookieJar**：
 * 网易 MUSIC_U 在 music.163.com、QQ 的 qm_keyst 在 qq.com 下，
 * 域名不同不会互相污染，共用反而省掉「登录后其它 client 看不到 cookie」的坑。
 */
class OkHttpDriver(
    private val source: String,
    private val jar: PersistentCookieJar? = null,
    headers: Map<String, String> = Http.defaultHeaders(source),
    /** 关掉自动跟随的调用点：QQ check_sig 要从 302 的 Set-Cookie 里取 p_skey */
    private val followRedirects: Boolean = true
) : HttpDriver {

    private val client: OkHttpClient = run {
        var b = Http.clientFor(source, headers).newBuilder()
        if (jar != null) b = b.cookieJar(jar)
        b.followRedirects(followRedirects).build()
    }

    override suspend fun get(url: String, headers: Map<String, String>): String =
        execute(Request.Builder().url(url).applyHeaders(headers).get().build()) { it.body?.string() ?: "" }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        contentType: String
    ): String {
        val req = Request.Builder()
            .url(url)
            .applyHeaders(headers)
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        return execute(req) { it.body?.string() ?: "" }
    }

    private fun Request.Builder.applyHeaders(h: Map<String, String>): Request.Builder = apply {
        h.forEach { (k, v) -> header(k, v) }
    }

    private suspend fun execute(req: Request, read: (Response) -> String): String =
        withContext(Dispatchers.IO) {
            val text = await(req)
            if (text.isEmpty()) {
                // 规格 1.6：weapi/eapi 失败可能是 200 + 0 字节，必须当失败处理
                throw SourceHttpException(req.url.toString(), 200, "empty body from ${req.url.host}")
            }
            text
        }

    private suspend fun await(req: Request): String =
        suspendCancellableCoroutine { cont ->
            val call: Call = client.newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(SourceHttpException(req.url.toString(), 0, "network: ${e.message}", e)))
                    }
                }

                override fun onResponse(c: Call, resp: Response) {
                    try {
                        resp.use { r ->
                            val body = r.body?.string() ?: ""
                            if (!r.isSuccessful) {
                                val snippet = body.take(200).replace('\n', ' ')
                                cont.resumeWith(
                                    Result.failure(
                                        SourceHttpException(r.request.url.toString(), r.code, "HTTP ${r.code} $snippet")
                                    )
                                )
                            } else {
                                cont.resumeWith(Result.success(body))
                            }
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                }
            })
        }
}
