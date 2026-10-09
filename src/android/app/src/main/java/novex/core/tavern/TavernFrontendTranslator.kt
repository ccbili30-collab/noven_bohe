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
 * 远程引用转译期一律不联网：保留原文并记入 Outcome.remoteRefs，由导入后
 * 的 TavernAssetHarvester 抓回存为卡资源、改写为 novex-asset:// 本地引用；
 * 远程引导页（$('body').load(cdn)）离线时输出带 remotePage meta 的占位
 * def，收割期回填正文。沙箱断网不变，收割后的卡离线可玩。
 * 装饰类替换（改气泡颜色/剥标记）不转译，逐条记 notes。
 */
object TavernFrontendTranslator {

    /** 一个前端件模块：标题 + 含 ```novex-def 围栏的正文。 */
    data class Module(val title: String, val defFenceText: String)

    /** 收割素材清单条目：url + 形态提示（page 为远程引导整页）。 */
    data class Outcome(val modules: List<Module>, val notes: List<String>, val remoteRefs: List<String> = emptyList())

    /**
     * @param fetchRemote 仅用于 REMOTE_LOADER 整页抓取（页本身是内容，不是素材）。
     *   缺省 null 时 loader 输出带 remotePage meta 的占位 def，素材收割期补正文；
     *   其余远程引用一律保留原文并记入 remoteRefs，由 TavernAssetHarvester 收割。
     */
    fun translate(scripts: JSONArray?, fetchRemote: (String) -> ByteArray? = { null }): Outcome {
        if (scripts == null) return Outcome(emptyList(), emptyList())
        val refs = linkedSetOf<String>()
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
                    val html = localizeRemoteRefs(replace.trim().trim('`').trim(), notes, name, refs)
                    val marker = markerLiteral(find)
                    modules += Module(name, defFence(name, html, marker, autoMarker = marker in hiddenMarkers, index = ++index))
                    if (marker != null && marker in hiddenMarkers) notes += "$name：标记每回合自动注入（伴生隐藏脚本），已标 autoMarker"
                }
                TavernCardAnalyzer.RegexFate.REMOTE_LOADER -> {
                    val url = remoteLoadUrl(replace)
                    val bytes = url?.let { u -> runCatching { fetchRemote(u) }.getOrNull() }
                    if (bytes != null) {
                        val html = localizeRemoteRefs(String(bytes, Charsets.UTF_8), notes, name, refs)
                        val marker = markerLiteral(find)
                        modules += Module(name, defFence(name, html, marker, autoMarker = marker in hiddenMarkers, index = ++index))
                        notes += "$name：远程页面已抓取内联（${bytes.size / 1024}KB）"
                    } else if (url != null) {
                        // 离线转译：页本身也交给收割——占位 def 带 remotePage，
                        // 收割期抓回正文回填；抓不到则页面持续留占位文案。
                        refs += url
                        val marker = markerLiteral(find)
                        modules += Module(name, defFence(name, "<div>页面素材收集中…</div>", marker,
                            autoMarker = marker in hiddenMarkers, index = ++index, remotePage = url))
                        notes += "$name：远程页面（$url）待素材收割补齐"
                    } else {
                        notes += "$name：远程引导形态未识别，不转译"
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
        return Outcome(modules, notes, refs.toList())
    }

    /** def 围栏正文：meta 首行 + 模板。id 从名字生成稳定的 slug。 */
    private fun defFence(name: String, html: String, marker: String?, autoMarker: Boolean, index: Int, remotePage: String? = null): String {
        val meta = JSONObject()
            .put("id", "tavern-${slugify(name).ifBlank { "page-$index" }}")
            .put("kind", "html")
            .put("name", name)
            .put("compat", "tavern")
        if (marker != null) meta.put("marker", marker)
        if (autoMarker && marker != null) meta.put("autoMarker", true)
        if (remotePage != null) meta.put("remotePage", remotePage)
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
     * 远程引用收集（转译期零网络，预览秒开）：
     * - jquery/lodash 的 CDN script → 剥除（岛按 compat 注入 vendor 副本，重复会冲突）
     * - 字体/图标 CDN 样式（googleapis/font-awesome/material symbols）→ 剥除降级
     * - 其余 script src / stylesheet link / src 引用 → **保留原文**并记入 refs，
     *   由导入后的 TavernAssetHarvester 统一抓回、改写为 novex-asset:// 本地资源
     */
    private fun localizeRemoteRefs(html: String, notes: MutableList<String>, name: String, refs: MutableSet<String>): String {
        var out = html
        out = SCRIPT_TAG_REGEX.replace(out) { m ->
            val url = m.groupValues[1]
            if (VENDORED_LIB_REGEX.containsMatchIn(url)) {
                notes += "$name：vendored 库引用已剥除（岛注入 $url）"
                ""
            } else {
                refs += url
                m.value
            }
        }
        out = LINK_TAG_REGEX.replace(out) { m ->
            val tag = m.value
            val url = m.groupValues[1]
            when {
                !tag.contains("stylesheet") -> tag
                FONT_CDN_REGEX.containsMatchIn(url) -> {
                    notes += "$name：字体/图标引用已剥除（沙箱断网，降级系统字体）"
                    ""
                }
                else -> { refs += url; tag }
            }
        }
        // img/video/audio/source 的 src 一律收集；href 的 <a> 跳转目标不是素材，不收集
        out = URL_ATTR_REGEX.replace(out) { m ->
            if (m.groupValues[1] == "src") refs += m.groupValues[2]
            m.value
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
}
