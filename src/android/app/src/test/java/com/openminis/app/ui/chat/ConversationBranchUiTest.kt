package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchUiTest {
    @Test
    fun `user bubble never emits a branch switcher`() {
        // [T-branch-affordance] 2026-10-11 决策：人类输入不挂切换器——
        // 有兄弟版本也不挂；编辑过的旧提问由父行「后续分支」入口回看。
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
    fun `single path assistant reply still shows a one of one switcher`() {
        // [T-branch-affordance] 恒显：分支数钳到 ≥1，单版本也显示 1/1——
        // 位置恒定，不随重试出现/消失。
        val items = buildFlatChatItems(
            listOf(ChatMessage(id = "a1", role = "assistant", content = "唯一版本")),
        )

        assertEquals(FlatChatItem.BranchSwitcher("a1", 0, 1), items.last())
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
