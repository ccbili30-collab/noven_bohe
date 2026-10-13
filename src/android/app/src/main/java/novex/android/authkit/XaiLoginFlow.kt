package novex.android.authkit

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * xAI（Grok）OAuth 凭据座（P3.5c 自 auth/XAIOAuthManager 真重写）。
 *
 * [T-dead-code-sweep] 浏览器授权码交互登录链（runLogin/awaitCallbackCode/
 * buildAuthorizeRequest/exchangeDiscovered/exchangeForm/parseIdToken/
 * Custom Tab 关闭探测及 companion login）已随五家登录入口一起退役
 * ——全仓零调用方。留下的是凭据座本体：OIDC 发现的令牌端点解析
 * （刷新要用，导入的凭据首刷时按需拉发现文档）、403 档位特判刷新、
 * 手工 bearer 与导入导出。
 *
 * 存活的两点 xAI 特有约定（冻结面）：
 *  - 令牌端点不是常量：从 OIDC 发现文档解析并缓存进加密 prefs；
 *    端点必须落在 `*.x.ai` 之下才被采信（开放重定向护栏）。
 *  - 刷新对 HTTP 403 特判：订阅档位不含 API 权限时刷新会回 403，不是
 *    令牌坏——保住令牌包，等用户升档或改手工 key，不强制重登。
 */
class XaiLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "XaiLoginFlow"

        const val OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
    }

    // 抽象面占位：实际令牌端点由 OIDC 发现解析，刷新绕开这个常量走
    // [resolveTokenEndpoint]。
    override val tokenEndpoint = "https://auth.x.ai/oauth/token"
    override val clientIdValue = OAUTH_CLIENT_ID
    override val clientSecretValue: String? = null

    /** 登录或导入时写入的持久化 aux，只读。 */
    val accountId: String?
        get() = vault.readAux("account_id")

    val email: String?
        get() = vault.readAux("email")

    val displayName: String?
        get() = vault.readAux("display_name")

    // ── OIDC 发现 ─────────────────────────────────────────────────────

    /**
     * 拉发现文档、校验双端点域、缓存进加密 prefs。任一端点不在 `*.x.ai`
     * 之下即抛——防发现文档被指向第三方端点。
     */
    private suspend fun discoverEndpoints(): Pair<String, String> = withContext(Dispatchers.IO) {
        val (status, body) = OAuthWire.get(DISCOVERY_URL)
        check(status in 200..299 && body.isNotEmpty()) {
            "xAI OIDC discovery failed: HTTP $status"
        }
        val json = JSONObject(body)
        val authorize = json.optString("authorization_endpoint", "")
        val token = json.optString("token_endpoint", "")
        check(authorize.isNotEmpty() && token.isNotEmpty()) {
            "xAI OIDC discovery missing endpoints"
        }
        require(Uri.parse(authorize).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted authorization endpoint: $authorize"
        }
        require(Uri.parse(token).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted token endpoint: $token"
        }
        vault.writeAux("auth_endpoint", authorize)
        vault.writeAux("token_endpoint", token)
        authorize to token
    }

    private suspend fun resolveTokenEndpoint(): String =
        vault.readAux("token_endpoint")?.takeIf { it.isNotEmpty() }
            ?: discoverEndpoints().second

    // ── 刷新 ───────────────────────────────────────────────────────────

    override suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val rotating = vault.readTokens()?.optString("refresh_token").orEmpty()
        if (rotating.isEmpty()) return@withContext false

        try {
            val (status, body) = OAuthWire.postForm(
                resolveTokenEndpoint(),
                mapOf("grant_type" to "refresh_token", "refresh_token" to rotating, "client_id" to clientIdValue),
            )
            when (status) {
                403 -> { // 档位不含 API 权限：保令牌包，给升档/手工 key 留后路。
                    Log.w(TAG, "xAI refresh got 403 — preserving token (subscription tier issue)"); false
                }
                in 200..299 -> {
                    val json = JSONObject(body)
                    vault.writeTokens(json.put("refresh_token", json.optString("refresh_token", rotating)))
                    true
                }
                else -> { Log.e(TAG, "xAI refresh failed: $status"); false }
            }
        } catch (e: Exception) {
            Log.e(TAG, "xAI refresh error", e); false
        }
    }
}
