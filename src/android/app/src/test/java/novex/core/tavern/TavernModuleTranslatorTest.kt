package novex.core.tavern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 整卡转译：酒馆 JSON → 中立模块规格（字段/世界书触发/前端件/报告）。 */
class TavernModuleTranslatorTest {

    private fun entry(name: String, content: String = "$name 正文", constant: Boolean = false,
        enabled: Boolean = true, keys: List<String> = emptyList(), regex: Boolean = false,
        selective: Boolean = false, secondary: List<String> = emptyList()): JSONObject = JSONObject()
        .put("name", name).put("content", content).put("constant", constant)
        .put("enabled", enabled).put("use_regex", regex).put("selective", selective)
        .put("keys", JSONArray(keys)).put("secondary_keys", JSONArray(secondary))

    private fun card(vararg entries: JSONObject, regexScripts: JSONArray = JSONArray()): String =
        JSONObject().put("spec", "chara_card_v3").put("data", JSONObject()
            .put("name", "测试角色").put("description", "背景")
            .put("first_mes", "你好").put("character_book", JSONObject().put("entries", JSONArray(entries.toList())))
            .put("extensions", JSONObject().put("regex_scripts", regexScripts))).toString()

    @Test
    fun `tavern card detection accepts v3 and rejects plain json`() {
        assertTrue(TavernModuleTranslator.isTavernCardJson(card()))
        assertFalse(TavernModuleTranslator.isTavernCardJson("""{"foo":1}"""))
        assertFalse(TavernModuleTranslator.isTavernCardJson("not json"))
    }

    @Test
    fun `field modules map seven fields with labels`() {
        val out = TavernModuleTranslator.translate(card())
        val titles = out.fieldModules.map { it.title }
        assertTrue("人物背景" in titles && "开场白" in titles && "回复后置要求" in titles)
        assertEquals("测试角色", out.cardName)
    }

    @Test
    fun `constant entry becomes Always keyword entry becomes Keywords`() {
        val out = TavernModuleTranslator.translate(card(
            entry("常驻", constant = true),
            entry("触发", keys = listOf("腹痛", "生病")),
            entry("没条件"),
        ))
        assertEquals(TavernModuleTranslator.UseSpec.Always, out.bookModules[0].use)
        val kw = out.bookModules[1].use as TavernModuleTranslator.UseSpec.Keywords
        assertEquals(listOf("腹痛", "生病"), kw.words)
        assertFalse(kw.requireAll)
        assertEquals(TavernModuleTranslator.UseSpec.Manual, out.bookModules[2].use)
    }

    @Test
    fun `selective merges secondary keys into requireAll`() {
        val out = TavernModuleTranslator.translate(card(
            entry("双条件", keys = listOf("状态"), selective = true, secondary = listOf("绘里奈")),
        ))
        val kw = out.bookModules.single().use as TavernModuleTranslator.UseSpec.Keywords
        assertTrue(kw.requireAll)
        assertTrue(kw.words.containsAll(listOf("状态", "绘里奈")))
    }

    @Test
    fun `regex alternation keys expand literals other regex falls back to Manual`() {
        val out = TavernModuleTranslator.translate(card(
            entry("交替", keys = listOf("腹痛|头痛|发烧"), regex = true),
            entry("真正则", keys = listOf(".+病.*"), regex = true),
        ))
        val kw = out.bookModules[0].use as TavernModuleTranslator.UseSpec.Keywords
        assertEquals(listOf("腹痛", "头痛", "发烧"), kw.words)
        assertEquals(TavernModuleTranslator.UseSpec.Manual, out.bookModules[1].use)
        assertTrue(out.report.any { "正则关键词" in it && "降级" in it })
    }

    @Test
    fun `disabled and separator entries are skipped with report lines`() {
        val out = TavernModuleTranslator.translate(card(
            entry("====分区====_开始", content = ""),
            entry("停用", enabled = false),
        ))
        assertTrue(out.bookModules.isEmpty())
        assertTrue(out.report.any { "分区" in it })
        assertTrue(out.report.any { "禁用" in it })
    }

