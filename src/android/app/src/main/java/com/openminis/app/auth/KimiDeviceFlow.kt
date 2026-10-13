package com.openminis.app.auth

/**
 * [T-kimi-oauth] Kimi Code OAuth 的存活常量与判定器。
 *
 * [T-dead-code-sweep] RFC 8628 设备码交互登录链随五家登录入口退役：
 * parseDeviceAuthorization/classifyPoll/bumpedInterval 与
 * DeviceAuthorization/PollResult 类型一并删除——唯一调用方
 * （KimiLoginFlow 的设备登录编排）已不存在。留下的是仍被引用的：
 *  - [TOKEN_URL]/[CODING_API_BASE]：KimiLoginFlow 刷新路径与
 *    ProviderModelRefresh/ProviderFactory 的装配端点；
 *  - [shouldDeleteAfterInvalidGrant]：刷新四分类的「先比对再删除」裁决。
 */
object KimiDeviceFlow {

    const val AUTH_HOST = "https://auth.kimi.com"
    const val TOKEN_URL = "$AUTH_HOST/api/oauth/token"

    /** 编码 API 基址。供应层自行补 `/v1`：真实端点在 `/coding/v1/…` 下，
     *  `/coding/chat/completions` 直接 404。 */
    const val CODING_API_BASE = "https://api.kimi.com/coding"

    /**
     * [T-oauth-refresh-race] invalid_grant 后的「先比对再删除」裁决
     * （iOS 36d506ca）：只有盘上 refresh token 仍是这轮失败请求发出的
     * 那枚时才该删——并发赢家可能已轮换它，此时删的是刚赢来的新登录
     * （“登录 45 分钟后被登出”那一类 bug）。true = 应删凭据。
     */
    fun shouldDeleteAfterInvalidGrant(staleRefreshToken: String, currentStoredRefreshToken: String?): Boolean =
        currentStoredRefreshToken?.equals(staleRefreshToken) ?: true
}
