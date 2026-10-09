package com.openminis.app.cards

import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentRef
import novex.content.ContentTransfer
import novex.content.ModuleRouting
import novex.content.ModuleTemporality
import novex.content.ModuleUse
import novex.core.NovexDocumentBlock
import novex.core.NovexDocumentBlockKind
import novex.core.NovexDocumentSnapshot
import novex.storage.CardStore
import novex.storage.ChangeSource

/**
 * [T-doc-tiered-delivery] 附件文档→会话资料卡物化。
 *
 * 正文不进 prompt、靠模型主动调工具的投递方式在工具链弱的模型上等于
 * 不送达（DeepSeek 解读 docx 失败的实际根因）。改为确定性投递：
 * 小文档全文内联；大文档物化为 WORLD 资料卡——大纲模块常驻注入当
 * 地图，章节模块走关键词触发注入，同时进管理范围供 read_card 精确
 * 回读。三档语义与卡生态原生 routing×use 对齐，不新造机制。
 *
 * 卡片编号由快照校验值派生：同一文档重复附加/重发幂等，不重复建卡。
 */
object NovexDocumentAttachmentCard {
    /** 正文小于此值直接全量内联，不建卡。 */
    const val INLINE_CHAR_LIMIT = 30_000
    /** 单个章节模块的正文上限；超长节按序分块，共享同一触发词。 */
    const val SECTION_CHAR_LIMIT = 8_000
    /** 大卡内联预览（大纲+开头正文）的字符上限。 */
    const val PREVIEW_CHAR_LIMIT = 6_000

    fun cardIdFor(snapshot: NovexDocumentSnapshot): String =
        "doccard-" + java.util.UUID.nameUUIDFromBytes(
            "novex-doc-card:${snapshot.sha256}".toByteArray(Charsets.UTF_8),
        ).toString()

    /** 幂等：同快照重复调用直接复用已存在的卡。返回卡根编号。 */
    fun materialize(store: CardStore, snapshot: NovexDocumentSnapshot): String {
        val cardId = cardIdFor(snapshot)
        if (store.open(cardId) != null) return cardId
        val modules = buildList {
            add(outlineModule(store, snapshot))
            addAll(sectionModules(store, snapshot))
        }
        runCatching {
            store.save(
                ContentDocument(cardId, CardKind.WORLD, "资料：${snapshot.title}", modules).validate(),
                null, ChangeSource.AI, "attachment:${snapshot.sha256.take(12)}",
            )
        }.getOrElse { failure ->
            // 并发/重试下另一物化可能已抢先落库：卡存在即幂等达成，其余异常上抛。
            if (store.open(cardId) != null) return cardId
            throw failure
        }
        return cardId
    }

    /** 全文正文（用于内联投递）。 */
    fun bodyText(snapshot: NovexDocumentSnapshot): String =
        snapshot.blocks.joinToString("\n") { it.text }.trim()

    /** 大卡预览：目录行 + 截断的开头正文。 */
    fun previewText(snapshot: NovexDocumentSnapshot): String {
        val outline = outlineLines(snapshot).joinToString("\n")
        val head = bodyText(snapshot).take(PREVIEW_CHAR_LIMIT)
        return buildString {
            if (outline.isNotBlank()) append("【目录】\n").append(outline).append("\n\n")
            append("【正文开头】\n").append(head)
        }.trim()
    }

    private fun outlineLines(snapshot: NovexDocumentSnapshot): List<String> =
        snapshot.blocks.filter { it.kind == NovexDocumentBlockKind.HEADING }
            .map { "${"  ".repeat((it.headingLevel ?: 1) - 1)}${it.text}" }

    private fun outlineModule(store: CardStore, snapshot: NovexDocumentSnapshot): ContentModule {
        val sections = sections(snapshot)
        val text = buildString {
            append("附件文档《").append(snapshot.title).append("》的资料卡。")
            append("共 ").append(sections.size).append(" 节；聊到章节标题相关内容时细则会自动出现，")
            append("也可用读卡工具精确回读。\n\n")
            outlineLines(snapshot).forEach { append(it).append('\n') }
        }.trim()
        return ContentModule(
            id = "mod-outline", name = "文档说明与目录",
            blocks = listOf(textBlock(store, "mod-outline-b1", text)),
            use = ModuleUse.Always, routing = ModuleRouting.STANDBY,
            temporality = ModuleTemporality.CONSTANT,
        )
    }

    private fun sectionModules(store: CardStore, snapshot: NovexDocumentSnapshot): List<ContentModule> =
        sections(snapshot).flatMapIndexed { index, section ->
            section.chunks().mapIndexed { part, chunkText ->
                val title = if (part == 0) section.title else "${section.title}（续${part + 1}）"
                ContentModule(
                    id = "mod-sec-$index-$part", name = title,
                    blocks = listOf(textBlock(store, "mod-sec-$index-$part-b1", chunkText)),
                    use = ModuleUse.Keywords(words = triggerWords(section.title, snapshot.title),
                        caseSensitive = false, requireAll = false),
                    routing = ModuleRouting.STANDBY,
                    temporality = ModuleTemporality.CONSTANT,
                )
            }
        }

    private data class Section(val title: String, val text: String) {
        fun chunks(): List<String> {
            if (text.length <= SECTION_CHAR_LIMIT) return listOf(text)
            val out = mutableListOf<String>()
            var rest = text
            while (rest.isNotEmpty()) {
                val cut = rest.take(SECTION_CHAR_LIMIT)
                val boundary = cut.lastIndexOf('\n').takeIf { it > SECTION_CHAR_LIMIT / 2 } ?: cut.length
                out += rest.take(boundary)
                rest = rest.drop(boundary).trimStart()
            }
            return out
        }
    }

    /** 按顶级标题分节；无标题块全部归入「正文」节。标题块正文落进自己所属的顶级节。 */
    private fun sections(snapshot: NovexDocumentSnapshot): List<Section> {
        val grouped = linkedMapOf<String, StringBuilder>()
        fun bucket(title: String) = grouped.getOrPut(title) { StringBuilder() }
        for (block in snapshot.blocks) {
            bucket(block.headingPath.firstOrNull() ?: "正文").append(block.text).append('\n')
        }
        return grouped.entries.map { (title, text) -> Section(title, text.toString().trim()) }
            .filter { it.text.isNotBlank() }
    }

    /** 触发词：节标题原文 + 去序号前缀的标题；都无标题时回落文档名。 */
    private fun triggerWords(sectionTitle: String, docTitle: String): List<String> {
        val stripped = sectionTitle.replace(Regex("^[第\\d\\s一二三四五六七八九十百千]+[章节条款部分篇回卷、.．\\-—:：\\s]+"), "").trim()
        return listOf(sectionTitle, stripped, docTitle)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
            .ifEmpty { listOf(docTitle.ifBlank { "文档" }) }
    }

    private fun textBlock(store: CardStore, id: String, text: String): ContentBlock {
        val ref = store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("doc-attach"), ref))) { text.byteInputStream(Charsets.UTF_8) }
        return ContentBlock.Text(id, ref)
    }
}
