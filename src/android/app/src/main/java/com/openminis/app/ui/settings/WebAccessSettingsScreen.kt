package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.WebAccessSettingsRepository
import com.openminis.app.data.repository.WebAccessSettingsRepository.SearchBackend
import com.openminis.app.ui.components.openExternalUrl
import novex.android.ui.NovexSettingsScaffold
import novex.android.ui.NovexSettingsSection
import novex.android.ui.NovexSettingsVectorRow
import novex.android.ui.OutlinedTextField
import novex.android.ui.TextButton

/**
 * 联网设置：web_search 工具的搜索后端凭据页。
 *
 * 设计要点（与 WebAccessSettingsRepository 注释一致）：
 *  - 搜索后端和聊天模型解耦——key 填在这里，联网是 app 级能力；
 *  - 每家一行说明 + 取 key 跳转 + 密钥输入框（随输随存），填了即
 *    启用，多家都有 key 时按仓库里的优先级表取第一家；
 *  - web_fetch 不依赖这里，是任何配置下都可用的免费件。
 */
@Composable
fun WebAccessSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { WebAccessSettingsRepository(context) }
    val keys by repo.keys.collectAsState()

    NovexSettingsScaffold(title = "联网", onBack = onBack) {
        NovexSettingsSection(
            title = "搜索后端",
            footer = "web_search 工具按上面的顺序使用第一家已配置的后端；web_fetch（打开网页）不消耗这些额度，任何情况下都可用。",
        ) {
            Backend.entries.forEachIndexed { index, backend ->
                BackendRow(
                    backend = backend,
                    savedKey = keys[backend.repoBackend].orEmpty(),
                    onKeyChange = { repo.setKey(backend.repoBackend, it) },
                    onGetKey = { openExternalUrl(context, backend.keyUrl) },
                    showDivider = index < Backend.entries.size - 1,
                )
            }
        }
    }
}

/** UI 行的静态描述：展示名、一句说明、取 key 链接、对应仓库枚举。 */
private enum class Backend(
    val repoBackend: SearchBackend,
    val headline: String,
    val subtitle: String,
    val keyUrl: String,
) {
    ZHIPU_CODING_PLAN(
        repoBackend = SearchBackend.ZHIPU_CODING_PLAN,
        headline = "智谱 Coding Plan",
        subtitle = "订阅内含 web_search_prime 检索，搜索计进套餐额度，不单独扣费。填入 Coding Plan 的 API Key。",
        keyUrl = "https://www.bigmodel.cn/claude-code?tab=api",
    ),
    ZHIPU_PAYG(
        repoBackend = SearchBackend.ZHIPU_PAYG,
        headline = "智谱（按量）",
        subtitle = "开放平台 Web Search API（search_std），按次计费。填入开放平台 API Key。",
        keyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
    ),
    TAVILY(
        repoBackend = SearchBackend.TAVILY,
        headline = "Tavily",
        subtitle = "通用搜索 API，免费额度每月 1000 次。",
        keyUrl = "https://app.tavily.com/home",
    ),
    BRAVE(
        repoBackend = SearchBackend.BRAVE,
        headline = "Brave Search",
        subtitle = "通用搜索 API，免费档每月 2000 次。",
        keyUrl = "https://brave.com/search/api/",
    ),
    DEEPSEEK(
        repoBackend = SearchBackend.DEEPSEEK,
        headline = "DeepSeek",
        subtitle = "官方 Anthropic 兼容端点的服务端联网搜索，每次检索消耗一次模型调用。仅 api.deepseek.com 官方 Key 有效，第三方中转不支持。",
        keyUrl = "https://platform.deepseek.com/api_keys",
    ),
}

@Composable
private fun BackendRow(
    backend: Backend,
    savedKey: String,
    onKeyChange: (String) -> Unit,
    onGetKey: () -> Unit,
    showDivider: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        NovexSettingsVectorRow(
            title = backend.headline,
            subtitle = if (savedKey.isNotEmpty()) "${backend.subtitle}\n已配置" else backend.subtitle,
            showChevron = false,
            showDivider = false,
            minHeight = 0.dp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = savedKey,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("API Key") },
        )
        TextButton(onClick = onGetKey) {
            Text(
                "获取 ${backend.headline} 密钥 →",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showDivider) {
            HorizontalDivider()
        }
    }
}
