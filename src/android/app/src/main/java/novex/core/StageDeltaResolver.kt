package novex.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 舞台协议 v0.3 B6：档案真相源 = 日志。
 *
 * `deltas` = 沿活动路径收集的增量事件流：消息里的 `novex-use` 信封 +
 * 查证回写附属记录（[StageAnnotation]），每条锚定产生它的 messageId
 * 并按路径序编号（seq）。
 *
 * 合并语义 = 信封/事件自带的操作动词（创作者声明，宿主不猜——对应
 * MvuStateProtocol 的 replace/delta/insert/remove/move 同款契约）。
 * 宿主不做物化合并：`archive` 快照是作品按序重放 deltas 的派生物，
 * 丢了重放即可（r2 B6「日志即真相」）。
 */
object StageDeltaResolver {

    /** 只认完整闭合的 novex-use 围栏（流式半截不算数，写完整才生效）。 */
    private val USE_FENCE = Regex(
        "```\\s*novex-use[^\\n`]*\\n(.*?)```",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    data class MessageRef(val id: String, val role: String, val content: String)
    data class Delta(val seq: Int, val messageId: String, val envelopeJson: String)

    /** 抽出文本里全部 novex-use 信封原文（要求可解析为 JSON 对象）。 */
    fun extractUseEnvelopes(content: String): List<String> =
        USE_FENCE.findAll(content).mapNotNull { m ->
            m.groupValues[1].trim().takeIf { raw ->
                runCatching { JSONObject(raw) }.isSuccess
            }
        }.toList()

    /**
     * 沿活动路径收集 deltas：助手消息内的信封 + 锚定该消息的附属记录
     * （附属记录排在本条自带信封之后——查证发生在消息之后）。锚的
     * 消息不在路径上时，其附属记录自然消失（分支隔离）。
     */
    fun collect(
        messages: List<MessageRef>,
        annotations: List<StageAnnotation>,
    ): List<Delta> {
        val byMessage = annotations.groupBy { it.messageId }
        val out = mutableListOf<Delta>()
        var seq = 0
        messages.forEach { m ->
            if (m.role != "assistant") return@forEach
            extractUseEnvelopes(m.content).forEach { env ->
                out += Delta(seq++, m.id, env)
            }
            byMessage[m.id].orEmpty().forEach { a ->
                out += Delta(seq++, m.id, a.envelopeJson)
            }
        }
        return out
    }

    /** deltas → 帧 JSON 数组字符串：[{seq, messageId, envelope}]。 */
    fun toJson(deltas: List<Delta>): String {
        val arr = JSONArray()
        deltas.forEach { d ->
            arr.put(JSONObject()
                .put("seq", d.seq)
                .put("messageId", d.messageId)
                .put("envelope",
                    runCatching { JSONObject(d.envelopeJson) }.getOrElse { JSONObject() }))
        }
        return arr.toString()
    }
}
