package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前端件契约：def/use 围栏解析、{{}} 代入、槽位归属裁定。 */
class NovexArtifactsTest {

    private fun msg(content: String, role: String = "assistant") =
        ChatMessage(id = "m$content".hashCode().toString(), role = role, content = content)

    @Test
    fun `language gates accept only the two fence kinds`() {
        assertTrue(isNovexDefLanguage("novex-def"))
        assertTrue(isNovexDefLanguage(" NOVEX-DEF "))
        assertTrue(isNovexUseLanguage("novex-use"))
        assertTrue(!isNovexDefLanguage("novex-use"))
        assertTrue(!isNovexUseLanguage("novex-def"))
        assertTrue(!isNovexDefLanguage("html"))
    }

    @Test
    fun `def parses meta line and keeps template verbatim`() {
        val def = parseNovexDef(
            """
            {"id":"hp","kind":"math","name":"血量条","slot":"top"}
            \frac{ {{hp}} }{ {{max}} }
            """.trimIndent(),
        )!!
        assertEquals("hp", def.id)
        assertEquals("math", def.kind)
        assertEquals("血量条", def.name)
        assertEquals("top", def.defaultSlot)
        assertEquals("\\frac{ {{hp}} }{ {{max}} }", def.template)
    }

    @Test
    fun `def tolerates unknown kind and missing meta`() {
        assertNull(parseNovexDef("no json here"))
        assertNull(parseNovexDef("""{"kind":"html"}"""))
        // kind 缺省/非法 → 回落 html；slot 已退役，原样保留不影响落点。
        val def = parseNovexDef("""{"id":"x","kind":"bogus","slot":"sideways"}
<div/>""")!!
        assertEquals("html", def.kind)
        assertEquals("sideways", def.defaultSlot)
    }

    @Test
    fun `use reads data object and loose keys`() {
        val a = parseNovexUse("""{"id":"hp","slot":"bottom","data":{"hp":42,"max":100}}""")!!
        // slot 是历史字段：仍被解析剔除（不进 data），但不再影响落点。
        assertEquals(mapOf("hp" to "42", "max" to "100"), a.data)

        val b = parseNovexUse("""{"id":"hp","hp":42,"note":"灼烧"}""")!!
        assertEquals(mapOf("hp" to "42", "note" to "灼烧"), b.data)
        assertNull(parseNovexUse("""{"slot":"top"}"""))
    }

    @Test
    fun `substitution only touches keys present in data`() {
        val tpl = """hp={{hp}} vue={{msg}} spaced={{ hp }}"""
        val out = substituteNovexArtifact(tpl, mapOf("hp" to "42"))
        assertEquals("hp=42 vue={{msg}} spaced=42", out)
        // 无数据 / 无占位符 → 原样。
        assertEquals(tpl, substituteNovexArtifact(tpl, emptyMap()))
        assertEquals("plain", substituteNovexArtifact("plain", mapOf("x" to "1")))
    }

