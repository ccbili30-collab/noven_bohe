package novex.android.ui.cards

import novex.content.CardKind

/**
 * [T008] 模块模板：添加模块面板的类型宫格数据源。模板=名称+一句话说明+
 * 正文脚手架（Markdown）；落卡就是普通命名模块——不引入新存储形态，
 * 旧卡/导出/既有编辑器全部兼容。脚手架只是起点，用户自由改写。
 */
data class ModuleTemplate(
    val name: String,
    val description: String,
    val scaffold: String,
    /** 组内排序与分组展示用。 */
    val group: String,
)

internal object ModuleTemplates {

    private const val G_WORLD = "世界模块"
    private const val G_CHARACTER = "角色模块"
    private const val G_COMMON = "通用"

    private val world = listOf(
        ModuleTemplate(
            "时间线", "世界大事记，按时间排列",
            "## 纪元元年\n- **事件**：\n- **影响**：\n\n## 纪元一百年\n- **事件**：\n- **影响**：\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "地图与地理", "大陆、海域与地标的文字地图",
            "## 大陆总览\n\n## 重要地点\n### 地名\n- **位置**：\n- **特征**：\n- **传闻**：\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "地区设定", "各地区的风土与规则",
            "## 地区名\n- **概况**：\n- **势力**：\n- **禁忌**：\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "势力设定", "王国、组织与它们的关系",
            "## 势力名\n- **定位**：\n- **首领**：\n- **与其他势力**：\n- **敌对**：\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "种族设定", "种族特征与相互观感",
            "## 种族名\n- **外貌**：\n- **天赋**：\n- **文化**：\n- **与他族关系**：\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "力量体系", "能力来源、等级与代价",
            "## 力量来源\n\n## 等级与阶位\n\n## 修炼与代价\n\n## 禁忌之术\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "宗教与政治", "信仰、政权与它们的水火",
            "## 信仰\n\n## 政权结构\n\n## 冲突与平衡\n",
            G_WORLD,
        ),
        ModuleTemplate(
            "文化与美学", "衣食住行、艺术与礼俗",
            "## 衣食住行\n\n## 艺术与节庆\n\n## 礼俗与禁忌\n",
            G_WORLD,
        ),
    )

    private val character = listOf(
        ModuleTemplate(
            "外貌性格", "长相、气质与性情",
            "## 外貌\n\n## 性格\n\n## 说话方式\n\n## 习惯与怪癖\n",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "属性面板", "数值与成长，游戏面板式",
            "| 属性 | 值 | 说明 |\n|---|---|---|\n| 等级 | 1 | |\n| 生命 | 100 | |\n| 攻击 | 10 | |\n| 防御 | 5 | |\n",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "随身装备", "武器、道具与它们的来历",
            "## 装备名\n- **类型**：\n- **能力**：\n- **来历**：\n",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "天赋技能", "与生俱来的与后天练的",
            "## 技能名\n- **类型**：天赋/修炼\n- **效果**：\n- **代价或限制**：\n",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "兴趣爱好", "偏好、厌恶与消遣",
            "- **喜欢**：\n- **讨厌**：\n- **消遣**：\n- **隐秘爱好**：\n",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "关系", "与他人（角色/玩家）的羁绊",
            "## 对方名字\n- **关系**：挚友/宿敌/师徒/…\n- **熟络度**：0-100\n- **双向描述**：\n- **心结**：\n",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "多形态", "分身与形态切换设定",
            "## 本体\n- **外貌差分**：默认形态\n- **性格偏移**：无\n- **语录**：\n\n## 形态名\n- **外貌差分**：\n- **性格偏移**：\n- **语录**：",
            G_CHARACTER,
        ),
        ModuleTemplate(
            "轨迹", "成长事件时间线",
            "## 时间或年龄\n- **事件**：\n- **参与人**：\n- **后果**：\n",
            G_CHARACTER,
        ),
    )

    private val common = listOf(
        ModuleTemplate(
            "自定义模块", "空白模块，自由书写",
            "",
            G_COMMON,
        ),
        ModuleTemplate(
            "专属扮演指令", "只属于这张卡的调教指令",
            "- 说话语气：\n- 称呼规则：\n- 行为边界：\n",
            G_COMMON,
        ),
    )

    /** 当前卡域可用模板：世界卡=世界+通用，角色卡=角色+通用。 */
    fun forKind(kind: CardKind): List<ModuleTemplate> =
        if (kind == CardKind.WORLD) world + common else character + common
}
