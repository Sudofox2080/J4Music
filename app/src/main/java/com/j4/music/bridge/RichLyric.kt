package com.j4.music.bridge

import com.j4.music.source.Lyric
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把各音源的「富歌词」原始文本解析成前端能直接渲染的逐字结构。
 *
 * 前端（js/lyric.js）只认这一种形状：
 *   [{ "ms": 1234, "text": "整行文本", "tr": "翻译", "chars": [{"c":"字","t":1234}, ...] }]
 * 而四家音源给的东西完全不同：
 *   - 网易 yrc：[1234,3400](1234,300,0)字(1534,300,0)符 ……   （括号里依次是 起ms,时长ms,占位）
 *   - krc 文本（形如 [0,4410]<0,630,0>字）：与 yrc 同一套尖括号语法，复用 parseYrcLike
 *   - 华为 hmrc：类似 yrc 但方括号段之间可能没有行首时间戳
 *   - 其它：直接是纯 LRC，交给前端自己按行解析
 *
 * 兜底原则：逐字解析失败一律返回空数组（前端见 rich 为空就回落到 lrc 行模式），
 * 宁可退化成普通滚动歌词，也不要因为格式不认识导致整首歌词空白。
 */
object RichLyric {

    fun parse(ly: Lyric): JSONArray {
        val kind = ly.lyricRichKind.lowercase()
        val raw = ly.rich
        if (raw.isBlank()) return JSONArray()
        // 兜底防污染：rich 偶尔会是没解开的 base64 原文，别当歌词渲染。
        // 它既不是 yrc 也不是 lrc，硬解析会把整段 base64 当成一句话显示在播放页
        // （就是用户报的「歌词和歌名对不上」）。这里宁可返回空，让前端回落到 lrc。
        if (looksLikeBase64Blob(raw)) return JSONArray()
        return when (kind) {
            "yrc", "krc", "hmrc", "qrc", "krc-xml" -> runCatching { parseYrcLike(raw) }.getOrDefault(JSONArray())
            "krc-raw" -> JSONArray()   // 没解出明文，交给 LRC 模式
            else -> runCatching { parseYrcLike(raw) }.getOrDefault(JSONArray())
        }
    }

