package com.openminis.app.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.markdown.MarkdownText
import novex.android.ui.NovexIcons
import novex.android.ui.Scaffold
import novex.android.ui.TopAppBar

private const val MANUAL_ASSET_DIR = "skills/novex-manual"

/** 说明书一章：清单顺序即目录顺序。 */
private data class ManualChapter(
    val file: String,
    val title: String,
    val subtitle: String,
)

/**
 * Novex 说明书 —— 设置 → 文档。
 *
 * 目录与正文直接读 assets 里的捆绑技能目录（与 AI 经 skill_read 看到的是
 * 同一批文件）：FILES.txt 定顺序，每章标题取文件首个 `# ` 行，副标题从
 * SKILL.md 索引的章节地图（`file` → 覆盖范围）解析——单一事实源，
 * 说明书加章改章这里自动跟上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovexManualScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val chapters = remember { loadManualChapters(context) }
    var openedIndex by remember { mutableStateOf<Int?>(null) }

    val idx = openedIndex
    val opened = idx?.let { chapters.getOrNull(it) }
    if (opened != null && idx != null) {
        val content = remember(opened.file) { readManualAsset(context, opened.file) }
        ManualChapterPage(
            chapter = opened,
            content = content,
            hasPrev = idx > 0,
            hasNext = idx < chapters.lastIndex,
            onBack = { openedIndex = null },
            onPrev = { openedIndex = idx - 1 },
            onNext = { openedIndex = idx + 1 },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("说明书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Text(
                    text = "Novex 全部能力说明。也可以不读——在任意对话里对 AI 说「读说明书教我用 XX」，它会自己去读对应章节再讲给你听。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            itemsIndexed(chapters) { index, chapter ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openedIndex = index }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (chapter.subtitle.isNotBlank()) {
                            Text(
                                text = chapter.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Icon(
                        imageVector = NovexIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index < chapters.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualChapterPage(
    chapter: ManualChapter,
    content: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chapter.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, contentDescription = "返回目录")
                    }
                },
            )
        },
        bottomBar = {
            if (hasPrev || hasNext) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (hasPrev) {
                        Text(
                            text = "← 上一章",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onPrev)
                                .padding(8.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (hasNext) {
                        Text(
                            text = "下一章 →",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onNext)
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                MarkdownText(
                    markdown = content,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun loadManualChapters(context: Context): List<ManualChapter> {
    val files = runCatching {
        context.assets.open("$MANUAL_ASSET_DIR/FILES.txt").bufferedReader().use { reader ->
            reader.readLines().map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }.getOrDefault(listOf("SKILL.md"))
    val descriptions = parseChapterDescriptions(context)
    return files.mapNotNull { file ->
        if (file.equals("FILES.txt", ignoreCase = true)) return@mapNotNull null
        val head = runCatching {
            context.assets.open("$MANUAL_ASSET_DIR/$file").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@mapNotNull null
        val title = if (file == "SKILL.md") {
            "总览 · 你可以不看这本说明书"
        } else {
            head.lineSequence()
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
                ?: file.removeSuffix(".md")
        }
        val subtitle = if (file == "SKILL.md") {
            "Novex 是什么 + 章节地图 + AI 代读教学"
        } else {
            descriptions[file].orEmpty()
        }
        ManualChapter(file = file, title = title, subtitle = subtitle)
    }
}

/** 从 SKILL.md 索引的章节地图解析 `file` → 覆盖范围说明。 */
private fun parseChapterDescriptions(context: Context): Map<String, String> {
    val index = readManualAsset(context, "SKILL.md")
    val result = LinkedHashMap<String, String>()
    val row = Regex("^\\|\\s*`([^`]+\\.md)`\\s*\\|([^|]+)\\|([^|]+)\\|")
    for (line in index.lineSequence()) {
        val match = row.find(line.trim()) ?: continue
        val coverage = match.groupValues[2].trim()
        val whenToRead = match.groupValues[3].trim()
        result[match.groupValues[1].trim()] =
            whenToRead.ifEmpty { coverage }
    }
    return result
}

private fun readManualAsset(context: Context, file: String): String =
    runCatching {
        context.assets.open("$MANUAL_ASSET_DIR/$file").bufferedReader().use { it.readText() }
    }.getOrDefault("").let { stripFrontmatter(it) }

/** SKILL.md 的 YAML frontmatter 是元数据不是正文——展示层剥掉。 */
private fun stripFrontmatter(text: String): String {
    if (!text.startsWith("---")) return text
    val end = text.indexOf("\n---", 3)
    return if (end >= 0) text.substring(end + 4).trimStart('\n') else text
}
