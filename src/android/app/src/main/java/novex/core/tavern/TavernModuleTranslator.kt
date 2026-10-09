package novex.core.tavern

import org.json.JSONArray
import org.json.JSONObject

/**
 * 整卡转译器：酒馆角色卡 JSON → 新卡体系的模块规格（中立法案——不依赖
 * novex.content 类型，由导入边界组装成 ContentModule）。
 *
 * 世界书条目复用 [TavernBookTranslator] 的规范化产物（正则/次关键词/
 * 宏已归一），再映射为模块触发规则——两条链路同一套转译语义：
 *   constant        → Always（每轮注入）
 *   keys 字面量      → Keywords（任一命中；selective → 全部命中才注入）
 *   keys 正则        → 纯 `a|b` 字面量交替可展开为词表；否则降级 Manual
 *   无键非常驻      → Manual（作者意图是条件条目，不无条件注入）
 *   禁用/分区标记    → 不产出模块，记报告
 */
object TavernModuleTranslator {

    /** 模块触发规则（中立规格，对应 ModuleUse 的三态）。 */
    sealed interface UseSpec {
        data object Always : UseSpec
        data object Manual : UseSpec
        data class Keywords(val words: List<String>, val caseSensitive: Boolean, val requireAll: Boolean) : UseSpec
    }

    /** 一个待组装模块：标题 + 正文 + 触发规则 + 卡级标签。 */
    data class ModuleSpec(
        val title: String,
        val text: String,
        val use: UseSpec? = null,
        val tags: List<String> = emptyList(),
    )

    data class Outcome(
        val cardName: String,
        val summary: String,
        /** 七字段模块（含 depth_prompt 并入回复后置要求）。 */
        val fieldModules: List<ModuleSpec>,
        /** 世界书条目 → 触发模块。 */
        val bookModules: List<ModuleSpec>,
        /** 前端件模块（novex-frontend 标签，正文为 novex-def 围栏）。 */
        val frontendModules: List<ModuleSpec>,
        /** 转译报告逐条——未转译/降级项的诚实清单。 */
        val report: List<String>,
        /** 页面内收集到的远程素材引用——导入后由收割器抓回本地化。 */
        val remoteRefs: List<String> = emptyList(),
    )

    private val FIELDS = linkedMapOf(
        "description" to "人物背景",
        "personality" to "人格与表达",
        "scenario" to "扮演场景",
        "first_mes" to "开场白",
        "mes_example" to "示例对话",
        "system_prompt" to "角色专属指令",
        "post_history_instructions" to "回复后置要求",
    )

    /** 识别「这是酒馆卡」的轻量判定——非卡 JSON 一律返回 null，走原文导入。 */
    fun isTavernCardJson(text: String): Boolean = runCatching {
        val root = JSONObject(text)
        val spec = root.optString("spec")
        val data = root.optJSONObject("data")
        spec in setOf("chara_card_v2", "chara_card_v3") ||
            (data != null && data.has("name") && (data.has("first_mes") || data.has("character_book"))) ||
            (spec.isEmpty() && root.has("name") && root.has("first_mes"))
    }.getOrDefault(false)

