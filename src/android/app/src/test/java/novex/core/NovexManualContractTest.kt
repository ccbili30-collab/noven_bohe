package novex.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明书（assets/skills/novex-manual）↔ 能力面的契约测试。
 *
 * 钉三件事，防说明书与真实工具面漂移（P0-2 同类病）：
 *  1. FILES.txt 清单与磁盘文件双向一致——种了没登记的章、登记了不存在的
 *     章都算坏。
 *  2. SKILL.md 索引的章节地图覆盖每一章——模型查目录漏掉的章等于不存在。
 *  3. 现役能力名在说明书某章有记载；退役名（novex_*、select_answer_identity
 *     等 legacy 过滤集）不得作为可调用工具出现。
 */
class NovexManualContractTest {

    private fun manualDir(): File {
        for (candidate in listOf(
            "src/main/assets/skills/novex-manual",
            "app/src/main/assets/skills/novex-manual",
        )) {
            val dir = File(candidate)
            if (dir.isDirectory) return dir
        }
        error("novex-manual asset dir not found (cwd=${File("").absolutePath})")
    }

    private fun manifestFiles(): List<String> =
        File(manualDir(), "FILES.txt").readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun file(name: String) = File(manualDir(), name)

    private fun allChapterText(): String =
        manifestFiles().joinToString("\n") { file(it).readText() }

    @Test
    fun `manifest lists every markdown chapter and every entry exists`() {
        val manifest = manifestFiles()
        assertTrue("FILES.txt must list SKILL.md", manifest.contains("SKILL.md"))
        for (name in manifest) {
            assertTrue("manifest entry '$name' missing on disk", file(name).isFile)
        }
        val onDisk = manualDir().listFiles { f -> f.extension == "md" }
            .orEmpty().map { it.name }
        for (name in onDisk) {
            assertTrue("chapter '$name' on disk but not in FILES.txt", manifest.contains(name))
        }
    }

    @Test
    fun `index chapter map covers every chapter`() {
        // 只认章节地图表格行（| `file.md` | ... |）——正文里的示例路径不算登记。
        val index = file("SKILL.md").readText()
        val row = Regex("^\\|\\s*`([^`]+\\.md)`\\s*\\|")
        val mapped = index.lineSequence()
            .mapNotNull { row.find(it.trim())?.groupValues?.get(1) }
            .toSet()
        for (name in manifestFiles().filter { it != "SKILL.md" }) {
            assertTrue("chapter '$name' not registered in SKILL.md chapter map", mapped.contains(name))
        }
        for (name in mapped) {
            assertTrue("SKILL.md chapter map references '$name' not in FILES.txt", manifestFiles().contains(name))
        }
    }

    @Test
    fun `active capabilities are documented`() {
        val body = allChapterText()
        val required = listOf(
            // 卡片族（整合卡现役名）
            "create_card", "create_card_bulk", "read_card", "read_text_block",
            "add_module", "add_module_bulk", "add_child_module",
            "write_module_text", "replace_text_range",
            "move_module", "place_module",
            "rename_card", "rename_module", "set_module_options",
            // 呈现与本局
            "render_panel", "present_choices", "register_controls",
            "update_playthrough_state", "save_checkpoint", "set_player_identity",
            // 资源与联网
            "generate_image", "web_search", "web_fetch", "skill_read",
            "document_inspect", "document_read",
            // 工作区
            "workspace_inspect", "workspace_search", "workspace_read",
            "workspace_write", "workspace_edit", "workspace_compute",
            // 协议关键字
            "NOVEN_FRAME", "novex-def", "novex-use", "novex-skin", "novex-frontend",
        )
        for (name in required) {
            assertTrue("active capability '$name' has no manual coverage", body.contains(name))
        }
    }

    @Test
    fun `retired tool names are not presented as callable`() {
        val body = allChapterText()
        // legacy 过滤集同口径：任何 novex_ 前缀工具名与已退役会话动作都不得
        // 出现在说明书正文（whats-new 等历史叙述也不写工具名）。
        assertFalse(
            "manual still documents novex_* tools: " +
                Regex("\\bnovex_[a-z_]+").findAll(body).map { it.value }.toSet().joinToString(),
            Regex("\\bnovex_[a-z_]").containsMatchIn(body),
        )
        for (dead in listOf(
            "select_answer_identity", "start_interactive_fiction",
            "end_interactive_fiction", "browser_use",
        )) {
            assertFalse("retired tool '$dead' present in manual", body.contains(dead))
        }
    }
}
