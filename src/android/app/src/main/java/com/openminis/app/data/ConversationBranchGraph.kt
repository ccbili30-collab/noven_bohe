package com.openminis.app.data

/**
 * Resolves and mutates one conversation's persisted message tree.
 *
 * The active transcript starts at the session's active root and follows each
 * message's active child. Sibling messages and all of their descendants remain
 * stored; selecting a sibling only changes the parent's active-child pointer.
 * This module is deliberately pure so loading a branch can never execute a
 * tool, issue a model request, or repeat any other external side effect.
 */
class ConversationBranchGraph private constructor(
    nodes: List<Node>,
    private val activeRootId: String?,
    requestedActiveLeafId: String?,
) {
    data class Node(
        val id: String,
        val parentId: String?,
        val activeChildId: String?,
        val order: Int,
    )

    data class SiblingPosition(val index: Int, val count: Int)

    data class Mutation(
        val activeRootId: String?,
        val activeLeafId: String?,
        val activePathIds: List<String>,
        val activeChildUpdates: Map<String, String?> = emptyMap(),
        val deletedMessageIds: Set<String> = emptySet(),
        val newMessageParentId: String? = null,
    )

    private val byId: Map<String, Node> = nodes.associateBy(Node::id)
    private val childrenByParent: Map<String?, List<Node>> = nodes
        .groupBy(Node::parentId)
        .mapValues { (_, children) -> children.sortedWith(compareBy(Node::order, Node::id)) }

    private val resolvedActivePath: List<Node> = resolveActivePath(
        rootId = activeRootId,
        preferredLeafId = requestedActiveLeafId,
    )

    val activePathIds: List<String> = resolvedActivePath.map(Node::id)

    val activeLeafId: String? = resolvedActivePath.lastOrNull()?.id

    fun containsOnActivePath(messageId: String): Boolean =
        activePathIds.contains(messageId)

    fun siblingPosition(messageId: String): SiblingPosition? {
        val node = byId[messageId] ?: return null
        val siblings = childrenByParent[node.parentId].orEmpty()
        val index = siblings.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        return SiblingPosition(index = index + 1, count = siblings.size)
    }

    /**
     * Position the next appended child of [parentId] would occupy: appends
     * always land as the last sibling, so index == count == existing + 1.
     * Used to tag a just-persisted row before the next graph reload.
     */
    fun siblingPositionForNewChild(parentId: String?): SiblingPosition {
        val count = childrenByParent[parentId].orEmpty().size + 1
        return SiblingPosition(index = count, count = count)
    }

    /**
     * The child-line navigator for a node that is the visible leaf while
     * stored children sit beyond it (forked edit/retry window, or a crash
     * mid-fork). Index is the active child's 1-based position, 0 when no
     * child is currently selected; count is the stored child count.
     */
    fun childSetPosition(messageId: String): SiblingPosition? {
        val node = byId[messageId] ?: return null
        val children = childrenByParent[messageId].orEmpty()
        if (children.isEmpty()) return null
        val index = children.indexOfFirst { it.id == node.activeChildId } + 1
        return SiblingPosition(index = index, count = children.size)
    }

    /**
     * Step through a node's children from the node itself — the parent-side
     * counterpart of [switchSibling]. Selects the adjacent child (wrapping
     * from an unselected state toward the nearest end) and keeps that
     * child's saved tail.
     */
    fun switchChildFrom(messageId: String, delta: Int): Mutation {
        val node = requireNode(messageId)
        val children = childrenByParent[messageId].orEmpty()
        require(children.isNotEmpty()) { "Message $messageId has no child branches" }
        val currentIndex = children.indexOfFirst { it.id == node.activeChildId }
        val targetIndex = (currentIndex + delta).coerceIn(0, children.lastIndex)
        val target = children[targetIndex]
        val prefix = ancestorPath(messageId)
        val tail = activeTailFrom(target.id)
        return mutation(
            rootId = prefix.firstOrNull()?.id,
            path = prefix + tail,
            activeChildUpdates = mapOf(messageId to target.id),
        )
    }

    /** Select the adjacent sibling while keeping that sibling's saved tail. */
    fun switchSibling(messageId: String, delta: Int): Mutation {
        val node = requireNode(messageId)
        val siblings = childrenByParent[node.parentId].orEmpty()
        val currentIndex = siblings.indexOfFirst { it.id == messageId }
        require(currentIndex >= 0) { "Message $messageId is missing from its sibling set" }
        val targetIndex = (currentIndex + delta).coerceIn(0, siblings.lastIndex)
        val target = siblings[targetIndex]
        val prefix = ancestorPath(node.parentId)
        val tail = activeTailFrom(target.id)
        val root = (prefix.firstOrNull() ?: tail.firstOrNull())?.id
        val updates = node.parentId?.let { mapOf(it to target.id) }.orEmpty()
        return mutation(
            rootId = root,
            path = prefix + tail,
            activeChildUpdates = updates,
        )
    }

    /**
     * Re-enter after a user message. The existing reply subtree is retained;
     * the next appended assistant row will be another child of [messageId].
     *
     * Only the leaf anchor moves — the active-child pointer is left in place
     * so the retained line stays pointer-linked until the new reply lands and
     * `markActiveChild` retargets it. If the send never lands (setup failure,
     * process death) the old line is still reachable and [switchChildFrom]
     * restores it in one step.
     */
    fun forkReplyFrom(messageId: String): Mutation {
        requireNode(messageId)
        val path = ancestorPath(messageId)
        return mutation(
            rootId = path.firstOrNull()?.id,
            path = path,
            newMessageParentId = messageId,
        )
    }

    /**
     * Re-enter before an edited message. The next appended user row becomes a
     * sibling of the original row, which remains available for navigation.
     * Same leaf-only contract as [forkReplyFrom].
     */
    fun forkEditedMessageFrom(messageId: String): Mutation {
        val node = requireNode(messageId)
        val path = ancestorPath(node.parentId)
        return mutation(
            rootId = path.firstOrNull()?.id,
            path = path,
            newMessageParentId = node.parentId,
        )
    }

    /** Delete only this subtree and activate its nearest remaining sibling. */
    fun deleteBranchFrom(messageId: String): Mutation {
        val node = requireNode(messageId)
        val deleted = descendantsIncluding(messageId)
        val siblings = childrenByParent[node.parentId].orEmpty()
        val index = siblings.indexOfFirst { it.id == messageId }
        val remainingSiblings = siblings.filterNot { it.id in deleted }
        val fallback = when {
            remainingSiblings.isEmpty() -> null
            index > 0 -> siblings.subList(0, index).lastOrNull { it.id !in deleted }
                ?: remainingSiblings.first()
            else -> remainingSiblings.first()
        }
        val prefix = ancestorPath(node.parentId)
        val tail = fallback?.let { activeTailFrom(it.id, excluded = deleted) }.orEmpty()
        val path = prefix + tail
        val updates = node.parentId?.let { mapOf(it to fallback?.id) }.orEmpty()
        return mutation(
            rootId = (prefix.firstOrNull() ?: tail.firstOrNull())?.id,
            path = path,
            activeChildUpdates = updates,
            deletedMessageIds = deleted,
        )
    }

    private fun mutation(
        rootId: String?,
        path: List<Node>,
        activeChildUpdates: Map<String, String?> = emptyMap(),
        deletedMessageIds: Set<String> = emptySet(),
        newMessageParentId: String? = null,
    ) = Mutation(
        activeRootId = rootId,
        activeLeafId = path.lastOrNull()?.id,
        activePathIds = path.map(Node::id),
        activeChildUpdates = activeChildUpdates,
        deletedMessageIds = deletedMessageIds,
        newMessageParentId = newMessageParentId,
    )

    private fun resolveActivePath(rootId: String?, preferredLeafId: String?): List<Node> {
        val byPointers = rootId?.let(::activeTailFrom).orEmpty()
        if (byPointers.lastOrNull()?.id == preferredLeafId || preferredLeafId == null) {
            return byPointers
        }
        val byLeaf = ancestorPath(preferredLeafId)
        return if (byLeaf.firstOrNull()?.id == rootId) byLeaf else byPointers
    }

    private fun ancestorPath(messageId: String?): List<Node> {
        if (messageId == null) return emptyList()
        val reversed = mutableListOf<Node>()
        val visited = mutableSetOf<String>()
        var cursor: String? = messageId
        while (cursor != null && visited.add(cursor)) {
            val node = byId[cursor] ?: break
            reversed += node
            cursor = node.parentId
        }
        return reversed.asReversed()
    }

    private fun activeTailFrom(
        messageId: String,
        excluded: Set<String> = emptySet(),
    ): List<Node> {
        val path = mutableListOf<Node>()
        val visited = mutableSetOf<String>()
        var cursor: String? = messageId
        while (cursor != null && cursor !in excluded && visited.add(cursor)) {
            val node = byId[cursor] ?: break
            path += node
            cursor = node.activeChildId
        }
        return path
    }

    private fun descendantsIncluding(messageId: String): Set<String> {
        val result = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.add(messageId)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!result.add(id)) continue
            childrenByParent[id].orEmpty().forEach { pending.add(it.id) }
        }
        return result
    }

    private fun requireNode(messageId: String): Node =
        requireNotNull(byId[messageId]) { "Unknown conversation message: $messageId" }

    companion object {
        fun open(
            nodes: List<Node>,
            activeRootId: String?,
            activeLeafId: String?,
        ): ConversationBranchGraph {
            require(nodes.map(Node::id).toSet().size == nodes.size) {
                "Conversation message ids must be unique"
            }
            return ConversationBranchGraph(nodes, activeRootId, activeLeafId)
        }
    }
}
