package novex.android.authkit

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.openminis.app.auth.KimiDeviceFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Kimi Code 凭据座（P3.5c 自 auth/KimiOAuthManager 真重写）。
 *
 * 原为 RFC 8628 设备授权 Grant 登录流。[T-dead-code-sweep] 设备码
 * 交互登录链（requestDeviceAuthorization/pollForToken/runDeviceLogin/
 * persistLoginSuccess 及 companion login）已随五家登录入口一起退役
 * ——全仓零调用方。留下的是凭据座本体：单飞合并刷新、手工 bearer、
 * 导入导出；KimiDeviceFlow 对象保留是因为 CODING_API_BASE/TOKEN_URL
 * 常量与 classifyPoll 判定器同时被 ProviderModelRefresh/ProviderFactory
 * 的存活装配路径引用。
 *
 * client id 可经 AndroidManifest meta-data `KimiOAuthClientID` 覆写；
 * 缺省借官方 CLI 的第一方身份。显式置空则关闭登录入口。
 *
 * 刷新带并发合并与“先比对再删除”：invalid_grant 只有在盘上 refresh
 * token 仍是失败请求发出的那枚时才清库（并发轮换后的陈旧失败不认）。
 */
class KimiLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "KimiLoginFlow"

        /** 官方 Kimi Code CLI 的 OAuth client id（CLIProxyAPI 公开源码所见）。 */
        const val OFFICIAL_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"

        private const val REFRESH_LEEWAY_MS = 5L * 60 * 1000
    }

    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val tokenEndpoint = KimiDeviceFlow.TOKEN_URL
    override val clientIdValue: String
        get() {
            val override = try {
                appContext.packageManager
                    .getApplicationInfo(appContext.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("KimiOAuthClientID")
            } catch (_: Exception) {
                null
            }
            // 显式空覆写 = 关闭登录；缺省回落官方值。
            return override ?: OFFICIAL_CLIENT_ID
        }
    override val clientSecretValue: String? = null

    /** auth.kimi.com 只认表单体（JSON 换来 `client_id is required`）。 */
    private fun postForm(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val (status, body) = OAuthWire.postForm(url, params)
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }
        return status to json
    }

    // ── 刷新（单飞 + 先比对再删除）────────────────────────────────────

    suspend fun classifiedRefresh(): RefreshOutcome = RefreshGate.serialized(instanceId) {
        val stored = vault.readTokens() ?: return@serialized RefreshOutcome.NO_TOKEN
        val rotating = stored.optString("refresh_token", "")
        if (rotating.isEmpty()) return@serialized RefreshOutcome.NO_TOKEN
        if (RefreshGate.fresherArrived(vault, stored.optLong("expire_at", 0))) {
            Log.d(TAG, "refresh coalesced — fresher token already stored")
            return@serialized RefreshOutcome.SUCCESS
        }

        try {
            val (status, json) = postForm(
                tokenEndpoint,
                mapOf("grant_type" to "refresh_token", "refresh_token" to rotating, "client_id" to clientIdValue),
            )
            if (status in 200..299 && json.optString("access_token", "").isNotEmpty()) {
                vault.writeTokens(restamp(json, rotating, stored))
                Log.i(TAG, "refresh ok (expires in ${json.optLong("expires_in", 0)}s)")
                return@serialized RefreshOutcome.SUCCESS
            }

            val lower = json.toString().lowercase()
            val fatal = status in 400..403 || "invalid_grant" in lower || "refresh_token_reused" in lower
            if (fatal) {
                // 先比对再删除：盘上已被并发轮换成新值时，这枚失败是
                // 陈旧的——保新凭据不删。
                val onDisk = vault.readTokens()?.optString("refresh_token", "")
                if (!KimiDeviceFlow.shouldDeleteAfterInvalidGrant(rotating, onDisk)) {
                    Log.w(TAG, "stale invalid_grant ignored — token rotated concurrently; keeping new credentials")
                    return@serialized RefreshOutcome.SUCCESS
                }
                Log.e(TAG, "refresh token rejected — wiping stored credentials")
                logout()
                return@serialized RefreshOutcome.INVALID_GRANT
            }
            Log.w(TAG, "refresh transient failure — keeping token")
            RefreshOutcome.TRANSIENT
        } catch (e: Exception) {
            Log.w(TAG, "refresh transient error — keeping token: ${e.message}")
            RefreshOutcome.TRANSIENT
        }
    }

    /** 刷新应答落库前的整备：补回被省略的 refresh_token、续盖设备身份与时间戳。 */
    private fun restamp(json: JSONObject, rotating: String, prior: JSONObject): JSONObject {
        if (!json.has("refresh_token") || json.optString("refresh_token").isEmpty()) {
            json.put("refresh_token", rotating) // 不轮换的服务端会省略——保旧值
        }
        return json
            .put("device_id", prior.optString("device_id", UUID.randomUUID().toString()))
            .put("last_refresh", System.currentTimeMillis())
    }

    override suspend fun refreshToken(): Boolean = classifiedRefresh() == RefreshOutcome.SUCCESS

    override fun refreshLeewayMs(): Long = REFRESH_LEEWAY_MS

    /** 手工 bearer 优先；有 refresh token 且进 5 分钟窗才刷新。 */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        vault.loadManualBearer()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val stored = vault.readTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val nearExpiry = stored.optString("refresh_token", "").isNotEmpty() &&
            expireAt > 0 && expireAt - System.currentTimeMillis() <= REFRESH_LEEWAY_MS
        if (!nearExpiry) return@withContext token

        when (classifiedRefresh()) {
            RefreshOutcome.SUCCESS -> vault.readTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null // classifiedRefresh 内已 logout()
            else -> if (expireAt in 1..System.currentTimeMillis()) null else token
        }
    }
}
