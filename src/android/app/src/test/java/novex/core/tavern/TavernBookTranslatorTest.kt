package novex.core.tavern

import novex.core.NovexTavernWorldbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 转译器与 v2 求值的合成卡测试——fixture 手写，不引入真实卡内容。 */
class TavernBookTranslatorTest {

    private fun entry(
        name: String,
        content: String = "正文",
        enabled: Boolean = true,
        constant: Boolean = false,
        keys: List<String> = emptyList(),
        secondaryKeys: List<String> = emptyList(),
        useRegex: Boolean = false,
        selective: Boolean = false,
        scanDepth: Int? = null,
        ext: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("name", name).put("content", content).put("enabled", enabled)
        .put("constant", constant).put("keys", JSONArray(keys))
        .put("secondary_keys", JSONArray(secondaryKeys))
        .put("use_regex", useRegex).put("selective", selective)
        .put("extensions", JSONObject().apply {
            if (scanDepth != null) put("scan_depth", scanDepth)
            ext()
        })

    private fun book(vararg entries: JSONObject): JSONObject =
        JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it) } })

    private fun evaluate(snapshotJson: String, messages: List<String>) =
        NovexTavernWorldbook.evaluate("v1", snapshotJson, messages, 2048) { it.length }

    @Test
    fun `plain constant and keyword entries translate and inject`() {
        val snap = TavernBookTranslator.translate(book(
            entry("常驻", "永远在场", constant = true),
            entry("触发", "绘里奈资料", keys = listOf("绘里奈")),
        ), "测试角色").snapshotJson
        val hits = evaluate(snap, listOf("今天遇到绘里奈了")).fragments
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.text.contains("绘里奈资料") })
    }

    @Test
    fun `regex keys match patterns and slashed form is unwrapped`() {
        val snap = TavernBookTranslator.translate(book(
            entry("正则", "命中内容", keys = listOf("/绘里奈|奈奈/"), useRegex = true),
        ), "x").snapshotJson
        assertTrue(evaluate(snap, listOf("奈奈今天没来")).fragments.isNotEmpty())
        assertTrue(evaluate(snap, listOf("没人来")).fragments.isEmpty())
    }

    @Test
    fun `selective requires primary and secondary key`() {
        val snap = TavernBookTranslator.translate(book(
            entry("条件", "复合命中", keys = listOf("绘里奈"), secondaryKeys = listOf("生气"), selective = true),
        ), "x").snapshotJson
        assertTrue(evaluate(snap, listOf("绘里奈今天没来")).fragments.isEmpty())
        assertTrue(evaluate(snap, listOf("绘里奈生气了")).fragments.isNotEmpty())
    }

    @Test
    fun `char and user macros expand at capture, unknown macros dropped`() {
        val snap = TavernBookTranslator.translate(book(
            entry("宏", "{{user}} 遇到 {{char}}，使用 {{unknown_thing}}。", constant = true),
        ), "绘里奈").snapshotJson
        val content = evaluate(snap, emptyList()).fragments.single().text
        assertTrue(content.contains("你 遇到 绘里奈"))
        assertFalse(content.contains("{{char}}"))
        assertFalse(content.contains("unknown_thing"))
    }

    @Test
    fun `time tokens expand at evaluation not capture`() {
        val snap = TavernBookTranslator.translate(book(
            entry("时间", "现在是 {{date}}", constant = true),
        ), "x").snapshotJson
        assertTrue(snap.contains("{{date}}")) // 快照里保留令牌
        assertFalse(evaluate(snap, emptyList()).fragments.single().text.contains("{{date}}"))
    }

    @Test
    fun `scanDepth honored for keyword hits`() {
        val snap = TavernBookTranslator.translate(book(
            entry("浅扫", "命中", keys = listOf("旧词"), scanDepth = 1),
        ), "x").snapshotJson
        // 关键词在倒数第二条：depth=1 扫不到
        assertTrue(evaluate(snap, listOf("旧词", "新消息")).fragments.isEmpty())
        assertTrue(evaluate(snap, listOf("无关", "旧词")).fragments.isNotEmpty())
    }

    @Test
    fun `disabled separator and vectorized entries are omitted with reasons`() {
        val snap = TavernBookTranslator.translate(book(
            entry("====分区====_开始", content = ""),
            entry("禁用", enabled = false),
            entry("向量", keys = listOf("x"), ext = { put("vectorized", true) }),
        ), "x").snapshotJson
        val res = evaluate(snap, listOf("x"))
        assertTrue(res.fragments.isEmpty())
        assertTrue(res.omissions.any { it.reason.contains("分区标记") })
        assertTrue(res.omissions.any { it.reason.contains("禁用") })
        assertTrue(res.omissions.any { it.reason.contains("向量") })
    }

    @Test
    fun `probability and sticky degrade with approximation notes`() {
        val snap = TavernBookTranslator.translate(book(
            entry("降级", "近似内容", keys = listOf("触发词"), ext = {
                put("probability", 50); put("sticky", 3)
            }),
        ), "x").snapshotJson
        val res = evaluate(snap, listOf("触发词出现"))
        assertTrue(res.fragments.isNotEmpty())
        assertTrue(res.omissions.any { it.reason.contains("概率") })
        assertTrue(res.omissions.any { it.reason.contains("计时") || it.reason.contains("黏性") })
    }

    @Test
    fun `decorator lines are stripped from content`() {
        val snap = TavernBookTranslator.translate(book(
            entry("修饰符", "@@depth 4\n@@position 1\n真正的正文", constant = true),
        ), "x").snapshotJson
        val content = evaluate(snap, emptyList()).fragments.single().text
        assertEquals("真正的正文", content)
    }

    @Test
    fun `v1 snapshots still evaluate via legacy path`() {
        val v1 = JSONObject()
            .put("runtimeVersion", 1)
            .put("book", JSONObject().put("entries", JSONArray().put(
                JSONObject().put("name", "常驻").put("content", "v1 内容")
                    .put("enabled", true).put("constant", true).put("keys", JSONArray())
            )))
            .put("defaultScanDepth", 2).put("defaultCaseSensitive", false)
            .put("defaultPosition", "after_char").put("localTokenBudget", 2048)
            .toString()
        val hits = evaluate(v1, emptyList()).fragments
        assertEquals(1, hits.size)
        assertEquals("v1 内容", hits.single().text)
    }
}
