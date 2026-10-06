package novex.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-onboarding] 官方供应商预设的守护测试：
 *  - 智谱静态目录只收文档确证型号（glm-5.3 / glm-5.3-flash；官方无 /models
 *    端点，目录只能内置，型号一个都不能发明——glm-5.3-air 查无此 ID 不入表）；
 *  - 两条目录都必须声明 reasoning_effort 档位枚举（low/high/max，文档口径），
 *    留 null 会让解析器在未声明档位上放弃吸附；
 *  - 智谱基址是完整路径，预设必须以 appendV1Suffix=false 落库（/api/paas/v4
 *    被追加 /v1 即 404）；
 *  - host 判定只认官方域名（中转不误伤）。
 */
class NovexProviderPresetsTest {

    @Test
    fun `智谱静态目录只收文档确证的两款旗舰 GLM 模型`() {
        val ids = NovexProviderPresets.zhipuStaticModels.map { it.id }
        assertEquals(listOf("glm-5.3", "glm-5.3-flash"), ids)
        NovexProviderPresets.zhipuStaticModels.forEach { model ->
            // 文档明示的容量：上下文 1M、输出 128K。
            assertEquals("ctx(${model.id})", 1_000_000, model.contextWindow)
            assertEquals("out(${model.id})", 128_000, model.maxOutputTokens)
            assertEquals("reasoning(${model.id})", true, model.supportsReasoning)
            // GLM-5.3 系思考能力页口径：API 档位仅 low/high/max（其余输入报错）。
            assertEquals("effort(${model.id})", listOf("low", "high", "max"), model.reasoningEffortValues)
        }
    }

    @Test
    fun `智谱基址是完整路径且预设关闭 v1 追加`() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4", NovexProviderPresets.ZHIPU_BASE_URL)
        // effectiveBaseURL 在 appendV1Suffix=false 时必须原样返回（根因②）。
        val instance = ProviderInstance(
            id = "preset-zhipu",
            label = NovexProviderPresets.ZHIPU_LABEL,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = NovexProviderPresets.ZHIPU_BASE_URL,
            appendV1Suffix = false,
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            instance.effectiveBaseURL,
        )
    }

    @Test
    fun `host 判定只认官方域名`() {
        assertTrue(NovexProviderPresets.isZhipuBase("https://open.bigmodel.cn/api/paas/v4"))
        assertTrue(NovexProviderPresets.isZhipuBase("https://open.bigmodel.cn/api/paas/v4/"))
        assertFalse(NovexProviderPresets.isZhipuBase("https://relay.example.com/api/paas/v4"))
        // 伪造域中转（子串包含会误命中，传输层思考席位同口径）。
        assertFalse(NovexProviderPresets.isZhipuBase("https://open.bigmodel.cn.relay.tld/api/paas/v4"))
        assertFalse(NovexProviderPresets.isZhipuBase(null))

        assertTrue(NovexProviderPresets.isDeepSeekBase("https://api.deepseek.com"))
        assertFalse(NovexProviderPresets.isDeepSeekBase("https://api.deepseek.com.example.org"))
        assertFalse(NovexProviderPresets.isDeepSeekBase(null))
    }

    @Test
    fun `Coding Plan 通道按路径命中且铺套餐目录`() {
        val coding = "https://open.bigmodel.cn/api/coding/paas/v4"
        val codingIntl = "https://api.z.ai/api/coding/paas/v4"
        // 同 host：思考席位与智谱专属处理照常命中。
        assertTrue(NovexProviderPresets.isZhipuBase(coding))
        assertTrue(NovexProviderPresets.isZhipuBase(codingIntl))
        assertTrue(NovexProviderPresets.isZhipuCodingPlanBase(coding))
        assertTrue(NovexProviderPresets.isZhipuCodingPlanBase(codingIntl))
        assertTrue(NovexProviderPresets.isZhipuCodingPlanBase("$coding/"))
        // 按量路径与伪造中转都不算套餐通道。
        assertFalse(NovexProviderPresets.isZhipuCodingPlanBase(NovexProviderPresets.ZHIPU_BASE_URL))
        assertFalse(NovexProviderPresets.isZhipuCodingPlanBase("https://open.bigmodel.cn.relay.tld/api/coding/paas/v4"))
        assertFalse(NovexProviderPresets.isZhipuCodingPlanBase(null))

        val codingIds = NovexProviderPresets.zhipuCatalogForBase(coding).map { it.id }
        assertTrue("coding catalog must offer glm-4.7", "glm-4.7" in codingIds)
        assertTrue("glm-5.3" in codingIds)
        // 按量目录仍是双旗舰，不被套餐目录污染。
        assertEquals(
            listOf("glm-5.3", "glm-5.3-flash"),
            NovexProviderPresets.zhipuCatalogForBase(NovexProviderPresets.ZHIPU_BASE_URL).map { it.id },
        )
        // onboarding 预设键与套餐基址是接入契约，改不得。
        assertEquals("zhipu-coding", NovexProviderPresets.PRESET_ZHIPU_CODING)
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", NovexProviderPresets.ZHIPU_CODING_BASE_URL)
        assertEquals("https://api.z.ai/api/coding/paas/v4", NovexProviderPresets.ZAI_CODING_BASE_URL)
    }

    @Test
    fun `深度求索兜底目录是官方双模型`() {
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner"),
            NovexProviderPresets.deepSeekFallbackModels.map { it.id },
        )
        assertEquals("https://api.deepseek.com", NovexProviderPresets.DEEPSEEK_BASE_URL)
        assertEquals("https://platform.deepseek.com/api_keys", NovexProviderPresets.DEEPSEEK_KEY_HELP_URL)
        assertEquals("https://open.bigmodel.cn/usercenter/apikeys", NovexProviderPresets.ZHIPU_KEY_HELP_URL)
    }
}
