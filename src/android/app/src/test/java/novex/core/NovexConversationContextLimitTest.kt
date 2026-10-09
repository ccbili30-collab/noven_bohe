package novex.core

import org.junit.Assert.*
import org.junit.Test

class NovexConversationContextLimitTest {
    @Test fun newConversationStartsAtMinimumAndOldConfigurationKeepsInheritedPolicy() {
        // [T-default-capacity-300k] 默认容量契约更新：64K → 300K（用户决策 2026-09-16）。
        assertEquals(300_000, NovexConversationConfiguration.empty("new").snapshot.contextLimitTokens)
        val old = NovexConversationConfigurationCodec.decode("""{"version":1,"executionMode":"approval"}""", "old")
        assertNull(old.contextLimitTokens)
        assertEquals(128_000, NovexConversationContextLimit.effective(1_000_000, old.contextLimitTokens, 128_000))
    }

    @Test fun conversationCanUseDetectedMillionWithoutChangingGroupOrOtherConversation() {
        val original = NovexConversationConfiguration.empty("chat").snapshot
        val changed = original.copy(contextLimitTokens = NovexConversationContextLimit.selection(1_000_000, 1_000_000))
        val restored = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(changed), "chat")
        assertEquals(1_000_000, restored.contextLimitTokens)
        assertEquals(1_000_000, NovexConversationContextLimit.effective(1_000_000, restored.contextLimitTokens, 128_000))
        assertEquals(300_000, original.contextLimitTokens)
        assertTrue(restored.hasPersistentConfiguration)
    }

    @Test fun modelSwitchClampsCapacityWithoutLosingSavedChoice() {
        assertEquals(32_000, NovexConversationContextLimit.minimum(32_000))
        assertEquals(32_000, NovexConversationContextLimit.effective(32_000, 1_000_000, null))
        assertEquals(1_000_000, NovexConversationContextLimit.effective(1_000_000, 1_000_000, null))
        assertEquals(2_097_152, NovexConversationContextLimit.selection(Int.MAX_VALUE, 2_097_152))
        assertNull(NovexConversationContextLimit.effective(null, 1_000_000, null))
        assertNull(NovexConversationContextLimit.effective(0, 1_000_000, null))
    }

    @Test fun globalDefaultAppliesBelowConversationAndGroup() {
        // 全局默认是最低优先级兜底：会话显式值 > 组显式值 > 全局值 > 模型窗口。
        assertEquals(400_000, NovexConversationContextLimit.effective(1_000_000, null, null, 400_000))
        assertEquals(128_000, NovexConversationContextLimit.effective(1_000_000, null, 128_000, 400_000))
        assertEquals(200_000, NovexConversationContextLimit.effective(1_000_000, 200_000, 128_000, 400_000))
        // 全局默认仍受模型物理窗口钳制。
        assertEquals(64_000, NovexConversationContextLimit.effective(64_000, null, null, 400_000))
        // 未设全局默认时行为不变。
        assertEquals(1_000_000, NovexConversationContextLimit.effective(1_000_000, null, null, null))
    }
}
