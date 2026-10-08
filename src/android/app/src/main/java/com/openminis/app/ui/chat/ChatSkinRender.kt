package com.openminis.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.openminis.app.data.character.SkinAvatar
import com.openminis.app.data.character.SkinBar
import com.openminis.app.data.character.SkinBubble
import com.openminis.app.data.character.SkinSlice
import com.openminis.app.ui.theme.ChatPalette
import com.openminis.app.ui.theme.DarkChatPalette
import com.openminis.app.ui.theme.LightChatPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// 皮肤渲染层 v1：气泡 = 令牌绘制（圆角填充/纹理平铺/描边/角饰/阴影）
// 或显式 slice（多段拉伸位图）。素材值一律 dp——位图图素按声明的
// scale 折算，密度无关。位图经 SkinBitmapCache 共享 + 按目标尺寸降采样，
// 同一皮肤图不再每个气泡各解码一份。

internal fun parseSkinColor(raw: String?): Color? =
    raw?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** 绝对写法归一到 minis:// 形式；"./" 相对路径由调用方先过 ChatSkin.resolveAssetPath。 */
internal fun normalizeSkinImagePath(path: String): String = when {
    path.startsWith("novex-res://") -> "minis://" + path.removePrefix("novex-res://")
    path.startsWith("/var/minis/") -> "minis://" + path.removePrefix("/var/minis/")
    else -> path
}

// ─── 位图缓存 ────────────────────────────────────────────────────────────────

/**
 * 皮肤位图共享缓存：key = 绝对路径 + mtime + 采样档位。同一皮肤的所有气泡
 * 复用一份解码结果；大图按最长边 1024 降采样（气泡/饰件场景足够）。
 */
internal object SkinBitmapCache {
    private const val MAX_DECODE_EDGE = 1024
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getOrDecode(file: java.io.File): Bitmap? {
        val key = "${file.absolutePath}|${file.lastModified()}"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE_EDGE) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        cache.put(key, bmp)
        return bmp
    }
}

/** 解析皮肤素材为 ImageBitmap（缓存 + 降采样）；缺失/解码失败 → null（调用方回落）。 */
@Composable
internal fun rememberSkinBitmap(path: String?, sessionId: String?): ImageBitmap? {
    val context = LocalContext.current
    if (path.isNullOrBlank()) return null
    val bitmap by produceState<ImageBitmap?>(null, path, sessionId) {
        value = withContext(Dispatchers.IO) {
            resolveMdMediaFile(context, normalizeSkinImagePath(path), sessionId)
                ?.let(SkinBitmapCache::getOrDecode)
                ?.asImageBitmap()
        }
    }
    return bitmap
}

// ─── 气泡 ────────────────────────────────────────────────────────────────────

/** 气泡变体键：状态位（queued/streaming/error）+ 分组位（groupFirst/Mid/Last）。 */
internal enum class SkinGroupPos { SOLO, FIRST, MID, LAST }

/** 解析生效气泡规格：基础令牌上叠状态变体，再叠分组变体（分组让"首/中/尾"能削角）。 */
internal fun SkinBubble?.effective(stateKeys: List<String>, groupPos: SkinGroupPos): SkinBubble? {
    val spec = this ?: return null
    var merged = spec
    for (key in stateKeys) {
        spec.variants?.get(key)?.let { merged = merged.mergedWith(it) }
    }
    val groupKey = when (groupPos) {
        SkinGroupPos.FIRST -> "groupFirst"
        SkinGroupPos.MID -> "groupMid"
        SkinGroupPos.LAST -> "groupLast"
        SkinGroupPos.SOLO -> null
    }
    groupKey?.let { spec.variants?.get(it)?.let { v -> merged = merged.mergedWith(v) } }
    return merged.takeUnless { it.isEmpty }
}

/** 气泡需要的位图资源：纹理 / 角饰 / 切片图。 */
internal class SkinBubbleAssets(
    val texture: ImageBitmap?,
    val corners: ImageBitmap?,
    val slice: ImageBitmap?,
)

