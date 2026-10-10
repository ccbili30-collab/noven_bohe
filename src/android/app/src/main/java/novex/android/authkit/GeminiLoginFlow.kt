package novex.android.authkit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Google Gemini CLI OAuth 凭据座（P3.5c 自 auth/GeminiOAuthManager 真重写）。
 *
 * [T-dead-code-sweep] 浏览器授权码交互登录链与登录后资料补拉
 * （authorizationUrl/afterTokensPersisted/fetchAccountEmail/
 * discoverProjectIfNeeded/loadCodeAssist/onboardUser/pollOperation 及
 * HTTP 小助手）已随五家登录入口一起退役——全仓零调用方。留下的是
 * 凭据座本体：form 体刷新四分类与单飞合并、手工 bearer、导入导出，
 * 以及 email / gcp_project 两个持久化 aux 的只读属性（ProviderTransfer
 * 跨端导入导出沿用同名 aux 键）。
 *
 * 刷新分类与并发合并复用 [RefreshGate]；Google 对失效 refresh token 回
 * 400 + invalid_grant / invalid_token 判 INVALID_GRANT 清库，5xx 与网络
 * 错误保凭据。
 */
class GeminiLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "GeminiLoginFlow"

        /** 提前刷新窗：Google access token 1 小时有效，5 分钟窗防尾race。 */
        private const val REFRESH_LEEWAY_MS = 5L * 60 * 1000
    }

    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val tokenEndpoint = "https://oauth2.googleapis.com/token"
    override val clientIdValue =
        "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"

    // 占位值。Google OAuth 要求 client id 配 secret；刷新体沿用该约定。
    // API-key 供应商不受影响。
    override val clientSecretValue = "GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxx"

    /** 登录或导入时写入的持久化 aux，只读。 */
    val email: String?
        get() = vault.readAux("email")

    val gcpProjectId: String?
        get() = vault.readAux("gcp_project")

    // ── 刷新（form 体 + 四分类 + 单飞合并）────────────────────────────

    override suspend fun refreshToken(): Boolean = classifiedRefresh() == RefreshOutcome.SUCCESS

    suspend fun classifiedRefresh(): RefreshOutcome = RefreshGate.serialized(instanceId) {
        val stored = vault.readTokens() ?: return@serialized RefreshOutcome.NO_TOKEN
        val rotating = stored.optString("refresh_token", "")
        if (rotating.isEmpty()) return@serialized RefreshOutcome.NO_TOKEN
        if (RefreshGate.fresherArrived(vault, stored.optLong("expire_at", 0))) {
            Log.d(TAG, "refresh coalesced — fresher token already stored")
            return@serialized RefreshOutcome.SUCCESS
        }

        try {
            val (status, body) = OAuthWire.postForm(
                tokenEndpoint,
                mapOf(
                    "grant_type" to "refresh_token", "refresh_token" to rotating,
                    "client_id" to clientIdValue, "client_secret" to (clientSecretValue ?: ""),
                ),
            )
            when {
                status in 200..299 -> {
                    val json = JSONObject(body)
                    if (!json.has("refresh_token")) json.put("refresh_token", rotating)
                    vault.writeTokens(json)
                    Log.i(TAG, "Gemini refresh ok, expires in ${json.optLong("expires_in", 0)}s")
                    RefreshOutcome.SUCCESS
                }
                else -> classifyFailure(status, body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini refresh transient error — keeping token", e)
            RefreshOutcome.TRANSIENT
        }
    }

    private fun classifyFailure(status: Int, body: String): RefreshOutcome {
        val lower = body.lowercase()
        val fatal = status == 400 || status == 401 || status == 403 ||
            lower.contains("invalid_grant") || lower.contains("invalid_token")
        if (!fatal) {
            Log.w(TAG, "Gemini refresh transient failure ($status) — keeping token")
            return RefreshOutcome.TRANSIENT
        }
        Log.e(TAG, "Gemini refresh token invalid ($status): ${OAuthWire.redactForLog(body)} — clearing credentials")
        logout()
        return RefreshOutcome.INVALID_GRANT
    }

    override fun refreshLeewayMs(): Long = REFRESH_LEEWAY_MS

    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = vault.readTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        if (expireAt <= 0 || expireAt - System.currentTimeMillis() > REFRESH_LEEWAY_MS) {
            return@withContext token
        }
        when (classifiedRefresh()) {
            RefreshOutcome.SUCCESS -> vault.readTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT, RefreshOutcome.NO_TOKEN -> null
            RefreshOutcome.TRANSIENT -> token.takeIf { expireAt > System.currentTimeMillis() }
        }
    }
}
