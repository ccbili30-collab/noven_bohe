package novex.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transcript 黄金样例（test/resources/stage-golden/）：两端共享的解析
 * 规格——"不一致算手机缺陷"的前提是有样例可执行（r2 §A/B1）。PC 首批
 * 样例到货后与此目录合并，样例即规格，不改实现去迁就预期。
 */
class StageTranscriptGoldenTest {

    @Test
    fun `golden samples parse to expected segments`() {
        val dir = File(javaClass.classLoader.getResource("stage-golden")!!.toURI())
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        assertTrue("stage-golden 样例目录为空", files.isNotEmpty())
        files.forEach { file ->
            val root = JSONObject(file.readText())
            val cast = root.optJSONArray("cast")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val inputs = root.getJSONArray("input").let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    StageTranscriptParser.Input(
                        messageId = o.getString("id"),
                        role = o.getString("role"),
                        content = o.getString("content"),
                        streaming = o.optBoolean("streaming", false),
                    )
                }
            }
            val got = StageTranscriptParser.parse(inputs, cast)
            val expected = root.getJSONArray("expected")
            assertEquals("${file.name}: 段数", expected.length(), got.size)
            (0 until expected.length()).forEach { i ->
                val e = expected.getJSONObject(i)
                val g = got[i]
                val tag = "${file.name} #$i"
                assertEquals("$tag id", e.getString("id"), g.id)
                assertEquals("$tag messageId", e.getString("messageId"), g.messageId)
                assertEquals("$tag role", e.getString("role"), g.role)
                assertEquals("$tag kind", e.getString("kind"), g.kind)
                assertEquals("$tag text", e.getString("text"), g.text)
                assertEquals("$tag html", e.optNullable("html"), g.html)
                assertEquals("$tag speaker", e.optNullable("speaker"), g.speaker)
                assertEquals("$tag image", e.optNullable("image"), g.image)
                assertEquals("$tag streaming", e.getBoolean("streaming"), g.streaming)
            }
        }
    }

    private fun JSONObject.optNullable(key: String): String? =
        if (isNull(key)) null else optString(key, null)
}
