package novex.android.ui.cards

/**
 * [T008] 结构化阅读态的纯解析层（无 Compose 依赖，可 JVM 单测）。
 * 输入=模块 Markdown 正文（模板脚手架的天然形态），输出=结构化模型；
 * 解析不出结构（用户改成了自由文本）返回 null，阅读层回退 Markdown 直渲。
 */
internal enum class StructuredKind { TIMELINE, ATTRIBUTE_PANEL, RELATIONS }

internal sealed interface StructuredContent {
    data class Timeline(val nodes: List<TimelineNode>) : StructuredContent
    data class Panel(val columns: List<String>, val rows: List<List<String>>) : StructuredContent
    data class Relations(val entries: List<RelationEntry>) : StructuredContent
}

internal data class TimelineNode(
    val time: String,
    val events: List<String>,
    val participants: List<String>,
    val consequences: List<String>,
)

internal data class RelationEntry(
    val name: String,
    val type: String?,
    val familiarity: String?,
    val description: String?,
    val knot: String?,
)

/** 模块名→结构化形态；只认模板命名，用户自建同名模块同样受益。 */
internal fun structuredKindFor(moduleName: String): StructuredKind? = when (moduleName.trim()) {
    "时间线", "轨迹" -> StructuredKind.TIMELINE
    "属性面板" -> StructuredKind.ATTRIBUTE_PANEL
    "关系" -> StructuredKind.RELATIONS
    else -> null
}

internal fun parseStructured(kind: StructuredKind, text: String): StructuredContent? = when (kind) {
    StructuredKind.TIMELINE -> parseTimeline(text)
    StructuredKind.ATTRIBUTE_PANEL -> parsePanel(text)
    StructuredKind.RELATIONS -> parseRelations(text)
}

private val bullet = Regex("""^\s*[-*]\s+(.*)$""")
private val heading = Regex("""^\s*#{1,3}\s+(.+)$""")
private val field = Regex("""^\*\*(.+?)\*\*[：:]\s*(.*)$""")

private fun bulletValue(line: String): String? = bullet.find(line)?.groupValues?.get(1)?.trim()?.ifBlank { null }

private fun parseTimeline(text: String): StructuredContent.Timeline? {
    val nodes = mutableListOf<TimelineNode>()
    var time: String? = null
    var events = mutableListOf<String>()
    var participants = mutableListOf<String>()
    var consequences = mutableListOf<String>()
    fun flush() {
        val t = time ?: return
        if (events.isEmpty() && participants.isEmpty() && consequences.isEmpty()) return
        nodes += TimelineNode(t, events.toList(), participants.toList(), consequences.toList())
        events = mutableListOf(); participants = mutableListOf(); consequences = mutableListOf()
    }
    for (line in text.lineSequence()) {
        val h = heading.find(line)?.groupValues?.get(1)?.trim()
        if (h != null) { flush(); time = h; continue }
        val v = bulletValue(line) ?: continue
        val f = field.find(v)
        when (f?.groupValues?.get(1)) {
            "事件" -> f.groupValues[2].ifBlank { null }?.let { events += it }
            "参与人" -> f.groupValues[2].ifBlank { null }?.let { participants += it }
            "影响", "后果" -> f.groupValues[2].ifBlank { null }?.let { consequences += it }
            else -> events += v
        }
    }
    flush()
    return if (nodes.isEmpty()) null else StructuredContent.Timeline(nodes)
}

private fun parsePanel(text: String): StructuredContent.Panel? {
    val rows = text.lineSequence()
        .filter { it.trim().startsWith("|") }
        .map { it.trim().trim('|').split('|').map { c -> c.trim() } }
        .filterNot { cells -> cells.all { c -> c.isBlank() || setOf("-", ":", "---").any { c.all { ch -> ch == ':' || ch == '-' } && c.isNotEmpty() } } }
        .toList()
    if (rows.size < 2) return null
    val header = rows.first()
    val body = rows.drop(1).map { it.dropLast(maxOf(0, it.size - header.size)) }
    return StructuredContent.Panel(header, body)
}

private fun parseRelations(text: String): StructuredContent.Relations? {
    val entries = mutableListOf<RelationEntry>()
    var name: String? = null
    var type: String? = null
    var familiarity: String? = null
    var description: String? = null
    var knot: String? = null
    fun flush() {
        val n = name ?: return
        if (type == null && familiarity == null && description == null && knot == null) return
        entries += RelationEntry(n, type, familiarity, description, knot)
        type = null; familiarity = null; description = null; knot = null
    }
    for (line in text.lineSequence()) {
        val h = heading.find(line)?.groupValues?.get(1)?.trim()
        if (h != null) { flush(); name = h; continue }
        val v = bulletValue(line) ?: continue
        val f = field.find(v)
        when (f?.groupValues?.get(1)) {
            "关系" -> type = f.groupValues[2].ifBlank { null } ?: type
            "熟络度" -> familiarity = f.groupValues[2].ifBlank { null } ?: familiarity
            "双向描述" -> description = f.groupValues[2].ifBlank { null } ?: description
            "心结" -> knot = f.groupValues[2].ifBlank { null } ?: knot
            else -> if (description == null) description = v
        }
    }
    flush()
    return if (entries.isEmpty()) null else StructuredContent.Relations(entries)
}
