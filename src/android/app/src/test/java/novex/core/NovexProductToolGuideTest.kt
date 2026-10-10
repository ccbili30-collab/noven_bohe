package novex.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-truth] 指南文本必须与现役工具目录一致：现役整合卡工具族
 * （create_card/read_card 等）在场时不得再谎报"不能建卡"；legacy 过滤
 * 集合里的死名（novex_* 管理工具、select_answer_identity、
 * start_interactive_fiction、end_interactive_fiction、世界书族）任何
 * 情况下都不许作为可调用工具写进指南。
 */
class NovexProductToolGuideTest {

    private fun guide(vararg tools: String) = NovexProductToolGuide.build(tools.toSet())

    @Test
    fun creationToolsProduceCreationGuidanceNotDenial() {
        val text = guide("create_card", "create_card_bulk")
        assertTrue(text.contains("create_card_bulk"))
        assertTrue(text.contains("create_card"))
        assertFalse(text.contains("本轮未提供卡片工具"))
    }

    @Test
    fun boundCardToolsProduceReadAndEditGuidance() {
        val text = guide(
            "create_card", "create_card_bulk", "read_card", "read_text_block",
            "write_module_text", "replace_text_range", "add_module", "add_child_module",
            "add_module_bulk", "move_module", "place_module", "remove_module",
            "rename_card", "rename_module", "set_module_options",
        )
        assertTrue(text.contains("read_card"))
        assertTrue(text.contains("read_text_block"))
        assertTrue(text.contains("draft_version"))
        assertFalse(text.contains("本轮未提供卡片工具"))
    }

    @Test
    fun noCardToolsProducesHonestDenial() {
        val text = guide("present_choices", "render_panel")
        assertTrue(text.contains("本轮未提供卡片工具"))
        assertFalse(text.contains("create_card_bulk"))
        assertFalse(text.contains("read_card"))
    }

    @Test
    fun retiredManagementToolNamesAreNeverReferenced() {
        // 即使调用方误把死名塞进 availableTools，指南也不能把它们写成可用指令。
        val text = guide(
            "create_card", "create_card_bulk", "read_card", "read_text_block",
            "set_player_identity", "inspect_story_images", "select_story_image",
            "document_inspect", "document_read", "learning_read",
            "workspace_inspect", "workspace_search", "workspace_read",
            "workspace_write", "workspace_edit", "workspace_compute",
            "save_checkpoint", "render_panel", "register_controls",
            "update_playthrough_state", "present_choices", "generate_image",
            // 死名混入输入也不应出现在输出
            "novex_inspect_content", "novex_write_card", "novex_update_card",
            "novex_write_module", "novex_move_module", "novex_link_cards",
            "novex_propose_content_changes", "novex_apply_content_changes",
            "novex_read_context", "select_answer_identity",
            "start_interactive_fiction", "end_interactive_fiction",
            "inspect_worldbook_choices", "set_game_worldbooks", "set_current_worldbooks",
            "file_read", "file_write", "file_edit", "browser_use", "shell_execute",
        )
        listOf(
            "novex_inspect_content", "novex_write_card", "novex_update_card",
            "novex_write_module", "novex_move_module", "novex_link_cards",
            "novex_propose_content_changes", "novex_apply_content_changes",
            "novex_read_context", "select_answer_identity",
            "start_interactive_fiction", "end_interactive_fiction",
            "inspect_worldbook_choices", "set_game_worldbooks", "set_current_worldbooks",
            "file_read", "file_write", "file_edit", "browser_use", "shell_execute",
            "use_current_player_identity", "put_card_reference", "remove_card_reference",
            "card_references", "card_backlinks", "exchange_compatibility",
            "private_modules", "role_instructions", "answer_identity（独立回答人格）",
            "create_world", "create_character", "create_game", "creation_key",
        ).forEach { dead ->
            assertFalse("指南不得引用已退役名 $dead", text.contains(dead))
        }
    }

    @Test
    fun playerIdentityLineFollowsItsLiveTool() {
        val withTool = guide("create_card", "set_player_identity")
        assertTrue(withTool.contains("set_player_identity"))
        assertFalse(withTool.contains("use_current_player_identity"))
        val without = guide("create_card")
        assertFalse(without.contains("set_player_identity"))
    }

    @Test
    fun conditionalGatesTrackTheirOwnTools() {
        val bare = guide("create_card")
        assertFalse(bare.contains("document_inspect"))
        assertFalse(bare.contains("learning_read"))
        assertFalse(bare.contains("workspace_inspect"))
        assertFalse(bare.contains("inspect_story_images"))
        assertFalse(bare.contains("save_checkpoint"))
        assertFalse(bare.contains("generate_image"))

        val full = guide(
            "create_card", "document_inspect", "document_read", "learning_read",
            "workspace_inspect", "inspect_story_images", "select_story_image",
            "save_checkpoint", "generate_image",
        )
        assertTrue(full.contains("document_inspect"))
        assertTrue(full.contains("learning_read"))
        assertTrue(full.contains("workspace_inspect"))
        assertTrue(full.contains("inspect_story_images"))
        assertTrue(full.contains("save_checkpoint"))
        assertTrue(full.contains("generate_image"))
    }

    @Test
    fun personaDoesNotGrantToolAuthority() {
        val text = guide()
        assertTrue(text.contains("回答人格只影响身份和表达，不增加或取消工具权限"))
    }
}
