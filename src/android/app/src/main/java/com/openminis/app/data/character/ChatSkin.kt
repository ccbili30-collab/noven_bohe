package com.openminis.app.data.character

import org.json.JSONArray
import org.json.JSONObject

// 会话皮肤包规范 v1。气泡 = 令牌（fill/texture/border/shadow/padding/
// maxWidth/contentScheme/corners）或显式声明的 slice（多段拉伸图），
// 不再用"整张位图 + insets 即切点即边宽"。所有素材值都以 dp 计，
// slice.img 的 scale 声明图是几倍分辨率，客户端换算。
//
// 三个来源按优先级生效：
//   1. 消息里最新的 ```novex-skin 围栏（模型/创作者写入）
//   2. 绑定卡里打 novex-skin 标签的模块（卡自带皮肤，仅会话无皮肤时播种）
//   3. 已持久化的皮肤
//
// 图片路径：绝对写法 /var/minis/...、minis://...、novex-res://...；
// "./x.png" 相对写法按 skin.base（缺省 minis://shared/skins/<name>/）解析。
// 解析本身仍走界面岛同一条 minis:// 安全通道。

private fun optColor(json: JSONObject, key: String): String? =
    json.optString(key).trim().ifEmpty { null }

private fun optNumList(json: JSONObject, key: String): List<Int>? =
    json.optJSONArray(key)?.let { arr ->
        (0 until minOf(4, arr.length())).map { arr.optInt(it) }
    }?.takeIf { it.isNotEmpty() }

private fun optRangeList(json: JSONObject, key: String): List<IntRange>? =
    json.optJSONArray(key)?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONArray(i)?.let { pair ->
                if (pair.length() >= 2) pair.optInt(0)..pair.optInt(1) else null
            }
        }
    }?.takeIf { it.isNotEmpty() }

/** 描边：颜色 + 线宽(dp) + 圆角(dp)。 */
data class SkinStroke(
    val color: String? = null,
    val width: Float? = null,
    val radius: Float? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): SkinStroke = SkinStroke(
            color = optColor(json, "color"),
            width = json.optDouble("width").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            radius = json.optDouble("radius").takeIf { !it.isNaN() && it >= 0 }?.toFloat(),
        )
    }
}

/** 阴影：颜色 + 模糊半径(dp) + 偏移(dp)。 */
data class SkinShadow(
    val color: String? = null,
    val blur: Float? = null,
    val dx: Float = 0f,
    val dy: Float = 0f,
) {
    companion object {
        fun fromJson(json: JSONObject): SkinShadow = SkinShadow(
            color = optColor(json, "color"),
            blur = json.optDouble("blur").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            dx = json.optDouble("dx").takeIf { !it.isNaN() }?.toFloat() ?: 0f,
            dy = json.optDouble("dy").takeIf { !it.isNaN() }?.toFloat() ?: 0f,
        )
    }
}

/** 平铺纹理：素材图 + 是否平铺 + 叠印透明度。 */
data class SkinTexture(
    val img: String,
    val tile: Boolean = true,
    val alpha: Float = 1f,
) {
    companion object {
        fun fromJson(json: JSONObject): SkinTexture? {
            val img = json.optString("img").trim().ifEmpty { null } ?: return null
            return SkinTexture(
                img = img,
                tile = json.optBoolean("tile", true),
                alpha = json.optDouble("alpha", 1.0).toFloat().coerceIn(0f, 1f),
            )
        }
    }
}

/** 四角饰件：透明底小 PNG，贴在四角，不拉伸。inset 可为负（允许画出边外）。 */
data class SkinCorners(
    val img: String,
    val size: Float = 20f,
    val inset: Float = 0f,
) {
    companion object {
        fun fromJson(json: JSONObject): SkinCorners? {
            val img = json.optString("img").trim().ifEmpty { null } ?: return null
            return SkinCorners(
                img = img,
                size = json.optDouble("size", 20.0).toFloat().coerceAtLeast(1f),
                inset = json.optDouble("inset", 0.0).toFloat(),
            )
        }
    }
}

