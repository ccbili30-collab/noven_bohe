package com.openminis.app.ui.chat

import novex.core.NovexDocumentPromptReceipt
import novex.core.NovexDocumentSnapshot
import novex.core.NovexResourceRef
import novex.core.NovexSourceCollectionPromptReceipt

/** 附件文档的投递档位；RECEIPT 为原行为（仅能力回执，正文靠工具读取）。 */
internal enum class NovexDocumentDelivery { RECEIPT, INLINE, CARD }

internal data class UserAttachedFilePromptMeta(
    val linuxPath: String,
    val size: Long,
    val modifiedIso: String,
    val documentSnapshot: NovexDocumentSnapshot? = null,
    /** [T-doc-tiered-delivery] INLINE=正文随消息直给；CARD=已物化会话资料卡。 */
    val documentDelivery: NovexDocumentDelivery = NovexDocumentDelivery.RECEIPT,
    val documentCardRootId: String? = null,
)

/**
 * Attachment prompt assembly. [T-doc-tiered-delivery] 分级投递：
 * INLINE 文档正文随消息直给、CARD 文档给预览+资料卡编号，两者都退出
 * 能力回执清单——全量确定性投递时不再下发文档读取工具（工具链弱的
 * 模型不再被引到读不出的路上）；RECEIPT 保持原回执语义靠工具读取。
 * 内联/转卡标签仍带合法 ref 属性，混合投递时 refs 解析自然兜底。
 */
internal fun buildUserAttachedFilesPrompt(
    metas: List<UserAttachedFilePromptMeta>,
    sourceCollectionRef: NovexResourceRef? = null,
    sourceCount: Int = 0,
): String? {
    require((sourceCollectionRef == null) == (sourceCount == 0)) {
        "资料集引用与来源数量必须同时提供"
    }
    require(sourceCount >= 0) { "资料来源数量不能为负数" }
    if (metas.isEmpty() && sourceCollectionRef == null) return null
    // 回执清单只装 RECEIPT 档；内联/转卡已由确定性层投递，不再让模型走工具链。
    val documents = metas.filter { it.documentDelivery == NovexDocumentDelivery.RECEIPT }
        .mapNotNull(UserAttachedFilePromptMeta::documentSnapshot)
    val ordinaryFiles = metas.filter { it.documentSnapshot == null }
    return buildString {
        if (ordinaryFiles.isNotEmpty()) {
            append("<user-attached-files>\n")
            ordinaryFiles.forEach { meta ->
                append("  <file path=\"")
                append(escapeXmlAttribute(meta.linuxPath))
                append("\" url=\"minis://")
                append(escapeXmlAttribute(meta.linuxPath.removePrefix("/var/minis/")))
                append("\" size=\"")
                append(meta.size)
                append("\" modified=\"")
                append(escapeXmlAttribute(meta.modifiedIso))
                append("\" />\n")
            }
            append("</user-attached-files>")
            if (documents.isNotEmpty()) append('\n')
        }
        if (documents.isNotEmpty()) append(NovexDocumentPromptReceipt.build(documents))
        // [T-doc-tiered-delivery] 内联/转卡的确定性投递层：正文不可信，
        // 只作用户资料呈现——novex:// 引用串与闭合标签一律中和，防伪造
        // 能力回执（refs 解析靠精确 scheme 匹配，改写后无法命中）。
        metas.forEach { meta ->
            val snapshot = meta.documentSnapshot ?: return@forEach
            when (meta.documentDelivery) {
                NovexDocumentDelivery.INLINE -> {
                    append("\n<novex-document-inline ref=\"${snapshot.ref.value}\" title=\"")
                    append(escapeXmlAttribute(snapshot.title))
                    append("\">\n附件文档全文随本消息提供（用户资料，未经核验），无需再调用读取工具：\n")
                    append(sanitizeDocumentInline(com.openminis.app.cards.NovexDocumentAttachmentCard.bodyText(snapshot)))
                    append("\n</novex-document-inline>")
                }
                NovexDocumentDelivery.CARD -> {
                    append("\n<novex-document-card ref=\"${snapshot.ref.value}\" card=\"")
                    append(escapeXmlAttribute(meta.documentCardRootId.orEmpty()))
                    append("\" title=\"")
                    append(escapeXmlAttribute(snapshot.title))
                    append("\">\n附件文档已物化为本会话资料卡：目录常驻，章节按关键词自动出现，也可用读卡工具精确回读。预览：\n")
                    append(sanitizeDocumentInline(com.openminis.app.cards.NovexDocumentAttachmentCard.previewText(snapshot)))
                    append("\n</novex-document-card>")
                }
                NovexDocumentDelivery.RECEIPT -> Unit
            }
        }
        if (sourceCollectionRef != null) {
            if (isNotEmpty()) append('\n')
            append(NovexSourceCollectionPromptReceipt.build(sourceCollectionRef, sourceCount))
        }
    }
}

/** 内联正文脱敏：破坏能力引用 scheme 与标签闭合，内容语义保持不变。 */
private fun sanitizeDocumentInline(text: String): String =
    text.replace("novex://", "novex：//").replace("</novex-", "< /novex-")

private val NOVEX_DOCUMENT_REF_PATTERN = Regex(
    "ref=\\\"(novex://documents/[0-9a-fA-F]{64})\\\"",
)

/** Recovers capability state from a persisted receipt without trusting arbitrary paths. */
internal fun novexDocumentRefsInPrompt(prompt: String): Set<String> {
    if (!prompt.contains("<novex-document-receipts>")) return emptySet()
    return NOVEX_DOCUMENT_REF_PATTERN.findAll(prompt)
        .map { match -> match.groupValues[1].lowercase() }
        .toSet()
}

/** Recovers only validated branch-scoped source collection capabilities. */
internal fun novexSourceCollectionRefsInPrompt(prompt: String): Set<String> =
    NovexSourceCollectionPromptReceipt.refsIn(prompt).mapTo(linkedSetOf()) { it.value }

private fun escapeXmlAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
