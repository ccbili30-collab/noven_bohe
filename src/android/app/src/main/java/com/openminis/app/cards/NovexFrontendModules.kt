package com.openminis.app.cards

// 卡级前端件标记：带本标签的模块正文写 ```novex-def 围栏，会话开始时
// 这些定义被预铺进前端件注册表（见 ui/chat/NovexArtifacts.kt）——卡作者/
// 模型做卡时把调好的界面结构随卡封进，玩家开局即可被 novex-use 调用，
// 且随卡导出/分享。tags 字段经 CardStructureCodec 正常往返。
internal const val NOVEX_FRONTEND_MODULE_TAG = "novex-frontend"

// 卡级皮肤包标记：带本标签的模块正文放 ```novex-skin 围栏（皮肤 JSON），
// 激活卡时播种为会话皮肤——卡自带气泡/顶图/底图/头像框/覆盖层外观，
// 随卡导出分享。会话已有皮肤或用户已换肤时不覆盖（每会话只播种一次）。
internal const val NOVEX_SKIN_MODULE_TAG = "novex-skin"

internal fun novex.content.ContentModule.isFrontendModule(): Boolean =
    NOVEX_FRONTEND_MODULE_TAG in tags

internal fun novex.content.ContentModule.isSkinModule(): Boolean =
    NOVEX_SKIN_MODULE_TAG in tags