/**
 * 显式多段拉伸切片。stretchX/stretchY 是图素区间列表（[[l,r]...]），
 * 区间内拉伸、区间外原样；content 是内容安全区图素 [l,t,r,b]；
 * min 是最小渲染尺寸 dp [w,h]，目标小于它时调用方回退令牌层。
 * scale 声明素材是几倍图（位图图素 ÷ scale = dp）。
 */
data class SkinSlice(
    val img: String,
    val scale: Float = 1f,
    val stretchX: List<IntRange>? = null,
    val stretchY: List<IntRange>? = null,
    val content: List<Int>? = null,
    val min: List<Int>? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): SkinSlice? {
            val img = json.optString("img").trim().ifEmpty { null } ?: return null
            return SkinSlice(
                img = img,
                scale = json.optDouble("scale", 1.0).toFloat().coerceAtLeast(0.25f),
                stretchX = optRangeList(json, "stretchX"),
                stretchY = optRangeList(json, "stretchY"),
                content = optNumList(json, "content"),
                min = optNumList(json, "min"),
            )
        }
    }
}

/**
 * 气泡皮肤令牌。全部字段可空——声明了才覆盖，未声明回落气泡默认观感。
 * variants 里的键是状态/分组名（streaming/queued/error/groupFirst/
 * groupMid/groupLast），每个变体是覆盖在基础令牌上的部分声明。
 */
data class SkinBubble(
    val fill: String? = null,
    val texture: SkinTexture? = null,
    val border: SkinStroke? = null,
    val shadow: SkinShadow? = null,
    /** [l,t,r,b] dp 内容内边距。 */
    val padding: List<Int>? = null,
    val maxWidth: Float? = null,
    /** "light"=亮底深字 / "dark"=暗底浅字——决定气泡内部整套内容配色。 */
    val contentScheme: String? = null,
    val corners: SkinCorners? = null,
    /** 位图切片：存在时优先于令牌绘制（fill/border/corners 不再画）。 */
    val slice: SkinSlice? = null,
    /** 显式文字色覆盖（优先于 contentScheme 推导）。 */
    val text: String? = null,
    val variants: Map<String, SkinBubble>? = null,
) {
    val isEmpty: Boolean
        get() = fill == null && texture == null && border == null &&
            shadow == null && padding == null && maxWidth == null &&
            contentScheme == null && corners == null && slice == null &&
            text == null && variants.isNullOrEmpty()

    /** 把变体字段叠加到基础令牌上；变体未声明的字段继承基础值。 */
    fun mergedWith(overlay: SkinBubble): SkinBubble = SkinBubble(
        fill = overlay.fill ?: fill,
        texture = overlay.texture ?: texture,
        border = overlay.border ?: border,
        shadow = overlay.shadow ?: shadow,
        padding = overlay.padding ?: padding,
        maxWidth = overlay.maxWidth ?: maxWidth,
        contentScheme = overlay.contentScheme ?: contentScheme,
        corners = overlay.corners ?: corners,
        slice = overlay.slice ?: slice,
        text = overlay.text ?: text,
        variants = variants,
    )

    companion object {
        fun fromJson(json: JSONObject): SkinBubble {
            val variants = json.optJSONObject("variants")?.let { obj ->
                obj.keys().asSequence().associateWith { key ->
                    fromJson(obj.getJSONObject(key))
                }
            }
            return SkinBubble(
                fill = optColor(json, "fill"),
                texture = json.optJSONObject("texture")?.let(SkinTexture::fromJson),
                border = json.optJSONObject("border")?.let(SkinStroke::fromJson),
                shadow = json.optJSONObject("shadow")?.let(SkinShadow::fromJson),
                padding = optNumList(json, "padding"),
                maxWidth = json.optDouble("maxWidth")
                    .takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                contentScheme = json.optString("contentScheme").trim()
                    .lowercase().takeIf { it == "light" || it == "dark" },
                corners = json.optJSONObject("corners")?.let(SkinCorners::fromJson),
                slice = json.optJSONObject("slice")?.let(SkinSlice::fromJson),
                text = optColor(json, "text"),
                variants = variants,
            )
        }
    }
}

