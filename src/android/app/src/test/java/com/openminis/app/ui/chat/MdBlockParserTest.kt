package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面岛解析面的行为钉：围栏闭合严格度（带信息串的 ``` 行不闭合）与
 * 裸 HTML 块拦截（无围栏标记也能进岛，散文中的孤立标签不受牵连）。
 */
class MdBlockParserTest {

    // ── 围栏闭合严格度 ────────────────────────────────────────────────────

    @Test
    fun `info string line inside fenced block does not close it`() {
        // 模型在 ```html 里嵌 ```css 子块很常见——CommonMark 口径下带信息
        // 串的行是内容，不是闭合符。松判定会把岛拦腰截断、后半截漏成文本。
        val blocks = parseMarkdownBlocksBlocking(
            "```html\n<div>a</div>\n```css\n.b{}\n```\n正文",
        )
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("html", code.language)
        assertTrue(code.code.contains("```css"))
        assertTrue(code.code.contains(".b{}"))
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }

    @Test
    fun `bare fence line still closes the block`() {
        val blocks = parseMarkdownBlocksBlocking(
            "```html\n<div></div>\n```\n正文",
        )
        assertEquals(2, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("<div></div>", code.code)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    // ── 裸 HTML 块拦截 ────────────────────────────────────────────────────

    @Test
    fun `unfenced html block routes to island language`() {
        val blocks = parseMarkdownBlocksBlocking(
            "<div class=\"panel\">\n  <b>血量</b> 80\n</div>\n\n后续正文",
        )
        val island = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("html", island.language)
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }

    @Test
    fun `unfenced html directly after prose still splits at tag start`() {
        // 模型常写「下面是界面」紧跟着 <div>（无空行）——段落收集循环必须
        // 在标签起头处断开，否则整段并进段落变成纯文本。
        val blocks = parseMarkdownBlocksBlocking(
            "先看这个\n<div><b>x</b></div>",
        )
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val island = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("html", island.language)
    }

    @Test
    fun `stray tag mention without closer stays paragraph`() {
        // 散文里的孤立标签提及（没有闭合标记）不得被吞进岛。
        val blocks = parseMarkdownBlocksBlocking("可以用 <div 这样的标签")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }
}