    /**
     * 单行、超长、只含 base64 字符集、且几乎没有 CJK —— 判定为未解码的密文。
     * 正常歌词至少会带 `[mm:ss]` 或中文，不会命中。
     */
    fun looksLikeBase64Blob(raw: String): Boolean {
        if (raw.length < 400) return false
        if (raw.contains('\n')) return false
        val cjk = raw.count { it.code in 0x4E00..0x9FFF }
        if (cjk > 2) return false
        val b64 = raw.count { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        return b64.toDouble() / raw.length > 0.98
    }

    /**
     * 解析 [起ms,时长ms](起,时长,占位)字(起,时长,占位)符 这种格式。
     * 一行里没有括号就是普通整行歌词（chars 为空）。
     */
    fun parseYrcLike(text: String): JSONArray {
        val lines = JSONArray()
        // krc 明文开头带 UTF-8 BOM（\uFEFF），不剥掉的话第一行 "[id:...]" 前面的不可见字符
        // 会让头部过滤正则匹配不上，第一行就变成一行 0ms 的垃圾歌词。
        val clean = text.removePrefix("\uFEFF")
        val translations = parsePlainLrcTextToMap(clean) // 若文本里混了 [mm:ss.xx]翻译 也能用上

        for (rawLine in clean.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue

            val head = Regex("^\\[(\\d+),(?:\\d+)\\]").find(line)
            // krc 明文头部带 [id:]/[ar:]/[ti:]/[by:]/[hash:]/[al:]/[sign:]/[qq:]/[total:]/[offset:]/[language:]
            // 这些是元数据参数行，不是歌词。以前它们会被当成「以 0ms 开始的一行」塞进 rich，
            // 播放页第一屏就是 [id:$00000000] 这种东西。非 "[数字,数字]" 开头且带冒号的方括号行一律丢弃。
            if (head == null && Regex("^\\[[A-Za-z_]+:.*]$").matches(line)) continue
            val headMs = head?.groupValues?.get(1)?.toLongOrNull()
            val body = if (head != null) line.substring(head.range.last + 1) else line

            val chars = ArrayList<JSONObject>()
            val plain = StringBuilder()
            var lastTime = headMs ?: 0L

            val re = Regex("[<(](-?\\d+),(\\d+)(?:,-?\\d+)?[>)]\\s*([^<(]*)")
            var pos = 0
            while (true) {
                val m = re.find(body, pos) ?: break
                val start = m.groupValues[1].toLongOrNull() ?: lastTime
                lastTime = start
                val chunk = m.groupValues[3]
                if (chunk.isNotEmpty()) {
                    plain.append(chunk)
                    // 一个字一个 time 会把整行打得过散（中文歌一般整词），
                    // 这里按「括号内文本整体」给同一个起点，视觉上仍是逐字滑动
                    for (c in chunk) chars.add(JSONObject().put("c", c.toString()).put("t", start))
                }
                pos = m.range.last + 1
            }
            if (chars.isEmpty()) {
                // 没有逐字括号：去掉行首 [mm:ss.xx] 当整行
                val lrcHead = Regex("^\\[\\d+:\\d+(?:[.:]\\d+)?\\]").find(body)
                val content = if (lrcHead != null) body.substring(lrcHead.range.last + 1) else body
                if (content.isBlank()) continue
                val ms = headMs ?: lrcHead?.let { parseLrcTime(it.value) } ?: 0L
                lines.put(JSONObject().apply {
                    put("ms", ms)
                    put("text", content.trim())
                    put("tr", translations[content.trim()] ?: "")
                    put("chars", JSONArray())
                })
            } else {
                val ms = headMs ?: chars.firstOrNull()?.optLong("t") ?: 0L
                val txt = plain.toString().trim()
                lines.put(JSONObject().apply {
                    put("ms", ms)
                    put("text", txt)
                    put("tr", translations[txt] ?: "")
                    put("chars", JSONArray(chars.toList()))
                })
            }
        }
        return lines
    }

    /** "[mm:ss.xx]" / "[mm:ss:xx]" → 毫秒 */
    fun parseLrcTime(tag: String): Long {
        val m = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]").find(tag) ?: return 0L
        val min = m.groupValues[1].toLongOrNull() ?: 0L
        val sec = m.groupValues[2].toLongOrNull() ?: 0L
        val fracRaw = m.groupValues[3]
        val frac = if (fracRaw.isEmpty()) 0L else {
            val v = fracRaw.toLongOrNull() ?: 0L
            if (fracRaw.length == 3) v else if (fracRaw.length == 2) v * 10 else v * 100
        }
        return min * 60_000 + sec * 1_000 + frac
    }

    /** 从纯 LRC 文本里抽 [mm:ss.xx]文本 → 文本，用来给逐字歌词补翻译 */
    private fun parsePlainLrcTextToMap(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val m = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)$").find(line.trim()) ?: continue
            val content = m.groupValues[4].trim()
            if (content.isNotEmpty()) out.putIfAbsent(content, "")
        }
        return out
    }

    /**
     * 把「标准 LRC + 翻译 LRC」按时间戳对齐合并。
     * 前端拿到 rich 后若某行没有 tr，会调用自己的 mergeTranslation；这里提前做一次，
     * 保证顺序与时间轴严格一致（前端按数组下标对，一旦有条目缺失就整体错位）。
     */
    fun mergeTranslation(rich: JSONArray, translatedLrc: String): JSONArray {
        if (translatedLrc.isBlank() || rich.length() == 0) return rich
        val trByTime = HashMap<Long, String>()
        for (line in translatedLrc.lineSequence()) {
            val tag = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]").find(line.trim()) ?: continue
            val ms = parseLrcTime(tag.value)
            val content = line.trim().substring(tag.range.last + 1).trim()
            if (content.isNotEmpty()) trByTime.putIfAbsent(ms, content)
        }
        if (trByTime.isEmpty()) return rich
        val times = trByTime.keys.sorted()
        for (i in 0 until rich.length()) {
            val o = rich.optJSONObject(i) ?: continue
            if (o.optString("tr").isNotEmpty()) continue
            val ms = o.optLong("ms")
            // 找最近的翻译时间戳（相差 1.5 秒内算同一行）
            var best: Long? = null
            for (t in times) {
                if (best == null || kotlin.math.abs(t - ms) < kotlin.math.abs(best - ms)) best = t
                if (t > ms + 1500) break
            }
            val bt = best
            if (bt != null && kotlin.math.abs(bt - ms) <= 1500) o.put("tr", trByTime[bt] ?: "")
        }
        return rich
    }
}
