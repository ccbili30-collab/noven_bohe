package novex.core.tavern

import org.json.JSONArray
import org.json.JSONObject

/**
 * 前端转译器：酒馆 extensions.regex_scripts → novex-def 模块正文。
 *
 * 酒馆的做法是「模型发占位标记 → 显示正则把标记整段换成 HTML 页」。转译
 * 后语义不变但落进我们的协议：标记写进 def meta 的 marker 字段，显示期
 * 由 novex 管线就地展开为 novex-use 投影（NovexArtifacts.expandNovexMarkers）。
 *
 * 远程引导（$('body').load(cdn)/外链页面）在导入期抓取并内联——运行时
 * 沙箱断网不变，离线可玩；抓不到记 notes，不阻塞导入。
 * 装饰类替换（改气泡颜色/剥标记）不转译，逐条记 notes。
 */
object TavernFrontendTranslator {

    /** 一个前端件模块：标题 + 含 ```novex-def 围栏的正文。 */
    data class Module(val title: String, val defFenceText: String)

    data class Outcome(val modules: List<Module>, val notes: List<String>)

    /**
     * @param fetchRemote 导入期联网抓取（URL → 字节）。注入点：默认 null
     *   表示离线导入——远程引导项全部记 notes 跳过；UI 层可传真实实现。
     */
    fun translate(scripts: JSONArray?, fetchRemote: (String) -> ByteArray? = { null }): Outcome {
        if (scripts == null) return Outcome(emptyList(), emptyList())
        // 「对AI隐藏X」伴生脚本：同 findRegex、空替换——酒馆里这类标记由框架
        // 每回合自动注入消息、再对模型隐藏。对应我们 def meta 的 autoMarker：
        // 显示期补插标记再展开，模型无需也看不到占位符。
        val hiddenMarkers = (0 until scripts.length()).mapNotNull { i ->
            scripts.optJSONObject(i)
                ?.takeIf { it.optString("replaceString").isBlank() }
                ?.let { markerLiteral(it.optString("findRegex")) }
        }.toSet()
        val modules = mutableListOf<Module>()
        val notes = mutableListOf<String>()
        var index = 0
        for (i in 0 until scripts.length()) {
            val s = scripts.optJSONObject(i) ?: continue
            val name = s.optString("scriptName").ifBlank { "前端 ${++index}" }
            val replace = s.optString("replaceString")
            val find = s.optString("findRegex")
            when (TavernCardAnalyzer.classifyRegex(find, replace)) {
                TavernCardAnalyzer.RegexFate.MARKER_FRONTEND -> {
                    val html = localizeRemoteRefs(replace.trim().trim('`').trim(), fetchRemote, notes, name)
                    val marker = markerLiteral(find)
                    modules += Module(name, defFence(name, html, marker, autoMarker = marker in hiddenMarkers, index = ++index))
                    if (marker != null && marker in hiddenMarkers) notes += "$name：标记每回合自动注入（伴生隐藏脚本），已标 autoMarker"
                }
                TavernCardAnalyzer.RegexFate.REMOTE_LOADER -> {
                    val url = remoteLoadUrl(replace)
                    val bytes = url?.let { u -> runCatching { fetchRemote(u) }.getOrNull() }
                    if (bytes != null) {
                        val html = localizeRemoteRefs(String(bytes, Charsets.UTF_8), fetchRemote, notes, name)
                        val marker = markerLiteral(find)
                        modules += Module(name, defFence(name, html, marker, autoMarker = marker in hiddenMarkers, index = ++index))
                        notes += "$name：远程页面已抓取内联（${bytes.size / 1024}KB）"
                    } else {
                        notes += "$name：远程页面${url?.let { "（$it）" } ?: ""}未能抓取，未转译——联网导入可补齐"
                    }
                }
                TavernCardAnalyzer.RegexFate.STRIPPER ->
                    notes += "$name：剥除输出标记（语义由状态管线承担），不转译"
                TavernCardAnalyzer.RegexFate.COSMETIC ->
                    notes += "$name：装饰性替换，不转译"
                TavernCardAnalyzer.RegexFate.UNKNOWN ->
                    notes += "$name：替换形态未识别，不转译"
            }
        }
        return Outcome(modules, notes)
    }

    /** def 围栏正文：meta 首行 + 模板。id 从名字生成稳定的 slug。 */
    private fun defFence(name: String, html: String, marker: String?, autoMarker: Boolean, index: Int): String {
        val meta = JSONObject()
            .put("id", "tavern-${slugify(name).ifBlank { "page-$index" }}")
            .put("kind", "html")
            .put("name", name)
            .put("compat", "tavern")
        if (marker != null) meta.put("marker", marker)
        if (autoMarker && marker != null) meta.put("autoMarker", true)
        return "```novex-def\n$meta\n$html\n```"
    }

    /** find 串 → 可显示期命中的字面量标记；含正则元字符的取不到 → null（件仍可经 novex-use 调用）。 */
    internal fun markerLiteral(find: String): String? {
        var f = find.trim()
        if (f.isEmpty() || f.length > 80) return null
        Regex("^/(.+)/[a-zA-Z]*$", RegexOption.DOT_MATCHES_ALL).matchEntire(f)
            ?.let { f = it.groupValues[1] }
        val literal = f.replace(Regex("""\\(.)""")) { it.groupValues[1] }
        return literal.takeIf { it.isNotBlank() && it.none { c -> c in ".^$*+?()[]{}|" } }
    }

