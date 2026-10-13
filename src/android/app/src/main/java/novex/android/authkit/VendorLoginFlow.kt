package novex.android.authkit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import org.json.JSONObject

/**
 * 供应商 OAuth 凭据座的公共骨架（P3.5c 自 auth/OAuthManager 真重写）。
 *
 * [T-dead-code-sweep] 「授权页 → localhost 回调 → code 换 token」的交互
 * 登录编排已随五家登录入口一起退役（全仓零调用方）：authorizeEndpoint /
 * loopbackPort / redirectTail / scopeValue / redirectUri / authorizationUrl /
 * nextPkce / nextState / pendingVerifier / pendingState / startBrowserLogin /
 * exchangeCode / exchangeForm / afterTokensPersisted 及 LoopbackReceiver /
 * PkceMaterial / LoopbackRedirectRelay / OAuthRedirectActivity 整链拆除。
 * 留下的是凭据座本体：刷新 / 有效令牌 / 手工 bearer / 跨端导入导出。
 * 导入的供应商凭据仍靠这套活着，子类各自的登录差量（JSON 体交换、
 * OIDC 发现、设备码）已随编排移除。
 *
 * 公开方法名沿用旧门面（validAccessToken / refreshToken / isAuthenticated /
 * logout / …）——调用方遍布全仓且大量走全限定名引用，形状属冻结面。
 *
 * 刷新时序语义（冻结面）：
 *  - [validAccessToken] 的缺省提前刷新窗是 4 小时（供应商普遍 24h 有效期
 *    下的宽口径；Anthropic/Gemini/Kimi 子类收紧到 5 分钟）；
 *  - 手工 bearer 优先级最高——设了就不刷新、原样返回；
 *  - 刷新失败且令牌已实际过期 → 清凭据返回 null；未过期则继续用旧值。
 *
 * @param appContext 任意 Context，内部只当 Application 用
 * @param instanceId 供应商实例 id，拼进全部存储键
 */
abstract class VendorLoginFlow(
    protected val appContext: Context,
    protected val instanceId: String,
) {
    companion object {
        private const val TAG = "VendorLoginFlow"

        /** 缺省提前刷新窗（毫秒）。 */
        private const val DEFAULT_REFRESH_LEEWAY_MS = 4L * 3600 * 1000

        /** 按实例的供应商类型挑凭据座；非 OAuth 型返回 null。 */
        fun forInstance(context: Context, instance: ProviderInstance): VendorLoginFlow? =
            when (instance.providerType) {
                ProviderType.anthropic -> ClaudeLoginFlow(context, instance.id)
                ProviderType.openAI -> CodexLoginFlow(context, instance.id)
                ProviderType.xAI -> XaiLoginFlow(context, instance.id)
                ProviderType.kimiCode -> KimiLoginFlow(context, instance.id)
                else -> null
            }

        /**
         * 旧门面名（冻结面）：把 HTTP 体清理成可进日志的形态——打码已知
         * 凭据字段的值并截断。等价于 [OAuthWire.redactForLog]。
         */
        fun sanitizeBody(body: String, maxLen: Int = 300): String =
            OAuthWire.redactForLog(body, maxLen)
    }

    // ── 各厂差量声明（刷新面）─────────────────────────────────────────

    abstract val tokenEndpoint: String
    abstract val clientIdValue: String
    abstract val clientSecretValue: String?

    /** 子类定制刷新表单体。 */
    protected open fun refreshForm(refreshToken: String): Map<String, String> {
        val form = mutableMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientIdValue,
        )
        clientSecretValue?.let { form["client_secret"] = it }
        return form
    }

    // ── 状态 ───────────────────────────────────────────────────────────

    protected val vault = CredentialVault(appContext, instanceId)

    // ── 刷新与取有效令牌 ───────────────────────────────────────────────

    protected open fun refreshLeewayMs(): Long = DEFAULT_REFRESH_LEEWAY_MS

    open suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = vault.readTokens() ?: return@withContext false
        val rotating = stored.optString("refresh_token", "").ifEmpty { return@withContext false }
        try {
            val (status, body) = OAuthWire.postForm(tokenEndpoint, refreshForm(rotating))
            if (status !in 200..299) {
                Log.e(TAG, "token refresh rejected: $status")
                return@withContext false
            }
            val json = JSONObject(body)
            // 不轮换 refresh_token 的端点会省略该字段——保住旧值。
            if (!json.has("refresh_token")) json.put("refresh_token", rotating)
            vault.writeTokens(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "token refresh error", e)
            false
        }
    }

    /**
     * 取一枚当前可用的 access token：手工 bearer 优先，其次存储令牌，
     * 临期自动刷新；刷新失败且已实际过期则清凭据返回 null。
     */
    open suspend fun validAccessToken(): String? {
        vault.loadManualBearer()?.takeIf { it.isNotEmpty() }?.let { return it }

        val stored = vault.readTokens() ?: return null
        val token = stored.optString("access_token", "").ifEmpty { return null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val nearExpiry = expireAt > 0 && (expireAt - now) < refreshLeewayMs()
        if (nearExpiry) {
            if (refreshToken()) {
                return vault.readTokens()?.optString("access_token")
            }
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "token expired and refresh failed — clearing credentials")
                logout()
                return null
            }
        }
        return token
    }

    fun isAuthenticated(): Boolean {
        if (vault.loadManualBearer()?.isNotEmpty() == true) return true
        return vault.readTokens()?.optString("access_token", "")?.isNotEmpty() == true
    }

    fun logout() = vault.wipe()

    // ── 手工 bearer / 跨端导出导入 ─────────────────────────────────────

    fun saveManualBearerToken(token: String) = vault.saveManualBearer(token)

    fun loadManualBearerToken(): String? = vault.loadManualBearer()

    fun deleteManualBearerToken() = vault.deleteManualBearer()

    /** 登录流程存的结构化令牌包原文（JSON 字符串），未登录过返回 null。 */
    fun exportStoredTokensJson(): String? = vault.readTokens()?.toString()

    /**
     * 回填一份 [exportStoredTokensJson] 产出的令牌包。逐字写入——绝对
     * expire_at 原样保留，绝不按 expires_in 重算（导入时刻重算就错了）。
     */
    fun importStoredTokensJson(json: String) = vault.importTokens(json)

    /** 读一个辅助 OAuth 字符串（如 Gemini 的 email / gcp_project）。 */
    fun exportOAuthString(key: String): String? = vault.readAux(key)

    /** 回填一个辅助 OAuth 字符串。 */
    fun importOAuthString(key: String, value: String) = vault.writeAux(key, value)
}