    @Test
    fun `marker frontend script becomes tagged def module`() {
        val scripts = JSONArray().put(JSONObject()
            .put("scriptName", "状态栏")
            .put("findRegex", "<StatusPlaceHolderImpl/>")
            .put("replaceString", "<html><body><div>状态栏页面</div></body></html>"))
        val out = TavernModuleTranslator.translate(card(regexScripts = scripts))
        assertEquals(1, out.frontendModules.size)
        val m = out.frontendModules.single()
        assertEquals(listOf("novex-frontend"), m.tags)
        assertTrue(m.text.startsWith("```novex-def"))
        assertTrue(""""marker":"<StatusPlaceHolderImpl/>"""" in m.text || """"marker": "<StatusPlaceHolderImpl/>"""" in m.text)
        assertTrue(""""compat":"tavern"""" in m.text || """"compat": "tavern"""" in m.text)
        assertTrue("<div>状态栏页面</div>" in m.text)
    }

    @Test
    fun `remote loader keeps page url offline emits remotePage module`() {
        val scripts = JSONArray().put(JSONObject()
            .put("scriptName", "远程页")
            .put("findRegex", "【开场白】")
            .put("replaceString", """<script>${'$'}('body').load('https://cdn.example.com/page.html')</script>"""))
        // 转译期零网络：产出带 remotePage meta 的占位 def，收割期回填正文
        val offline = TavernModuleTranslator.translate(card(regexScripts = scripts))
        assertEquals(1, offline.frontendModules.size)
        assertTrue("remotePage" in offline.frontendModules.single().text)
        assertTrue("https://cdn.example.com/page.html" in offline.remoteRefs)
        assertTrue(offline.report.any { "待素材收割" in it })
        // 注入抓取器时仍可整页内联（测试/未来联网通道）
        val online = TavernModuleTranslator.translate(card(regexScripts = scripts)) {
            "<html><body>远程页内容</body></html>".toByteArray()
        }
        assertEquals(1, online.frontendModules.size)
        assertTrue("远程页内容" in online.frontendModules.single().text)
        assertTrue(online.report.any { "已抓取内联" in it })
    }

    @Test
    fun `cosmetic and stripper scripts are reported not translated`() {
        val scripts = JSONArray()
            .put(JSONObject().put("scriptName", "装饰").put("findRegex", "x").put("replaceString", "<b>y</b>"))
            .put(JSONObject().put("scriptName", "清理").put("findRegex", "<UpdateVariable>").put("replaceString", ""))
        val out = TavernModuleTranslator.translate(card(regexScripts = scripts))
        assertTrue(out.frontendModules.isEmpty())
        assertTrue(out.report.any { "装饰" in it })
        assertTrue(out.report.any { "剥除" in it })
    }

    @Test
    fun `helper scripts land in report`() {
        val source = JSONObject(card()).apply {
            getJSONObject("data").getJSONObject("extensions")
                .put("tavern_helper", JSONObject().put("scripts",
                    JSONArray().put(JSONObject().put("name", "变量结构"))))
        }.toString()
        val out = TavernModuleTranslator.translate(source)
        assertTrue(out.report.any { "变量结构" in it && "不执行" in it })
    }

    @Test
    fun `markerLiteral extracts display literal from find patterns`() {
        assertEquals("<StatusPlaceHolderImpl/>", TavernFrontendTranslator.markerLiteral("<StatusPlaceHolderImpl/>"))
        assertEquals("<StatusPlaceHolderImpl/>", TavernFrontendTranslator.markerLiteral("/<StatusPlaceHolderImpl\\/>/g"))
        assertEquals("【开场白】", TavernFrontendTranslator.markerLiteral("【开场白】"))
        assertNull(TavernFrontendTranslator.markerLiteral(".*标记.*"))
    }

    @Test
    fun `vendored libs and font cdns are stripped from localized html`() {
        val html = """<html><head>
            |<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Oswald">
            |<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
            |<script src="https://code.jquery.com/jquery-3.6.0.min.js"></script>
            |<script src="https://cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.21/lodash.min.js"></script>
            |</head><body><div>状态栏</div></body></html>""".trimMargin().replace("\n", "")
        val scripts = JSONArray().put(JSONObject()
            .put("scriptName", "状态栏").put("findRegex", "<StatusPlaceHolderImpl/>")
            .put("replaceString", html))
        val out = TavernModuleTranslator.translate(card(regexScripts = scripts))
        val text = out.frontendModules.single().text
        assertFalse("fonts.googleapis.com" in text)
        assertFalse("font-awesome" in text)
        assertFalse("jquery.com" in text)
        assertFalse("lodash" in text.substringAfter("</head>"))
        assertTrue(out.report.any { "vendored" in it })
        assertTrue(out.report.any { "字体/图标" in it })
    }

    @Test
    fun `remote scripts kept for harvest vendored libs still stripped`() {
        val html = """<html><body>
            |<div>页面内容</div>
            |<script src="https://cdn.example.com/lib.js"></script>
            |<script src="https://code.jquery.com/jquery-3.6.0.min.js"></script>
            |</body></html>""".trimMargin().replace("\n", "")
        val scripts = JSONArray().put(JSONObject()
            .put("scriptName", "页").put("findRegex", "【P】").put("replaceString", html))
        val out = TavernModuleTranslator.translate(card(regexScripts = scripts))
        val text = out.frontendModules.single().text
        // 转译期不联网：远程脚本引用原样保留并计入收割清单
        assertTrue("cdn.example.com/lib.js" in text)
        assertTrue("https://cdn.example.com/lib.js" in out.remoteRefs)
        // vendored 库仍剥除（岛按 compat 注入副本）
        assertFalse("jquery.com" in text)
    }

    @Test
    fun `image and media refs kept remote collected for harvest`() {
        val html = """<html><body>
            |<img src="https://cdn.example.com/pic.webp">
            |<video><source src="https://cdn.example.com/pv.mp4"></video>
            |</body></html>""".trimMargin().replace("\n", "")
        val scripts = JSONArray().put(JSONObject()
            .put("scriptName", "页").put("findRegex", "【P】").put("replaceString", html))
        val out = TavernModuleTranslator.translate(card(regexScripts = scripts))
        val text = out.frontendModules.single().text
        // 不再内联 data: URI——引用原样保留并进入收割清单
        assertTrue("https://cdn.example.com/pic.webp" in text)
        assertTrue("https://cdn.example.com/pv.mp4" in text)
        assertTrue(out.remoteRefs.containsAll(listOf(
            "https://cdn.example.com/pic.webp", "https://cdn.example.com/pv.mp4")))
    }

    @Test
    fun `companion hide script marks def autoMarker`() {
        val scripts = JSONArray()
            .put(JSONObject().put("scriptName", "状态栏")
                .put("findRegex", "<StatusPlaceHolderImpl/>")
                .put("replaceString", "<html><body><div>栏</div></body></html>"))
            .put(JSONObject().put("scriptName", "对ai隐藏状态栏")
                .put("findRegex", "<StatusPlaceHolderImpl/>")
                .put("replaceString", ""))
            .put(JSONObject().put("scriptName", "开场页")
                .put("findRegex", "【开场白】")
                .put("replaceString", "<html><body><div>开场</div></body></html>"))
        val out = TavernModuleTranslator.translate(card(regexScripts = scripts))
        val status = out.frontendModules.first { "状态栏" in it.title }
        val opening = out.frontendModules.first { "开场页" in it.title }
        // 伴生隐藏脚本命中 → autoMarker；无伴生脚本的开场页不自动补插。
        assertTrue(""""autoMarker":true""" in status.text || """"autoMarker": true""" in status.text)
        assertTrue("autoMarker" !in opening.text)
        assertTrue(out.report.any { "autoMarker" in it })
    }
}
