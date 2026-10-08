package novex.core.tavern

import novex.core.PlaythroughValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * MVU（MagVarUpdate）协议解释器——酒馆社区事实标准的变量更新格式：
 * 模型在回复末尾输出 `<UpdateVariable><Analysis>…</Analysis><JSONPatch>[…]</JSONPatch></UpdateVariable>`，
 * 块内是 JSON Patch（RFC 6902 变体：replace/delta/insert/remove/move）。
 *
 * 转译语义：补丁应用到「本局状态」扁平键表——JSON 指针段以点号连接
 * （`/月宫绾音/好感度` → `月宫绾音.好感度`），对象/数组值递归摊平为叶子
 * 键。产出的更新项与 update_playthrough_state 工具同构（{key,value}/
 * {remove}），经 PlaythroughStateRegistration 走同一条落库路径。
 *
 * 显示语义：[stripBlocks] 把协议块从显示文本剥除（等价酒馆「隐藏变量
 * 更新」显示正则），原文持久化不变——模型下一轮仍能在上下文里看到自己
 * 的更新输出。
 */
object MvuStateProtocol {

    /** 一次提取：清理后的显示文本 + 解析成功的补丁数组。 */
    data class Extraction(val text: String, val patches: List<JSONArray>)

    private val BLOCK = Regex("""<UpdateVariable>.*?</UpdateVariable>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val OPEN_TAIL = Regex("""<UpdateVariable>[\s\S]*$""", RegexOption.IGNORE_CASE)
    private val PATCH = Regex("""<JSONPatch>(.*?)</JSONPatch>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** 显示期剥除：整块（含未闭合的流式尾部）。 */
    fun stripBlocks(text: String): String {
        if (!text.contains("<UpdateVariable", ignoreCase = true)) return text
        return OPEN_TAIL.replace(BLOCK.replace(text, ""), "")
    }

    /** 提取补丁；返回的 text 已剥除协议块（供需要干净文本的调用方）。 */
    fun extract(text: String): Extraction {
        if (!text.contains("<UpdateVariable", ignoreCase = true)) return Extraction(text, emptyList())
        val patches = mutableListOf<JSONArray>()
        BLOCK.findAll(text).forEach { m ->
            PATCH.find(m.value)?.groupValues?.get(1)?.trim()?.let { body ->
                runCatching { JSONArray(body) }.getOrNull()?.let { patches += it }
            }
        }
        return Extraction(stripBlocks(text), patches)
    }

    /**
     * 把一条补丁应用到工作值表（原地修改，供连续补丁看到先前结果），
     * 返回 update_playthrough_state 同构的更新项数组。
     * 无法定位/类型不符的条目跳过（记 skipped 数），不抛错——卡文本指示的
     * 输出本来就不可信，容错优先。
     */
    fun applyPatch(patch: JSONArray, values: MutableMap<String, PlaythroughValue>, skipped: IntArray = intArrayOf(0)): JSONArray {
        val updates = JSONArray()
        for (i in 0 until patch.length()) {
            val item = patch.optJSONObject(i)
            val path = item?.optString("path")?.let(::pointer)
            if (item == null || path == null) { skipped[0]++; continue }
            when (item.optString("op")) {
                "replace", "insert" -> {
                    val segs = resolveInsertPath(path, values)
                    if (segs == null) skipped[0]++
                    else emitReplace(segs, item.opt("value"), values, updates)
                }
                "delta" -> {
                    val key = keyOf(path)
                    val cur = values[key] as? PlaythroughValue.Number
                    val delta = item.opt("value") as? Number
                    if (cur == null || delta == null) { skipped[0]++; continue }
                    val next = PlaythroughValue.Number(cur.value + delta.toDouble(), cur.max)
                    values[key] = next
                    updates.put(putItem(key, next))
                }
                "remove" -> emitRemove(keyOf(path), values, updates)
                "move" -> {
                    val from = item.optString("from").let(::pointer)
                    val fromKey = from?.let(::keyOf)
                    val subtree = if (fromKey == null) emptyMap()
                        else values.filterKeys { it == fromKey || it.startsWith("$fromKey.") }
                    if (subtree.isEmpty()) { skipped[0]++; continue }
                    val to = keyOf(path)
                    emitRemove(fromKey!!, values, updates)
                    subtree.forEach { (k, v) ->
                        val nk = to + k.removePrefix(fromKey)
                        values[nk] = v
                        updates.put(putItem(nk, v))
                    }
                }
                else -> skipped[0]++
            }
        }
        return updates
    }

    /** insert 的 `-`（追加）与对象键落位：数组追加需看当前兄弟索引。 */
    private fun resolveInsertPath(path: List<String>, values: Map<String, PlaythroughValue>): List<String>? {
        if (path.isEmpty()) return null
        if (path.last() != "-") return path
        val parent = keyOf(path.dropLast(1))
        val next = values.keys
            .mapNotNull { k -> k.removePrefix("$parent.").takeIf { it != k }?.substringBefore('.')?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 0
        return path.dropLast(1) + next.toString()
    }

    /** 标量直接 set；对象/数组先清旧子树再递归摊平叶子。 */
    private fun emitReplace(
        segs: List<String>, raw: Any?,
        values: MutableMap<String, PlaythroughValue>, updates: JSONArray,
    ) {
        val key = keyOf(segs)
        when (raw) {
            is JSONObject, is JSONArray -> {
                emitRemove(key, values, updates)
                flatten(key, raw, values, updates)
            }
            null, JSONObject.NULL -> emitRemove(key, values, updates)
            else -> {
                val v = leaf(raw) ?: return
                // replace 数字时沿用上限（schema 约束不被顺手抹掉）
                val kept = if (v is PlaythroughValue.Number && v.max == null)
                    (values[key] as? PlaythroughValue.Number)?.max?.let { v.copy(max = it) } ?: v
                else v
                values[key] = kept
                updates.put(putItem(key, kept))
            }
        }
    }

    private fun flatten(
        key: String, node: Any,
        values: MutableMap<String, PlaythroughValue>, updates: JSONArray,
    ) {
        when (node) {
            is JSONObject -> node.keys().forEach { k -> flatten("$key.$k", node.get(k), values, updates) }
            is JSONArray -> (0 until node.length()).forEach { i -> flatten("$key.$i", node.get(i), values, updates) }
            else -> leaf(node)?.let { values[key] = it; updates.put(putItem(key, it)) }
        }
    }

    /** 删除键及其整棵子树（扁平表上前缀枚举）。 */
    private fun emitRemove(key: String, values: MutableMap<String, PlaythroughValue>, updates: JSONArray) {
        val doomed = values.keys.filter { it == key || it.startsWith("$key.") }
        doomed.forEach { values.remove(it); updates.put(JSONObject().put("remove", it)) }
    }

    private fun leaf(raw: Any): PlaythroughValue? = when (raw) {
        is Boolean -> PlaythroughValue.Flag(raw)
        is Number -> PlaythroughValue.Number(raw.toDouble())
        is String -> PlaythroughValue.Text(raw)
        else -> null
    }

    private fun putItem(key: String, v: PlaythroughValue): JSONObject {
        val o = JSONObject().put("key", key)
        when (v) {
            is PlaythroughValue.Text -> o.put("value", v.value)
            is PlaythroughValue.Flag -> o.put("value", v.value)
            is PlaythroughValue.Number -> {
                o.put("value", v.value)
                v.max?.let { o.put("max", it) }
            }
        }
        return o
    }

    /** `/a/b/c` JSON 指针 → 段表（~1→/ ~0→~）；空指针非法。 */
    private fun pointer(raw: String): List<String>? {
        val p = raw.trim()
        if (!p.startsWith("/") || p.length < 2) return null
        return p.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
            .takeIf { it.all(String::isNotEmpty) }
    }

    private fun keyOf(segs: List<String>) = segs.joinToString(".")
}