    /** `.load('http…')`/首个 src|href 远程引用 → URL。 */
    private fun remoteLoadUrl(replace: String): String? =
        Regex("""\.load\(\s*['"](https?://[^'"]+)['"]""").find(replace)?.groupValues?.get(1)
            ?: Regex("""(?:src|href)\s*=\s*['"](https?://[^'"]+)['"]""").find(replace)?.groupValues?.get(1)

    /**
     * 远程引用导入期本地化（运行时沙箱断网不变）：
     * - jquery/lodash 的 CDN script → 剥除（岛按 compat 注入 vendor 副本，重复会冲突）
     * - 其余 script src → 抓回内联为 <script> 正文；失败剥除
     * - 字体/图标 CDN 样式（googleapis/font-awesome/material symbols）→ 剥除降级
     * - 其余 stylesheet link → 抓回改为 <style>；失败剥除
     * - img 等 src → data: URI 内联（单资源 1.5MB、整页 4MB 封顶）
     * - video/audio/source → 保留远程引用并记 notes（离线不可播，不耗内联预算）
     */
    private fun localizeRemoteRefs(html: String, fetchRemote: (String) -> ByteArray?, notes: MutableList<String>, name: String): String {
        var out = html
        var fetchedTotal = 0

        fun fetch(url: String): ByteArray? = runCatching { fetchRemote(url) }.getOrNull()

        // script src：vendored 库剥除，其余抓回内联正文
        out = SCRIPT_TAG_REGEX.replace(out) { m ->
            val url = m.groupValues[1]
            when {
                VENDORED_LIB_REGEX.containsMatchIn(url) -> {
                    notes += "$name：vendored 库引用已剥除（岛注入 $url）"
                    ""
                }
                else -> {
                    val body = fetch(url)?.takeIf { it.size <= MAX_INLINE_ONE }
                    if (body != null) {
                        fetchedTotal += body.size
                        notes += "$name：脚本已内联（${body.size / 1024}KB）"
                        "<script>\n${String(body, Charsets.UTF_8)}\n</script>"
                    } else {
                        notes += "$name：脚本 $url 未能抓取，已剥除（相关功能缺失）"
                        ""
                    }
                }
            }
        }

        // stylesheet link：字体/图标 CDN 剥除降级，其余抓回为 <style>
        out = LINK_TAG_REGEX.replace(out) { m ->
            val tag = m.value
            val url = m.groupValues[1]
            when {
                !tag.contains("stylesheet") -> tag
                FONT_CDN_REGEX.containsMatchIn(url) -> {
                    notes += "$name：字体/图标引用已剥除（沙箱断网，降级系统字体）"
                    ""
                }
                else -> {
                    val css = fetch(url)?.takeIf { it.size <= MAX_INLINE_ONE }
                    if (css != null) {
                        fetchedTotal += css.size
                        "<style>\n${String(css, Charsets.UTF_8)}\n</style>"
                    } else {
                        notes += "$name：样式表 $url 未能抓取，已剥除"
                        ""
                    }
                }
            }
        }

        // 图片 src → data: URI；媒体源保留远程引用
        out = URL_ATTR_REGEX.replace(out) { m ->
            val attr = m.groupValues[1]
            val url = m.groupValues[2]
            if (fetchedTotal >= MAX_INLINE_TOTAL) {
                notes += "$name：内联预算用尽，$url 保留远程引用（离线缺图）"
                return@replace m.value
            }
            val mime = IMAGE_MIMES[url.substringBefore('?').substringAfterLast('.', "").lowercase()]
            if (mime == null) {
                notes += "$name：媒体源 $url 保留远程引用（离线不可播）"
                return@replace m.value
            }
            val bytes = fetch(url)
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_INLINE_ONE) {
                notes += "$name：素材 $url ${if (bytes != null) "超过内联上限" else "未能抓取"}，保留远程引用（离线缺图）"
                return@replace m.value
            }
            fetchedTotal += bytes.size
            "$attr=\"data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}\""
        }
        return out
    }

    private fun slugify(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9一-鿿]+"), "-").trim('-').take(40)

    private val URL_ATTR_REGEX = Regex("""(src|href)\s*=\s*["'](https?://[^"']+)["']""")
    private val SCRIPT_TAG_REGEX = Regex("""<script[^>]*\bsrc\s*=\s*["'](https?://[^"']+)["'][^>]*>\s*</script>""", RegexOption.IGNORE_CASE)
    private val LINK_TAG_REGEX = Regex("""<link[^>]*\bhref\s*=\s*["'](https?://[^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val VENDORED_LIB_REGEX = Regex("""jquery|lodash""", RegexOption.IGNORE_CASE)
    private val FONT_CDN_REGEX = Regex("""fonts\.googleapis|fonts\.gstatic|font-awesome|fontawesome|material.icons|material.symbols|cdnjs.*\.css""", RegexOption.IGNORE_CASE)
    private val IMAGE_MIMES = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
        "avif" to "image/avif", "ico" to "image/x-icon",
    )
    private const val MAX_INLINE_ONE = 1_500_000
    private const val MAX_INLINE_TOTAL = 4_000_000
}
