package novex.core.tavern

import org.json.JSONArray
import org.json.JSONObject

/**
 * 酒馆卡导入转译器 · 第 0 层：资产盘点与转译报告。
 *
 * 本包是唯一"方言区"——酒馆 JSON 进，novex 卡包出。运行时不认识
 * 酒馆：世界书条目、正则脚本、helper 脚本、前端页面在这里被分类
 * 为「可转译 / 需降级 / 不执行」，转译本身由后续层完成。
 *
 * [analyze] 只做静态盘点，不联网、不执行任何外部内容。
 */
object TavernCardAnalyzer {

    /** 一条世界书条目在当前管线下的归宿分类。 */
    enum class EntryFate {
        /** 现有子集执行器可注入（enabled、无正则/次关键词/宏/修饰符）。 */
        USABLE,
        /** 需要转译层处理：正则关键词、次关键词、宏、修饰符、概率等。 */
        NEEDS_TRANSLATION,
        /** 作者在卡里已禁用。 */
        DISABLED,
        /** 分隔标记/纯排版条目（如 ====分区====）。 */
        MARKER,
    }

    data class WorldbookInventory(
        val total: Int,
        val usable: Int,
        val needsTranslation: Int,
        val disabled: Int,
        val markers: Int,
        /** 触发「需转译」的原因出现次数（供报告展开）。 */
        val reasonCounts: Map<String, Int>,
    )

    /** 一条美化/替换正则的分类。 */
    enum class RegexFate {
        /** 占位符 → 整页 HTML：转译为卡内前端件（novex-def 模块）。 */
        MARKER_FRONTEND,
        /** 小体量替换：气泡装饰/文案替换，丢弃。 */
        COSMETIC,
        /** replaceString 为空：剥除协议残留（如 <UpdateVariable>），保留语义由状态层负责。 */
        STRIPPER,
        /** 远程加载引导（$('body').load/cdn）：导入时抓取内联，抓不到记报告。 */
        REMOTE_LOADER,
        /** 无法归类。 */
        UNKNOWN,
    }

    data class RegexItem(val name: String, val fate: RegexFate, val note: String)

    /** 一张酒馆卡的全量资产盘点。 */
    data class Inventory(
        val cardName: String,
        val spec: String,
        val worldbook: WorldbookInventory?,
        val regexItems: List<RegexItem>,
        val helperScriptNames: List<String>,
        val remoteAssetUrls: List<String>,
        val hasDepthPrompt: Boolean,
        val alternateGreetingCount: Int,
    )

    /** 面向用户的转译报告：每项资产的去向一句话。 */
    data class Report(val lines: List<String>) {
        fun render(): String = lines.joinToString("\n")
    }

    fun analyze(sourceJson: String): Inventory? = runCatching {
        val root = JSONObject(sourceJson)
        val spec = root.optString("spec").ifBlank { if (root.has("data")) "chara_card_v2" else "" }
        // v1 无 spec 字段：用 name+first_mes 识别，避免任意 JSON 被误收。
        val looksV1 = spec.isEmpty() && root.has("name") && root.has("first_mes")
        if (spec !in setOf("chara_card_v2", "chara_card_v3") && !looksV1) return null
        val data = root.optJSONObject("data") ?: root
        Inventory(
            cardName = data.optString("name").ifBlank { root.optString("name") },
            spec = spec.ifBlank { "chara_card_v1" },
            worldbook = analyzeBook(data.optJSONObject("character_book")),
            regexItems = analyzeRegexScripts(data.optJSONObject("extensions")?.optJSONArray("regex_scripts")),
            helperScriptNames = analyzeHelperScripts(data.optJSONObject("extensions")?.optJSONObject("tavern_helper")),
            remoteAssetUrls = collectRemoteUrls(data.optJSONObject("extensions")?.optJSONArray("regex_scripts")),
            hasDepthPrompt = data.optJSONObject("extensions")?.optJSONObject("depth_prompt") != null,
            alternateGreetingCount = data.optJSONArray("alternate_greetings")?.length() ?: 0,
        )
    }.getOrNull()

