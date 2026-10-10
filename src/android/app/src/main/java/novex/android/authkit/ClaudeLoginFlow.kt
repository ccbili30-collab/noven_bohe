package novex.android.authkit

import android.content.Context
import android.util.Log
import com.openminis.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Anthropic（Claude Code）OAuth 凭据座（P3.5c 自 auth/ClaudeOAuthManager
 * 真重写）。
 *
 * [T-dead-code-sweep] 浏览器授权码交互登录链（runLogin/awaitCallbackCode/
 * exchangeJson 及 companion login）已随五家登录入口一起退役——全仓零
 * 调用方。留下的是凭据座本体：刷新四分类、并发合并、手工 bearer 与
 * 导入导出（基类），以及请求侧需要的系统提示前缀常量。
 *
 * 刷新结果的四分类与并发合并：并行请求同时发现临期时，按实例的
 * [Mutex] 只放一个去打网络，其余等锁回来直接读新令牌；HTTP 400/401/403
 * 或体里点名 invalid_grant / refresh_token 才判定凭据作废并清库，5xx 与
 * 网络错误保凭据留给重试。
 */
class ClaudeLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "ClaudeLoginFlow"
        private const val REFRESH_LEEWAY_MS = 5L * 60 * 1000

        /**
         * Claude Code OAuth 凭据必须随请求携带的系统提示前缀。
         * 值不硬编码：构建期经 BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
         * 注入，来源是私仓的 provider-customization.properties（公开镜像只带
         * 空值示例）。未配置时读到即抛——首个 OAuth (Claude Code) 请求需要
         * 它的时刻才炸，而不是构建期。
         */
        val ANTHROPIC_OAUTH_IDENTIFIER_PROMPT: String
            get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.ifEmpty {
                throw IllegalStateException(
                    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy " +
                        "app/provider-customization.properties.example to " +
                        "app/provider-customization.properties and set " +
                        "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth.",
                )
            }

        private val refreshGates = ConcurrentHashMap<String, Mutex>()
        private fun gateFor(instanceId: String): Mutex = refreshGates.getOrPut(instanceId) { Mutex() }
    }

    /** 刷新结局四分类：UI 按它决定“重试请求”还是“请用户重登”。 */
    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val tokenEndpoint = "https://console.anthropic.com/v1/oauth/token"
    override val clientIdValue = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val clientSecretValue: String? = null

    // ── 刷新 ───────────────────────────────────────────────────────────

    override suspend fun refreshToken(): Boolean = classifiedRefresh() == RefreshOutcome.SUCCESS

    suspend fun classifiedRefresh(): RefreshOutcome = withContext(Dispatchers.IO) {
        gateFor(instanceId).withLock {
            val stored = vault.readTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val rotating = stored.optString("refresh_token", "")
            if (rotating.isEmpty()) return@withLock RefreshOutcome.NO_TOKEN

            // 等锁期间别的协程已刷过： expire_at 比进门时新且仍未过期，直接判成功。
            if (fresherTokenArrived(stored.optLong("expire_at", 0))) {
                Log.d(TAG, "refresh coalesced — fresher token already stored")
                return@withLock RefreshOutcome.SUCCESS
            }

            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("client_id", clientIdValue)
                put("refresh_token", rotating)
            }
            try {
                val (status, responseBody) = OAuthWire.postJson(tokenEndpoint, body.toString())
                if (status in 200..299) {
                    val json = JSONObject(responseBody)
                    if (!json.has("refresh_token")) json.put("refresh_token", rotating)
                    vault.writeTokens(json)
                    Log.i(TAG, "refresh ok, expires in ${json.optLong("expires_in", 0)}s")
                    return@withLock RefreshOutcome.SUCCESS
                }
                return@withLock classifyFailure(status, responseBody, rotating)
            } catch (e: Exception) {
                Log.w(TAG, "refresh transient error — keeping token", e)
                RefreshOutcome.TRANSIENT
            }
        }
    }

    private fun classifyFailure(status: Int, responseBody: String, rotating: String): RefreshOutcome {
        val lower = responseBody.lowercase()
        val fatal = status == 400 || status == 401 || status == 403 ||
            lower.contains("invalid_grant") ||
            lower.contains("refresh_token")
        if (!fatal) {
            Log.w(TAG, "refresh transient failure ($status) — keeping token")
            return RefreshOutcome.TRANSIENT
        }
        Log.e(TAG, "refresh token invalid ($status): ${OAuthWire.redactForLog(responseBody)} — clearing credentials")
        logout()
        return RefreshOutcome.INVALID_GRANT
    }

    private fun fresherTokenArrived(priorExpireAt: Long): Boolean {
        val now = System.currentTimeMillis()
        val latest = vault.readTokens()?.optLong("expire_at", 0) ?: 0
        return latest > priorExpireAt && latest > now
    }

    override fun refreshLeewayMs(): Long = REFRESH_LEEWAY_MS

    /**
     * 5 分钟窗内的临期刷新；INVALID_GRANT（已清库）与 NO_TOKEN 返回 null，
     * TRANSIENT 下旧令牌未实际过期就继续用。
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = vault.readTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val nearExpiry = expireAt > 0 && (expireAt - now) <= REFRESH_LEEWAY_MS
        if (!nearExpiry) return@withContext token

        when (classifiedRefresh()) {
            RefreshOutcome.SUCCESS ->
                vault.readTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null // classifiedRefresh 内已 logout()
            RefreshOutcome.TRANSIENT -> if (expireAt > 0 && now >= expireAt) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}
