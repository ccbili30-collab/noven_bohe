package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionAnchorsTest {
    @Test
    fun `anchor is keyed by the last item of each assistant message`() {
        val items = listOf(
            FlatChatItem.AssistantHeader("m1"),
            FlatChatItem.AssistantProcess("m1", emptyList(), key = "proc:m1"),
            FlatChatItem.AssistantError("m1", "boom"),
            FlatChatItem.AssistantHeader("m2"),
            FlatChatItem.AssistantText("m2", AssistantBlock("t2", "text", "ok"), false, messageMarkdown = "ok"),
            FlatChatItem.BranchSwitcher("m2", index = 0, count = 2),
        )
        val anchors = assistantActionAnchors(items)

        assertEquals(setOf(items[2].key, items[5].key), anchors.keys)
        assertEquals("m1", anchors.getValue(items[2].key).messageId)
        assertEquals("m2", anchors.getValue(items[5].key).messageId)
        assertFalse(anchors.getValue(items[2].key).isStreaming)
    }

    @Test
    fun `header and switcher only rows emit no action anchor`() {
        // [T-empty-reply-no-actions] 空壳助手行（错挂重试遗留）只有署名和
        // 分支行——没有正文可复制、没有内容可重试，不产操作锚。
        val items = listOf(
            FlatChatItem.AssistantHeader("m1"),
            FlatChatItem.BranchSwitcher("m1", index = 0, count = 1),
        )

        assertTrue(assistantActionAnchors(items).isEmpty())
    }

    @Test
    fun `header rows belong to their message`() {
        assertEquals("m1", FlatChatItem.AssistantHeader("m1").ownerMessageId)
    }

    @Test
    fun `in-flight work row counts as streaming so the row stays hidden`() {
        val items = listOf(
            FlatChatItem.AssistantProcess(
                "m1",
                emptyList(),
                key = "proc:m1",
                liveToolStatuses = listOf(ToolBlockStatus.RUNNING),
            ),
        )
        val anchors = assistantActionAnchors(items)

        assertTrue(anchors.getValue("proc:m1").isStreaming)
    }

    @Test
    fun `settled work row does not block the action row`() {
        val items = listOf(
            FlatChatItem.AssistantProcess(
                "m1",
                emptyList(),
                key = "proc:m1",
                liveToolStatuses = emptyList(),
            ),
        )
        val anchors = assistantActionAnchors(items)

        assertFalse(anchors.getValue("proc:m1").isStreaming)
    }
}