    fun report(inventory: Inventory): Report {
        val lines = mutableListOf<String>()
        inventory.worldbook?.let { wb ->
            lines += "世界书 ${wb.total} 条：" + buildList {
                if (wb.usable > 0) add("${wb.usable} 条可直接注入")
                if (wb.needsTranslation > 0) add("${wb.needsTranslation} 条经转译层处理（正则/次关键词/宏）")
                if (wb.disabled > 0) add("${wb.disabled} 条原卡已禁用")
                if (wb.markers > 0) add("${wb.markers} 条分区标记不入库")
            }.joinToString("，")
        }
        val frontends = inventory.regexItems.filter { it.fate == RegexFate.MARKER_FRONTEND }
        val remotes = inventory.regexItems.filter { it.fate == RegexFate.REMOTE_LOADER }
        if (frontends.isNotEmpty()) {
            lines += "内嵌前端 ${frontends.size} 个（${frontends.joinToString("、") { it.name }}）：转译为卡内前端件"
        }
        if (remotes.isNotEmpty()) {
            lines += "远程前端 ${remotes.size} 个：导入时联网抓取内联；抓取失败则该项不执行"
        }
        val cosmetic = inventory.regexItems.count { it.fate == RegexFate.COSMETIC }
        val strippers = inventory.regexItems.count { it.fate == RegexFate.STRIPPER }
        if (cosmetic + strippers > 0) {
            lines += "装饰/清理正则 ${cosmetic + strippers} 条：不转译（语义由本体管线承担）"
        }
        if (inventory.helperScriptNames.isNotEmpty()) {
            lines += "酒馆助手脚本 ${inventory.helperScriptNames.size} 个（${inventory.helperScriptNames.take(6).joinToString("、")}" +
                (if (inventory.helperScriptNames.size > 6) "…" else "") + "）：不执行，变量类逻辑由状态层承接"
        }
        if (inventory.remoteAssetUrls.isNotEmpty()) {
            lines += "远程素材 ${inventory.remoteAssetUrls.size} 处：导入时尝试本地化，失败按缺失资源处理"
        }
        if (inventory.hasDepthPrompt) lines += "深度提示：映射为回复后置要求"
        if (inventory.alternateGreetingCount > 0) lines += "备选开场白 ${inventory.alternateGreetingCount} 条"
        return Report(lines)
    }

    // ─── 内部分析 ────────────────────────────────────────────────────

    private fun analyzeBook(book: JSONObject?): WorldbookInventory? {
        val entries = book?.optJSONArray("entries") ?: return null
        var usable = 0; var needs = 0; var disabled = 0; var markers = 0
        val reasons = mutableMapOf<String, Int>()
        fun bump(reason: String) { reasons[reason] = (reasons[reason] ?: 0) + 1 }
        for (i in 0 until entries.length()) {
            val row = entries.optJSONObject(i) ?: continue
            if (row.opt("enabled") == false) { disabled++; continue }
            val title = row.optString("name").ifBlank { row.optString("comment") }
            if (title.matches(Regex("^={2,}.*={2,}.*")) && row.optString("content").isBlank()) {
                markers++; continue
            }
            val ext = row.optJSONObject("extensions") ?: JSONObject()
            var complex = false
            if (row.optBoolean("use_regex", false)) { bump("正则关键词"); complex = true }
            if (row.optBoolean("selective", false) || row.optJSONArray("secondary_keys")?.length() ?: 0 > 0) {
                bump("次关键词"); complex = true
            }
            val content = row.optString("content")
            if (content.contains("{{") || content.trimStart().startsWith("@@")) {
                bump("宏/修饰符"); complex = true
            }
            val prob = ext.opt("probability")
            if (prob is Number && prob.toDouble() != 100.0) { bump("概率"); complex = true }
            for (k in listOf("sticky", "cooldown", "delay", "delay_until_recursion", "selectiveLogic")) {
                val v = ext.opt(k)
                if (v is Number && v.toDouble() != 0.0) { bump("计时/黏性"); complex = true; break }
            }
            val pos = when {
                ext.has("position") -> ext.optInt("position", 1)
                row.has("position") -> (row.opt("position") as? Number)?.toInt() ?: 1
                else -> 1
            }
            if (pos !in 0..1) { bump("插入位置"); complex = true }
            if (complex) needs++ else usable++
        }
        return WorldbookInventory(entries.length(), usable, needs, disabled, markers, reasons)
    }

