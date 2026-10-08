package novex.android.ui.cards

import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentRef
import org.junit.Assert.*
import org.junit.Test

/** [T008-P3] 阅读器 bodyModules 过滤行为钉：简介/总览/novex-hidden 三类不进页签。 */
class BodyModulesFilterTest {
    private fun textModule(id: String, name: String, tags: List<String> = emptyList()) =
        ContentModule(id, name, listOf(ContentBlock.Text("$id-block", ContentRef(id))), tags = tags)

    @Test fun `简介总览与隐藏模块被过滤`() {
        val card = ContentDocument(
            "card", CardKind.CHARACTER, "角色",
            modules = listOf(
                textModule("intro", "简介"),
                textModule("overview", "总览"),
                textModule("visible", "外貌性格"),
                textModule("hidden", "装备", tags = listOf("novex-hidden")),
            ),
        )
        val body = card.bodyModules()
        assertEquals(listOf("外貌性格"), body.map { it.name })
    }

    @Test fun `无隐藏时全部保留`() {
        val card = ContentDocument(
            "card", CardKind.WORLD, "世界",
            modules = listOf(textModule("a", "时间线"), textModule("b", "势力设定")),
        )
        assertEquals(listOf("时间线", "势力设定"), card.bodyModules().map { it.name })
    }
}
