package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchUiTest {
    @Test
    fun `user bubble never emits a branch switcher`() {
        // [T-branch-affordance] 人类输入不挂切换器——有兄弟版本也不挂；
        // 编辑过的旧提问由回复一侧的兄弟翻页器切换。
        val message = ChatMessage(
            id = "u2",
            role = "user",
            content = "第二个版本",
            branchIndex = 2,
            branchCount = 2,
        )

        val items = buildFlatChatItems(listOf(message))

        assertEquals(listOf(FlatChatItem.UserBubble(message)), items)
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
    fun `single path assistant reply emits no switcher`() {
        // [T-branch-affordance] 2026-10-13 修订：只有 1 个版本时翻页器
        // 无导航对象——恒显 1/1 是纯噪音，不再渲染。
        val items = buildFlatChatItems(
            listOf(ChatMessage(id = "a1", role = "assistant", content = "唯一版本")),
        )

        assertTrue(items.none { it is FlatChatItem.BranchSwitcher })
    }

    @Test
    fun `phantom row with siblings but no visible content emits no switcher`() {
        // 零可见内容的助手行（比如只有隐藏工具块的静默轮）——哪怕有兄弟
        // 版本也不该悬空挂一个翻页器。
        val items = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "",
                    toolBlocks = listOf(
                        AssistantBlock(id = "t", kind = "text", content = ""),
                    ),
                    branchIndex = 1,
                    branchCount = 2,
                ),
            ),
        )

        assertTrue(items.none { it is FlatChatItem.BranchSwitcher })
    }

    @Test
    fun `streaming waiting shell emits no branch switcher yet`() {
        // 首个内容未到的等待壳只有打字行——不是回复，切换器等内容到达再出现。
        val items = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "",
                    isStreaming = true,
                    isAwaitingModelResponse = true,
                ),
            ),
        )

        assertTrue(items.none { it is FlatChatItem.BranchSwitcher })
    }
}
