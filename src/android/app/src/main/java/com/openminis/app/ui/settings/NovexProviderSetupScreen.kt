package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import novex.android.data.model.*
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.tools.AgentTools
import novex.android.ui.AlertDialog
import novex.android.ui.Button
import novex.android.ui.NovexCheckToggle
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.OutlinedButton
import novex.android.ui.OutlinedTextField
import novex.android.ui.TextButton
import novex.android.ui.novexScaledSp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

private enum class ModelResultState { PASSED, WARNING, FAILED, CANCELLED }
private data class ModelVerificationUiResult(
    val state: ModelResultState,
    val message: String,
)
private data class SetupValues(
    val base: String,
    val key: String,
    val models: List<String>,
)
private data class ConnectionVerification(
    val models: NovexModelVerification,
    val fatalError: String? = null,
)

internal const val NOVEX_DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"

internal fun novexModelDisplayName(modelId: String): String =
    if (modelId == NOVEX_DEFAULT_DEEPSEEK_MODEL) {
        "DeepSeek V4 Flash（深度求索 V4 快速版）"
    } else {
        LLMModel.modelDisplayName(modelId)
    }

internal fun toggleModelSelection(current: List<String>, clicked: String): List<String> {
    val clean = clicked.trim()
    if (clean.isEmpty()) return current.distinct()
    val normalized = current.map(String::trim).filter(String::isNotEmpty).distinct()
    return if (clean in normalized) normalized - clean else normalized + clean
}

internal fun looksLikeImageGenerationModel(modelId: String): Boolean =
    novex.android.data.model.ChatModelSelection.imageGenerationId(modelId)

/** Input capability assigned to a chat model saved by the simplified setup. */
internal fun novexChatInputModalities(
    modelId: String,
    existingInputModalities: List<String>? = null,
): List<String> {
    val existing = existingInputModalities.orEmpty()
        .map(String::normalizeModalityName)
        .filter(String::isNotEmpty)
        .distinct()
    if (existing.isNotEmpty()) {
        return (listOf("text") + existing).distinct()
    }

    // OpenAI-compatible /models normally exposes only an id, not modality
    // metadata. Keep inference deliberately conservative: names that explicitly
    // advertise vision/visual/VL get native image input; ordinary chat and
    // image-generation model names are not upgraded implicitly.
    val normalizedId = modelId.trim().lowercase()
    val explicitlyVisual = looksLikeVisionInputModel(
        normalizedId,
        novexModelDisplayName(modelId),
    )
    return if (explicitlyVisual) listOf("text", "image") else listOf("text")
}

internal fun novexCanonicalBase(base: String, appendV1Suffix: Boolean): String {
    val normalized = base.trim().trimEnd('/')
    return if (appendV1Suffix && !normalized.endsWith("/v1")) "$normalized/v1" else normalized
}

/**
 * [T-provider-direction] 模型连接页的接口方向。旧「新增供应商」多步页（AddProviderScreen）
 * 已死代码化——所有连接都从本页创建，方向在这里选。Chat = 现状默认；
 * Responses = openAI 类型 + useResponsesAPI；Anthropic = anthropic 类型。
 */
internal enum class NovexProviderDirection(
    val buttonLabel: String,
    val headerTitle: String,
    val headerSubtitle: String,
    val hint: String,
    val defaultLabel: String,
    val defaultBase: String,
    val defaultKeyHelpUrl: String,
    val defaultKeyHelpLabel: String,
) {
    CHAT(
        buttonLabel = "Chat",
        headerTitle = "连接 OpenAI（开放人工智能）兼容接口",
        headerSubtitle = "支持 DeepSeek（深度求索）与常见中转站。可直接保存启用，也可以按需检测当前模型或全部模型。",
        hint = "默认 /v1/chat/completions，中转站最通用。",
        defaultLabel = "DeepSeek",
        defaultBase = "https://api.deepseek.com",
        defaultKeyHelpUrl = "https://platform.deepseek.com/api_keys",
        defaultKeyHelpLabel = "前往 DeepSeek（深度求索）获取密钥",
    ),
    RESPONSES(
        buttonLabel = "Responses",
        headerTitle = "连接 OpenAI Responses 接口",
        headerSubtitle = "适合只开放 /v1/responses 一个口子的中转站，或官方 OpenAI。其余流程与 Chat 方向一致。",
        hint = "走 /v1/responses；chat 口子不可用的中转选这个。",
        defaultLabel = "OpenAI",
        defaultBase = "https://api.openai.com",
        defaultKeyHelpUrl = "https://platform.openai.com/api-keys",
        defaultKeyHelpLabel = "前往 OpenAI 官网获取密钥",
    ),
    ANTHROPIC(
        buttonLabel = "Anthropic",
        headerTitle = "连接 Anthropic 兼容接口",
        headerSubtitle = "适合 Anthropic 兼容中转（Claude 系模型），请求走 /v1/messages。其余流程与 Chat 方向一致。",
        hint = "走 /v1/messages；Anthropic 兼容中转选这个。",
        defaultLabel = "Anthropic",
        defaultBase = "https://api.anthropic.com",
        defaultKeyHelpUrl = "https://console.anthropic.com/settings/keys",
        defaultKeyHelpLabel = "前往 Anthropic 官网获取密钥",
    ),
}

