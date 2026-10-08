package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchUiTest {
    @Test
    fun `user branch switcher appears directly after its bubble`() {
        val message = ChatMessage(
            id = "u2",
            role = "user",
            content = "第二个版本",
            branchIndex = 2,
            branchCount = 2,
        )

        val items = buildFlatChatItems(listOf(message))

        assertTrue(items[0] is FlatChatItem.UserBubble)
        assertEquals(
            FlatChatItem.BranchSwitcher("u2", 2, 2),
            items[1],
        )
    }

    @Test
    fun `assistant switcher sits at the very bottom of the reply`() {
        // 岛也是回复的一部分——分支行沉到整条回复（含岛/工具块）之后，
        // 与玩家气泡下的分支行位置对称。
        val message = ChatMessage(
            id = "a2",
            role = "assistant",
            content = "完成",
            toolBlocks = listOf(
                AssistantBlock(id = "tool", kind = "tool_use", toolName = "file_read"),
                AssistantBlock(id = "text", kind = "text", content = "完成"),
            ),
            branchIndex = 2,
            branchCount = 2,
        )

        val items = buildFlatChatItems(listOf(message))

        assertEquals(1, items.count { it is FlatChatItem.BranchSwitcher })
        assertEquals(
            FlatChatItem.BranchSwitcher("a2", 2, 2),
            items.last(),
        )
    }

    @Test
    fun `assistant switcher keeps the identity header but sits last`() {
        val message = ChatMessage(
            id = "a2",
            role = "assistant",
            content = "完成",
            branchIndex = 1,
            branchCount = 3,
        )

        val items = buildFlatChatItems(listOf(message), showAssistantIdentity = true)

        assertEquals(FlatChatItem.AssistantHeader("a2"), items[0])
        assertEquals(FlatChatItem.BranchSwitcher("a2", 1, 3), items.last())
    }

    @Test
    fun `leaf with stored children emits a child branch picker`() {
        // fork 窗口/截断态：用户消息是可见叶子而库里还有子行。
        val items = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "u1",
                    role = "user",
                    content = "重试点",
                    childBranchIndex = 1,
                    childBranchCount = 2,
                ),
            ),
        )

        assertEquals(FlatChatItem.ChildBranchPicker("u1", 1, 2), items.last())
    }

    @Test
    fun `assistant leaf with stored children emits picker at reply end`() {
        val items = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "旧回复",
                    childBranchIndex = 0,
                    childBranchCount = 1,
                ),
            ),
        )

        assertEquals(FlatChatItem.ChildBranchPicker("a1", 0, 1), items.last())
    }

    @Test
    fun `mid-path message never emits a child picker`() {
        val items = buildFlatChatItems(
            listOf(
                ChatMessage(id = "u1", role = "user", content = "普通消息"),
                ChatMessage(id = "a1", role = "assistant", content = "回复"),
            ),
        )

        assertTrue(items.none { it is FlatChatItem.ChildBranchPicker })
    }

    @Test
    fun `single path message has no branch switcher`() {
        val items = buildFlatChatItems(
            listOf(ChatMessage(id = "u1", role = "user", content = "唯一版本")),
        )

        assertTrue(items.none { it is FlatChatItem.BranchSwitcher })
    }
}
