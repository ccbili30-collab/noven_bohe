package novex.android.authkit

import android.content.Context

/**
 * DNS / 代理连通失败时抛出：UI 捕获后给本地化的“检查网络或代理”提示，
 * 而不是裸露 OkHttp 报文。
 *
 * [T-dead-code-sweep] 现仅保留类型本身（OAuthNetworkUnreachableException
 * 经 com.openminis.app.auth.OpenAIOAuthManager 类型别名钉成冻结面）；
 * 唯一的抛出点（登录交换重试链）已随交互登录路径退役。
 */
class OAuthNetworkUnreachableException(cause: Throwable) : Exception(cause.message, cause)

/**
 * OpenAI Codex OAuth 凭据座（P3.5c 自 auth/OpenAIOAuthManager 真重写）。
 *
 * [T-dead-code-sweep] 浏览器授权码交互登录链（runLogin/awaitCallbackCode/
 * exchangeJsonWithRetry/parseIdToken/proxyAwareClient/logNetworkEnvironment
 * 及 companion login）已随五家登录入口一起退役——全仓零调用方。留下的
 * 是凭据座本体：基类的刷新/手工 bearer/导入导出，加上令牌端点与
 * ChatGPT 账号属性（ProviderFactory 装配 codexAccountId 引用，属冻结面；
 * 读取的是既有登录或导入时写入的持久化 aux）。
 */
class CodexLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    override val tokenEndpoint = "https://auth.openai.com/oauth/token"
    override val clientIdValue = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val clientSecretValue: String? = null

    /** id_token（JWT）里的 ChatGPT 账号，持久化 aux，供装配层引用。 */
    val accountId: String?
        get() = vault.readAux("account_id")

    val planType: String?
        get() = vault.readAux("plan_type")
}
