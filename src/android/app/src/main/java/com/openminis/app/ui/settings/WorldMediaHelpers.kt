package com.openminis.app.ui.settings

import com.openminis.app.data.character.MediaAssetSlot
import java.io.File

// [T-dead-code-sweep] 原 WorldCatalogScreens.kt 的三代叠葬屏幕已删
// （CatalogWorldDetailScreen/CatalogWorldEditorScreen 等零调用方），
// 本文件只剩仍被活路径引用的媒体槽助手。

internal data class WorldImageSlotSpec(
    val slot: MediaAssetSlot,
    val label: String,
    val required: Boolean = false,
)

internal fun worldImageSlots(): List<WorldImageSlotSpec> = listOf(
    WorldImageSlotSpec(MediaAssetSlot.WORLD_COVER, "世界封面"),
    WorldImageSlotSpec(MediaAssetSlot.WORLD_LOGO, "世界标志"),
    WorldImageSlotSpec(MediaAssetSlot.WORLD_BACKGROUND, "全屏背景"),
)

internal fun String?.existingMediaFile(): File? = this?.let(::File)?.takeIf(File::exists)