@Composable
internal fun rememberSkinBubbleAssets(spec: SkinBubble?, base: String?, sessionId: String?): SkinBubbleAssets {
    val texture = rememberSkinBitmap(spec?.texture?.img?.let { p -> resolveSkinPath(p, base) }, sessionId)
    val corners = rememberSkinBitmap(spec?.corners?.img?.let { p -> resolveSkinPath(p, base) }, sessionId)
    val slice = rememberSkinBitmap(spec?.slice?.img?.let { p -> resolveSkinPath(p, base) }, sessionId)
    return SkinBubbleAssets(texture, corners, slice)
}

private fun resolveSkinPath(path: String, base: String?): String? =
    if (path.startsWith("./")) base?.trimEnd('/')?.let { "$it/" + path.removePrefix("./") } else path

/** 气泡内容内边距：slice.content(图素/scale) > padding > 默认。 */
internal fun skinBubblePadding(spec: SkinBubble, sliceBitmap: ImageBitmap?): PaddingValues {
    spec.padding?.let { p ->
        return PaddingValues(
            start = p.getOrElse(0) { 14 }.dp, top = p.getOrElse(1) { 10 }.dp,
            end = p.getOrElse(2) { 14 }.dp, bottom = p.getOrElse(3) { 10 }.dp,
        )
    }
    spec.slice?.content?.let { c ->
        val scale = spec.slice.scale
        return PaddingValues(
            start = (c.getOrElse(0) { 16 } / scale).dp,
            top = (c.getOrElse(1) { 10 } / scale).dp,
            end = (c.getOrElse(2) { 16 } / scale).dp,
            bottom = (c.getOrElse(3) { 10 } / scale).dp,
        )
    }
    return PaddingValues(horizontal = 14.dp, vertical = 8.dp)
}

/**
 * 气泡绘制：slice 在位且尺寸达标 → 多段拉伸位图；否则令牌层
 * （阴影 → 圆角填充+纹理 → 描边 → 四角饰件）。全部画在 drawBehind，
 * 不动尺寸、不动触摸区。
 */
internal fun Modifier.skinBubble(spec: SkinBubble, assets: SkinBubbleAssets): Modifier =
    drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind
        val slice = spec.slice
        val sliceBmp = assets.slice
        if (slice != null && sliceBmp != null &&
            sliceFits(slice, w, h)
        ) {
            drawSlice(slice, sliceBmp, w, h)
            return@drawBehind
        }
        drawTokenBubble(spec, assets, w, h)
    }

private fun DrawScope.sliceFits(slice: SkinSlice, w: Float, h: Float): Boolean {
    val min = slice.min ?: return true
    val minW = min.getOrElse(0) { 0 }.dp.toPx()
    val minH = min.getOrElse(1) { 0 }.dp.toPx()
    return w >= minW && h >= minH
}

