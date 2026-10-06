package novex.android.data.model

import java.net.URI

/*
 * 官方供应商接入预设（provider onboarding）：「选择接入方式」三卡（智谱 /
 * 深度求索 / 自定义）中前两卡的数据事实——base URL、取钥链接与静态模型目录。
 *
 * 这里只放厂商成文事实，不放推断：
 *  - 智谱没有公开 /models 端点，目录只能静态内置（glm-5.3 与 glm-5.3-flash
 *    两个文档确证 id，docs.bigmodel.cn 模型概览核对；上下文/输出上限只写
 *    文档明示的值）；
 *  - DeepSeek 官方支持 /models 拉取，静态表仅作拉取失败的兜底。
 * 预设落库复用 ProviderInstance 既有字段（customBaseURL / appendV1Suffix /
 * keyHelpUrl），不改 Room schema、不改 prefs 键名、不改 JSON 契约。
 */
object NovexProviderPresets {

    /** 导航参数用预设键（add_provider?preset=…）。 */
    const val PRESET_ZHIPU = "zhipu"
    const val PRESET_DEEPSEEK = "deepseek"

    // ---- 智谱（Z.ai / BigModel） -----------------------------------------

    /**
     * 智谱官方 OpenAI 兼容端点的完整基址。路径已是终态（/api/paas/v4），
     * 绝不能追加 /v1——预设必须以 appendV1Suffix=false 落库。
     */
    const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

    /**
     * 智谱 Coding Plan 套餐通道：与按量端点同协议，仅路径多一段
     * /coding/，计费走套餐额度而非 token 按量。国内与国际站各一条
     * 基址（同 key 通用），用户在自定义供应商里填即可接入。
     */
    const val ZHIPU_CODING_BASE_URL = "https://open.bigmodel.cn/api/coding/paas/v4"
    const val ZAI_CODING_BASE_URL = "https://api.z.ai/api/coding/paas/v4"
    const val ZHIPU_KEY_HELP_URL = "https://open.bigmodel.cn/usercenter/apikeys"
    const val ZHIPU_LABEL = "智谱"

    /**
     * 智谱官方 host（思考席位与静态目录的命中判定用）。两个都是真官方
     * 域：open.bigmodel.cn 是国内站，api.z.ai 是国际站（z.ai）——同一
     * 套 GLM 接口方言，Coding Plan 两侧都开。
     */
    val ZHIPU_HOST = "open.bigmodel.cn"
    val Z_AI_HOST = "api.z.ai"
    private val ZHIPU_HOSTS = setOf(ZHIPU_HOST, Z_AI_HOST)

    fun isZhipuBase(base: String?): Boolean =
        runCatching { URI(base.orEmpty().trim()).host?.lowercase() }.getOrNull() in ZHIPU_HOSTS

    /** Coding Plan 通道判定：官方 host 路径带 /coding/ 段。 */
    fun isZhipuCodingPlanBase(base: String?): Boolean =
        runCatching { URI(base.orEmpty().trim()) }.getOrNull()
            ?.let { it.host?.lowercase() in ZHIPU_HOSTS && it.path.orEmpty().contains("/coding/") }
            ?: false

    /**
     * 智谱 host 的目录按通道分派：Coding Plan 端点铺套餐目录（与按量
     * 目录型号重叠但上下文/输出规格不同），其余铺按量静态表。
     */
    fun zhipuCatalogForBase(base: String?): List<LLMModel> =
        if (isZhipuCodingPlanBase(base)) zhipuCodingPlanModels else zhipuStaticModels

    /** DeepSeek 官方 host（api.deepseek.com），/models 兜底目录的命中判定用。 */
    fun isDeepSeekBase(base: String?): Boolean =
        runCatching { URI(base.orEmpty().trim()).host?.lowercase() }.getOrNull() == DEEPSEEK_HOST

    val DEEPSEEK_HOST = "api.deepseek.com"

