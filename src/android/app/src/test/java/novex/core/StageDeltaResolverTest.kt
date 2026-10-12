package novex.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StageDeltaResolverTest {

    private fun msg(id: String, role: String, content: String) =
        StageDeltaResolver.MessageRef(id, role, content)

    private fun use(data: String) = "```novex-use\n$data\n```"

    @Test
    fun envelopesCollectedInPathOrderWithSeq() {
        val deltas = StageDeltaResolver.collect(
            listOf(
                msg("m1", "assistant", "开篇。" + use("""{"op":"replace","path":"/兵力","value":4200}""")),
                msg("m2", "assistant", use("""{"op":"delta","path":"/兵力","value":-3000}""") + "夜袭。"),
            ),
            emptyList(),
        )
        assertEquals(2, deltas.size)
        assertEquals(0, deltas[0].seq)
        assertEquals("m1", deltas[0].messageId)
        assertEquals(1, deltas[1].seq)
        assertEquals("m2", deltas[1].messageId)
    }

    @Test
    fun annotationEnvelopesMergeAfterOwnMessageEnvelopes() {
        val deltas = StageDeltaResolver.collect(
            listOf(
                msg("m1", "assistant", use("""{"op":"replace","path":"/忠诚","value":92}""")),
                msg("m2", "assistant", "纯文本。"),
            ),
            listOf(StageAnnotation("m1", """{"kind":"query-answer","data":{}}""", "档案/忠诚")),
        )
        assertEquals(2, deltas.size)
        assertEquals("m1", deltas[0].messageId)
        assertEquals("""{"op":"replace","path":"/忠诚","value":92}""".trim(), deltas[0].envelopeJson.trim())
        assertEquals("""{"kind":"query-answer","data":{}}""", deltas[1].envelopeJson)
    }

    @Test
    fun annotationOnMessageOffPathIsInvisible() {
        // 分支隔离：附属记录锚定的消息不在活动路径 → 该记录随消息消失。
        val deltas = StageDeltaResolver.collect(
            listOf(msg("m1", "assistant", "本分支正文。")),
            listOf(StageAnnotation("m-sibling", """{"kind":"query-answer","data":{}}""")),
        )
        assertEquals(0, deltas.size)
    }

    @Test
    fun userRoleFencesAndBrokenEnvelopesAreDropped() {
        val deltas = StageDeltaResolver.collect(
            listOf(
                // 用户消息里手打的围栏不构成档案事件。
                msg("u1", "user", use("""{"op":"replace","path":"/兵力","value":0}""")),
                // 半截围栏（流式中断）不算数。
                msg("m1", "assistant", "```novex-use\n{\"op\":\"replace\""),
                // 非 JSON 信封丢弃。
                msg("m2", "assistant", use("not-json")),
            ),
            emptyList(),
        )
        assertEquals(0, deltas.size)
    }

    @Test
    fun toJsonEmitsSeqMessageIdEnvelopeShape() {
        val json = StageDeltaResolver.toJson(
            StageDeltaResolver.collect(
                listOf(msg("m1", "assistant", use("""{"op":"replace","path":"/忠诚","value":92}"""))),
                emptyList(),
            )
        )
        val first = JSONArray(json).getJSONObject(0)
        assertEquals(0, first.getInt("seq"))
        assertEquals("m1", first.getString("messageId"))
        assertEquals("replace", first.getJSONObject("envelope").getString("op"))
    }
}
