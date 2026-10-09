package com.openminis.app.cards

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [T-created-scope-fallback] 建卡已 saved 但 managed 回写失败时：
 * 创建调用本身必须如实报 saved（不能报"卡片操作未完成"），且后续
 * read_card/read/edit 判定要能从会话日志恢复本会话创建的范围。
 * 反例：范围不得跨会话泄漏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class CreatedScopeFallbackTest {

    private fun namespace(chat: String, reply: String) = "${chat.length}:$chat${reply.length}:$reply"

    /** 与 CardToolProtocol.parse 的建卡编号推导保持一致。 */
    private fun createdRootId(chat: String, reply: String, callId: String): String {
        val ns = namespace(chat, reply)
        return java.util.UUID.nameUUIDFromBytes(("${ns.length}:$ns$callId").toByteArray()).toString()
    }

    private fun cards(): IntegratedCards = IntegratedCards(
        RuntimeEnvironment.getApplication(),
        readBinding = { CardBinding().encode() },
        // 模拟回写永久不可用（会话未保存/配置写入失败等）：managed 永远登记不上。
        updateBinding = { _ -> error("登记不可用") },
    )

    @Test fun savedCreateSurvivesBindingWritebackFailureAndStaysReadable() = runBlocking {
        val cards = cards()
        val chat = "session-fallback-${System.nanoTime()}"
        val create = cards.execute(chat, "m1", "call-create", "create_card", "{\"kind\":\"WORLD\",\"name\":\"回归世界\"}")
        // 卡片已落库：结果必须如实报 saved，登记失败不得把它说成"未完成"。
        assertTrue(create.output, create.success)
        assertTrue(create.output.contains("saved"))

        // managed 确实没写上；读卡判定改由会话日志中的创建记录兜底。
        val root = createdRootId(chat, "m1", "call-create")
        val read = cards.execute(chat, "m2", "call-read", "read_card", "{\"root_id\":\"$root\",\"target_id\":\"$root\"}")
        assertTrue(read.output, read.success)
        assertTrue(read.output.contains("\"status\":\"read\""))
        assertFalse(read.output.contains("不在当前管理范围"))
    }

    @Test fun sessionCreatedScopeDoesNotLeakAcrossConversations() = runBlocking {
        val cards = cards()
        val chatA = "session-a-${System.nanoTime()}"
        val chatB = "session-b-${System.nanoTime()}"
        cards.execute(chatA, "m1", "call-create", "create_card", "{\"kind\":\"WORLD\",\"name\":\"A的世界\"}")
        val root = createdRootId(chatA, "m1", "call-create")
        val read = cards.execute(chatB, "m2", "call-read", "read_card", "{\"root_id\":\"$root\",\"target_id\":\"$root\"}")
        assertFalse(read.output, read.success)
        assertTrue(read.output.contains("不在当前管理范围"))
    }
}
