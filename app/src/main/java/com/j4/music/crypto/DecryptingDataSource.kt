package com.j4.music.crypto

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException

/**
 * 给 Media3 用的解密 [DataSource]（规格 6.3 的骨架）。
 *
 * ## Media3 1.5.1 的真实接口形状（已用 javap 核对过 aar，别按别的版本写）
 *
 * ```
 * public interface DataSource extends DataReader {
 *   void   addTransferListener(TransferListener);
 *   long   open(DataSpec) throws IOException;     // ← 返回 long，不是 void
 *   Uri    getUri();                              // ← 可空
 *   Map<String, List<String>> getResponseHeaders();  // default 方法
 *   void   close() throws IOException;
 * }
 * public interface DataReader { int read(byte[], int, int) throws IOException; }
 * public interface TransferListener {
 *   void onTransferInitializing(DataSource, DataSpec, boolean);
 *   void onTransferStart(DataSource, DataSpec, boolean);
 *   void onBytesTransferred(DataSource, DataSpec, boolean, int);   // ← 五个参数，多一个 DataSpec
 *   void onTransferEnd(DataSource, DataSpec, boolean);
 * }
 * public final class DataSpec { final Uri uri; final long position; final long length; ... }
 * ```
 *
 * 这里继承 [BaseDataSource] 而不是直接实现 [DataSource]：`BaseDataSource` 已经把
 * `addTransferListener` / `transferInitializing` / `transferStarted` / `bytesTransferred`
 * / `transferEnded` 都写好了，自己实现容易漏掉（漏 `addTransferListener` 会直接编译不过）。
 *
 * ## seek 支持
 *
 * 全靠 [open] 里对 `spec.position` / `spec.length` 的处理：
 *
 * - `spec.position` 是**明文坐标**（播放器眼里的音频流位置），不是文件坐标；
 * - `spec.length` 可能是 [C.LENGTH_UNSET]，此时用 `reader.size - position` 兜底；
 * - `read` 返回的是**明文长度**，接收方按明文坐标继续要。
 *
 * 对应 TODO_VERIFY.md V8（Media3 自定义 DataSource 的 seek 行为）：本实现保证
 * "任意 position 都能正确读出"（[CryptoProbe] 用乱序 seek 重组验过数学前提），
 * 但"拖到 50% 播放时听感与日志是否正常"要在真机上跑过才算实证，
 * 所以 [open] 把每次拿到的 position 记在 [lastOpenPosition]，接上实现后看一眼日志就知道
 * 播放器到底传了什么。
 *
 * 若上层把**明文**文件交给本类（后缀没处理时会这样），[open] 退化成直接读文件，
 * 不会抛异常打断播放。
 */
