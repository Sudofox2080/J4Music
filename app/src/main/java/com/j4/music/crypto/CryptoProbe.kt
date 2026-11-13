package com.j4.music.crypto

import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/**
 * 本机自检工具：所有断言都能在 `main`（纯 JVM）与 Android 单测里跑同一份代码。
 *
 * ## 为什么值得写
 *
 * 这一族解码器错了**不会报错，只会产出噪音**：keybox 抄错一个下标、TEA 字节序反了、
 * 5120 分段忘了清 box，症状都是"能播但是沙沙声"，靠听是查不出来的。
 * 所以把三件最容易错的事做成可重放的断言：
 *
 * 1. **NCM 合成往返**：现场造一个 NCM（头 + keydata + meta + keybox 加密的假音频），
 *    再用 [NcmCipher.parse] 解析回来，逐字节比对音频体与 meta 字段。
 *    这一步同时校验了头布局（keyLen 在偏移 8 而不是 10）、RC4→AES 的顺序、keybox 置换。
 * 2. **QMC v2 MAP 周期**（规格 2.3）：`p = 0x7FFF` 与 `p = 0` 不同、`p = 0x8000` 与 `p = 1` 相同。
 *    抄成 `>= 0x8000` 或 `% 0x8000` 这条就会挂。
 *
 * 额外覆盖：RC4 变体的"分块 == 全量"、"乱序 seek 重组 == 全量"、单字节边界读
 * （对应 `tools/streamcheck.py`），以及 [CryptoPrimitives.simpleKeyByFormula] 与硬编码
 * `SIMPLE_KEY` 的等价性（规格 2.2 第三步的 tan 公式）。
 *
 * ## 怎么跑
 *
 * Android：`CryptoProbe.selfTest().forEach(::println)`。
 * 纯 JVM（不含 android.util 依赖，但 [NcmCipher] 只用了 javax.crypto）：
 * `java -cp ... com.j4.music.crypto.CryptoProbeKt` —— 见文件末尾的 `main`。
 *
 * 每项结果都是 `"PASS: 描述"` 或 `"FAIL: 描述（细节）"`，不抛异常，
 * 这样一次就能看到全部失败项，而不是被第一处断言打断。
 */
object CryptoProbe {