/** 顶栏/输入栏横带：底色 + 可选平铺纹理 + 边缘细线（hairline）。 */
data class SkinBar(
    val fill: String? = null,
    val texture: SkinTexture? = null,
    /** 边缘细线：color/width(dp)/edge(top|bottom)。 */
    val hairlineColor: String? = null,
    val hairlineWidth: Float = 1f,
    val hairlineEdge: String = "bottom",
) {
    val isEmpty: Boolean
        get() = fill == null && texture == null && hairlineColor == null

    companion object {
        fun fromJson(json: JSONObject): SkinBar = SkinBar(
            fill = optColor(json, "fill"),
            texture = json.optJSONObject("texture")?.let(SkinTexture::fromJson),
            hairlineColor = json.optJSONObject("hairline")
                ?.let { optColor(it, "color") },
            hairlineWidth = json.optJSONObject("hairline")
                ?.optDouble("width", 1.0)?.toFloat()?.coerceAtLeast(0.5f) ?: 1f,
            hairlineEdge = json.optJSONObject("hairline")
                ?.optString("edge", "bottom")?.lowercase()
                ?.takeIf { it == "top" || it == "bottom" } ?: "bottom",
        )
    }
}

/** 头像框：原生画环（颜色 + 线宽 dp），可再叠一张方形饰件图。 */
data class SkinAvatar(
    val color: String? = null,
    val width: Float? = null,
    val img: String? = null,
) {
    val isEmpty: Boolean
        get() = color == null && width == null && img == null

    companion object {
        fun fromJson(json: JSONObject): SkinAvatar = SkinAvatar(
            color = optColor(json, "color"),
            width = json.optDouble("width").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            img = json.optString("img").trim().ifEmpty { null },
        )
    }
}

/** 背景：图 + scrim 暗化罩层（#AARRGGBB）。皮肤声明的图才走皮肤罩层。 */
data class SkinBackground(
    val img: String,
    val scrim: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): SkinBackground? {
            val img = json.optString("img").trim().ifEmpty { null } ?: return null
            return SkinBackground(img = img, scrim = optColor(json, "scrim"))
        }
    }
}