@OptIn(UnstableApi::class)
class DecryptingDataSource @JvmOverloads constructor(
    transferListener: TransferListener? = null
) : BaseDataSource(/* isNetwork = */ true) {

    init {
        if (transferListener != null) addTransferListener(transferListener)
    }

    private var reader: DecryptingReader? = null
    /** 明文回退路径用的原始字节数组句柄（FileDataSource 会自己持锁读，这里只需要 seek 能力）。 */
    private var plainFile: java.io.RandomAccessFile? = null
    private var uri: Uri? = null
    private var dataSpec: DataSpec? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = 0
    private var plainTotal: Long = 0

    /** 最近一次 [open] 收到的 `spec.position`（明文坐标），V8 验证时打日志用。 */
    var lastOpenPosition: Long = 0
        private set

    /** 最近一次 [open] 得到的明文总长度（0 表示空文件或路径异常）。 */
    var lastTotalSize: Long = 0
        private set

    /** 这次 open 走的是"解密"还是"明文直读"。 */
    var lastDecrypted: Boolean = false
        private set

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        uri = dataSpec.uri
        lastOpenPosition = dataSpec.position
        transferInitializing(dataSpec)

        val file = fileOf(dataSpec.uri)
        val cipher = runCatching { CipherFactory.forFile(file) }.getOrNull()

        if (cipher == null) {
            // 明文文件：直接按文件坐标读
            val raf = try {
                java.io.RandomAccessFile(file, "r")
            } catch (e: IOException) {
                throw IOException("打不开文件：$file", e)
            }
            plainFile = raf
            plainTotal = raf.length()
            readPosition = if (dataSpec.position == C.LENGTH_UNSET.toLong()) 0 else dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                maxOf(0L, plainTotal - readPosition)
            }
            lastTotalSize = plainTotal
            lastDecrypted = false
        } else {
            val r = DecryptingReader(file, cipher)
            reader = r
            lastTotalSize = r.size
            readPosition = maxOf(0L, dataSpec.position)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, maxOf(0L, r.size - readPosition))
            } else {
                maxOf(0L, r.size - readPosition)
            }
            lastDecrypted = true
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val want = minOf(length.toLong(), bytesRemaining).toInt()

        val r = reader
        if (r != null) {
            val n = r.readAt(readPosition, buffer, offset, want)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            readPosition += n
            bytesRemaining -= n
            bytesTransferred(n)
            return n
        }

        val raf = plainFile ?: return C.RESULT_END_OF_INPUT
        raf.seek(readPosition)
        val n = raf.read(buffer, offset, want)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        readPosition += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    /** 空 map：这一族格式没有任何额外响应头。 */
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        val had = reader != null || plainFile != null
        runCatching { reader?.close() }
        reader = null
        runCatching { plainFile?.close() }
        plainFile = null
        uri = null
        readPosition = 0
        bytesRemaining = 0
        dataSpec = null
        if (had) transferEnded()
    }

    private fun fileOf(uri: Uri): File {
        // 优先用 file:// 的 path；j4enc:// 这类虚拟 scheme 的 host 就是绝对路径的一部分，
        // 两种写法都兼容：uri.path 为空时用 uri.toString() 去掉 scheme。
        val path = uri.path
        if (!path.isNullOrEmpty()) return File(path)
        val s = uri.toString()
        val idx = s.indexOf("://")
        if (idx > 0) {
            val rest = s.substring(idx + 3)
            val slash = rest.indexOf('/')
            if (slash == 0) return File(rest)
            if (slash > 0) return File("/" + rest)
            return File(rest)
        }
        throw IOException("URI 没有 path，无法定位文件：$uri")
    }
}

/**
 * 工厂：给每个播放实例造一个 [DecryptingDataSource]。
 *
 * `upstream == null` 时自己判定要不要解密（加密走 [DecryptingDataSource]，明文直接读文件）；
 * 给了 `upstream` 就把"判定 + 分流"做成一层包装，明文文件原样交给 upstream，
 * 保住它带来的能力（[androidx.media3.datasource.cache.CacheDataSource]、OkHttp 等）。
 */
@OptIn(UnstableApi::class)
class DecryptingDataSourceFactory(
    private val upstream: DataSource.Factory? = null
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val up = upstream?.createDataSource() ?: return DecryptingDataSource(null)
        return RoutingDataSource(up)
    }

    /**
     * 有 upstream 时的包装：`open` 时看文件要不要解密，
     * 不需要就把 DataSpec **原样**交给 upstream；需要就自己解。
     *
     * 注意这里没有用 [ResolvingDataSource] 改写 URI：本单元不改任何既有文件，
     * 也不能假定上层注册过 `j4enc` scheme，所以分流放在 open 里做。
     */
    @OptIn(UnstableApi::class)
    private class RoutingDataSource(
        private val upstream: DataSource
    ) : BaseDataSource(/* isNetwork = */ true) {

        private var active: DataSource? = null
        private var useSelf = false
        private var self: DecryptingDataSource? = null
        private var uri: Uri? = null

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            close()
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val needsDecrypt = runCatching {
                val f = dataSpec.uri.path?.takeIf { it.isNotEmpty() }?.let { File(it) }
                f != null && CipherFactory.isEncrypted(f)
            }.getOrDefault(false)

            val n: Long
            if (needsDecrypt) {
                val d = DecryptingDataSource(null)
                self = d
                useSelf = true
                n = d.open(dataSpec)
                active = d
            } else {
                useSelf = false
                n = upstream.open(dataSpec)
                active = upstream
            }
            return n
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val a = active ?: return C.RESULT_END_OF_INPUT
            val n = a.read(buffer, offset, length)
            if (n > 0) bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            runCatching { active?.responseHeaders ?: emptyMap() }.getOrDefault(emptyMap())

        override fun close() {
            val had = active != null
            runCatching { active?.close() }
            active = null
            self = null
            useSelf = false
            uri = null
            if (had) transferEnded()
        }
    }
}