    /**
     * 智谱静态模型目录：官方无 /models 端点，目录只能内置（第三方实践可用但
     * 未成文，保守取舍为仅静态）。
     * 事实来源 docs.bigmodel.cn（2026-10 核对）：glm-5.3 与 glm-5.3-flash
     * 上下文 1M / 最大输出 128K；thinking 能力页写明 5.3 系 API 档位仅
     * low/high/max（「其余输入将报错」，默认 max）且不再支持 disabled——两条
     * 都声明该档位枚举，解析器才会在 xhigh 等未声明档上向下吸附。目录只收
     * 文档确证的型号，型号一个不发明（glm-5.3-air 查无此 ID，不入表）。
     */
    val zhipuStaticModels: List<LLMModel> = listOf(
        LLMModel(
            id = "glm-5.3",
            displayName = "GLM-5.3",
            provider = "智谱",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
        ),
        LLMModel(
            id = "glm-5.3-flash",
            displayName = "GLM-5.3-Flash",
            provider = "智谱",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
        ),
    )

    /**
     * Coding Plan 通道静态目录（同样无 /models 端点）。型号与规格来自
     * z.ai coding 供应商目录（glm-4.x/5.x 全系）；思考 effort 档位仅
     * 官方文档确证的 5.3 系照录 low/high/max，其余型号不发明档位。
     */
    val zhipuCodingPlanModels: List<LLMModel> = listOf(
        LLMModel(
            id = "glm-5.3",
            displayName = "GLM-5.3",
            provider = "智谱 Coding Plan",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
        ),
        LLMModel(
            id = "glm-5.3-flash",
            displayName = "GLM-5.3-Flash",
            provider = "智谱 Coding Plan",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
            inputModalities = listOf("text", "image"),
        ),
        LLMModel(
            id = "glm-5.3-highspeed",
            displayName = "GLM-5.3-Highspeed",
            provider = "智谱 Coding Plan",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-5.2",
            displayName = "GLM-5.2",
            provider = "智谱 Coding Plan",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-5.2-highspeed",
            displayName = "GLM-5.2-Highspeed",
            provider = "智谱 Coding Plan",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-5.1",
            displayName = "GLM-5.1",
            provider = "智谱 Coding Plan",
            contextWindow = 200_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-5-turbo",
            displayName = "GLM-5-Turbo",
            provider = "智谱 Coding Plan",
            contextWindow = 200_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-5v-turbo",
            displayName = "GLM-5V-Turbo",
            provider = "智谱 Coding Plan",
            contextWindow = 200_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            inputModalities = listOf("text", "image"),
        ),
        LLMModel(
            id = "glm-4.7",
            displayName = "GLM-4.7",
            provider = "智谱 Coding Plan",
            contextWindow = 200_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-4.6v",
            displayName = "GLM-4.6V",
            provider = "智谱 Coding Plan",
            contextWindow = 128_000,
            maxOutputTokens = 32_000,
            supportsReasoning = true,
            inputModalities = listOf("text", "image"),
        ),
    )

    // ---- 深度求索（DeepSeek） --------------------------------------------

    /** DeepSeek 官方端点；/v1 后缀官方兼容，appendV1Suffix 保持默认开。 */
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEEPSEEK_KEY_HELP_URL = "https://platform.deepseek.com/api_keys"
    const val DEEPSEEK_LABEL = "深度求索"

    /** DeepSeek 官方支持 /models；此表仅在拉取失败时兜底。 */
    val deepSeekFallbackModels: List<LLMModel> = listOf(
        LLMModel(
            id = "deepseek-chat",
            displayName = "DeepSeek Chat",
            provider = "DeepSeek",
            supportsReasoning = null,
        ),
        LLMModel(
            id = "deepseek-reasoner",
            displayName = "DeepSeek Reasoner",
            provider = "DeepSeek",
            supportsReasoning = true,
        ),
    )
}
