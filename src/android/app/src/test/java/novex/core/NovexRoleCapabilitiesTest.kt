package novex.core

import com.openminis.app.agent.buildNovexToolWorldSection
import com.openminis.app.data.character.CharacterCard
import com.openminis.app.data.character.CharacterSystemPromptComposer
import com.openminis.app.tools.AgentTools
import org.junit.Assert.*
import org.junit.Test

class NovexRoleCapabilitiesTest {
    @Test
    fun `changing persona retains available standard tools without enabling absent capabilities`() {
        val available = AgentTools.makeAgentTools(
            imageGenerationConfigured = false,
            interactiveFictionActive = true, documentsAvailable = true,
            sourceCollectionsAvailable = true, workspaceAvailable = true,
        ).mapTo(linkedSetOf()) { it.name }
        assertTrue("novex_propose_content_changes" in available)
        assertTrue("workspace_write" in available)
        assertTrue("register_controls" in available)
        assertFalse("shell_execute" in available)
        assertFalse("generate_image" in available)
        assertFalse("novex_apply_memory_changes" in available)
    }

    @Test
    fun `both personas receive the application card creation and source structure protocol`() {
        // [T-prompt-truth] 测试集合模拟生产语义：makeAgentTools 目录仍带
        // novex_* 旧名，legacy 过滤在 ChatViewModel.agentTools 层——角色提示词
        // 会把整个集合原样列入"本次只允许使用这些工具"，所以必须用同一口径
        // 扣掉 legacy 名，否则死名会经原样清单泄漏进提示词。
        val legacy = com.openminis.app.tools.NovexCardFileTools.names +
            com.openminis.app.tools.NovexManagementTools.definitions().map { it.name } +
            com.openminis.app.tools.NovexWorldbookTools.names +
            setOf(com.openminis.app.tools.NovexConversationActionTools.START_GAME,
                com.openminis.app.tools.NovexConversationActionTools.SELECT_IDENTITY,
                "end_interactive_fiction")
        val tools = AgentTools.makeAgentTools().mapTo(linkedSetOf()) { it.name }
            .filterTo(linkedSetOf()) { it !in legacy } +
            setOf("create_card", "create_card_bulk", "read_card", "read_text_block")
        val role = CharacterCard(id = "c1", name = "艾琳", createdAt = 1, updatedAt = 1)
        val prompts = listOf(
            buildNovexToolWorldSection("session", "", tools),
            CharacterSystemPromptComposer.compose(role.toJson().toString(), null, null, tools),
        )
        prompts.forEach { prompt ->
            assertTrue(prompt.contains("默认创建应用内卡片"))
            assertTrue(prompt.contains("章节、主题与条目"))
            assertTrue(prompt.contains("外部卡原文导入可以保留一个完整原文模块并直接使用"))
            assertFalse(prompt.contains("不得把整份资料塞进一个模块"))
            assertTrue(prompt.contains("create_card_bulk"))
            assertTrue(prompt.contains("点击确认执行才提交本次"))
            assertFalse(prompt.contains("novex_update_card"))
            assertFalse(prompt.contains("content_example"))
            assertTrue(prompt.contains("保存后重新读取"))
        }
    }
}
