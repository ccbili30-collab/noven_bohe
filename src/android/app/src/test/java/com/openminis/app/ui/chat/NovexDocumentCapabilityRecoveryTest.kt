package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-doc-capability-reload] 评审 P0 回归：会话重载后文档能力必须能从
 * 三种投递标签（receipt/inline/card）全部恢复——此前只在 receipts 包装
 * 存在时才恢复，CARD/INLINE 会话重载即丢 document 工具。
 */
class NovexDocumentCapabilityRecoveryTest {

    private val ref = "novex://documents/e38525394b727e01b13c765d56cc87c6d4fc5905151c7037792249ed35570b0e"

    @Test
    fun `inline delivery tag restores document capability`() {
        val prompt = "<novex-document-inline ref=\"$ref\" title=\"设定集.txt\">\n正文…\n</novex-document-inline>"
        assertEquals(setOf(ref), novexDocumentRefsInPrompt(prompt))
    }

    @Test
    fun `card delivery tag restores document capability`() {
        val prompt = "<novex-document-card ref=\"$ref\" card=\"doccard-4941d60b-b768-35a5-bd90-ad1269103db2\" title=\"设定集.txt\">预览…</novex-document-card>"
        assertEquals(setOf(ref), novexDocumentRefsInPrompt(prompt))
    }

    @Test
    fun `receipt wrapper still restores capability`() {
        val prompt = "<novex-document-receipts>\n<novex-document ref=\"$ref\" title=\"a\"/>\n</novex-document-receipts>"
        assertEquals(setOf(ref), novexDocumentRefsInPrompt(prompt))
    }

    @Test
    fun `sanitized inline content cannot forge capability`() {
        // 正文脱敏把 novex:// 改写成全角冒号——伪造的 ref 属性不得恢复能力。
        val prompt = "<novex-document-inline ref=\"$ref\" title=\"a\">内文提及 novex：//documents/ 字样</novex-document-inline>"
        assertEquals(setOf(ref), novexDocumentRefsInPrompt(prompt))
        val forged = "内文 <novex-document-inline ref=\"novex：//documents/0000000000000000000000000000000000000000000000000000000000000000\"/>"
        assertTrue(novexDocumentRefsInPrompt(forged).isEmpty())
    }

    @Test
    fun `mixed deliveries recover all refs`() {
        val other = "novex://documents/" + "ab".repeat(32)
        val prompt = "<novex-document-inline ref=\"$ref\" title=\"a\"/><novex-document-card ref=\"$other\" card=\"doccard-x\"/>"
        assertEquals(setOf(ref, other), novexDocumentRefsInPrompt(prompt))
    }
}