/** 编辑已有连接时按实例反显方向（anthropic 类型 → Anthropic；openAI + useResponsesAPI → Responses）。 */
internal fun novexDirectionOf(instance: ProviderInstance?): NovexProviderDirection = when {
    instance == null -> NovexProviderDirection.CHAT
    instance.providerType == ProviderType.anthropic -> NovexProviderDirection.ANTHROPIC
    instance.useResponsesAPI -> NovexProviderDirection.RESPONSES
    else -> NovexProviderDirection.CHAT
}

internal fun novexProviderInstanceForSave(
    existing: ProviderInstance?,
    label: String,
    base: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection = NovexProviderDirection.CHAT,
    keyHelpUrl: String? = null,
): ProviderInstance {
    val fallbackLabel = when (direction) {
        NovexProviderDirection.CHAT -> "OpenAI 兼容接口"
        NovexProviderDirection.RESPONSES -> "OpenAI Responses 接口"
        NovexProviderDirection.ANTHROPIC -> "Anthropic 兼容接口"
    }
    val providerType = if (direction == NovexProviderDirection.ANTHROPIC) ProviderType.anthropic else ProviderType.openAI
    return (existing ?: ProviderInstance(
        id = UUID.randomUUID().toString(),
        label = label.ifBlank { fallbackLabel },
        providerType = providerType,
        credentialType = ProviderCredential.apiKey,
    )).copy(
        label = label.ifBlank { fallbackLabel },
        providerType = providerType,
        customBaseURL = base.trim().trimEnd('/'),
        // 智谱 host 全线终态路径（按量 /api/paas/v4 与套餐
        // /api/coding/paas/v4 同则）：追加 /v1 必 404，落库恒关。
        appendV1Suffix = if (novex.android.data.model.NovexProviderPresets.isZhipuBase(base)) false else appendV1Suffix,
        useResponsesAPI = direction == NovexProviderDirection.RESPONSES,
        isEnabled = true,
        // 官方预设的取钥链接随实例落库（编辑既有实例时保留原值，不冲掉）。
        keyHelpUrl = keyHelpUrl ?: existing?.keyHelpUrl,
    )
}

/** 官方预设规格（[ProviderOnboardingScreen] 三卡带入）：预填字段 + 静态模型目录。 */
internal data class NovexPresetSpec(
    val label: String,
    val headerTitle: String,
    val headerSubtitle: String,
    val keyHelpLabel: String,
    val base: String,
    val keyHelpUrl: String,
    /** 智谱基址已是终态路径（/api/paas/v4），绝不能追加 /v1。 */
    val appendV1: Boolean,
    val staticModels: List<LLMModel>,
    /** 静态表的角色：智谱是唯一目录（无 /models 端点）；DeepSeek 仅兜底。 */
    val staticOnly: Boolean,
)

internal fun novexPresetSpec(preset: String?): NovexPresetSpec? = when (preset) {
    novex.android.data.model.NovexProviderPresets.PRESET_ZHIPU -> NovexPresetSpec(
        label = novex.android.data.model.NovexProviderPresets.ZHIPU_LABEL,
        headerTitle = "连接智谱（Z.ai）接口",
        headerSubtitle = "GLM 系列官方接口；地址与模型目录已预填（官方无模型列表接口），填入密钥即可保存启用。",
        keyHelpLabel = "前往智谱开放平台（bigmodel.cn）获取密钥",
        base = novex.android.data.model.NovexProviderPresets.ZHIPU_BASE_URL,
        keyHelpUrl = novex.android.data.model.NovexProviderPresets.ZHIPU_KEY_HELP_URL,
        appendV1 = false,
        staticModels = novex.android.data.model.NovexProviderPresets.zhipuStaticModels,
        staticOnly = true,
    )
    novex.android.data.model.NovexProviderPresets.PRESET_ZHIPU_CODING -> NovexPresetSpec(
        label = "智谱 Coding Plan",
        headerTitle = "连接智谱 Coding Plan（Z.ai）接口",
        headerSubtitle = "GLM Coding Plan 套餐通道，计费走订阅额度；地址与套餐模型目录已预填，填入密钥即可保存启用。",
        keyHelpLabel = "前往智谱开放平台（bigmodel.cn）获取密钥",
        base = novex.android.data.model.NovexProviderPresets.ZHIPU_CODING_BASE_URL,
        keyHelpUrl = novex.android.data.model.NovexProviderPresets.ZHIPU_KEY_HELP_URL,
        appendV1 = false,
        staticModels = novex.android.data.model.NovexProviderPresets.zhipuCodingPlanModels,
        staticOnly = true,
    )
    novex.android.data.model.NovexProviderPresets.PRESET_DEEPSEEK -> NovexPresetSpec(
        label = novex.android.data.model.NovexProviderPresets.DEEPSEEK_LABEL,
        headerTitle = "连接深度求索（DeepSeek）接口",
        headerSubtitle = "官方接口；地址已预填，可拉取模型目录，拉取失败时用内置兜底列表。",
        keyHelpLabel = "前往 DeepSeek（深度求索）获取密钥",
        base = novex.android.data.model.NovexProviderPresets.DEEPSEEK_BASE_URL,
        keyHelpUrl = novex.android.data.model.NovexProviderPresets.DEEPSEEK_KEY_HELP_URL,
        appendV1 = true,
        staticModels = novex.android.data.model.NovexProviderPresets.deepSeekFallbackModels,
        staticOnly = false,
    )
    else -> null
}

