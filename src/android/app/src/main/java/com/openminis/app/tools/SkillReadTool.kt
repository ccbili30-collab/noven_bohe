package com.openminis.app.tools

import android.content.Context
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.AgentToolParam
import novex.android.repo.SkillMarkdown
import org.json.JSONObject

/**
 * skill_read 工具：技能挂载（/var/minis/skills/）的窄面只读入口。
 *
 * 通用设备路径工具 file_read/file_write 已随工具裁军退役
 * （NovexRawToolRetirementTest 契约），但技能体系的按需加载
 * 语义本来就是"索引进提示词、正文用时再读"——这个面单独开，
 * 只放行技能目录前缀，不还原通用文件读取。执行层直接复用
 * FileReadTool.execute（同一份挂载表解析与截断协议）。
 */
object SkillReadTool {
    const val NAME = "skill_read"

    /** 允许的虚拟路径前缀：技能根目录及其子路径。 */
    private const val ALLOWED_PREFIX = "${SkillMarkdown.VIRTUAL_ROOT}/"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from an installed skill (its SKILL.md instructions or bundled references). Only paths under the skills directory are readable.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Load island design guide'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Skill file path, e.g. the <path> value listed in <available_skills>"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    /**
     * 路径合法才放行：必须落在技能根下。越界路径给出可纠错文案而不是
     * 静默映射——模型能据此改参数重试。
     */
    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: invalid JSON arguments", false, toolTitle = NAME)
        val path = args.optString("path", "")
        if (!path.startsWith(ALLOWED_PREFIX)) {
            return ToolExecutionResult(
                "Error: skill_read only reads files under ${SkillMarkdown.VIRTUAL_ROOT}/ — got: $path",
                false,
                toolTitle = args.optString("tool_title", NAME),
            )
        }
        return FileReadTool.execute(argsJson, sessionId, context)
    }
}
