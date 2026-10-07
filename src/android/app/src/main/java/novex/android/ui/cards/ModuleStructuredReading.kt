package novex.android.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import com.openminis.app.ui.noven.NovenColors
import novex.content.ContentBlock
import novex.content.ContentModule

/** 结构化正文读取上限：超过即回退 Markdown 分页（几十万字模块不硬吃）。 */
private const val STRUCTURED_CHAR_CAP = 24000

/**
 * [T008] 结构化阅读态：模板模块（时间线/属性面板/关系）不再 Markdown 直渲。
 * 解析失败或超长 → 回退原有 ReadingText 分页，行为零回归。
 */
@Composable internal fun StructuredModuleReading(module: ContentModule, kind: StructuredKind, model: CardSessionModel) {
    val ref = (module.blocks.single() as ContentBlock.Text).content
    var parsed by remember(module.id) { mutableStateOf<Pair<StructuredContent?, String>?>(null) }
    LaunchedEffect(ref, kind) {
        val builder = StringBuilder()
        var offset = 0L
        var more = true
        while (more && builder.length < STRUCTURED_CHAR_CAP) {
            val page = runCatching { model.textPage(ref, offset, 4096) }.getOrNull() ?: break
            builder.append(page.text)
            more = page.next != null
            offset += 4096
        }
        val text = builder.toString()
        parsed = (if (builder.length >= STRUCTURED_CHAR_CAP) null else parseStructured(kind, text)) to text
    }
    val state = parsed
    if (state == null) { Text("载入中", style = NovexType.Metadata, color = NovexColors.SecondaryText); return }
    when (val content = state.first) {
        is StructuredContent.Timeline -> TimelineReading(content)
        is StructuredContent.Panel -> PanelReading(content)
        is StructuredContent.Relations -> RelationsReading(content)
        // 解析不出结构：超长或自由文本，回退 Markdown 分页（沿用阅读器既有加载器）。
        null -> com.openminis.app.ui.chat.MarkdownBlock(rawText = state.second, isStreaming = false)
    }
}

@Composable private fun TimelineReading(timeline: StructuredContent.Timeline) {
    Column(Modifier.fillMaxWidth()) {
        timeline.nodes.forEachIndexed { index, node ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // 左侧时间轴：圆点+贯穿线
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 16.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(NovenColors.Mint))
                    if (index != timeline.nodes.lastIndex)
                        Box(Modifier.width(2.dp).fillMaxHeight().padding(vertical = 2.dp).background(NovenColors.Mint.copy(alpha = 0.25f)))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 0.dp, bottom = 14.dp, end = 16.dp)) {
                    Text(node.time, style = NovexType.ItemTitle, color = NovenColors.Text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    node.events.forEach {
                        Text("· $it", style = NovexType.Body, color = NovexColors.SecondaryText)
                    }
                    node.participants.forEach {
                        Text("参与：$it", style = NovexType.Metadata, color = NovexColors.TertiaryText)
                    }
                    node.consequences.forEach {
                        Text("→ $it", style = NovexType.Metadata, color = NovexColors.SecondaryText)
                    }
                }
            }
        }
    }
}

@Composable private fun PanelReading(panel: StructuredContent.Panel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        panel.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NovexColors.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.getOrElse(0) { "" }, style = NovexType.ItemTitle, color = NovexColors.Text,
                    modifier = Modifier.weight(1.2f))
                Text(row.getOrElse(1) { "" }, style = NovexType.ItemTitle, color = NovenColors.Mint,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.8f))
                if (panel.columns.size > 2)
                    Text(row.getOrElse(2) { "" }, style = NovexType.Metadata, color = NovexColors.TertiaryText,
                        modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun RelationsReading(relations: StructuredContent.Relations) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        relations.entries.forEach { entry ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NovexColors.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(NovenColors.Mint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center) {
                        Text(entry.name.take(1), color = NovenColors.Mint, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.name, style = NovexType.ItemTitle, color = NovenColors.Text)
                            entry.type?.let {
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(NovenColors.Mint.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text(it, style = NovexType.Metadata, color = NovenColors.Mint)
                                }
                            }
                            entry.familiarity?.let {
                                Text(it, style = NovexType.Metadata, color = NovexColors.TertiaryText)
                            }
                        }
                        entry.description?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, style = NovexType.Metadata, color = NovexColors.SecondaryText, maxLines = 2)
                        }
                    }
                }
                entry.knot?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("心结：$it", style = NovexType.Metadata, color = NovexColors.TertiaryText)
                }
            }
        }
    }
}
