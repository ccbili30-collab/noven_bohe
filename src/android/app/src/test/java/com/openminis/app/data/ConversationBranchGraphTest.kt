package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchGraphTest {
    @Test
    fun `retry keeps the old reply and creates a sibling reply branch`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        val fork = graph.forkReplyFrom("u1")

        assertTrue(fork.deletedMessageIds.isEmpty())
        assertEquals("u1", fork.newMessageParentId)
        assertEquals(listOf("u1"), fork.activePathIds)

        val afterReply = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a3", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
                node("a3", "u1", null, 4),
            ),
            activeRootId = "u1",
            activeLeafId = "a3",
        )

        assertEquals(ConversationBranchGraph.SiblingPosition(2, 2), afterReply.siblingPosition("a3"))
        assertEquals(listOf("u1", "a3"), afterReply.activePathIds)

        val oldReply = afterReply.switchSibling("a3", -1)
        assertEquals(listOf("u1", "a1", "u2", "a2"), oldReply.activePathIds)
        assertEquals("a2", oldReply.activeLeafId)
    }

    @Test
    fun `editing creates a sibling user branch without deleting the original timeline`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        val fork = graph.forkEditedMessageFrom("u2")

        assertTrue(fork.deletedMessageIds.isEmpty())
        assertEquals("a1", fork.newMessageParentId)
        assertEquals(listOf("u1", "a1"), fork.activePathIds)
    }

    @Test
    fun `fork is leaf-only so an aborted send leaves the old line reachable`() {
        // fork 不清 active_child——发送中断时旧线指针完好，父级导航一步归位。
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        val fork = graph.forkReplyFrom("u2")

        assertTrue(fork.activeChildUpdates.isEmpty())
        assertEquals("a2", node("u2", "a1", "a2", 2).activeChildId)

        // fork 后的图：叶子截到 u2，而 u2 的子行还在——子分支导航可回。
        val duringFork = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "u2",
        )
        assertEquals("u2", duringFork.activeLeafId)
        assertEquals(
            ConversationBranchGraph.SiblingPosition(1, 1),
            duringFork.childSetPosition("u2"),
        )

        val restore = duringFork.switchChildFrom("u2", +1)
        assertEquals(listOf("u1", "a1", "u2", "a2"), restore.activePathIds)
        assertEquals("a2", restore.activeLeafId)
        assertEquals(mapOf("u2" to "a2"), restore.activeChildUpdates)
    }

    @Test
    fun `switchChildFrom steps across multiple stored children`() {
        // 叶子停在用户消息、其下挂着两条回复（fork 后旧线 + 已落新线）。
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, null, 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", null, 2),
                node("a2", "u1", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "u1",
        )

        assertEquals(
            ConversationBranchGraph.SiblingPosition(0, 2),
            graph.childSetPosition("u1"),
        )

        val intoFirst = graph.switchChildFrom("u1", +1)
        assertEquals("a1", intoFirst.activeChildUpdates["u1"])
        assertEquals(listOf("u1", "a1", "u2"), intoFirst.activePathIds)
    }

    @Test
    fun `siblingPositionForNewChild places an appended row last`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", null, 1),
                node("a2", "u1", null, 2),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        assertEquals(
            ConversationBranchGraph.SiblingPosition(3, 3),
            graph.siblingPositionForNewChild("u1"),
        )
        assertEquals(
            ConversationBranchGraph.SiblingPosition(1, 1),
            graph.siblingPositionForNewChild("a1"),
        )
    }

    @Test
    fun `active path includes tool results and selects only summaries and memories anchored on that path`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "tr1", 1),
                node("tr1", "a1", "a2", 2),
                node("a2", "tr1", null, 3),
                node("a-old", "u1", null, 4),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        assertEquals(listOf("u1", "a1", "tr1", "a2"), graph.activePathIds)
        assertTrue(graph.containsOnActivePath("tr1"))
        assertTrue(graph.containsOnActivePath("a2"))
        assertEquals(false, graph.containsOnActivePath("a-old"))
    }

    @Test
    fun `deleting the active branch falls back to the adjacent sibling and preserves its descendants`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a2", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a3", 2),
                node("a3", "u2", null, 3),
                node("a2", "u1", "u3", 4),
                node("u3", "a2", null, 5),
            ),
            activeRootId = "u1",
            activeLeafId = "u3",
        )

        val deletion = graph.deleteBranchFrom("a2")

        assertEquals(setOf("a2", "u3"), deletion.deletedMessageIds)
        assertEquals(listOf("u1", "a1", "u2", "a3"), deletion.activePathIds)
        assertEquals("a3", deletion.activeLeafId)
    }

    private fun node(
        id: String,
        parentId: String?,
        activeChildId: String?,
        order: Int,
    ) = ConversationBranchGraph.Node(
        id = id,
        parentId = parentId,
        activeChildId = activeChildId,
        order = order,
    )
}
