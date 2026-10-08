package novex.core.tavern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入盘点器的合成卡测试。fixture 全部手写——不引入任何真实卡内容，
 * 规避真实卡的版权限制，同时覆盖酒馆生态的关键结构形态。
 */
class TavernCardAnalyzerTest {

    private fun card(block: JSONObject.() -> Unit): String = JSONObject()
        .put("spec", "chara_card_v3").put("spec_version", "3.0")
        .put("data", JSONObject().put("name", "测试卡").apply(block))
        .toString()

    private fun entry(
        name: String,
        content: String = "正文",
        enabled: Boolean = true,
        constant: Boolean = false,
        keys: List<String> = emptyList(),
        useRegex: Boolean = false,
        selective: Boolean = false,
        secondaryKeys: List<String> = emptyList(),
        ext: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("name", name).put("content", content).put("enabled", enabled)
        .put("constant", constant).put("keys", JSONArray(keys))
        .put("use_regex", useRegex).put("selective", selective)
        .put("secondary_keys", JSONArray(secondaryKeys))
        .put("extensions", JSONObject().apply(ext))

    @Test
    fun `plain book entries classify usable`() {
        val inv = TavernCardAnalyzer.analyze(card {
            put("character_book", JSONObject().put("entries", JSONArray()
                .put(entry("常驻设定", constant = true))
                .put(entry("关键词条目", keys = listOf("月宫", "绾音")))))
        })!!
        assertEquals(2, inv.worldbook!!.usable)
        assertEquals(0, inv.worldbook!!.needsTranslation)
    }

    @Test
    fun `regex selective macro entries classify needs-translation`() {
        val inv = TavernCardAnalyzer.analyze(card {
            put("character_book", JSONObject().put("entries", JSONArray()
                .put(entry("正则", keys = listOf("/绘里奈|奈奈/"), useRegex = true))
                .put(entry("次关键词", keys = listOf("绘里奈"), selective = true, secondaryKeys = listOf("好感")))
                .put(entry("宏", content = "{{user}} 进入了 {{char}} 的世界"))
                .put(entry("修饰符", content = "@@depth 4\n内容"))
                .put(entry("概率", ext = { put("probability", 50) }))))
        })!!
        val wb = inv.worldbook!!
        assertEquals(0, wb.usable)
        assertEquals(5, wb.needsTranslation)
        assertTrue(wb.reasonCounts.containsKey("正则关键词"))
        assertTrue(wb.reasonCounts.containsKey("次关键词"))
        assertTrue(wb.reasonCounts.containsKey("宏/修饰符"))
        assertTrue(wb.reasonCounts.containsKey("概率"))
    }

    @Test
    fun `disabled and separator entries counted separately`() {
        val inv = TavernCardAnalyzer.analyze(card {
            put("character_book", JSONObject().put("entries", JSONArray()
                .put(entry("====变量系统====_开始", content = ""))
                .put(entry("关闭条目", enabled = false))))
        })!!
        assertEquals(1, inv.worldbook!!.markers)
        assertEquals(1, inv.worldbook!!.disabled)
    }

    @Test
    fun `marker plus whole-page html classifies as frontend`() {
        val html = "```\n<!doctype html>\n<html><body><div>状态栏</div></body></html>\n```"
        val inv = TavernCardAnalyzer.analyze(card {
            put("extensions", JSONObject().put("regex_scripts", JSONArray()
                .put(JSONObject().put("scriptName", "状态栏-完整版")
                    .put("findRegex", "<StatusPlaceHolderImpl/>")
                    .put("replaceString", html))))
        })!!
        val item = inv.regexItems.single()
        assertEquals(TavernCardAnalyzer.RegexFate.MARKER_FRONTEND, item.fate)
        assertTrue(item.note.contains("StatusPlaceHolder"))
    }

    @Test
    fun `cdn loader classifies as remote-loader and url collected`() {
        val loader = "```\n<body><script> \$('body').load('https://cdn.jsdelivr.net/gh/a/b/p.html')</script></body>\n```"
        val inv = TavernCardAnalyzer.analyze(card {
            put("extensions", JSONObject().put("regex_scripts", JSONArray()
                .put(JSONObject().put("scriptName", "状态栏")
                    .put("findRegex", "<StatusPlaceHolderImpl/>")
                    .put("replaceString", loader))))
        })!!
        assertEquals(TavernCardAnalyzer.RegexFate.REMOTE_LOADER, inv.regexItems.single().fate)
        assertTrue(inv.remoteAssetUrls.any { it.contains("jsdelivr") })
    }

    @Test
    fun `empty replacement classifies as stripper, small as cosmetic`() {
        val inv = TavernCardAnalyzer.analyze(card {
            put("extensions", JSONObject().put("regex_scripts", JSONArray()
                .put(JSONObject().put("scriptName", "去变量")
                    .put("findRegex", "<UpdateVariable>.+?</UpdateVariable>")
                    .put("replaceString", ""))
                .put(JSONObject().put("scriptName", "美化")
                    .put("findRegex", "HP")
                    .put("replaceString", "<b>HP</b>"))))
        })!!
        assertEquals(TavernCardAnalyzer.RegexFate.STRIPPER, inv.regexItems[0].fate)
        assertEquals(TavernCardAnalyzer.RegexFate.COSMETIC, inv.regexItems[1].fate)
    }

    @Test
    fun `helper scripts listed by name`() {
        val inv = TavernCardAnalyzer.analyze(card {
            put("extensions", JSONObject().put("tavern_helper", JSONObject()
                .put("scripts", JSONArray()
                    .put(JSONObject().put("name", "变量结构"))
                    .put(JSONObject().put("name", "相册")))))
        })!!
        assertEquals(listOf("变量结构", "相册"), inv.helperScriptNames)
    }

    @Test
    fun `non-tavern json yields null inventory`() {
        assertNull(TavernCardAnalyzer.analyze("""{"foo":1}"""))
        assertNull(TavernCardAnalyzer.analyze("not json"))
    }

    @Test
    fun `report renders human-readable lines`() {
        val inv = TavernCardAnalyzer.analyze(card {
            put("character_book", JSONObject().put("entries", JSONArray()
                .put(entry("常驻", constant = true))
                .put(entry("正则", useRegex = true, keys = listOf("/a|b/")))))
            put("extensions", JSONObject().put("tavern_helper", JSONObject()
                .put("scripts", JSONArray().put(JSONObject().put("name", "MVU脚本")))))
        })!!
        val text = TavernCardAnalyzer.report(inv).render()
        assertTrue(text.contains("世界书 2 条"))
        assertTrue(text.contains("1 条可直接注入"))
        assertTrue(text.contains("1 条经转译层处理"))
        assertTrue(text.contains("MVU脚本"))
    }
}
