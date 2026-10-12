package novex.core

import novex.core.tavern.MvuStateProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * 舞台协议 v0.3 B1：transcript 叙事流——宿主解析一次、作品直接消费，
 * 两端共享黄金样例作规格（test/resources/stage-golden/）。
 *
 * 规则：
 * - 协议块剥除：novex-def/use/skin、```html 块、<UpdateVariable>（含流式
 *   未闭合尾部）——协议块永远不裸露（r2 B1）。
 * - 分段：空行切段落；纯图片段 → kind:"image"；文图混排段 → 文本段 +
 *   图片段（图片排在段后，v0.3 过渡语义）。
 * - 台词标记（过渡启发式，作品不得依赖 speaker 精确性——r2 §C）：
 *   段首 [可选@] + 名册内名字 + 全/半角冒号 → kind:"dialogue" + speaker；
 *   名字按长度降序匹配（"阿七妹"不被"阿七"截断）。
 * - html 安全子集：全部转义后仅 **加粗** / *斜体* / 换行→<br>。
 * - streaming：消息仍在流式时，该消息的最后一段标 streaming:true。
 * - 段 id：`<消息id>#<段内序号>`——一条消息会切成多段，段 id 必须含
 *   消息 id（r2 修订记录）。
 */
object StageTranscriptParser {

    data class Input(
        val messageId: String,
        val role: String,
        val content: String,
        val streaming: Boolean = false,
    )

    data class Segment(
        val id: String,
        val messageId: String,
        val role: String,
        val kind: String, // narration | dialogue | image
        val text: String = "",
        val html: String? = null,
        val speaker: String? = null,
        val image: String? = null,
        val streaming: Boolean = false,
    )

    // novex-def/use/skin 与 ```html 块同属"协议/件"，不进叙事流（含流式
    // 未闭合尾部——半截围栏也不能露进段里）。
    private val PROTOCOL_BLOCK = Regex(
        "```\\s*(novex-def|novex-use|novex-skin|html)[^\\n`]*\\n.*?```\\n?",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val OPEN_FENCE_TAIL = Regex(
        "```\\s*(novex-def|novex-use|novex-skin|html)[^\\n`]*\\n?[\\s\\S]*\$",
        RegexOption.IGNORE_CASE,
    )
    private val MD_IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)")
    private const val DEFAULT_MAX_SEGMENTS = 60

    fun parse(
        inputs: List<Input>,
        castNames: List<String> = emptyList(),
        maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    ): List<Segment> {
        val cast = castNames.filter { it.isNotBlank() }
            .distinct().sortedByDescending { it.length }
        val out = mutableListOf<Segment>()
        inputs.forEach { msg ->
            val cleaned = stripProtocol(msg.content)
            val paragraphs = cleaned.split(Regex("\n\\s*\n"))
                .map { it.trim() }.filter { it.isNotEmpty() }
            val msgSegs = mutableListOf<Segment>()
            paragraphs.forEach { para ->
                val images = MD_IMAGE.findAll(para).toList()
                val textPart = MD_IMAGE.replace(para, "").trim()
                if (textPart.isNotEmpty()) {
                    msgSegs += textSegment(msg, textPart, cast, "${msg.messageId}#${msgSegs.size}")
                }
                images.forEach { img ->
                    msgSegs += Segment(
                        id = "${msg.messageId}#${msgSegs.size}",
                        messageId = msg.messageId,
                        role = msg.role,
                        kind = "image",
                        text = img.groupValues[1],
                        image = img.groupValues[2],
                    )
                }
            }
            msgSegs.lastOrNull()?.let { last ->
                if (msg.streaming) {
                    msgSegs[msgSegs.lastIndex] = last.copy(streaming = true)
                }
            }
            out += msgSegs
        }
        return out.takeLast(maxSegments)
    }

    private fun textSegment(
        msg: Input,
        text: String,
        cast: List<String>,
        segmentId: String,
    ): Segment {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val speaker = cast.firstOrNull { name ->
            firstLine.startsWith("$name：") || firstLine.startsWith("$name:") ||
                firstLine.startsWith("@$name：") || firstLine.startsWith("@$name:")
        }
        return if (speaker != null) {
            // 台词：text/html 去掉"名字："前缀（speaker 单列，避免双显）。
            val body = text.substringAfter('：', text.substringAfter(':')).trimStart()
            Segment(
                id = segmentId,
                messageId = msg.messageId,
                role = msg.role,
                kind = "dialogue",
                text = body,
                html = toSafeHtml(body),
                speaker = speaker,
            )
        } else {
            Segment(
                id = segmentId,
                messageId = msg.messageId,
                role = msg.role,
                kind = "narration",
                text = text,
                html = toSafeHtml(text),
            )
        }
    }

    /** 剥协议块：整块 + 流式未闭合尾部（MVU 尾部走 stripBlocks）。 */
    internal fun stripProtocol(text: String): String =
        OPEN_FENCE_TAIL.replace(
            PROTOCOL_BLOCK.replace(MvuStateProtocol.stripBlocks(text), ""),
            "",
        ).trim()

    /** 安全子集：先全量转义，再认 **粗体**/*斜体*/换行三种标记。 */
    internal fun toSafeHtml(text: String): String {
        val esc = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        val br = esc.replace("\n", "<br>")
        val bold = BOLD.replace(br) { "<b>${it.groupValues[1]}</b>" }
        return ITALIC.replace(bold) { "<i>${it.groupValues[1]}</i>" }
    }

    /** 段表 → 帧 JSON 数组字符串。 */
    fun toJson(segments: List<Segment>): String {
        val arr = JSONArray()
        segments.forEach { s ->
            arr.put(JSONObject()
                .put("id", s.id)
                .put("messageId", s.messageId)
                .put("role", s.role)
                .put("kind", s.kind)
                .put("text", s.text)
                .put("html", s.html ?: JSONObject.NULL)
                .put("speaker", s.speaker ?: JSONObject.NULL)
                .put("image", s.image ?: JSONObject.NULL)
                .put("streaming", s.streaming))
        }
        return arr.toString()
    }
}
