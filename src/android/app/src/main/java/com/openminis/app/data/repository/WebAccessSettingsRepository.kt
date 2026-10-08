package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 联网能力设置：模型侧 web_search 工具的搜索后端凭据。
 *
 * 搜索后端与聊天模型是两件独立的事——web_search 是客户端实现的
 * function tool，工具内部拿着这里存的 key 去调对应搜索引擎；
 * 模型只负责发起调用，因此联网不挑 provider。
 *
 * 后端启用规则：某家填了 key 即启用；多家都有 key 时按
 * [BACKEND_ORDER] 的顺序取第一家服务（智谱 Coding Plan 优先——
 * 订阅额度抵扣不另扣费）。删掉 key 即停用。
 */
class WebAccessSettingsRepository(context: Context) {

    enum class SearchBackend(val keyPref: String, val displayName: String) {
        /** 智谱 Coding Plan：MCP web_search_prime，走套餐额度抵扣。 */
        ZHIPU_CODING_PLAN("webSearch.zhipuCodingKey", "智谱 Coding Plan"),

        /** 智谱按量：REST /web_search，按次计费。 */
        ZHIPU_PAYG("webSearch.zhipuPaygKey", "智谱（按量）"),

        TAVILY("webSearch.tavilyKey", "Tavily"),
        BRAVE("webSearch.braveKey", "Brave"),
    }

    private val store: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow(
        SearchBackend.entries.associateWith { store.getString(it.keyPref, null).orEmpty() },
    )

    /** 各后端当前已存的 key（"" = 未配）。 */
    val keys: StateFlow<Map<SearchBackend, String>> = _keys.asStateFlow()

    fun setKey(backend: SearchBackend, key: String) {
        val trimmed = key.trim()
        store.edit().putString(backend.keyPref, trimmed).apply()
        _keys.value = _keys.value + (backend to trimmed)
    }

    /** 当前生效后端：按优先级取第一个有 key 的；都没有 → null（工具不声明）。 */
    fun activeBackend(): Pair<SearchBackend, String>? =
        BACKEND_ORDER.firstNotNullOfOrNull { backend ->
            _keys.value[backend].orEmpty().takeIf { it.isNotEmpty() }?.let { backend to it }
        }

    companion object {
        private const val PREFS_NAME = "web_access_settings"
        private val BACKEND_ORDER = listOf(
            SearchBackend.ZHIPU_CODING_PLAN,
            SearchBackend.ZHIPU_PAYG,
            SearchBackend.TAVILY,
            SearchBackend.BRAVE,
        )
    }
}