    fun translate(sourceJson: String, fetchRemote: (String) -> ByteArray? = { null }): Outcome {
        val root = JSONObject(sourceJson)
        val data = root.optJSONObject("data") ?: root
        val name = data.optString("name").ifBlank { root.optString("name") }.ifBlank { "导入角色" }
        val report = mutableListOf<String>()

        val fieldModules = FIELDS.map { (field, label) ->
            var text = data.optString(field)
            if (field == "post_history_instructions") {
                val depth = data.optJSONObject("extensions")?.optJSONObject("depth_prompt")
                    ?.optString("prompt")?.takeIf { it.isNotBlank() }
                if (depth != null) text = text + "\n\n" + depth
            }
            // 字段模块是无条件资料（开局/常备），不设 use——DEFAULT 路由。
            ModuleSpec(label, text)
        }
        data.optJSONObject("extensions")?.optJSONObject("depth_prompt")
            ?.optString("prompt")?.takeIf { it.isNotBlank() }
            ?.let { report += "深度提示已并入「回复后置要求」" }

        val bookModules = mutableListOf<ModuleSpec>()
        data.optJSONObject("character_book")?.let { book ->
            val outcome = TavernBookTranslator.translate(book, name)
            val entries = JSONObject(outcome.snapshotJson).optJSONArray("entries") ?: JSONArray()
            var regexFallbacks = 0
            for (i in 0 until entries.length()) {
                val row = entries.optJSONObject(i) ?: continue
                val title = row.optString("title").ifBlank { "条目 ${i + 1}" }
                val keys = stringList(row.optJSONArray("keys"))
                val secondary = stringList(row.optJSONArray("secondaryKeys"))
                val sensitive = row.optBoolean("caseSensitive", false)
                val use = when {
                    row.optBoolean("constant", false) -> UseSpec.Always
                    row.optBoolean("keysRegex", false) -> {
                        // 纯字面量交替（a|b|c）可展开为词表；其余形态无法
                        // 用关键词表达——降级 Manual，靠 read_card 按需读。
                        val expanded = keys.mapNotNull(::regexAlternationLiterals)
                        if (expanded.size == keys.size && expanded.isNotEmpty())
                            UseSpec.Keywords(expanded.flatten().distinct() + secondary, sensitive, secondary.isNotEmpty())
                        else { regexFallbacks++; UseSpec.Manual }
                    }
                    keys.isEmpty() -> UseSpec.Manual
                    // selective：主键∧次键——Keywords requireAll 是全体 AND，
                    // 单主键+单次键时语义精确，多键时偏严（宁少注入不过载）。
                    secondary.isNotEmpty() -> UseSpec.Keywords(keys + secondary, sensitive, requireAll = true)
                    else -> UseSpec.Keywords(keys, sensitive, requireAll = false)
                }
                bookModules += ModuleSpec(title, row.optString("content"), use)
            }
            if (outcome.translated > 0) report += "世界书 ${outcome.translated} 条已转译为触发模块"
            outcome.omitted.forEach { report += "世界书略过：$it" }
            outcome.approximations.forEach { report += "近似：$it" }
            if (regexFallbacks > 0) report += "$regexFallbacks 条正则关键词条目降级为按需读取（关键词无法表达正则）"
        }

        val frontendOutcome = TavernFrontendTranslator.translate(
            data.optJSONObject("extensions")?.optJSONArray("regex_scripts"), fetchRemote)
        val frontendModules = frontendOutcome.modules.map {
            ModuleSpec(it.title, it.defFenceText, tags = listOf("novex-frontend"))
        }
        frontendOutcome.notes.forEach { report += it }

        val helpers = data.optJSONObject("extensions")?.optJSONObject("tavern_helper")
            ?.optJSONArray("scripts")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            }.orEmpty()
        if (helpers.isNotEmpty()) {
            report += "酒馆助手脚本 ${helpers.size} 个（${helpers.take(6).joinToString("、")}" +
                (if (helpers.size > 6) "…" else "") + "）不执行；变量类逻辑由前端件读 NOVEN_FRAME.state 承接"
        }
        val greetings = data.optJSONArray("alternate_greetings")?.length() ?: 0
        if (greetings > 0) report += "备选开场白 $greetings 条未展开（保留在原件归档）"

        if (frontendOutcome.remoteRefs.isNotEmpty())
            report += "页面引用远程素材 ${frontendOutcome.remoteRefs.size} 项，导入后收割为本地资源"
        return Outcome(name, data.optString("description").take(200),
            fieldModules, bookModules, frontendModules, report, frontendOutcome.remoteRefs)
    }

    /** `a|b|c` 纯字面量交替 → 词表；含其他正则元字符 → null。 */
    private fun regexAlternationLiterals(pattern: String): List<String>? {
        if (pattern.any { it in ".^$*+?()[]{}" } || '\\' in pattern) return null
        val parts = pattern.split('|').map { it.trim() }
        return parts.takeIf { it.isNotEmpty() && it.all(String::isNotBlank) }
    }

    private fun stringList(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
}