private fun DrawScope.drawTokenBubble(spec: SkinBubble, assets: SkinBubbleAssets, w: Float, h: Float) {
    val radius = spec.border?.radius?.dp?.toPx() ?: 12.dp.toPx()
    val canvas = drawContext.canvas.nativeCanvas

    // 阴影（先画：fill 盖在 shadow 上，shadowLayer 只产生外发光）。
    spec.shadow?.let { sh ->
        val color = parseSkinColor(sh.color) ?: Color(0x33000000)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.TRANSPARENT
            setShadowLayer(
                (sh.blur ?: 4f).dp.toPx(),
                sh.dx.dp.toPx(), sh.dy.dp.toPx(),
                color.toArgbNative(),
            )
        }
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
    }

    val path = Path().apply {
        addRoundRect(RoundRect(0f, 0f, w, h, radius, radius))
    }
    clipPath(path) {
        parseSkinColor(spec.fill)?.let { drawRect(it) }
        val tex = assets.texture
        val texSpec = spec.texture
        if (tex != null && texSpec != null) {
            if (texSpec.tile) {
                val shader = BitmapShader(tex.asAndroidBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.shader = shader
                    alpha = (texSpec.alpha * 255).roundToInt().coerceIn(0, 255)
                }
                canvas.drawRect(0f, 0f, w, h, paint)
            } else {
                drawImage(
                    image = tex,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tex.width, tex.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    alpha = texSpec.alpha,
                )
            }
        }
    }

    spec.border?.let { b ->
        parseSkinColor(b.color)?.let { c ->
            drawRoundRect(
                color = c,
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = Stroke(width = (b.width ?: 1f).dp.toPx()),
            )
        }
    }

    // 四角饰件：同一张图四角镜像贴，不拉伸。气泡太小（高不足
    // 饰件 2.5 倍）时省略——饰件在迷你泡上只会挤成一团。
    val corner = assets.corners
    if (corner != null && spec.corners != null) {
        val sz = spec.corners.size.dp.toPx()
        if (h >= sz * 2.5f && w >= sz * 2.5f) {
            val inset = spec.corners.inset.dp.toPx()
            val positions = arrayOf(
                floatArrayOf(inset, inset, 1f, 1f),
                floatArrayOf(w - inset, inset, -1f, 1f),
                floatArrayOf(inset, h - inset, 1f, -1f),
                floatArrayOf(w - inset, h - inset, -1f, -1f),
            )
            for ((px, py, sx, sy) in positions) {
                canvas.save()
                canvas.translate(px, py)
                canvas.scale(sx, sy)
                canvas.drawBitmap(
                    corner.asAndroidBitmap(),
                    android.graphics.Rect(0, 0, corner.width, corner.height),
                    android.graphics.RectF(
                        if (sx < 0) -sz else 0f, if (sy < 0) -sz else 0f,
                        if (sx < 0) 0f else sz, if (sy < 0) 0f else sz,
                    ),
                    null,
                )
                canvas.restore()
            }
        }
    }
}

/**
 * 多段拉伸切片：stretchX/stretchY 把位图切成分段网格，固定段按
 * px/scale 等比画、拉伸段瓜分剩余空间。未声明 stretch 时用 content
 * insets 退化为"固定角 + 中心拉伸"（旧格式等价语义）。
 */
private fun DrawScope.drawSlice(slice: SkinSlice, bmp: ImageBitmap, w: Float, h: Float) {
    val bw = bmp.width
    val bh = bmp.height
    val scale = slice.scale.coerceAtLeast(0.25f)
    val content = slice.content
    val xRanges = slice.stretchX ?: content?.let { listOf(it.getOrElse(0) { 0 }..(bw - it.getOrElse(2) { 0 })) }
    val yRanges = slice.stretchY ?: content?.let { listOf(it.getOrElse(1) { 0 }..(bh - it.getOrElse(3) { 0 })) }
    val xSegs = axisSegments(bw, xRanges)
    val ySegs = axisSegments(bh, yRanges)

    val xDst = dstSizes(xSegs, w, scale)
    val yDst = dstSizes(ySegs, h, scale)

    var dstY = 0f
    var srcY = 0
    for ((ri, row) in ySegs.withIndex()) {
        var dstX = 0f
        var srcX = 0
        for ((ci, col) in xSegs.withIndex()) {
            val dW = xDst[ci]
            val dH = yDst[ri]
            if (dW > 0.5f && dH > 0.5f) {
                drawImage(
                    image = bmp,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(col.length, row.length),
                    dstOffset = IntOffset(dstX.roundToInt(), dstY.roundToInt()),
                    dstSize = IntSize(dW.roundToInt(), dH.roundToInt()),
                )
            }
            srcX += col.length
            dstX += dW
        }
        srcY += row.length
        dstY += yDst[ri]
    }
}

private class AxisSeg(val length: Int, val stretch: Boolean)

/** 把轴切成 固定/拉伸 交替段：ranges 内为拉伸段，其余固定。 */
private fun axisSegments(total: Int, ranges: List<IntRange>?): List<AxisSeg> {
    if (ranges.isNullOrEmpty()) return listOf(AxisSeg(total, true))
    val sorted = ranges.map { it.first.coerceIn(0, total)..it.last.coerceIn(0, total) }
        .filter { !it.isEmpty() }.sortedBy { it.first }
    val segs = mutableListOf<AxisSeg>()
    var cursor = 0
    for (r in sorted) {
        if (r.first > cursor) segs += AxisSeg(r.first - cursor, false)
        segs += AxisSeg(r.last - r.first + 1, true)
        cursor = r.last + 1
    }
    if (cursor < total) segs += AxisSeg(total - cursor, false)
    return segs
}

