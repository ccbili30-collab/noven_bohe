package com.openminis.app.cards

import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.ModuleRouting
import novex.content.ModuleUse
import novex.core.NovexDocumentBlock
import novex.core.NovexDocumentBlockKind
import novex.core.NovexDocumentFormat
import novex.core.NovexDocumentSnapshot
import novex.core.NovexDocumentSourceAnchor
import novex.core.NovexDocumentStatus
import novex.storage.CardStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovexDocumentAttachmentCardTest {
    private val store by lazy {
        CardStore(File(RuntimeEnvironment.getApplication().filesDir, "doccard-test/${System.nanoTime()}").toPath())
    }

    private fun block(order: Int, kind: NovexDocumentBlockKind, text: String, path: List<String> = emptyList()) =
        NovexDocumentBlock(
            id = "block_$order",
            kind = kind,
            order = order,
            text = text,
            headingPath = path,
            headingLevel = path.size.takeIf { it > 0 },
            source = NovexDocumentSourceAnchor(part = "word/document.xml", ordinal = order),
        )

    private fun snapshot(blocks: List<NovexDocumentBlock>, sha: String = "a".repeat(64)) =
        NovexDocumentSnapshot(
            ref = novex.core.NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "test",
            title = "机制玩法",
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = blocks,
        )

    @Test
    fun `materialize produces outline plus keyword sections`() {
        val doc = snapshot(
            listOf(
                block(0, NovexDocumentBlockKind.HEADING, "序章", listOf("序章")),
                block(1, NovexDocumentBlockKind.PARAGRAPH, "世界观背景设定", listOf("序章")),
                block(2, NovexDocumentBlockKind.HEADING, "复苏规则", listOf("复苏规则")),
                block(3, NovexDocumentBlockKind.PARAGRAPH, "死亡触发复苏", listOf("复苏规则")),
            ),
        )
        val rootId = NovexDocumentAttachmentCard.materialize(store, doc)
        val card = store.open(rootId)!!.content
        assertEquals(CardKind.WORLD, card.kind)
        val modules = card.modules
        assertEquals(1 + 2, modules.size)
        // STANDBY + Always 在采用层 = 每轮必中（ModuleAdoption: routing 先于 use 分派）。
        assertTrue(modules[0].use is ModuleUse.Always)
        assertEquals(ModuleRouting.STANDBY, modules[0].routing)
        assertEquals("复苏规则", modules[2].name)
        val keywords = modules[2].use as ModuleUse.Keywords
        assertTrue("复苏规则" in keywords.words)
        assertEquals(ModuleRouting.STANDBY, modules[2].routing)
    }

    @Test
    fun `materialize is idempotent for identical snapshot`() {
        val doc = snapshot(listOf(block(0, NovexDocumentBlockKind.PARAGRAPH, "正文")))
        val first = NovexDocumentAttachmentCard.materialize(store, doc)
        val firstRev = store.open(first)!!.revision
        val second = NovexDocumentAttachmentCard.materialize(store, doc)
        assertEquals(first, second)
        assertEquals(firstRev, store.open(first)!!.revision)
    }

    @Test
    fun `oversize section splits into numbered modules`() {
        val huge = "长".repeat(NovexDocumentAttachmentCard.SECTION_CHAR_LIMIT + 100)
        val doc = snapshot(
            listOf(
                block(0, NovexDocumentBlockKind.HEADING, "巨章", listOf("巨章")),
                block(1, NovexDocumentBlockKind.PARAGRAPH, huge, listOf("巨章")),
            ),
        )
        val card = store.open(NovexDocumentAttachmentCard.materialize(store, doc))!!.content
        assertEquals(3, card.modules.size)
        assertEquals("巨章", card.modules[1].name)
        assertEquals("巨章（续2）", card.modules[2].name)
    }

    @Test
    fun `materialized text is readable back from content store`() {
        val doc = snapshot(
            listOf(
                block(0, NovexDocumentBlockKind.HEADING, "复苏规则", listOf("复苏规则")),
                block(1, NovexDocumentBlockKind.PARAGRAPH, "死亡触发复苏", listOf("复苏规则")),
            ),
        )
        val card = store.open(NovexDocumentAttachmentCard.materialize(store, doc))!!
        val ref = card.content.modules[1].blocks.filterIsInstance<ContentBlock.Text>().first().content
        val text = store.contents.open(ref).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(text.contains("死亡触发复苏"))
        assertTrue(text.contains("复苏规则"))
    }
}