    /** 自检用的确定性伪随机（不依赖 java.util.Random 的实现细节，跨平台可复现）。 */
    private class Lcg(seed: Long) {
        private var s = seed
        fun nextByte(): Byte {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33) and 0xFF).toByte()
        }

        fun bytes(n: Int): ByteArray = ByteArray(n) { nextByte() }
    }

    /** 逐项自检。返回形如 `"PASS: ..."` / `"FAIL: ..."` 的清单。 */
    fun selfTest(): List<String> {
        val out = ArrayList<String>()
        out += run("NCM 合成文件往返（头布局 + RC4→AES + keybox）") { checkNcmRoundTrip() }
        out += run("NCM keybox 是 0..255 的置换") { checkNcmKeyBoxPermutation() }
        out += run("NCM keyLen 偏移 = 8（写成 8+2+4 必须失败）") { checkNcmKeyLenOffset() }
        out += run("QMC v2 MAP p=0x7FFF 与 p=0 不同") { checkMapWrapBoundary() }
        out += run("QMC v2 MAP p=0x8000 与 p=1 相同") { checkMapWrapRollover() }
        out += run("QMC v2 SIMPLE_KEY 与 tan 公式一致") { checkSimpleKey() }
        out += run("QMC v2 RC4 分块(4096) == 全量") { checkRc4Chunking() }
        out += run("QMC v2 RC4 乱序 seek 重组 == 全量") { checkRc4Seek() }
        out += run("QMC v2 RC4 单字节边界读一致") { checkRc4SingleByte() }
        out += run("QMC v2 ekey 规整（%xx + 空格换 +）") { checkEkeyNormalize() }
        out += run("Tencent TEA 往返（加密逆运算自校验）") { checkTeaRoundTrip() }
        out += run("QMC v1 缺表必须抛「缺少静态 cipher 表」") { checkQmcV1MissingTable() }
        out += run("QMC v1 装表后流式解密可 seek") { checkQmcV1WithInjectedTable() }
        out += run("CipherFactory 后缀映射") { checkOutputExtension() }
        return out
    }

    // ---------------------------------------------------------------- 断言框架

    private inline fun run(name: String, body: () -> Unit): String = try {
        body()
        "PASS: $name"
    } catch (e: Throwable) {
        "FAIL: $name —— ${e::class.java.simpleName}: ${e.message}"
    }

    private fun expect(cond: Boolean, msg: () -> String) {
        if (!cond) throw AssertionError(msg())
    }

    private fun expectEq(actual: Any?, expected: Any?, what: String) {
        if (actual != expected) throw AssertionError("$what: 期望 $expected，实际 $actual")
    }

    private fun expectBytes(actual: ByteArray, expected: ByteArray, what: String) {
        if (actual.size != expected.size) {
            throw AssertionError("$what: 长度 期望 ${expected.size}，实际 ${actual.size}")
        }
        for (i in actual.indices) {
            if (actual[i] != expected[i]) {
                throw AssertionError("$what: 第 $i 字节 期望 0x${hex(expected[i])}，实际 0x${hex(actual[i])}")
            }
        }
    }

    private fun hex(b: Byte) = "%02X".format(b.toInt() and 0xFF)

    // ---------------------------------------------------------------- NCM

    /**
     * 合成一个完整 NCM 文件并解析回来。
     *
     * 构造端刻意按**规格**的 12 字节头（magic 4 + gap 4 + keyLen 4）来写，
     * 而不是 `refcheck.py` 里那个 14 字节头的自洽版本 —— 目标就是验证
     * "keyLen 在偏移 8" 这条易错约定。
     */
    private fun checkNcmRoundTrip() {
        val keyRaw = Lcg(0x4E434D).bytes(32)
        val audio = ByteArray(2000) { i -> (i * 7 + 3).toByte() }
        val ncm = buildFakeNcm(keyRaw, audio, musicName = "测试曲", format = "flac")

        val parsed = NcmCipher.parse(ncm)
        expectEq(parsed.dataStart.toInt(), ncm.size - audio.size, "dataStart（音频体起点）")
        expectBytes(parsed.keyRaw, keyRaw, "keyRaw")

        // 先用「手算」验证 keybox + 起始偏移这条数学前提是否成立（不经过任何类的方法）
        val box = NcmCipher.ncmKeyBox(parsed.keyRaw)
        val body = ncm.copyOfRange(ncm.size - audio.size, ncm.size)
        val manual = ByteArray(body.size) { (body[it].toInt() xor box[it and 0xFF].toInt()).toByte() }
        expectBytes(manual, audio, "手算解密（keybox + 音频体起点）")

        // 再验实现：这里刻意用**接口类型**去调 decrypt，
        // 因为真实播放链路拿到的正是 BlockCipher（CipherFactory.open 的返回类型）。
        // 注意拉的是密文（body），不是明文 audio —— 早先这里写成 audio.copyOf()，
        // 结果「解密」出的是把明文再异或一遍的噪音，自检自己把自己骗了一轮。
        val dec = body.copyOf()
        val asBlock: com.j4.music.crypto.BlockCipher = parsed
        asBlock.decrypt(dec, 0, dec.size, 0L)
        if (!dec.contentEquals(manual)) {
            var first = -1
            for (i in dec.indices) if (dec[i] != manual[i]) { first = i; break }
            // 越界调用本该被 require 拦下；拦不住说明那个 decrypt 根本不是这个类的实现
            val guard = runCatching { asBlock.decrypt(ByteArray(4), 0, 8, 0L) }.exceptionOrNull()
            throw AssertionError(
                "接口 decrypt 与手算不一致：第 $first 字节 手算 0x${hex(manual[first])}，实现 0x${hex(dec[first])}；" +
                    "越界守卫=${guard?.let { it::class.java.simpleName + ":" + it.message } ?: "没有抛（可疑）"}；" +
                    "box[0]=0x${hex(box[0])} box[1]=0x${hex(box[1])}；" +
                    "dataStart=${parsed.dataStart} keyLen=${parsed.keyLen} metaLen=${parsed.metaLen}"
            )
        }
        expectBytes(dec, audio, "音频体明文")

        // 分块 + 乱序都要一致（keybox 周期 256，块长故意取 250 制造跨周）。
        // 注意喂进去的必须是**密文 body 的切片**：拿明文切片去解只会得到噪音。
        val chunked = ByteArray(audio.size)
        // 起点必须能**铺满**整个音频体（250 的整数倍、乱序），否则没覆盖到的字节留 0，
        // 会被误判成解密错误 —— 早先的起点表在 950~1000 和 1450~1500 就漏了。
        val order = intArrayOf(1000, 0, 1500, 250, 1750, 500, 1250, 750)
        for (start in order) {
            val n = minOf(250, audio.size - start)
            val part = body.copyOfRange(start, start + n)
            parsed.decrypt(part, 0, n, start.toLong())
            System.arraycopy(part, 0, chunked, start, n)
        }
        expectBytes(chunked, audio, "乱序分块解密")

        // 单字节读边界
        for (p in intArrayOf(0, 1, 255, 256, 257, 511, 512, 1999)) {
            val one = byteArrayOf(body[p])
            parsed.decrypt(one, 0, 1, p.toLong())
            expect(one[0] == audio[p]) { "单字节解密在 p=$p 不一致" }
        }

        // meta
        val meta = parsed.meta ?: throw AssertionError("meta 没有解析出来")
        expectEq(meta.name, "测试曲", "meta.name")
        expectEq(meta.format, "flac", "meta.format")
        expectEq(meta.audioExtension(), "flac", "meta.audioExtension()")
        expectEq(meta.bitrate, 999000L, "meta.bitrate")
        expectEq(meta.durationMs, 123456L, "meta.durationMs")
        expectEq(meta.album, "专辑", "meta.album")
        expectEq(meta.artist, listOf("歌手甲", "歌手乙"), "meta.artist")
    }

    /**
     * 对外造样本：音频体传真实字节（比如一段真的 mp3），造出来的 .ncm 能被
     * [NcmCipher] 正常解开，也就能拿它端到端跑一遍「转译」。
     *
     * 存在的理由：本机没有任何真实的网易云 .ncm 文件，光靠单测往返只能证明
     * 加解密自洽，证明不了 `Transcode` → `CipherFactory` → 落盘 这条链路。
     */
    fun buildNcmSample(
        audio: ByteArray,
        musicName: String = "转译自检",
        format: String = "mp3",
        keyRaw: ByteArray = Lcg(0x4E434D).bytes(32)
    ): ByteArray = buildFakeNcm(keyRaw, audio, musicName, format)

    private fun buildFakeNcm(keyRaw: ByteArray, audio: ByteArray, musicName: String, format: String): ByteArray {
        val core = NcmCipher.CORE_KEY
        val metaKey = NcmCipher.META_KEY
        val xorKey = NcmCipher.XOR_KEY

        // keyData：明文 = "neteasecloudmusic" + keyRaw，先 AES 再 RC4（解端反序）
        val keyPlain = NcmCipher.KEY_PREFIX + keyRaw
        val keyAes = CryptoPrimitives.aesEcbEncryptPkcs5(core, keyPlain)
        val keyBlob = CryptoPrimitives.rc4(core, keyAes)

        // meta：XOR_KEY + base64(json)，先 XOR 再 AES
        val json = """{"musicName":"$musicName","album":"专辑","format":"$format","bitrate":999000,""" +
            """"duration":123456,"artist":[["歌手甲",1],["歌手乙",2]]}"""
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val metaPlainSrc = xorKey + b64.toByteArray(Charsets.US_ASCII)
        val metaXored = ByteArray(metaPlainSrc.size) {
            (metaPlainSrc[it].toInt() xor xorKey[it % xorKey.size].toInt()).toByte()
        }
        val metaEnc = CryptoPrimitives.aesEcbEncryptPkcs5(metaKey, metaXored)

        // 封面：留空（imgLen = 0），封面密钥是【待验证】项，单独在 decryptCover 里降级
        val img = ByteArray(0)

        val header = ByteArray(12)
        System.arraycopy(NcmCipher.MAGIC, 0, header, 0, 4)
        // 偏移 4..7 是 gap，规格说"固定只跳不判"，这里刻意写成非零，防止别人又去读它
        header[4] = 0x01; header[5] = 0x70; header[6] = 0x00; header[7] = 0x00
        CryptoPrimitives.putU32le(header, 8, keyBlob.size.toLong())

        val out = java.io.ByteArrayOutputStream()
        out.write(header)
        out.write(keyBlob)
        val lenBuf = ByteArray(4)
        CryptoPrimitives.putU32le(lenBuf, 0, metaEnc.size.toLong()); out.write(lenBuf)
        out.write(metaEnc)
        out.write(ByteArray(4))          // crc32，不校验
        out.write(ByteArray(2))          // gap
        CryptoPrimitives.putU32le(lenBuf, 0, img.size.toLong()); out.write(lenBuf)
        out.write(img)

        // 音频体：先 keybox 加密
        val box = NcmCipher.ncmKeyBox(keyRaw)
        val enc = ByteArray(audio.size) { (audio[it].toInt() xor box[it and 0xFF].toInt()).toByte() }
        out.write(enc)
        return out.toByteArray()
    }

    private fun checkNcmKeyBoxPermutation() {
        for (keyLen in intArrayOf(8, 13, 16, 32, 64)) {
            val box = NcmCipher.ncmKeyBox(Lcg(keyLen.toLong()).bytes(keyLen))
            expectEq(box.size, 256, "keybox 长度")
            val seen = BooleanArray(256)
            for (b in box) {
                val v = b.toInt() and 0xFF
                expect(!seen[v]) { "keybox 在 keyLen=$keyLen 时出现重复值 $v（不是置换）" }
                seen[v] = true
            }
        }
    }

    private fun checkNcmKeyLenOffset() {
        // 把 keyLen 写到偏移 10（8+2）上去的"经典错版"，本实现必须识别为非法而不是解出噪音
        val keyRaw = Lcg(11).bytes(16)
        val good = buildFakeNcm(keyRaw, ByteArray(64), "x", "mp3")
        val bad = good.copyOf()
        val realKeyLen = CryptoPrimitives.u32le(bad, 8)
        bad[10] = (bad[10].toInt() xor realKeyLen.toInt().toByte().toInt()).toByte() // 弄坏偏移 8 处的高字节
        var failed = false
        try {
            NcmCipher.parse(bad)
        } catch (e: NcmFormatException) {
            failed = true
        }
        expect(failed) { "被篡改的 keyLen 应当抛 NcmFormatException" }
        // 正常文件必须能解析
        NcmCipher.parse(good)
    }

    // ---------------------------------------------------------------- QMC v2

    private fun checkMapWrapBoundary() {
        val key = Lcg(0x4D4150).bytes(128)
        val a = byteArrayOf(0)
        val b = byteArrayOf(0)
        MapCipher(key).decrypt(a, 0, 1, 0x7FFFL)
        MapCipher(key).decrypt(b, 0, 1, 0L)
        expect(a[0] != b[0]) { "p=0x7FFF 与 p=0 解出的掩码相同（写成 >= 0x8000 或 % 0x8000 了）" }
    }

    private fun checkMapWrapRollover() {
        val key = Lcg(0x524F4C).bytes(128)
        val a = byteArrayOf(0)
        val b = byteArrayOf(0)
        MapCipher(key).decrypt(a, 0, 1, 0x8000L)
        MapCipher(key).decrypt(b, 0, 1, 1L)
        expect(a[0] == b[0]) { "p=0x8000 与 p=1 解出的掩码不同（回卷逻辑错）" }
        // 再验周期本身（周期 0x7FFF：p 与 p+0x7FFF 落在同一个下标上）
        val c = ByteArray(3)
        val d = ByteArray(3)
        MapCipher(key).decrypt(c, 0, 3, 0x7FFF + 5L)
        MapCipher(key).decrypt(d, 0, 3, 5L)
        expectBytes(c, d, "MAP 周期 0x7FFF")
    }

    private fun checkSimpleKey() {
        expectBytes(QmcV2Cipher.SIMPLE_KEY, QmcV2Cipher.simpleKeyByFormula(), "SIMPLE_KEY vs tan 公式")
    }

    private fun checkRc4Chunking() {
        val key = Lcg(1).bytes(512)
        val plain = Lcg(2).bytes(40000)
        val whole = plain.copyOf()
        Rc4Cipher(key).decrypt(whole, 0, whole.size, 0L)
        val chunked = plain.copyOf()
        var off = 0
        while (off < chunked.size) {
            val n = minOf(4096, chunked.size - off)
            Rc4Cipher(key).decrypt(chunked, off, n, off.toLong())
            off += n
        }
        expectBytes(chunked, whole, "RC4 分块 4096")

        // 同一实例跨块连续读也必须一致（段内快照路径）
        val same = plain.copyOf()
        val one = Rc4Cipher(key)
        off = 0
        while (off < same.size) {
            val n = minOf(4096, same.size - off)
            one.decrypt(same, off, n, off.toLong())
            off += n
        }
        expectBytes(same, whole, "RC4 同一实例跨块读")
    }

    private fun checkRc4Seek() {
        val key = Lcg(3).bytes(512)
        val plain = Lcg(4).bytes(40000)
        val whole = plain.copyOf()
        val one = Rc4Cipher(key)
        one.decrypt(whole, 0, whole.size, 0L)

        val starts = (0 until plain.size step 4096).toMutableList()
        // 确定性洗牌
        val rnd = Lcg(5)
        for (i in starts.indices.reversed()) {
            val j = ((rnd.nextByte().toInt() and 0x7F) * 37 + i * 13) % (i + 1)
            val t = starts[i]; starts[i] = starts[j]; starts[j] = t
        }
        val rebuilt = ByteArray(plain.size)
        for (s in starts) {
            val n = minOf(4096, plain.size - s)
            val part = plain.copyOfRange(s, s + n)
            // 每个块都用全新实例（最坏情况：没有快照可复用）
            Rc4Cipher(key).decrypt(part, 0, n, s.toLong())
            System.arraycopy(part, 0, rebuilt, s, n)
        }
        expectBytes(rebuilt, whole, "RC4 乱序 seek 重组")
    }

    private fun checkRc4SingleByte() {
        val key = Lcg(6).bytes(512)
        val plain = Lcg(7).bytes(40000)
        val whole = plain.copyOf()
        Rc4Cipher(key).decrypt(whole, 0, whole.size, 0L)
        for (p in intArrayOf(0, 1, 127, 128, 129, 5119, 5120, 5121, 10240, 39999)) {
            val one = byteArrayOf(plain[p])
            Rc4Cipher(key).decrypt(one, 0, 1, p.toLong())
            expect(one[0] == whole[p]) { "单字节读在 p=$p 不一致" }
        }
    }

    private fun checkEkeyNormalize() {
        val s = "abc%2Bdef%3D ghijk"
        val out = QmcV2Cipher.normalizeEkey(s)
        expectEq(out, "abc+def=+ghijk", "normalizeEkey")
        // 真实 ekey 里更常见的是直接的空格代替 +，以及换行。
        // 换行是「粘贴时折行」的产物，必须丢掉而不是当 +，否则 base64 会多出字符。
        expectEq(QmcV2Cipher.normalizeEkey("QUFB QUFBQUFB\r\nQQ=="), "QUFB+QUFBQUFBQQ==", "normalizeEkey 空格/换行")
    }

    // ---------------------------------------------------------------- TEA

    /**
     * TEA 的标准加密（16 轮，大端读入大端输出），只用来给 [CryptoPrimitives.tencentTea] 做逆运算自校验。
     */
    private fun teaEncryptBlock(v0in: Int, v1in: Int, key16: ByteArray): Pair<Int, Int> {
        var v0 = v0in
        var v1 = v1in
        val k0 = CryptoPrimitives.beInt(key16, 0)
        val k1 = CryptoPrimitives.beInt(key16, 4)
        val k2 = CryptoPrimitives.beInt(key16, 8)
        val k3 = CryptoPrimitives.beInt(key16, 12)
        var total = 0
        val delta = CryptoPrimitives.TEA_DELTA
        for (i in 0 until 16) {
            total += delta
            v0 += ((v1 shl 4) + k0) xor (v1 + total) xor ((v1 ushr 5) + k1)
            v1 += ((v0 shl 4) + k2) xor (v0 + total) xor ((v0 ushr 5) + k3)
        }
        return v0 to v1
    }

    private fun checkTeaRoundTrip() {
        val key = Lcg(0x7EA).bytes(16)
        val rnd = Lcg(0x9EA)
        repeat(64) {
            val blk = rnd.bytes(8)
            val v0 = CryptoPrimitives.beInt(blk, 0)
            val v1 = CryptoPrimitives.beInt(blk, 4)
            val (e0, e1) = teaEncryptBlock(v0, v1, key)
            val enc = ByteArray(8)
            CryptoPrimitives.bePut(enc, 0, e0)
            CryptoPrimitives.bePut(enc, 4, e1)
            val dec = CryptoPrimitives.teaDecryptBlock(enc, 0, key, false)
            expectBytes(dec, blk, "TEA 单块往返")
        }

        // 整条文（含首块 pad + 尾部 7 字节校验）的自校验：
        // 构造一个满足 tencentTea 约定的密文并验它能还原出原始明文的长度与内容。
        // 长度要凑成 8 的整数倍：total = 1(pad 字节) + pad + 2(跳过) + n + 7(尾部校验)，
        // 所以 n 必须满足 13 + n ≡ 0 (mod 8)，取 43。
        val plain = rnd.bytes(43)
        val enc = teaWrapForTest(plain, key)
        val dec = CryptoPrimitives.tencentTea(enc, key, false)
        expectBytes(dec, plain, "Tencent TEA 整条往返")
    }

    /**
     * 按 [CryptoPrimitives.tencentTea] 解码端的逆向约定打包一条密文。
     *
     * 解码端的规则是（`dec` 为逐块解密结果，`ivPrev` 为上一块密文）：
     *
     * ```
     * 块 0 解密得 dec0，pad = dec0[0] & 7
     * 从下标 1 + pad 起跳过 2 字节
     * 之后每输出一个字节：out[k] = dec[idx] ^ ivPrev[idx]
     * 最后 7 字节要求 dec[idx] == ivPrev[idx]
     * 后续块：dec = teaDecryptBlock(dec ^ ivCur)
     * ```
     *
     * 反解（两个坑都在这里，写错的表现是「尾部零校验失败」或「整条往返第 2 字节不一致」）：
     *
     * 1. 跨块链混的是**上一块的明文 dec**，不是上一块密文：
     *    `enc_0 = teaEncrypt(dec_0)`、`enc_i = teaEncrypt(dec_i) XOR dec_{i-1}`（i>=1）。
     * 2. 输出那一步是 `out[w] = dec_k[j] XOR ivPrev[j]`，而 `ivPrev` 是**上一块密文**。
     *    所以要 `out = 明文`，必须让 `dec_k[j] = 明文[w] XOR enc_{k-1}[j]`，
     *    光把明文原样写进 dec 只能让块 0 的头两个字节对上。
     *
     * 尾部 7 字节要求 `dec_last[tail] == enc_{last-1}[tail]`，所以按块顺序边算密文边摆下一块。
     *
     * 这个打包器只服务于自检（把"pad 跳过 / 跨块 XOR 链 / 尾 7 字节校验"三条边界跑到），
     * 不保证生成腾讯客户端认的密文。
     */
    private fun teaWrapForTest(plain: ByteArray, key: ByteArray): ByteArray {
        val pad = 3
        val total = 1 + pad + 2 + plain.size + 7
        require(total % 8 == 0) { "测试样本长度必须齐 8（实际 $total）" }
        val blocks = total / 8
        require(blocks >= 2) { "至少要有两块才能验跨块链" }

        val enc = ByteArray(total)
        var pIdx = 0
        var prevDec = ByteArray(8)
        var prevEnc = ByteArray(8)

        for (i in 0 until blocks) {
            val dec = ByteArray(8)
            if (i == 0) {
                // 块 0：dec[0] 放 pad(=3)，其后 3 字节是填充，再跳过 2 字节，正文从块内下标 6 起
                dec[0] = pad.toByte()
                dec[1] = 0x11; dec[2] = 0x22; dec[3] = 0x33
                for (j in 6 until 8) dec[j] = (plain[pIdx++].toInt() xor prevEnc[j].toInt()).toByte()
            } else {
                val rem = plain.size - pIdx
                val last = i == blocks - 1
                for (j in 0 until 8) {
                    dec[j] = if (last && j >= rem) prevEnc[j]
                    else (plain[pIdx++].toInt() xor prevEnc[j].toInt()).toByte()
                }
            }
            val (e0, e1) = teaEncryptBlock(CryptoPrimitives.beInt(dec, 0), CryptoPrimitives.beInt(dec, 4), key)
            val t = ByteArray(8)
            CryptoPrimitives.bePut(t, 0, e0)
            CryptoPrimitives.bePut(t, 4, e1)
            if (i == 0) {
                System.arraycopy(t, 0, enc, 0, 8)
            } else {
                for (j in 0 until 8) enc[i * 8 + j] = (t[j].toInt() xor prevDec[j].toInt()).toByte()
            }
            prevDec = dec
            prevEnc = enc.copyOfRange(i * 8, i * 8 + 8)
        }
        require(pIdx == plain.size) { "正文没有摆完：$pIdx/${plain.size}" }
        return enc
    }

    // ---------------------------------------------------------------- QMC v1

    private fun checkQmcV1MissingTable() {
        QmcV1Table.clear()
        var thrown: QmcV1UnsupportedException? = null
        try {
            QmcV1Cipher.create(null)
        } catch (e: QmcV1UnsupportedException) {
            thrown = e
        }
        expect(thrown != null) { "表为空时必须抛 QmcV1UnsupportedException" }
        expect(thrown!!.message!!.contains("缺少静态 cipher 表")) { "异常文案应包含「缺少静态 cipher 表」，实际 ${thrown.message}" }
    }

    private fun checkQmcV1WithInjectedTable() {
        val table = Lcg(0x01C1).bytes(0x400)
        QmcV1Table.install("qmcflac", table)
        val c = QmcV1Cipher.create(QmcV1Table.select("qmcflac"))
        val plain = Lcg(8).bytes(3000)
        val whole = plain.copyOf()
        c.decrypt(whole, 0, whole.size, 0L)
        val chunked = plain.copyOf()
        var off = 0
        while (off < chunked.size) {
            val n = minOf(333, chunked.size - off)
            c.decrypt(chunked, off, n, off.toLong())
            off += n
        }
        expectBytes(chunked, whole, "QMC v1 分块解密")
        // 回卷语义与 v2 MAP 同构：p = offset & 0x7FFF，所以 0x8000 等价于 1、0x7FFF 不等价于 0
        expect(c.maskAt(0x8000L) == c.maskAt(1L)) { "v1 回卷必须等价 p=1（0x8000 → 1）" }
        expect(c.maskAt(0x7FFFL) != c.maskAt(0L)) { "p=0x7FFF 不能与 p=0 相同" }
        QmcV1Table.clear()
    }

    // ---------------------------------------------------------------- factory

    private fun checkOutputExtension() {
        expectEq(CipherFactory.extensionOutForTest("mflac"), "flac", "mflac")
        expectEq(CipherFactory.extensionOutForTest("mgg"), "ogg", "mgg")
        expectEq(CipherFactory.extensionOutForTest("mmp3"), "mp3", "mmp3")
        expectEq(CipherFactory.extensionOutForTest("qmcflac"), "flac", "qmcflac")
        expectEq(CipherFactory.extensionOutForTest("qmcogg"), "ogg", "qmcogg")
        expectEq(CipherFactory.extensionOutForTest("qmc0"), "mp3", "qmc0")
        expectEq(CipherFactory.extensionOutForTest("qmc3"), "mp3", "qmc3")
        expectEq(CipherFactory.extensionOutForTest("QY2"), "ogg", "QY2 大小写不敏感")
        expectEq(CipherFactory.extensionOutForTest("MFLAC"), "flac", "MFLAC 大小写不敏感")
    }

    /** 便利：自检时只看失败项。 */
    fun failures(): List<String> = selfTest().filter { it.startsWith("FAIL") }

    fun hasFailure(): Boolean = selfTest().any { it.startsWith("FAIL") }
}

/**
 * 命令行自检入口：`java -cp <classes> com.j4.music.crypto.CryptoProbeKt`。
 *
 * 退出码 0 表示全部 PASS，1 表示有 FAIL —— 便于挂到 CI 或本地脚本里。
 * Android 侧不需要它，可以只调 [CryptoProbe.selfTest]。
 */
fun main() {
    val results = CryptoProbe.selfTest()
    results.forEach(::println)
    val fails = results.count { it.startsWith("FAIL") }
    println()
    println("${results.size - fails} passed, $fails failed")
    if (fails > 0) {
        kotlin.system.exitProcess(1)
    }
}
