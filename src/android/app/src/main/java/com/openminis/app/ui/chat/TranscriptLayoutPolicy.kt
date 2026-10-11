package com.openminis.app.ui.chat

/**
 * Single source of truth for the main conversation list's physical direction.
 *
 * Kept separate from [ScrollFollowPolicy] because layout direction is geometry,
 * while follow permission is interaction state. Mixing the two was what let a
 * passive remeasure look like an authorized scroll.
 */
internal const val CHAT_TRANSCRIPT_REVERSE_LAYOUT = false

internal fun <T> transcriptRowsForLayout(rowsOldestFirst: List<T>): List<T> =
    if (CHAT_TRANSCRIPT_REVERSE_LAYOUT) rowsOldestFirst.asReversed() else rowsOldestFirst

/** Normal chronological lists are at the live tail only when no forward scroll remains. */
internal fun isAtTranscriptLatest(canScrollForward: Boolean): Boolean = !canScrollForward

internal fun latestTranscriptItemIndex(totalItemsCount: Int): Int? =
    (totalItemsCount - 1).takeIf { it >= 0 }

/** Persistence may finish after the user has already started reading history. */
data class SubmittedUserTurn(val messageId: String, val requestedAtMs: Long) {
    fun canNavigateAfter(lastUserDragAtMs: Long): Boolean = requestedAtMs > lastUserDragAtMs
}

internal data class SubmittedTurnNavigation(
    val pendingMessageId: String? = null,
) {
    fun awaiting(messageId: String): SubmittedTurnNavigation =
        copy(pendingMessageId = messageId)

    fun resolve(rowKeys: List<String>): SubmittedTurnNavigationResolution {
        val messageId = pendingMessageId
            ?: return SubmittedTurnNavigationResolution(targetIndex = null, nextState = this)
        val targetIndex = rowKeys.indexOf("user:$messageId").takeIf { it >= 0 }
            ?: return SubmittedTurnNavigationResolution(targetIndex = null, nextState = this)
        return SubmittedTurnNavigationResolution(
            targetIndex = targetIndex,
            nextState = copy(pendingMessageId = null),
        )
    }
}

internal data class SubmittedTurnNavigationResolution(
    val targetIndex: Int?,
    val nextState: SubmittedTurnNavigation,
)

/**
 * 分支切换的视口锚：切换把尾巴换掉时 LazyColumn 的 key 锚失活、按 index
 * 兜底直接落到列表尾——用户看到的是"跳到最新位置"。改为记下切换目标行，
 * 列表重建后滚回它（已在视野内则不动）。pending 保留到命中或超时。
 */
internal data class BranchAnchorNavigation(
    val pendingAnchorMessageId: String? = null,
    val requestedAtMs: Long = 0L,
) {
    fun awaiting(messageId: String, nowMs: Long): BranchAnchorNavigation =
        copy(pendingAnchorMessageId = messageId, requestedAtMs = nowMs)

    fun resolve(items: List<FlatChatItem>, nowMs: Long): Pair<Int?, BranchAnchorNavigation> {
        val id = pendingAnchorMessageId ?: return null to this
        if (nowMs - requestedAtMs > STALE_MS) return null to copy(pendingAnchorMessageId = null)
        // ownerMessageId 只管助手名下的项；锚点也可能是用户行（切的是
        // 用户气泡的分支时），UserBubble 单独匹配。
        val idx = items.indexOfLast { item ->
            item.ownerMessageId == id ||
                (item as? FlatChatItem.UserBubble)?.message?.id == id ||
                (item as? FlatChatItem.UserBubble)?.message?.sourceDbIds?.contains(id) == true
        }
        return if (idx >= 0) idx to copy(pendingAnchorMessageId = null) else null to this
    }

    private companion object { const val STALE_MS = 2_000L }
}

internal enum class TranscriptViewportMove {
    SessionOpened,
    UserSentMessage,
    UserRequestedLatest,
    UserRetriedTurn,
    PassiveStreamGrowth,
    StreamCompleted,
    ImageMeasured,
    ToolCardMeasured,
    KeyboardInsetChanged,
}

/** Only direct navigation intent may move the conversation viewport. */
internal fun allowsTranscriptViewportMove(reason: TranscriptViewportMove): Boolean = when (reason) {
    TranscriptViewportMove.SessionOpened,
    TranscriptViewportMove.UserSentMessage,
    TranscriptViewportMove.UserRequestedLatest,
    TranscriptViewportMove.UserRetriedTurn -> true
    TranscriptViewportMove.PassiveStreamGrowth,
    TranscriptViewportMove.StreamCompleted,
    TranscriptViewportMove.ImageMeasured,
    TranscriptViewportMove.ToolCardMeasured,
    TranscriptViewportMove.KeyboardInsetChanged -> false
}

internal enum class TranscriptFollowEvent {
    UserRequestedLatest,
    /** [feat/ui-rikkahub] A user scroll settled at the live tail — re-stick. */
    UserRestingAtLatest,
    UserDragStarted,
    StreamCompleted,
}

/**
 * Layout growth is followed after explicit navigation or a user scroll that
 * settles at the live tail, until the next drag revokes it. Stream completion
 * can precede persistence and final row measurement, so it cannot revoke follow.
 */
internal data class TranscriptFollowState(
    val isFollowingLatest: Boolean = false,
) {
    fun after(event: TranscriptFollowEvent): TranscriptFollowState = when (event) {
        TranscriptFollowEvent.UserRequestedLatest,
        TranscriptFollowEvent.UserRestingAtLatest -> copy(isFollowingLatest = true)
        TranscriptFollowEvent.UserDragStarted -> copy(isFollowingLatest = false)
        TranscriptFollowEvent.StreamCompleted -> this
    }

    fun shouldMoveFor(reason: TranscriptViewportMove): Boolean =
        allowsTranscriptViewportMove(reason) || isFollowingLatest && reason in followableGrowth

    private companion object {
        val followableGrowth = setOf(
            TranscriptViewportMove.PassiveStreamGrowth,
            TranscriptViewportMove.StreamCompleted,
            TranscriptViewportMove.ImageMeasured,
            TranscriptViewportMove.ToolCardMeasured,
            TranscriptViewportMove.KeyboardInsetChanged,
        )
    }
}