    /** 单条替换正则的分类——盘点与转译器共用同一判定，避免两处漂移。
     *  「远程引导」= 替换体是空壳：除 script/link 标签外无可见内容、无
     *  结构性元素，整体只为拉取远程页面服务（如 <body><script>.load()）。
     *  整页里的远程素材引用（img/字体/CDN 脚本）不算引导——那是内嵌页的
     *  本地化问题，由导入期内联处理。 */
    internal fun classifyRegex(find: String, replace: String): RegexFate = when {
        replace.isBlank() -> RegexFate.STRIPPER
        isRemoteLoaderStub(replace) -> RegexFate.REMOTE_LOADER
        isWholePageHtml(replace) && isMarkerFind(find) -> RegexFate.MARKER_FRONTEND
        replace.length < 20_000 -> RegexFate.COSMETIC
        else -> RegexFate.UNKNOWN
    }

    /** 空壳判定：小体量、剥掉围栏/标签/script 后无可见文本、无内容元素，且含远程引用。 */
    private fun isRemoteLoaderStub(replace: String): Boolean {
        if (replace.length >= 2_000) return false
        val stripped = replace.trim().trim('`').trim()
            .replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("""<[^>]+>"""), "")
            .trim()
        if (stripped.isNotEmpty()) return false
        if (CONTENT_TAG_REGEX.containsMatchIn(replace)) return false
        return REMOTE_LOAD_REGEX.containsMatchIn(replace)
    }

    private fun analyzeRegexScripts(scripts: JSONArray?): List<RegexItem> {
        if (scripts == null) return emptyList()
        return buildList {
            for (i in 0 until scripts.length()) {
                val s = scripts.optJSONObject(i) ?: continue
                val name = s.optString("scriptName").ifBlank { "正则 $i" }
                val replace = s.optString("replaceString")
                val find = s.optString("findRegex")
                val fate = classifyRegex(find, replace)
                val note = when (fate) {
                    RegexFate.MARKER_FRONTEND -> "占位符「${find.trim()}」→ 内嵌页面 ${replace.length / 1024}KB"
                    RegexFate.REMOTE_LOADER -> "运行时从 CDN 拉取页面"
                    RegexFate.STRIPPER -> "剥除输出标记"
                    RegexFate.COSMETIC -> "小体量装饰替换"
                    RegexFate.UNKNOWN -> "大体量替换未归类"
                }
                add(RegexItem(name, fate, note))
            }
        }
    }

    private fun analyzeHelperScripts(helper: JSONObject?): List<String> {
        val scripts = helper?.optJSONArray("scripts") ?: return emptyList()
        return (0 until scripts.length()).mapNotNull { i ->
            scripts.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }

    /** 收集前端替换串里引用的远程 URL（图片/字体/CDN 脚本）。 */
    private fun collectRemoteUrls(scripts: JSONArray?): List<String> {
        if (scripts == null) return emptyList()
        val urls = LinkedHashSet<String>()
        for (i in 0 until scripts.length()) {
            val replace = scripts.optJSONObject(i)?.optString("replaceString") ?: continue
            URL_REGEX.findAll(replace).forEach { urls += it.value }
        }
        return urls.toList()
    }

    /** find 串是「占位标记」而非正文匹配：整段是一个字面量标签/短词。 */
    private fun isMarkerFind(find: String): Boolean {
        val f = find.trim()
        if (f.length > 60) return false
        // 形如 <XxxPlaceHolder/>、【开场白】、[STATUS] 这类单一标记
        return f.matches(Regex("""^<[^>]+/?>$|^【[^】]+】$|^\[[^\]]+\]$|^[A-Za-z_]+$"""))
    }

    private fun isWholePageHtml(replace: String): Boolean {
        val t = replace.trim().trim('`').trim()
        return t.contains("<html", ignoreCase = true) ||
            (t.startsWith("<!doctype", ignoreCase = true) || t.startsWith("<body", ignoreCase = true) || t.startsWith("<div", ignoreCase = true))
    }

    private val REMOTE_LOAD_REGEX = Regex("""\.load\(\s*['"]https?://|src\s*=\s*["']https?://|href\s*=\s*["']https?://""")
    private val CONTENT_TAG_REGEX = Regex("""<(img|video|audio|source|div|span|p|table|section|article|iframe|canvas|svg)\b""", RegexOption.IGNORE_CASE)
    private val URL_REGEX = Regex("""https?://[^\s"'<>`\\)]+""")
}
