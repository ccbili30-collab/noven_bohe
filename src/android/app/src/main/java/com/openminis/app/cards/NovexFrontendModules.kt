package com.openminis.app.cards

// 卡级前端件标记：带本标签的模块正文写 ```novex-def 围栏，会话开始时
// 这些定义被预铺进前端件注册表（见 ui/chat/NovexArtifacts.kt）——卡作者/
// 模型做卡时把调好的界面结构随卡封进，玩家开局即可被 novex-use 调用，
// 且随卡导出/分享。tags 字段经 CardStructureCodec 正常往返。
internal const val NOVEX_FRONTEND_MODULE_TAG = "novex-frontend"

internal fun novex.content.ContentModule.isFrontendModule(): Boolean =
    NOVEX_FRONTEND_MODULE_TAG in tags