    @Test
    fun `scene collects defs and uses render in place contributing nothing`() {
        val scene = collectNovexArtifactScene(
            listOf(
                msg(
                    """
                    ```novex-def
                    {"id":"hp","kind":"math"}
                    HP {{hp}}
                    ```
                    ```novex-use
                    {"id":"hp","slot":"top","hp":100}
                    ```
                    """.trimIndent(),
                ),
                msg("正文"),
                msg(
                    """
                    ```novex-use
                    {"id":"hp","slot":"bottom","hp":42}
                    ```
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(setOf("hp"), scene.defs.keys)
    }

    @Test
    fun `unclosed def fence during streaming does not register`() {
        val scene = collectNovexArtifactScene(
            listOf(
                msg("```novex-def\n{\"id\":\"x\",\"kind\":\"math\"}\nHP {{hp}}\n"),
            ),
        )
        assertTrue(scene.defs.isEmpty())
    }

    @Test
    fun `card-seeded defs stay in the registry`() {
        val seed = collectDefsFromText(
            """
            ```novex-def
            {"id":"hp","kind":"math","slot":"bottom"}
            HP {{hp}}
            ```
            """.trimIndent(),
        ).associateBy { it.id }
        assertEquals(setOf("hp"), seed.keys)
        val scene = collectNovexArtifactScene(
            listOf(msg("""```novex-use
{"id":"hp","hp":88}
```""")),
            seedDefs = seed,
        )
        assertEquals(setOf("hp"), scene.defs.keys)
    }

    @Test
    fun `in-message def overrides card-seeded def of same id`() {
        val seed = collectDefsFromText(
            """```novex-def
{"id":"hp","kind":"math"}
OLD {{hp}}
```""",
        ).associateBy { it.id }
        val scene = collectNovexArtifactScene(
            listOf(msg("""```novex-def
{"id":"hp","kind":"html"}
<div>{{hp}}</div>
```""")),
            seedDefs = seed,
        )
        assertEquals("html", scene.defs.getValue("hp").kind)
        assertEquals("<div>{{hp}}</div>", scene.defs.getValue("hp").template)
    }

    @Test
    fun `skin language gate is independent of def and use`() {
        assertTrue(isNovexSkinLanguage("novex-skin"))
        assertTrue(isNovexSkinLanguage(" NOVEX-SKIN "))
        assertTrue(!isNovexSkinLanguage("novex-def"))
        assertTrue(!isNovexDefLanguage("novex-skin"))
        assertTrue(!isNovexUseLanguage("novex-skin"))
    }

    @Test
    fun `skin fence returns latest block body`() {
        val text = """
            ```novex-skin
            {"name":"旧皮"}
            ```
            中间有正文
            ```novex-skin
            {"name":"新皮"}
            ```
        """.trimIndent()
        assertEquals("""{"name":"新皮"}""", latestNovexSkinFence(text)?.trim())
        assertNull(latestNovexSkinFence("没有围栏的文本"))
    }

    @Test
    fun `ChatSkin v1 token parse, legacy slot migration, base resolution`() {
        // v1 令牌格式：fill/texture/border/shadow/padding/maxWidth/
        // contentScheme/corners/input/background(scrim)。
        val skin = com.openminis.app.data.character.ChatSkin.fromJsonString(
            """
            {
              "spec": 1,
              "name": "墨玉羊皮",
              "base": "minis://shared/skins/demo/",
              "bubbleAssistant": {
                "fill": "#E9DDBB",
                "texture": {"img": "./paper.png", "tile": true, "alpha": 0.4},
                "border": {"color": "#A9853F", "width": 1, "radius": 12},
                "padding": [15, 11, 15, 11],
                "maxWidth": 330,
                "contentScheme": "light",
                "corners": {"img": "./corner.png", "size": 16, "inset": 3}
              },
              "bubbleUser": {
                "fill": "#402E20",
                "border": {"color": "#9C7B45", "width": 1, "radius": 16},
                "contentScheme": "dark",
                "variants": {"queued": {"fill": "#00000000"}}
              },
              "avatarFrame": {"color": "#C9A96A", "width": 1.5},
              "topBar": {"fill": "#1A1408", "hairline": {"color": "#8A6F3F", "edge": "bottom"}},
              "input": {"bg": "#241B12", "border": "#7A6036", "text": "#EFE3C8"},
              "background": {"img": "./bg.png", "scrim": "#5C000000"},
              "accent": "#C9A96A",
              "overlay": "<html>danmaku</html>"
            }
            """.trimIndent(),
        )!!
        assertEquals("墨玉羊皮", skin.name)
        val a = skin.bubbleAssistant!!
        assertEquals("#E9DDBB", a.fill)
        assertEquals("./paper.png", a.texture?.img)
        assertEquals("#A9853F", a.border?.color)
        assertEquals(listOf(15, 11, 15, 11), a.padding)
        assertEquals("light", a.contentScheme)
        assertEquals("./corner.png", a.corners?.img)
        assertEquals("dark", skin.bubbleUser?.contentScheme)
        assertEquals("#00000000", skin.bubbleUser?.variants?.get("queued")?.fill)
        assertEquals("#241B12", skin.inputBg)
        assertEquals("#5C000000", skin.background?.scrim)
        assertEquals("#C9A96A", skin.accent)
        assertEquals("<html>danmaku</html>", skin.overlay)

        // 相对路径挂到 base；绝对路径原样；无 base 的相对路径解析不出。
        assertEquals("minis://shared/skins/demo/paper.png",
            skin.resolveAssetPath("./paper.png"))
        assertEquals("minis://x/y.png", skin.resolveAssetPath("minis://x/y.png"))
        assertNull(skin.copy(base = null).resolveAssetPath("./paper.png"))

        // 旧格式 {"img","insets","text"} → slice 令牌等价迁移。
        val legacy = com.openminis.app.data.character.ChatSkin.fromJsonString(
            """{"bubbleUser": {"img": "minis://a.png", "insets": [24, 20, 24, 20], "text": "#F5E9D0"}}"""
        )!!
        assertEquals("minis://a.png", legacy.bubbleUser?.slice?.img)
        assertEquals(listOf(24, 20, 24, 20), legacy.bubbleUser?.slice?.content)
        assertEquals("#F5E9D0", legacy.bubbleUser?.text)

        // 空壳与非法值 → null（卸肤语义由调用方处理）。
        assertNull(com.openminis.app.data.character.ChatSkin.fromJsonString("{}"))
        assertNull(com.openminis.app.data.character.ChatSkin.fromJsonString("""{"name":"只有名字"}"""))
        assertNull(com.openminis.app.data.character.ChatSkin.fromJsonString(
            """{"bubbleUser": {}}"""))
        assertNull(com.openminis.app.data.character.ChatSkin.fromJsonString("不是 json"))
        assertNull(com.openminis.app.data.character.ChatSkin.fromJsonString(null))
    }

    @Test
    fun `def parses marker and tavern compat meta`() {
        val def = parseNovexDef(
            """
            {"id":"status","kind":"html","name":"状态栏","marker":"<StatusPlaceHolderImpl/>","compat":"tavern"}
            <div>page</div>
            """.trimIndent(),
        )!!
        assertEquals("<StatusPlaceHolderImpl/>", def.marker)
        assertTrue(def.tavernCompat)
        // 未声明 → 无标记、无适配（模型自写岛保持纯净）。
        val plain = parseNovexDef("""{"id":"x","kind":"html"}
<div/>""")!!
        assertNull(plain.marker)
        assertTrue(!plain.tavernCompat)
    }

    @Test
    fun `marker expansion rewrites literal to use fence at its position`() {
        val defs = mapOf("status" to NovexArtifactDef(
            id = "status", kind = "html", name = null, defaultSlot = null,
            template = "<div/>", marker = "<StatusPlaceHolderImpl/>"))
        val out = expandNovexMarkers("前文。<StatusPlaceHolderImpl/> 后文。", defs)
        assertTrue("```novex-use" in out)
        assertTrue("\"id\":\"status\"" in out.replace("\\",""))
        assertTrue(out.indexOf("前文") < out.indexOf("novex-use") && out.indexOf("novex-use") < out.indexOf("后文"))
        // 原文不被修改：无标记消息原样返回同一实例。
        val plain = "没有标记的消息"
        assertTrue(expandNovexMarkers(plain, defs) === plain)
        assertTrue(expandNovexMarkers("<StatusPlaceHolderImpl/>", emptyMap()) == "<StatusPlaceHolderImpl/>")
    }

    @Test
    fun `autoMarker appends absent marker at whole-text level only`() {
        val defs = mapOf("status" to NovexArtifactDef(
            id = "status", kind = "html", name = null, defaultSlot = null,
            template = "<div/>", marker = "<StatusPlaceHolderImpl/>", autoMarker = true))
        // 整条级调用：标记缺席时补到文末并展开成 use 围栏。
        val out = expandNovexMarkers("正文。", defs, appendAutoMarkers = true)
        assertTrue(out.indexOf("正文") < out.indexOf("novex-use"))
        // 已含标记不重复补；片段级调用（默认 false）不补。
        val has = expandNovexMarkers("带 <StatusPlaceHolderImpl/> 标记。", defs, appendAutoMarkers = true)
        assertEquals(1, Regex("novex-use").findAll(has).count())
        assertTrue(expandNovexMarkers("片段。", defs) === "片段。")
    }

    @Test
    fun `fullscreen flag follows display meta and tavern compat default`() {
        // 显式声明优先；缺省时 compat:tavern 的整页资产一律全屏，原生件内联。
        assertTrue(parseNovexDef("""{"id":"a","kind":"html","display":"fullscreen"}
<div/>""")!!.fullscreen)
        assertTrue(parseNovexDef("""{"id":"b","kind":"html","compat":"tavern"}
<div/>""")!!.fullscreen)
        assertTrue(!parseNovexDef("""{"id":"c","kind":"html"}
<div/>""")!!.fullscreen)
        // display:"inline" 可显式关掉酒馆件的全屏默认。
        assertTrue(!parseNovexDef("""{"id":"d","kind":"html","compat":"tavern","display":"inline"}
<div/>""")!!.fullscreen)
    }

    @Test
    fun `fullscreen anchor marks first use, later uses become entries`() {
        val scene = collectNovexArtifactScene(
            listOf(
                msg(
                    """
                    ```novex-def
                    {"id":"stage","kind":"html","display":"fullscreen"}
                    <div>{{v}}</div>
                    ```
                    ```novex-use
                    {"id":"stage","v":1}
                    ```
                    """.trimIndent(),
                ),
                msg(
                    """
                    中段
                    ```novex-use
                    {"id":"stage","v":2}
                    ```
                    """.trimIndent(),
                ),
            ),
        )
        val anchor = scene.fullscreenAnchors.getValue("stage")
        assertEquals("""{"id":"stage","v":1}""", anchor.code)
        assertEquals(mapOf("v" to "2"), scene.latestUseData.getValue("stage"))
        // 非全屏件不产生锚点；用户消息里的 use 不参与锚定。
        val plain = collectNovexArtifactScene(
            listOf(
                msg("""```novex-def
{"id":"x","kind":"html"}
<div/>
```"""),
                msg("""```novex-use
{"id":"x"}
```"""),
            ),
        )
        assertTrue(plain.fullscreenAnchors.isEmpty())
    }

    @Test
    fun `autoMarker fullscreen def anchors on the appended marker use`() {
        val defs = collectDefsFromText(
            """```novex-def
{"id":"pg","kind":"html","compat":"tavern","marker":"<Pg/>","autoMarker":true}
<div>page</div>
```""",
        ).associateBy { it.id }
        val scene = collectNovexArtifactScene(
            listOf(msg("正文。", role = "assistant")),
            seedDefs = defs,
        )
        // autoMarker 在整条消息末尾补出的 use 即锚点——与显示期展开产物同码。
        val anchor = scene.fullscreenAnchors.getValue("pg")
        assertEquals("""{"id":"pg"}""", anchor.code)
    }

    @Test
    fun `marker wrapped in a code fence is unwrapped then expanded`() {
        val defs = mapOf("status" to NovexArtifactDef(
            id = "status", kind = "html", name = null, defaultSlot = null,
            template = "<div/>", marker = "<StatusPlaceHolderImpl/>"))
        // 模型把标记包进裸代码围栏——展开后的 novex-use 必须出围栏，
        // 否则嵌套围栏按代码渲染。
        val out = expandNovexMarkers("前文。\n\n```\n<StatusPlaceHolderImpl/>\n```\n\n后文。", defs)
        assertTrue("```novex-use" in out)
        // 包裹围栏被拆掉：use 围栏之外只剩一个语言串，没有嵌套反引号。
        assertEquals(1, Regex("```").findAll(out).count() / 2)
        assertTrue(out.indexOf("前文") < out.indexOf("novex-use") && out.indexOf("novex-use") < out.indexOf("后文"))
    }
}