/** Novex 的单一 OpenAI（开放人工智能）兼容接口设置页。 */
@Composable
fun NovexProviderSetupScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    instanceId: String? = null,
    // [T-provider-onboarding] 官方预设键（zhipu/deepseek）；null = 手动流程。
    preset: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presetSpec = remember(preset) { novexPresetSpec(preset) }
    val existing = remember(instanceId) { instanceId?.let(providerRepository::instance) }
    var label by remember { mutableStateOf(existing?.label ?: presetSpec?.label ?: "DeepSeek") }
    var apiBase by remember { mutableStateOf(existing?.customBaseURL ?: presetSpec?.base ?: "https://api.deepseek.com") }
    var apiKey by remember { mutableStateOf(existing?.id?.let(providerRepository::loadApiKey) ?: "") }
    var appendV1Suffix by remember(instanceId) {
        mutableStateOf(existing?.appendV1Suffix ?: presetSpec?.appendV1 ?: true)
    }
    // [T-provider-direction] 接口方向：编辑时按实例反显，新建默认 Chat。
    // [T-provider-onboarding] 官方预设锁 Chat 方向——预填不可改成别的供应商。
    var direction by remember(instanceId) { mutableStateOf(novexDirectionOf(existing)) }
    var deleteConfirm by remember(instanceId) { mutableStateOf(false) }
    val existingEntries = remember(instanceId) { providerRepository.entriesFor(instanceId ?: "") }
    val initialModels = remember(instanceId, preset) {
        existingEntries.filterNot { novex.android.data.model.ChatModelSelection.imageOutput(it.model) || novex.android.data.model.ChatModelSelection.imageOutput(it.baseModel) }
            .map { it.model.id }.distinct()
            .ifEmpty {
                when {
                    existing != null -> emptyList()
                    presetSpec != null -> presetSpec.staticModels.map { it.id }
                    else -> listOf(NOVEX_DEFAULT_DEEPSEEK_MODEL)
                }
            }
    }
    val selectedModels = remember(instanceId, preset) { mutableStateListOf<String>().apply { addAll(initialModels) } }
    val modelToolsEnabled = remember(instanceId) {
        mutableStateMapOf<String, Boolean>().apply {
            existingEntries
                .filterNot { novex.android.data.model.ChatModelSelection.imageOutput(it.model) || novex.android.data.model.ChatModelSelection.imageOutput(it.baseModel) }
                .forEach { entry -> put(entry.model.id, entry.model.supportsTools != false) }
        }
    }
    var manualModelId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // 拉取成功的可见反馈（静态目录重铺秒出无网络，没提示会"像没反应"）。
    var fetchNotice by remember { mutableStateOf<String?>(null) }
    var fetchingModels by remember { mutableStateOf(false) }
    var checkingModelId by remember { mutableStateOf<String?>(null) }
    var verificationJob by remember { mutableStateOf<Job?>(null) }
    val verificationResults = remember { mutableStateMapOf<String, ModelVerificationUiResult>() }
    val fetchedModels = remember(preset) {
        mutableStateListOf<String>().apply { addAll(presetSpec?.staticModels?.map { it.id } ?: emptyList()) }
    }
    val fetchedMetadata = remember(preset) {
        mutableStateMapOf<String, LLMModel>().apply { presetSpec?.staticModels?.forEach { put(it.id, it) } }
    }
    var fetchedMetadataSource by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun invalidateVerification() {
        verificationJob?.cancel()
        verificationJob = null
        checkingModelId = null
        error = null
        verificationResults.clear()
    }
    // [T-provider-direction] 切方向：默认地址/名称未动过则跟着换，清掉旧协议
    // 拉取的模型缓存与未动过的 DeepSeek 预选，避免把 chat 拉的列表当成
    // anthropic 口子的可用模型。已勾选的非默认模型保留（模型 id 常通用）。
    fun switchDirection(next: NovexProviderDirection) {
        if (next == direction) return
        if (apiBase == direction.defaultBase) apiBase = next.defaultBase
        if (label == direction.defaultLabel) label = next.defaultLabel
        direction = next
        if (next != NovexProviderDirection.CHAT &&
            selectedModels.size == 1 && selectedModels.first() == NOVEX_DEFAULT_DEEPSEEK_MODEL
        ) {
            selectedModels.clear()
        }
        fetchedModels.clear()
        fetchedMetadata.clear()
        fetchedMetadataSource = null
        invalidateVerification()
    }
    fun setSelectedModels(models: List<String>) {
        selectedModels.clear()
        selectedModels.addAll(models.map(String::trim).filter(String::isNotEmpty).distinct())
    }
    fun toolsEnabled(modelId: String): Boolean = modelToolsEnabled[modelId] != false
    // [T-provider-onboarding] 保存时的容量元数据：拉取结果（地址+密钥对得上才
    // 可信）之外，智谱官方 host 无条件叠加内置静态目录——静态表就是该端点
    // 唯一的目录来源，不依赖用户有没有点过「拉取」。
    fun metadataForSave(values: SetupValues): Map<String, LLMModel> {
        val live = if (fetchedMetadataSource == (novexCanonicalBase(values.base, appendV1Suffix) to values.key)) {
            fetchedMetadata.toMap()
        } else {
            emptyMap()
        }
        return if (novex.android.data.model.NovexProviderPresets.isZhipuBase(values.base)) {
            live + novex.android.data.model.NovexProviderPresets.zhipuCatalogForBase(values.base).associateBy { it.id }
        } else {
            live
        }
    }
    fun validate(requireModels: Boolean = true): SetupValues? {
        val base = apiBase.trim().trimEnd('/')
        val key = apiKey.trim()
        val models = selectedModels.map(String::trim).filter(String::isNotEmpty).distinct()
        error = when {
            base.isEmpty() -> "请填写接口地址"
            !base.startsWith("http://") && !base.startsWith("https://") -> "接口地址需要以 https:// 或 http:// 开头"
            key.isEmpty() -> "请填写 API 密钥"
            requireModels && models.isEmpty() -> "请至少勾选一个模型"
            else -> null
        }
        return if (error == null) SetupValues(base, key, models) else null
    }
    fun startVerification(values: SetupValues, modelId: String) {
        if (checkingModelId == modelId) {
            verificationJob?.cancel()
            return
        }
        if (checkingModelId != null) return
        checkingModelId = modelId
        error = null
        verificationResults.remove(modelId)
        verificationJob = scope.launch {
            try {
                val verification = verifyConnection(
                    values.base,
                    values.key,
                    appendV1Suffix,
                    direction,
                    listOf(modelId),
                    toolEnabledByModel = { toolsEnabled(it) },
                )
                if (verification.fatalError != null) {
                    verificationResults[modelId] = ModelVerificationUiResult(
                        ModelResultState.FAILED,
                        "$modelId：${verification.fatalError}",
                    )
                } else {
                    val result = verification.models
                    val state = when {
                        result.failures.any { it.modelId == modelId } -> ModelResultState.FAILED
                        result.warnings.any { it.modelId == modelId } -> ModelResultState.WARNING
                        else -> ModelResultState.PASSED
                    }
                    verificationResults[modelId] = ModelVerificationUiResult(
                        state,
                        formatNovexModelVerificationLine(result, modelId),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (checkingModelId == modelId) {
                    verificationResults[modelId] = ModelVerificationUiResult(
                        ModelResultState.CANCELLED,
                        "$modelId：检测已取消",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                verificationResults[modelId] = ModelVerificationUiResult(
                    ModelResultState.FAILED,
                    "$modelId：${failure.message ?: failure.javaClass.simpleName}",
                )
            } finally {
                if (checkingModelId == modelId) {
                    checkingModelId = null
                    verificationJob = null
                }
            }
        }
    }

    SettingsScaffold(
        title = if (existing == null) "连接模型" else "模型连接",
        onBack = onBack,
        actions = {
            if (existing != null) {
                IconButton(onClick = { deleteConfirm = true }) {
                    Icon(NovexIcons.Delete, contentDescription = "删除 AI 服务商")
                }
            }
        },
    ) {
        // —— 方向卡：说明 + 三选一分段；[T-provider-onboarding] 官方预设不设
        // 方向开关——预填不可改成别的供应商（智谱/DeepSeek 均为 Chat 方向）。
        SettingsSection(footer = presetSpec?.headerSubtitle ?: direction.headerSubtitle) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                Text(
                    presetSpec?.headerTitle ?: direction.headerTitle,
                    style = NovexType.SectionTitle,
                    color = NovexColors.Text,
                )
                if (presetSpec == null) {
                    Spacer(Modifier.height(12.dp))
                    ProviderDirectionSelector(direction = direction, onSelect = ::switchDirection)
                    Spacer(Modifier.height(6.dp))
                    Text(direction.hint, style = NovexType.Metadata, color = NovexColors.SecondaryText)
                }
            }
        }

        // —— 连接参数卡 ——
        SettingsSection(header = "连接") {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    label = { Text("名称") },
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (presetSpec == null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        label = { Text("接口地址") },
                        value = apiBase,
                        onValueChange = {
                            apiBase = it
                            // 智谱 host（含 Coding Plan 通道）全是终态路径——
                            // 填进去即收起 /v1 追加，与落库强制关同则。
                            if (novex.android.data.model.NovexProviderPresets.isZhipuBase(it)) appendV1Suffix = false
                            invalidateVerification()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    label = { Text("API（应用程序接口）密钥") },
                    value = apiKey,
                    onValueChange = { apiKey = it; invalidateVerification() },
                    leadingIcon = { Icon(NovexIcons.Key, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            // 官方预设（智谱两通道/DeepSeek）地址是限定终态路径，/v1 不是
            // 用户决策——整条收起；自定义流程里一旦填了智谱 host 同样收起
            //（落库侧 isZhipuBase 强制关兜底，双保险）。
            if (presetSpec == null &&
                !novex.android.data.model.NovexProviderPresets.isZhipuBase(apiBase)
            ) {
                HorizontalDivider(color = NovexColors.Divider, thickness = 0.5.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动补全 /v1", style = NovexType.ItemTitle, color = NovexColors.Text)
                        Text(
                            if (appendV1Suffix) "填写域名即可；请求时自动补上 /v1"
                            else "关闭后完全按填写的接口地址请求",
                            style = NovexType.Metadata,
                            color = NovexColors.SecondaryText,
                        )
                    }
                    NovexCheckToggle(
                        checked = appendV1Suffix,
                        onCheckedChange = {
                            appendV1Suffix = it
                            invalidateVerification()
                        },
                    )
                }
            }
            HorizontalDivider(color = NovexColors.Divider, thickness = 0.5.dp)
            // 取钥链接按实例自适应：带 keyHelpUrl 的实例显示取钥入口（通用机制）
            // （前尘 API → proxy.qianc.ltd）；[T-provider-onboarding] 官方预设
            //（智谱 → bigmodel.cn、DeepSeek → platform.deepseek.com）优先于方向默认；
            // 其余按接口方向给默认指引。
            val helpUrl = existing?.keyHelpUrl ?: presetSpec?.keyHelpUrl ?: direction.defaultKeyHelpUrl
            val helpLabel = existing?.keyHelpUrl?.let { "前往 ${existing.label} 官网获取密钥" }
                ?: presetSpec?.keyHelpLabel
                ?: direction.defaultKeyHelpLabel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl))) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(NovexIcons.Key, null, tint = NovexColors.SecondaryText, modifier = Modifier.size(18.dp))
                Text(
                    helpLabel,
                    style = NovexType.Body,
                    color = NovexColors.Primary,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Icon(
                    NovexIcons.KeyboardArrowRight,
                    contentDescription = null,
                    tint = NovexColors.SecondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // —— 模型卡 ——
        SettingsSection(
            header = "模型",
            footer = "勾选启用；扳手控制工具调用；刷新图标仅检测这一行。",
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (presetSpec?.staticOnly == true)
                            "官方无模型列表接口，内置目录已铺好——勾选即可，也可手动加型号。"
                        else
                            "从接口拉取可用模型，或手动输入模型名。",
                        style = NovexType.Metadata,
                        color = NovexColors.SecondaryText,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !fetchingModels && checkingModelId == null,
                        onClick = {
                            val values = validate(requireModels = false) ?: return@OutlinedButton
                            // [T-provider-onboarding] 智谱官方无 /models 端点：「拉取」=
                            // 重铺内置静态目录，不打网络——给个反馈，不然
                            // 秒出的重铺在已预填列表上毫无视觉变化。
                            if (presetSpec?.staticOnly == true ||
                                novex.android.data.model.NovexProviderPresets.isZhipuBase(values.base)
                            ) {
                                val catalog = novex.android.data.model.NovexProviderPresets.zhipuCatalogForBase(values.base)
                                fetchedModels.clear()
                                fetchedModels.addAll(catalog.map { it.id })
                                fetchedMetadata.clear()
                                catalog.forEach { fetchedMetadata[it.id] = it }
                                fetchedMetadataSource = novexCanonicalBase(values.base, appendV1Suffix) to values.key
                                error = null
                                fetchNotice = "已载入内置模型目录 ${catalog.size} 款"
                                if (selectedModels.none { it in fetchedModels }) {
                                    setSelectedModels(listOf(fetchedModels.first()))
                                }
                                return@OutlinedButton
                            }
                            fetchingModels = true
                            val metadataBase = novexCanonicalBase(values.base, appendV1Suffix)
                            scope.launch {
                                val models = fetchModels(values.base, values.key, appendV1Suffix, direction)
                                    .filterNot { looksLikeImageGenerationModel(it.id) }
                                fetchedMetadata.clear(); fetchedMetadata.putAll(models.associateBy { it.id })
                                fetchedMetadataSource = metadataBase to values.key
                                val ids = models.map { it.id }
                                fetchedModels.clear(); fetchedModels.addAll(ids)
                                if (models.isEmpty()) error = "没有拉取到模型，请检查地址和密钥，或继续手动填写模型名称。"
                                else {
                                    error = null
                                    fetchNotice = "已拉取 ${models.size} 款模型"
                                    if (selectedModels.none { it in ids }) setSelectedModels(listOf(ids.first()))
                                }
                                fetchingModels = false
                            }
                        },
                    ) {
                        if (fetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = NovexColors.Primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("拉取中")
                        } else {
                            Text("拉取模型")
                        }
                    }
                }
                fetchNotice?.let {
                    Text(
                        it,
                        style = NovexType.Metadata,
                        color = NovexColors.Primary,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        label = { Text("手动添加模型") },
                        value = manualModelId,
                        onValueChange = { manualModelId = it; error = null },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            val id = manualModelId.trim()
                            if (id.isNotEmpty()) {
                                if (looksLikeImageGenerationModel(id)) {
                                    error = "图片生成模型请前往“设置 → 生图服务”添加。"
                                } else {
                                    if (id !in fetchedModels) fetchedModels.add(id)
                                    setSelectedModels((selectedModels + id).distinct())
                                    manualModelId = ""
                                }
                            }
                        },
                        enabled = manualModelId.isNotBlank(),
                    ) {
                        Icon(NovexIcons.Add, "添加模型")
                    }
                }
            }
            val displayModels = (fetchedModels + selectedModels)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(100)
            if (displayModels.isEmpty()) {
                Text(
                    "尚无模型，请先拉取或手动添加。",
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                displayModels.forEachIndexed { index, modelId ->
                    if (index == 0) {
                        HorizontalDivider(color = NovexColors.Divider, thickness = 0.5.dp)
                    }
                    ProviderModelRow(
                        modelId = modelId,
                        selected = modelId in selectedModels,
                        toolsOn = toolsEnabled(modelId),
                        checking = checkingModelId == modelId,
                        checkEnabled = checkingModelId == null || checkingModelId == modelId,
                        onToggleSelect = { checked ->
                            setSelectedModels(
                                if (checked) (selectedModels + modelId).distinct()
                                else selectedModels - modelId,
                            )
                        },
                        onToggleTools = {
                            modelToolsEnabled[modelId] = !toolsEnabled(modelId)
                            verificationResults.remove(modelId)
                        },
                        onCheck = {
                            if (checkingModelId == modelId) {
                                verificationJob?.cancel()
                            } else {
                                val values = validate(requireModels = false) ?: return@ProviderModelRow
                                startVerification(values, modelId)
                            }
                        },
                    )
                    if (index < displayModels.lastIndex) {
                        HorizontalDivider(
                            color = NovexColors.Divider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
            }
        }

        // —— 检测结果卡 ——
        if (verificationResults.isNotEmpty()) {
            SettingsSection(header = "检测结果") {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    verificationResults.forEach { (_, result) ->
                        val color = when (result.state) {
                            ModelResultState.PASSED -> Color(0xFF168A45)
                            ModelResultState.WARNING -> Color(0xFFB77900)
                            ModelResultState.FAILED -> NovexColors.Danger
                            ModelResultState.CANCELLED -> NovexColors.SecondaryText
                        }
                        val icon = when (result.state) {
                            ModelResultState.PASSED -> NovexIcons.CheckCircle
                            ModelResultState.WARNING, ModelResultState.FAILED -> NovexIcons.Error
                            ModelResultState.CANCELLED -> NovexIcons.Close
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                            Text(result.message, color = color, style = NovexType.Metadata)
                        }
                    }
                }
            }
        }

        // —— 提交区 ——
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            error?.let {
                Text(it, color = NovexColors.Danger, style = NovexType.Metadata)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    val values = validate() ?: return@Button
                    error = null
                    runCatching {
                        saveConnections(
                            repository = providerRepository,
                            existing = existing,
                            label = label,
                            base = values.base,
                            appendV1Suffix = appendV1Suffix,
                            direction = direction,
                            keyHelpUrl = presetSpec?.keyHelpUrl,
                            key = values.key,
                            modelIds = values.models,
                            modelToolsEnabled = values.models.associateWith(::toolsEnabled),
                            metadata = metadataForSave(values),
                        )
                    }.onSuccess {
                        onSaved()
                    }.onFailure { failure ->
                        error = "保存模型连接失败：${failure.message ?: failure.javaClass.simpleName}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存并启用（${selectedModels.size}）") }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (deleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("删除这个 AI 服务商？") },
            text = {
                Text("服务商、密钥及其 ${existingEntries.size} 个模型会被移除，历史对话不会删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        providerRepository.removeInstance(existing.id)
                        deleteConfirm = false
                        onBack()
                    },
                ) {
                    Text("删除", color = NovexColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 接口方向分段胶囊：灰底容器 + 选中段反色药丸（与全局预设语言选择同款）。 */
@Composable
private fun ProviderDirectionSelector(
    direction: NovexProviderDirection,
    onSelect: (NovexProviderDirection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NovexColors.SurfaceMuted)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        NovexProviderDirection.values().forEach { option ->
            val selected = option == direction
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) NovexColors.Surface else NovexColors.SurfaceMuted)
                    .clickable { onSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.buttonLabel,
                    fontSize = novexScaledSp(13),
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) NovexColors.Text else NovexColors.SecondaryText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 模型行：勾选 + 名称 + 工具开关徽标 + 单项检测/停止。 */
@Composable
private fun ProviderModelRow(
    modelId: String,
    selected: Boolean,
    toolsOn: Boolean,
    checking: Boolean,
    checkEnabled: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onCheck: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovexCheckToggle(checked = selected, onCheckedChange = onToggleSelect)
        Text(
            novexModelDisplayName(modelId),
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = NovexType.Body,
            color = if (selected) NovexColors.Text else NovexColors.SecondaryText,
        )
        IconButton(onClick = onToggleTools) {
            Icon(
                NovexIcons.Build,
                contentDescription = if (toolsOn) "关闭该模型的工具调用" else "开启该模型的工具调用",
                modifier = Modifier.size(18.dp),
                tint = if (toolsOn) Color(0xFF168A45) else NovexColors.TertiaryText,
            )
        }
        if (checking) {
            Box(
                modifier = Modifier.size(36.dp).clickable(onClick = onCheck),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = NovexColors.Primary,
                )
            }
        } else {
            IconButton(enabled = checkEnabled, onClick = onCheck) {
                Icon(
                    NovexIcons.Refresh,
                    contentDescription = "检测 $modelId",
                    modifier = Modifier.size(18.dp),
                    tint = if (checkEnabled) NovexColors.SecondaryText else NovexColors.TertiaryText,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

private suspend fun fetchModels(
    base: String,
    key: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection,
): List<LLMModel> {
    val fetched = runCatching {
        val canonical = novexCanonicalBase(base, appendV1Suffix)
        val models = if (direction == NovexProviderDirection.ANTHROPIC) {
            com.openminis.app.provider.ModelsCatalogApi.fetchAnthropicModels(
                key,
                canonical,
                forceRefresh = true,
            )
        } else {
            com.openminis.app.provider.ModelsCatalogApi.fetchOpenAiModels(
                key,
                canonical,
                forceRefresh = true,
            )
        }
        models.distinctBy { it.id }
    }.getOrDefault(emptyList())
    if (fetched.isNotEmpty()) return fetched
    // [T-provider-onboarding] DeepSeek 官方 host 拉取失败（网络/密钥探活波动）
    // → 内置兜底目录（deepseek-chat / deepseek-reasoner），别让预设卡在空列表。
    return if (direction == NovexProviderDirection.CHAT && novex.android.data.model.NovexProviderPresets.isDeepSeekBase(base)) {
        novex.android.data.model.NovexProviderPresets.deepSeekFallbackModels
    } else {
        emptyList()
    }
}

private suspend fun verifyConnection(
    base: String,
    key: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection,
    modelIds: List<String>,
    toolEnabledByModel: (String) -> Boolean,
): ConnectionVerification = withContext(Dispatchers.IO) {
    val canonical = novexCanonicalBase(base, appendV1Suffix); val client = OkHttpClient()
    fun fatal(message: String): ConnectionVerification {
        return ConnectionVerification(NovexModelVerification(emptyList(), emptyList()), message)
    }
    // [T-provider-direction] Anthropic 方向的探活/鉴权探测带上版本头；
    // 兼容中转普遍同时收 Bearer，与模型目录 anthropic 方言的自定义端点行为一致。
    fun probeRequest(url: String, withKey: Boolean): Request = Request.Builder()
        .url(url)
        .apply { if (direction == NovexProviderDirection.ANTHROPIC) header("anthropic-version", "2023-06-01") }
        .apply { if (withKey) header("Authorization", "Bearer $key") }
        .build()
    val reachable = runCatching { client.newCall(probeRequest("$canonical/models", withKey = false)).execute().use { it.code in 200..499 } }.getOrDefault(false)
    if (!reachable) return@withContext fatal("无法连接接口地址")
    val authCode = runCatching { client.newCall(probeRequest("$canonical/models", withKey = true)).execute().use { it.code } }.getOrDefault(0)
    if (authCode == 401 || authCode == 403) return@withContext fatal("密钥无效或访问被拒绝（HTTP $authCode）")

    val instance = novexProviderInstanceForSave(
        existing = null,
        label = "连接检测",
        base = base,
        appendV1Suffix = appendV1Suffix,
        direction = direction,
    ).copy(id = "novex-check")
    val providerDisplayName = when (direction) {
        NovexProviderDirection.ANTHROPIC -> "Anthropic 兼容接口"
        else -> "OpenAI（开放人工智能）兼容接口"
    }
    val providerResults = mutableMapOf<String, Result<LLMProvider>>()
    fun providerFor(modelId: String): Result<LLMProvider> = providerResults.getOrPut(modelId) {
        val model = LLMModel(modelId, novexModelDisplayName(modelId), providerDisplayName)
        runCatching { ProviderFactory.create(instance, key, model) }
    }
    val verificationTools = AgentTools.makeAgentTools(
        supportsImageInput = false,
        visionGroupConfigured = false,
    )
    suspend fun probeChat(modelId: String): String? {
        val provider = providerFor(modelId).getOrElse {
            return "无法创建模型连接：${it.message ?: it.javaClass.simpleName}"
        }
        return runCatching {
            val response = withTimeout(45_000) {
                provider.sendMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER, "只回复：连接成功")),
                    null,
                    64,
                    tools = if (toolEnabledByModel(modelId)) verificationTools else emptyList(),
                )
            }
            when {
                response.stopReason == null ->
                    "流式响应没有 finish_reason（结束原因），连接可能已中断"
                response.text.isBlank() ->
                    "HTTP 200，finish_reason=${response.stopReason}，但没有返回有效文字"
                else -> null
            }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
    suspend fun probeTool(modelId: String): String? {
        val provider = providerFor(modelId).getOrElse {
            return "无法创建模型连接：${it.message ?: it.javaClass.simpleName}"
        }
        return runCatching {
            var called = false
            var finishReason: String? = null
            val visibleText = StringBuilder()
            withTimeout(45_000) {
                provider.streamMessage(
                    listOf(
                        LLMMessage(
                            LLMMessage.Role.USER,
                            "请立即调用 present_choices，提供“继续”和“返回”两个选项，不要输出文字。",
                        ),
                    ),
                    null,
                    256,
                    tools = verificationTools,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> visibleText.append(chunk.text)
                        is LLMStreamChunk.ToolCallComplete -> {
                            if (chunk.name == "present_choices") called = true
                        }
                        is LLMStreamChunk.Finished -> finishReason = chunk.stopReason
                        else -> Unit
                    }
                }
            }
            when {
                called -> null
                finishReason == null ->
                    "流式响应没有 finish_reason（结束原因），也没有结构化工具调用"
                visibleText.isBlank() ->
                    "HTTP 200，finish_reason=$finishReason，但没有文字或结构化工具调用"
                else ->
                    "finish_reason=$finishReason，只返回了文字，没有结构化 present_choices 调用"
            }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    val result = verifyNovexModels(
        modelIds = modelIds,
        repetitions = 3,
        shouldProbeTools = toolEnabledByModel,
        chatProbe = ::probeChat,
        toolProbe = ::probeTool,
    )
    ConnectionVerification(result)
}

private fun saveConnections(
    repository: ProviderRepository,
    existing: ProviderInstance?,
    label: String,
    base: String,
    appendV1Suffix: Boolean,
    direction: NovexProviderDirection,
    keyHelpUrl: String? = null,
    key: String,
    modelIds: List<String>,
    modelToolsEnabled: Map<String, Boolean>,
    metadata: Map<String, LLMModel> = emptyMap(),
) {
    require(modelIds.none { looksLikeImageGenerationModel(it) || metadata[it]?.let(novex.android.data.model.ChatModelSelection::imageOutput) == true }) {
        "请选择聊天模型，生图模型不能用于此对话"
    }
    val instance = novexProviderInstanceForSave(existing, label, base, appendV1Suffix, direction, keyHelpUrl)
    if (existing == null) repository.addInstance(instance) else repository.updateInstance(instance)
    repository.saveApiKey(instance.id, key)
    val previousEntries = repository.entriesFor(instance.id)
        .filterNot { novex.android.data.model.ChatModelSelection.imageOutput(it.model) || novex.android.data.model.ChatModelSelection.imageOutput(it.baseModel) }
    val previousIds = previousEntries.map { it.id }.toSet()
    val group = repository.config.value.modelGroups.firstOrNull { candidate ->
        candidate.memberEntryIds.any { it in previousIds }
    }
    previousEntries.filter { it.model.id !in modelIds }.forEach { repository.removeEntry(it.id) }
    modelIds.forEach { modelId ->
        if (repository.entriesFor(instance.id).none { it.model.id == modelId && novex.android.data.model.ChatModelSelection.eligible(it) }) {
            repository.addEntry(
                ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = novexConnectionModel(metadata[modelId], LLMModel(
                        id = modelId,
                        displayName = novexModelDisplayName(modelId),
                        provider = instance.label,
                        inputModalities = novexChatInputModalities(modelId),
                        outputModalities = listOf("text"),
                    ), instance.effectiveBaseURL),
                    overrides = ModelOverrides(
                        supportsTools = modelToolsEnabled[modelId],
                    ),
                    isCustom = true,
                ),
            )
        }
    }
    repository.entriesFor(instance.id)
        .filterNot { novex.android.data.model.ChatModelSelection.imageOutput(it.model) || novex.android.data.model.ChatModelSelection.imageOutput(it.baseModel) }
        .forEach { entry ->
        val refreshedBase = novexConnectionModel(metadata[entry.model.id], entry.baseModel, instance.effectiveBaseURL)
        val desiredInput = novexChatInputModalities(entry.model.id, entry.model.inputModalities)
        val desiredOutput = listOf("text")
        val desiredTools = modelToolsEnabled[entry.model.id]
        if (entry.baseModel != refreshedBase || entry.model.inputModalities != desiredInput ||
            entry.model.outputModalities != desiredOutput ||
            entry.overrides.supportsTools != desiredTools
        ) {
            repository.updateEntry(
                entry.copy(
                    baseModel = refreshedBase,
                    overrides = entry.overrides.copy(
                        inputModalities = desiredInput,
                        outputModalities = desiredOutput,
                        supportsTools = desiredTools,
                    ),
                ),
            )
        }
    }
    val selectedEntries = repository.entriesFor(instance.id).filter {
        it.model.id in modelIds && novex.android.data.model.ChatModelSelection.eligible(it)
    }
    val selectedIds = selectedEntries.map { it.id }
    if (group == null) {
        ModelGroup(name = "默认模型", memberEntryIds = selectedIds.toMutableList()).also {
            repository.addGroup(it)
            repository.defaultPrimaryGroupId = it.id
        }
    } else {
        val retained = group.memberEntryIds.filterNot { it in previousIds }
        repository.updateGroup(group.copy(memberEntryIds = (retained + selectedIds).distinct().toMutableList()))
        repository.defaultPrimaryGroupId = group.id
    }
}

/** Fetched capacities survive saving; separately stored user overrides are untouched. */
internal fun novexConnectionModel(fetched: LLMModel?, previous: LLMModel, base: String?): LLMModel =
    novex.android.data.model.NovexDeepSeekModelMetadata.official(
        fetched ?: com.openminis.app.provider.ModelsDevApi.enrichModel(previous), base)
