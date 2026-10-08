package novex.android.ui.cards

import org.junit.Assert.*
import org.junit.Test

/** [T008] 结构化解析行为钉：模板脚手架→结构；自由文本→null 回退。 */
class ModuleStructuredParseTest {
    @Test fun `时间线模板脚手架解析为节点`() {
        val text = """
            ## 纪元元年
            - **事件**：大风暴撕裂海岸
            - **影响**：港都沉没一半

            ## 纪元一百年
            - **事件**：学院建立
            - **参与人**：艾莉丝、诺亚
            - **后果**：魔法成为公共课
        """.trimIndent()
        val parsed = parseStructured(structuredKindFor("时间线")!!, text) as StructuredContent.Timeline
        assertEquals(2, parsed.nodes.size)
        assertEquals("纪元元年", parsed.nodes[0].time)
        assertEquals(listOf("大风暴撕裂海岸"), parsed.nodes[0].events)
        assertEquals(listOf("港都沉没一半"), parsed.nodes[0].consequences)
        assertTrue(parsed.nodes[0].participants.isEmpty())
        assertEquals(listOf("艾莉丝、诺亚"), parsed.nodes[1].participants)
    }

    @Test fun `轨迹与时间线同构`() {
        assertEquals(StructuredKind.TIMELINE, structuredKindFor("轨迹"))
        assertEquals(StructuredKind.TIMELINE, structuredKindFor("时间线"))
        assertEquals(StructuredKind.ATTRIBUTE_PANEL, structuredKindFor("属性面板"))
        assertEquals(StructuredKind.RELATIONS, structuredKindFor("关系"))
        assertNull(structuredKindFor("自定义模块"))
    }

    @Test fun `属性面板表格解析并跳过分隔行`() {
        val text = """
            | 属性 | 值 | 说明 |
            |---|---|---|
            | 等级 | 12 | |
            | 生命 | 240 | 上限 |
        """.trimIndent()
        val parsed = parseStructured(StructuredKind.ATTRIBUTE_PANEL, text) as StructuredContent.Panel
        assertEquals(listOf("属性", "值", "说明"), parsed.columns)
        assertEquals(2, parsed.rows.size)
        assertEquals("等级", parsed.rows[0][0])
        assertEquals("240", parsed.rows[1][1])
    }

    @Test fun `关系条目解析字段`() {
        val text = """
            ## 诺亚
            - **关系**：挚友
            - **熟络度**：87
            - **双向描述**：一起长大的青梅竹马
            - **心结**：从未说出口的歉意

            ## 神秘的旅人
            - **关系**：外部角色
        """.trimIndent()
        val parsed = parseStructured(StructuredKind.RELATIONS, text) as StructuredContent.Relations
        assertEquals(2, parsed.entries.size)
        assertEquals("诺亚", parsed.entries[0].name)
        assertEquals("挚友", parsed.entries[0].type)
        assertEquals("87", parsed.entries[0].familiarity)
        assertEquals("从未说出口的歉意", parsed.entries[0].knot)
        assertEquals("外部角色", parsed.entries[1].type)
        assertNull(parsed.entries[1].knot)
    }

    @Test fun `自由文本回退 null`() {
        assertNull(parseStructured(StructuredKind.TIMELINE, "只是一段自由书写的文字，没有结构。"))
        assertNull(parseStructured(StructuredKind.ATTRIBUTE_PANEL, "没有表格的正文。"))
        // 只有标题没有字段的节点不算时间线
        assertNull(parseStructured(StructuredKind.TIMELINE, "## 纪元\n\n正文一段。"))
    }

    @Test fun `宽松输入的普通列表算时间线事件`() {
        val text = """
            ## 幼年
            - 第一次见到海
            - 学会游泳
        """.trimIndent()
        val parsed = parseStructured(StructuredKind.TIMELINE, text) as StructuredContent.Timeline
        assertEquals(listOf("第一次见到海", "学会游泳"), parsed.nodes[0].events)
    }

    @Test fun `多形态解析本体与分身`() {
        assertEquals(StructuredKind.FORMS, structuredKindFor("多形态"))
        val text = """
            ## 本体
            - **外貌差分**：默认形态
            - **性格偏移**：无

            ## 黑月形态
            - **外貌差分**：黑发红瞳，夜色斗篷
            - **性格偏移**：更冷淡果断
            - **语录**：月色正好
            - 随意一句台词
        """.trimIndent()
        val parsed = parseStructured(StructuredKind.FORMS, text) as StructuredContent.Forms
        assertEquals(2, parsed.entries.size)
        assertEquals("本体", parsed.entries[0].name)
        assertEquals("默认形态", parsed.entries[0].appearanceDelta)
        assertEquals("黑月形态", parsed.entries[1].name)
        assertEquals(listOf("月色正好", "随意一句台词"), parsed.entries[1].quotes)
    }

    @Test fun `空字段多形态回退 null`() {
        assertNull(parseStructured(StructuredKind.FORMS, "自由文本，没有形态。"))
    }
}
