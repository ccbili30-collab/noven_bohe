package com.openminis.app.ui.chat

/** Presentation only: raw messages, tool results and the incremental flatten cache stay intact. */
internal fun foldNovexExecutionProcesses(input: List<FlatChatItem>): List<FlatChatItem> {
    // A merged assistant run contains a persisted checkpoint per model turn. Show only its latest state.
    var latestTask: FlatChatItem.AssistantInfo? = null
    val retainedTasks = mutableSetOf<String>()
    input.forEach { row ->
        if (row is FlatChatItem.UserBubble) { latestTask?.let { retainedTasks += it.key }; latestTask = null }
        if (row is FlatChatItem.AssistantInfo && row.block.toolName == NovexCardCreationTask.MARKER) latestTask = row
    }
    latestTask?.let { retainedTasks += it.key }
    val rows = input.filterNot { it is FlatChatItem.AssistantInfo && it.block.toolName == NovexCardCreationTask.MARKER && it.key !in retainedTasks }

    val output = mutableListOf<FlatChatItem>()
    var start = 0
    // [T-turn-single-card] 2026-09-16 用户批③（方案 A）→ 2026-10-11
    // [T-turn-segment-fold] 修订：折叠从"整回合一卡"改为"连续段一卡"——
    // 过程行被叙述/回复打断就另起一段，各段在自己的时序位置出卡；单
    // 迭代回合的呈现不变（仍是一张卡钉在回答前），多迭代回合不再错排
    // 成"思考-执行-思考-执行-回复"。
    fun foldable(row: FlatChatItem): Boolean = when (row) {
        // [T-live-tool-tail]（2026-09-17 用户批：参照 dsh/codex）**活流中**的
        // 飞行工具行不折叠：STREAMING/PENDING/RUNNING 留在主文流，配
        // ToolCallPill 的滚动小字尾巴持续可见"正在写什么"；状态落定后的
        // 下一次展平才收进工作行。messageIsStreaming 门槛把恢复路径（journal
        // 重放）产出的历史 PENDING/RUNNING 块挡在外面——那些不是本回合的
        // 活工具，照常折叠（净眼 P1）。
        is FlatChatItem.AssistantToolUse -> row.block.canFoldExecution() &&
            !(row.messageIsStreaming && row.block.toolStatus?.isInFlight() == true)
        is FlatChatItem.AssistantText -> row.block.presentationChannel() == NovexPresentationChannel.PROCESS_TEXT
        is FlatChatItem.AssistantMarkdownBlock -> row.executionText
        // [T-thinking-live] 进行中的思考块不折叠（用户 2026-09-15：思考应
        // 先实时显示文字，输出完再收档）：留在直播区由 ThinkingBlock 展开
        // 渲染、结束自动收起；后续块到达或回合冻结后，它才被收进工作记录。
        is FlatChatItem.AssistantThinking -> row.block.presentationChannel() == NovexPresentationChannel.THINKING &&
            !(row.isLastBlockOverall && row.messageIsStreaming)
        else -> false
    }
    fun flush(end: Int) {
        val turn = rows.subList(start, end)
        // [T-turn-segment-fold] 2026-10-11 决策修订：折叠按**连续段**而非整
        // 回合——多迭代回合（思考-执行-回复-思考-执行）里，回复后新到的过
        // 程若并入钉在回答前的唯一工作卡，视觉上就变成错排的"思考-执行-
        // 思考-执行-回复"。改为每段连续过程行在自己的时序位置折一张卡：
        // 回答前的过程 → 卡在回答前；回答后再来的过程 → 新卡在回答下面。
        // 单迭代回合仍是"一张卡钉在回答前"，语义不变。
        // 连续段：可折行紧挨算一段，被叙述/交互行打断就另起一段。
        val foldGroups = mutableListOf<MutableList<FlatChatItem>>()
        var prevWasFoldable = false
        for (row in turn) {
            if (foldable(row)) {
                if (prevWasFoldable) foldGroups.last() += row
                else foldGroups += mutableListOf(row)
                prevWasFoldable = true
            } else {
                prevWasFoldable = false
            }
        }
        // 纯 thinking 段已有专用折叠渲染，不换工作卡（沿用旧规则）。
        val groups = foldGroups.filter { group ->
            group.any { it is FlatChatItem.AssistantToolUse } ||
                group.any { it is FlatChatItem.AssistantText || it is FlatChatItem.AssistantMarkdownBlock }
        }
        if (groups.isEmpty()) { output += turn; start = end; return }

        val groupOfKey = HashMap<String, FlatChatItem.AssistantProcess>()
        // [T-live-tool-tail]（净眼 P2）飞行工具的进行中状态并入所在段的卡。
        val liveStatuses = turn.filterIsInstance<FlatChatItem.AssistantToolUse>()
            .filter { it.messageIsStreaming && it.block.toolStatus?.isInFlight() == true }
            .mapNotNull { it.block.toolStatus }
        for (group in groups) {
            val first = group.first()
            val messageId = when (first) {
                is FlatChatItem.AssistantToolUse -> first.messageId
                is FlatChatItem.AssistantText -> first.messageId
                is FlatChatItem.AssistantMarkdownBlock -> first.messageId
                is FlatChatItem.AssistantThinking -> first.messageId
                else -> error("Unexpected execution row")
            }
            val process = FlatChatItem.AssistantProcess(
                messageId, group, first.key,
                if (group == groups.last()) liveStatuses else emptyList(),
            )
            for (row in group) groupOfKey[row.key] = process
        }
        // 逐行回放：遇到一段的起点行就吐该段的卡；过程行本身不吐。
        val emitted = mutableSetOf<FlatChatItem.AssistantProcess>()
        for (row in turn) {
            val process = groupOfKey[row.key]
            when {
                process == null -> output += row
                process !in emitted -> { output += process; emitted += process }
                else -> Unit
            }
        }
        start = end
    }
    rows.forEachIndexed { index, row ->
        // 段边界：新回合开头（用户泡/助手头）与回合收尾（切换器/后续
        // 分支口）都断开。AssistantHeader 是防御项——system 等不产切换
        // 器的消息若将来带可折叠行，也不许跨消息并入同一卡。
        if (row is FlatChatItem.UserBubble ||
            row is FlatChatItem.AssistantHeader ||
            row is FlatChatItem.BranchSwitcher ||
            row is FlatChatItem.ChildBranchPicker
        ) flush(index)
    }
    flush(rows.size)
    return output
}

