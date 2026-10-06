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
        // kind 缺省/非法 → 回落 html；slot 非法 → null。
        val def = parseNovexDef("""{"id":"x","kind":"bogus","slot":"sideways"}
<div/>""")!!
        assertEquals("html", def.kind)
        assertNull(def.defaultSlot)
    }

    @Test
    fun `use reads data object and loose keys`() {
        val a = parseNovexUse("""{"id":"hp","slot":"bottom","data":{"hp":42,"max":100}}""")!!
        assertEquals("bottom", a.slot)
        assertEquals(mapOf("hp" to "42", "max" to "100"), a.data)

        val b = parseNovexUse("""{"id":"hp","hp":42,"note":"灼烧"}""")!!
        assertNull(b.slot)
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
    fun `scene collects defs and resolves pin by latest use per id`() {
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
                    ```novex-use
                    {"id":"hp","slot":"inline","hp":40}
                    ```
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(setOf("hp"), scene.defs.keys)
        // 最后一次 use 是 inline → 不占任何钉槽。
        assertTrue(scene.topPins.isEmpty())
        assertTrue(scene.bottomPins.isEmpty())
    }

    @Test
    fun `latest use claims its slot for the pin`() {
        val scene = collectNovexArtifactScene(
            listOf(
                msg(
                    """
                    ```novex-def
                    {"id":"a","kind":"html"}
                    <div>{{v}}</div>
                    ```
                    ```novex-use
                    {"id":"a","slot":"top","v":1}
                    ```
                    """.trimIndent(),
                ),
                msg("""```novex-use
{"id":"a","slot":"bottom","v":2}
```"""),
            ),
        )
        assertTrue(scene.topPins.isEmpty())
        assertEquals(1, scene.bottomPins.size)
        assertEquals(mapOf("v" to "2"), scene.bottomPins.single().second.data)
    }

    @Test
    fun `unclosed fence during streaming does not register`() {
        val scene = collectNovexArtifactScene(
            listOf(
                msg(
                    "```novex-def\n{\"id\":\"x\",\"kind\":\"math\"}\nHP {{hp}}\n```\n" +
                        "```novex-use\n{\"id\":\"x\",\"slot\":\"top\"",
                ),
            ),
        )
        assertEquals(setOf("x"), scene.defs.keys)
        assertTrue(scene.topPins.isEmpty()) // 未闭合的 use 不生效
    }

    @Test
    fun `use without def contributes nothing to pins`() {
        val scene = collectNovexArtifactScene(
            listOf(msg("""```novex-use
{"id":"ghost","slot":"top","v":1}
```""")),
        )
        assertTrue(scene.topPins.isEmpty())
        assertTrue(scene.bottomPins.isEmpty())
    }

    @Test
    fun `card-seeded defs resolve uses without in-message def`() {
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
        // 卡内 def 预铺 + use 无 slot → 用 def 的默认槽位 bottom。
        assertEquals(1, scene.bottomPins.size)
        assertEquals(mapOf("hp" to "88"), scene.bottomPins.single().second.data)
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
}