data class ChatSkin(
    /** 规范版本；缺省按 1 处理，未知字段忽略。 */
    val spec: Int = 1,
    val name: String? = null,
    /** "./" 相对路径的基准目录（minis:// 形式）。 */
    val base: String? = null,
    val bubbleUser: SkinBubble? = null,
    val bubbleAssistant: SkinBubble? = null,
    val avatarFrame: SkinAvatar? = null,
    val topBar: SkinBar? = null,
    val composer: SkinBar? = null,
    /** 输入框底色 #RRGGBB。 */
    val inputBg: String? = null,
    /** 输入框描边色。 */
    val inputBorder: String? = null,
    /** 输入框内文字色（提示语取其低透明度变体）。 */
    val inputText: String? = null,
    /** 皮肤顶栏副标（头像+角色名下面那行小字）。 */
    val topSubtitle: String? = null,
    /** 全局强调色——覆盖薄荷绿（按钮/链接/选中态）。 */
    val accent: String? = null,
    val background: SkinBackground? = null,
    /**
     * 全屏表现层 HTML：非空时整盖转录区成一张界面岛，消息流经
     * NOVEN_FRAME.messages（最近 30 条 {role,name,text}）推入。
     * 原生消息层仍在底下，用户可切回。
     */
    val overlay: String? = null,
) {
    /**
     * 皮肤素材路径归一："./x.png" 挂到 base 下；绝对写法
     * （minis://、novex-res://、/var/minis/）原样交给渲染层归一。
     * 无 base 时相对路径解析为 null——该槽位静默回退。
     */
    fun resolveAssetPath(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (p.startsWith("./")) {
            val b = base?.trim()?.trimEnd('/') ?: return null
            return "$b/" + p.removePrefix("./")
        }
        return p
    }

    val isEmpty: Boolean
        get() = bubbleUser == null && bubbleAssistant == null &&
            avatarFrame == null && topBar == null && composer == null &&
            inputBg == null && inputBorder == null && inputText == null &&
            topSubtitle == null && accent == null && background == null &&
            overlay == null

    companion object {
        fun fromJson(json: JSONObject): ChatSkin? {
            // 输入区兼容两种写法：嵌套 "input":{bg,border,text} 优先，
            // 平铺 inputBg/inputBorder/inputText 兜底。
            val input = json.optJSONObject("input")
            val background = json.optJSONObject("background")
                ?.let(SkinBackground::fromJson)
                // "background": "minis://..." 字符串简写 = 只换图。
                ?: json.optString("background").trim().ifEmpty { null }
                    ?.let { SkinBackground(img = it) }
            val skin = ChatSkin(
                spec = json.optInt("spec", 1),
                name = json.optString("name").trim().ifEmpty { null },
                base = json.optString("base").trim().ifEmpty { null },
                bubbleUser = bubbleSlot(json.optJSONObject("bubbleUser")),
                bubbleAssistant = bubbleSlot(json.optJSONObject("bubbleAssistant")),
                avatarFrame = json.optJSONObject("avatarFrame")
                    ?.let(SkinAvatar::fromJson)?.takeUnless { it.isEmpty }
                    ?: json.optString("avatarFrame").trim().ifEmpty { null }
                        ?.let { SkinAvatar(img = it) },
                topBar = json.optJSONObject("topBar")?.let(SkinBar::fromJson)
                    ?.takeUnless { it.isEmpty }
                    ?: json.optString("topBar").trim().ifEmpty { null }
                        ?.let { SkinBar(texture = SkinTexture(it, tile = false)) },
                composer = json.optJSONObject("composer")?.let(SkinBar::fromJson)
                    ?.takeUnless { it.isEmpty }
                    ?: json.optString("composer").trim().ifEmpty { null }
                        ?.let { SkinBar(texture = SkinTexture(it, tile = false)) },
                inputBg = input?.let { optColor(it, "bg") }
                    ?: optColor(json, "inputBg"),
                inputBorder = input?.let { optColor(it, "border") }
                    ?: optColor(json, "inputBorder"),
                inputText = input?.let { optColor(it, "text") }
                    ?: optColor(json, "inputText"),
                topSubtitle = json.optString("topSubtitle").trim().ifEmpty { null },
                accent = optColor(json, "accent"),
                background = background,
                overlay = json.optString("overlay").ifEmpty { null },
            )
            return skin.takeUnless { it.isEmpty }
        }

        /**
         * 气泡槽位分派：顶层有 "img" 的是旧格式 {"img","insets","text"}
         * → 等价 v1 slice；否则按 v1 令牌解析。空槽（{}）算未声明。
         */
        private fun bubbleSlot(obj: JSONObject?): SkinBubble? {
            obj ?: return null
            val spec = if (obj.has("img")) {
                val img = obj.optString("img").trim().ifEmpty { null } ?: return null
                SkinBubble(
                    slice = SkinSlice(
                        img = img,
                        scale = 1f,
                        content = optNumList(obj, "insets"),
                    ),
                    text = optColor(obj, "text"),
                )
            } else {
                SkinBubble.fromJson(obj)
            }
            return spec.takeUnless { it.isEmpty }
        }

        fun fromJsonString(raw: String?): ChatSkin? {
            if (raw.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(raw.trim())) }.getOrNull()
        }
    }
}
