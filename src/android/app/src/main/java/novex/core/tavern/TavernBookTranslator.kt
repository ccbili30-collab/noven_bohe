package novex.core.tavern

import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书转译器：酒馆 character_book → 规范化 v2 快照（我们的协议）。
 *
 * 转译在采用快照时发生——产物里不再有酒馆字段名：正则关键词、次
 * 关键词、宏、修饰符全部映射为 v2 schema 的显式字段。运行时只求值
 * v2（以及历史会话里的 v1 旧快照），不接触酒馆 schema。
 *
 * 不能无损映射的语义降级或丢弃，逐条记进 approximations/omitted，
 * 供注入诊断与导入报告使用——静默吞掉配置比报错更糟。
 */
object TavernBookTranslator {

    /** 宏白名单之外的 {{...}} 一律剥除并登记。 */
    private val MACRO_REGEX = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")

    /** /pattern/flags 形式的正则关键词（use_regex 条目里常见写法）。 */
    private val SLASHED_REGEX = Regex("""^/(.+)/[a-z]*$""")

    data class Outcome(val snapshotJson: String, val translated: Int, val omitted: List<String>, val approximations: List<String>)

    /**
     * @param charName 卡显示名——`{{char}}` 在转译期展开为它；`{{user}}`
     *   展开为"你"（玩家身份此时不可知）；`{{time}}/{{date}}` 保留为令牌，
     *   由求值期展开为当下时间。
     */
    fun translate(book: JSONObject, charName: String): Outcome {
        val entries = book.optJSONArray("entries") ?: JSONArray()
        val out = JSONArray()
        val omitted = mutableListOf<String>()
        val approximations = mutableListOf<String>()
        var translated = 0

        for (i in 0 until entries.length()) {
            val row = entries.optJSONObject(i) ?: continue
            val title = row.optString("name").ifBlank { row.optString("comment") }.ifBlank { "条目 ${i + 1}" }
            if (row.opt("enabled") == false) {
                omitted += "$title：原卡已禁用"
                continue
            }
            if (title.matches(Regex("^={2,}.*={2,}.*")) && row.optString("content").isBlank()) {
                omitted += "$title：分区标记不入库"
                continue
            }
            val ext = row.optJSONObject("extensions") ?: JSONObject()
            val notes = mutableListOf<String>()

            // 计时/黏性/分组/自动化：触发语义超出关键词范围，降级为普通条目。
            for (k in listOf("sticky", "cooldown", "delay", "delay_until_recursion", "selectiveLogic")) {
                val v = ext.opt(k)
                if (v is Number && v.toDouble() != 0.0) {
                    notes += "计时/黏性设置已忽略"; break
                }
            }
            for (k in listOf("group", "automation_id", "outlet")) {
                if (ext.optString(k).isNotEmpty()) { notes += "分组/自动化标记已忽略"; break }
            }
            val prob = ext.opt("probability")
            if (prob is Number && prob.toDouble() != 100.0) notes += "概率 ${prob.toDouble().toInt()}% → 按常开处理"
            if (ext.optBoolean("vectorized", false)) {
                omitted += "$title：向量检索触发暂不支持，未转译"
                continue
            }
            if (ext.optBoolean("exclude_recursion", false) || ext.optBoolean("prevent_recursion", false)) {
                // 递归防护在本协议里天然成立（不递归扫描），无需记录。
            }

            val scanDepth = listOf(
                (ext.opt("scan_depth") as? Number)?.toInt(),
                (row.opt("scan_depth") as? Number)?.toInt(),
                (book.opt("scan_depth") as? Number)?.toInt(),
            ).firstOrNull { it != null } ?: 2

            val caseSensitive = (ext.opt("case_sensitive") as? Boolean)
                ?: (row.opt("case_sensitive") as? Boolean) ?: false

            val positionCode = when {
                ext.has("position") -> (ext.opt("position") as? Number)?.toInt()
                row.has("position") -> (row.opt("position") as? Number)?.toInt()
                else -> null
            }
            val position = when (positionCode) {
                null, 1 -> "after_char"
                0 -> "before_char"
                else -> { notes += "插入位置 ${positionCode} → after_char"; "after_char" }
            }

            val keysRegex = row.optBoolean("use_regex", false)
            val keys = stringList(row.optJSONArray("keys")).map { raw ->
                if (keysRegex) SLASHED_REGEX.matchEntire(raw)?.groupValues?.get(1) ?: raw
                else raw.replace(MACRO_REGEX) { expandMacro(it.groupValues[1], charName, notes) }
            }
            val secondaryKeys = stringList(row.optJSONArray("secondary_keys")).map { raw ->
                raw.replace(MACRO_REGEX) { expandMacro(it.groupValues[1], charName, notes) }
            }
            if (keysRegex) notes += "关键词按正则匹配"
            if (row.optBoolean("selective", false) || secondaryKeys.isNotEmpty()) notes += "次关键词与主关键词同命中才注入"

            val content = row.optString("content")
            val translatedContent = translateContent(content, charName, notes)
            if (translatedContent.isBlank()) {
                omitted += "$title：转译后正文为空"
                continue
            }

            out.put(JSONObject()
                .put("title", title)
                .put("content", translatedContent)
                .put("constant", row.optBoolean("constant", false))
                .put("keys", JSONArray(keys))
                .put("keysRegex", keysRegex)
                .put("secondaryKeys", JSONArray(secondaryKeys))
                .put("scanDepth", scanDepth)
                .put("caseSensitive", caseSensitive)
                .put("position", position)
                .put("order", (row.opt("insertion_order") as? Number)?.toInt() ?: 100)
                .put("approximations", JSONArray(notes)))
            if (notes.isNotEmpty()) approximations += "$title：${notes.joinToString("；")}"
            translated++
        }

        val snapshot = JSONObject()
            .put("runtimeVersion", 2)
            .put("name", book.optString("name"))
            .put("entries", out)
            .put("omitted", JSONArray(omitted))
            .put("defaultScanDepth", 2)
            .put("defaultPosition", "after_char")
            .put("localTokenBudget", 2048)
        return Outcome(snapshot.toString(), translated, omitted, approximations)
    }

    internal fun translateContent(content: String, charName: String, notes: MutableList<String>): String {
        var text = content
        // 行首 @@ 修饰符：深度/位置已归一化进字段，其余剥除。
        val decoratorLines = text.lineSequence().takeWhile { it.trimStart().startsWith("@@") }.toList()
        if (decoratorLines.isNotEmpty()) {
            text = text.lineSequence().drop(decoratorLines.size).joinToString("\n")
            notes += "${decoratorLines.size} 行 @@ 修饰符已归一化或剥除"
        }
        text = text.replace(MACRO_REGEX) { expandMacro(it.groupValues[1], charName, notes) }
        return text.trim()
    }

    internal fun expandMacro(body: String, charName: String, notes: MutableList<String>): String = when (val b = body.trim()) {
        "char", "char_name" -> charName
        "user", "user_name" -> "你"
        "time", "date", "datetime" -> "{{$b}}" // 求值期展开为当下时间
        else -> {
            notes += "宏 {{$b}} 不支持，已剥除"
            ""
        }
    }

    private fun stringList(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
}
