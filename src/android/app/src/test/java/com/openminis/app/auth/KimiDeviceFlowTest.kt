package com.openminis.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-kimi-oauth] Kimi OAuth 存活面的纯逻辑覆盖：刷新 compare-before-delete
 * 裁决（iOS 36d506ca）。设备码登录链的解析/分类用例已随
 * [T-dead-code-sweep] 登录路径退役删除。
 */
class KimiDeviceFlowTest {

    @Test
    fun `delete only when stored refresh token still equals the failed one`() {
        // Concurrent winner rotated the token → KEEP the new credentials.
        assertFalse(KimiDeviceFlow.shouldDeleteAfterInvalidGrant("old-rt", "new-rt"))
        // No rotation happened → the failed token is genuinely invalid → delete.
        assertTrue(KimiDeviceFlow.shouldDeleteAfterInvalidGrant("old-rt", "old-rt"))
        // Nothing stored anymore → deleting is a no-op-safe default.
        assertTrue(KimiDeviceFlow.shouldDeleteAfterInvalidGrant("old-rt", null))
    }
}