// Interactive outputs remain directly usable. Every other tool state belongs to the process area.
private fun AssistantBlock.canFoldExecution(): Boolean =
    // Interactive results stay on the main transcript so a failed choice,
    // panel, or image action remains visible and actionable.  Non-interactive
    // tool I/O belongs to the process disclosure regardless of final status.
    toolName !in setOf("present_choices", "render_panel", "panel", "present_system_panel", "generate_image")

internal fun FlatChatItem.AssistantProcess.statusLabel(): String {
    val states = tools.map { it.block.toolStatus }
    return when {
        // [T-live-tool-tail] 主文流里还有飞行工具（被豁免未折）——回合仍在
        // 进行，即使已折部分全部落定（净眼 P2 修复）。
        liveToolStatuses.isNotEmpty() -> "进行中"
        states.any { it == ToolBlockStatus.RUNNING || it == ToolBlockStatus.STREAMING || it == ToolBlockStatus.PENDING } -> "进行中"
        states.any { it == ToolBlockStatus.FAILED || it == ToolBlockStatus.TIMEOUT } -> "有操作未完成"
        states.any { it == ToolBlockStatus.CANCELLED } -> "已停止"
        rows.any { it is FlatChatItem.AssistantText && it.isStreaming || it is FlatChatItem.AssistantThinking && it.messageIsStreaming } -> "进行中"
        else -> "查看记录"
    }
}

/** [T-live-tool-tail] 工具是否仍在飞行中（参数流式/待执行/执行中）——飞行中不折叠、挂滚动尾巴。 */
internal fun ToolBlockStatus.isInFlight(): Boolean =
    this == ToolBlockStatus.STREAMING || this == ToolBlockStatus.PENDING || this == ToolBlockStatus.RUNNING