/** 固定段按 px/scale 折算，拉伸段按源宽占比瓜分剩余像素。 */
private fun Density.dstSizes(segs: List<AxisSeg>, dstTotal: Float, scale: Float): FloatArray {
    val fixed = segs.filter { !it.stretch }.sumOf { it.length } / scale
    val stretchSrc = segs.filter { it.stretch }.sumOf { it.length }
    val leftover = (dstTotal - fixed.dp.toPx()).coerceAtLeast(0f)
    return FloatArray(segs.size) { i ->
        val s = segs[i]
        if (s.stretch) {
            if (stretchSrc > 0) leftover * s.length / stretchSrc else 0f
        } else (s.length / scale).dp.toPx()
    }
}

/** 气泡内容配色：contentScheme 声明亮/暗底 → 内部文字/markdown 整套换。 */
internal fun skinBubblePalette(base: ChatPalette, spec: SkinBubble?): ChatPalette {
    if (spec == null) return base
    val textOverride = parseSkinColor(spec.text)
    val src = when (spec.contentScheme) {
        "light" -> LightChatPalette
        "dark" -> DarkChatPalette
        else -> if (textOverride != null) base else return base
    }
    return base.copy(
        primaryText = textOverride ?: src.primaryText,
        secondaryText = src.secondaryText,
        tertiaryText = src.tertiaryText,
        disabledText = src.disabledText,
        codeBlockBg = src.codeBlockBg,
        codeBlockText = src.codeBlockText,
        inlineCodeBg = src.inlineCodeBg,
        inlineCodeText = src.inlineCodeText,
        blockquoteBar = src.blockquoteBar,
        tableBorder = src.tableBorder,
        separator = src.separator,
        toolBg = src.toolBg,
        toolBorder = src.toolBorder,
        toolCapsuleBg = src.toolCapsuleBg,
        thumbnailBorder = src.thumbnailBorder,
    )
}

// ─── 顶栏 / 输入栏横带 ───────────────────────────────────────────────────────

/** 横带皮肤：底色 + 可选平铺纹理 + 边缘细线（top|bottom）。 */
internal fun Modifier.skinBar(bar: SkinBar, texture: ImageBitmap?): Modifier =
    drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind
        parseSkinColor(bar.fill)?.let { drawRect(it) }
        if (texture != null && bar.texture != null) {
            val canvas = drawContext.canvas.nativeCanvas
            if (bar.texture.tile) {
                val shader = BitmapShader(
                    texture.asAndroidBitmap(),
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT,
                )
                canvas.drawRect(0f, 0f, w, h, android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.shader = shader
                    alpha = (bar.texture.alpha * 255).roundToInt().coerceIn(0, 255)
                })
            } else {
                drawImage(
                    image = texture,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(texture.width, texture.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    alpha = bar.texture.alpha,
                )
            }
        }
        parseSkinColor(bar.hairlineColor)?.let { c ->
            val lw = bar.hairlineWidth.dp.toPx()
            val y = if (bar.hairlineEdge == "top") lw / 2f else h - lw / 2f
            drawLine(c, Offset(0f, y), Offset(w, y), strokeWidth = lw)
        }
    }

// ─── 头像框 ──────────────────────────────────────────────────────────────────

/** 头像框皮肤：画在头像之上——先贴饰件图（如有），再画原生环（颜色+线宽）。 */
internal fun Modifier.skinAvatarFrame(avatar: SkinAvatar, img: ImageBitmap?): Modifier =
    drawWithContent {
        drawContent()
        if (img != null) {
            drawImage(
                image = img,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
        val color = parseSkinColor(avatar.color)
        val width = avatar.width
        if (color != null && width != null && width > 0) {
            val stroke = width.dp.toPx()
            drawCircle(
                color = color,
                radius = (size.minDimension - stroke) / 2f,
                center = center,
                style = Stroke(stroke),
            )
        }
    }

private fun Color.toArgbNative(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt(),
)

private fun ImageBitmap.asAndroidBitmap(): Bitmap = asAndroidBitmap()
