package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMStreamChunk
import novex.core.StageAnnotation
import novex.core.StageDeltaResolver

/**
 * 舞台协议 v0.3 B6 静默查证轮：作品发 novex-query → 同模型换"查证员"
 * 角色做一次不进消息树的调用 → 要求只回 novex-use 信封 → 写成附属记
 * 录（锚定发起时最近的助手消息，见 PC 回执 §B3：期间新回合开始也锚
 * 这里）→ 宿主推帧 reason:"query"。失败不回写、不重试风暴。
 */
internal fun ChatViewModel.runIslandQuery(
    domain: String,
    entity: String,
    anchorMessageId: String?,
    onDone: (Boolean) -> Unit = {},
) {
    val provider = currentProvider ?: run { onDone(false); return }
    val anchor = anchorMessageId ?: run { onDone(false); return }
    // P1 单模型双提示词：同一模型，查证角色。信封形状自洽即可——作品
    // 按 envelope.data 重放。
    val prompt = buildString {
        append("你是本局资料的查证员。这是一次后台查证，不面向玩家，")
        append("不进入对话历史。根据你掌握的本局设定回答下述查询，只回复一个 ")
        append("```novex-use 围栏，内容为一个 JSON 对象，形如 ")
        append("{\"kind\":\"query-answer\",\"query\":{\"domain\":\"")
        append(domain).append("\",\"entity\":\"").append(entity)
        append("\"},\"data\":{...}}——不要输出任何其他文字。")
    }
    viewModelScope.launch {
        val out = StringBuilder()
        runCatching {
            withTimeout(90_000) {
                provider.streamMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER, prompt)),
                    null, 1024,
                ).collect { chunk -> if (chunk is LLMStreamChunk.Text) out.append(chunk.text) }
            }
        }
        val envelope = StageDeltaResolver.extractUseEnvelopes(out.toString()).firstOrNull()
            ?: run { onDone(false); return@launch }
        runCatching {
            novexSettingsStore.update { configuration ->
                configuration.copy(stageAnnotations = configuration.stageAnnotations +
                    StageAnnotation(anchor, envelope, "$domain/$entity"))
            }
        }.onSuccess { onDone(true) }.onFailure { onDone(false) }
    }
}
