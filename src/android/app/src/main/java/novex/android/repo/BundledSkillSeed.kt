package novex.android.repo

import com.openminis.app.data.repository.SkillRepository

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 捆绑技能种子 —— 首启安装与后续升级的唯一种植口。
 *
 * 只剩 assets/skills/ 下的目录技能（SKILL.md + 可选兄弟文件，FILES.txt 清单
 * 优先，缺清单时回退递归列举——部分运行时的 AssetManager.list() 对目录
 * 返回空数组，清单让拷贝在任何环境都确定）。
 *
 * 种植纪律（每条都对应过一个真实缺陷，勿简化）：
 *  1. 版本门槛：本地版本 ≥ 捆绑版本且技能目录非空 → 整体跳过。目录非空
 *     这一条件让「行在、文件丢」的半装状态在下次启动自愈而不必改版本号。
 *  2. 升级不覆盖用户数据：只改 description/version/body 与时间戳，不改
 *     启用位与使用计数；同名用户编辑过的兄弟文件不会被重写（见第 1 条
 *     的跳过条件）。
 *  3. 单技能失败不外溢：任何一个捆绑技能种植失败只记日志，绝不把异常
 *     抛进应用启动路径（GH#147 崩溃循环的教训）。
 */
internal class BundledSkillSeed(
    private val context: Context,
    private val repo: SkillRepository,
) {

    private val logTag = "NovexSkillSeed"

    /** assets 目录（相对 assets 根），与 app 打包内容一一对应。 */
    private val assetSkillDirs = listOf(
        "skills/humanizer-zh",
        "skills/human-writing",
        "skills/island-craft",
        "skills/novex-manual",
    )

    /**
     * 已退役的捆绑技能 id。这些技能曾随种子分发（skill-creator 被银卡
     * register_controls 取代、wenyou-maker 随文游功能撤下、card-organizer
     * 与 create_card 工具重叠），存量用户注册表里的 BUNDLED 行在下次启动
     * 清除——只删捆绑来源，用户自行导入的同名技能不碰。
     */
    private val retiredBundledIds = setOf(
        "skill-creator",
        "wenyou-maker",
        "card-organizer",
    )

    fun plantAll() {
        retireStaleBundles()
        for (dir in assetSkillDirs) {
            runCatching { plantAssetSkill(dir) }
                .onFailure { Log.w(logTag, "bundled skill '$dir' not planted: ${it.message}", it) }
        }
    }

    private fun retireStaleBundles() {
        repo.skills.value
            .filter { it.id in retiredBundledIds && it.importSource == SkillRepository.ImportSource.BUNDLED }
            .forEach {
                repo.delete(it.id)
                Log.i(logTag, "retired bundled skill '${it.id}'")
            }
    }

    // ── assets 目录技能 ────────────────────────────────────────────────

    private fun plantAssetSkill(assetDir: String) {
        val text = context.assets.open("$assetDir/${SkillMarkdown.FILE_NAME}")
            .bufferedReader().use { it.readText() }
        val parsed = SkillMarkdown.parse(text) ?: run {
            Log.w(logTag, "bundled '$assetDir' has invalid ${SkillMarkdown.FILE_NAME}; skipped")
            return
        }
        val id = SkillMarkdown.slugOf(parsed.name)
        if (id.isBlank()) return
        val existing = repo.skills.value.firstOrNull { it.id == id }
        if (existing != null && existing.version >= parsed.version && repo.files.isPopulated(id)) {
            return // 已装且完好：不碰用户可能改过的文件。
        }
        if (existing == null) {
            repo.add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                version = parsed.version,
                source = SkillRepository.ImportSource.BUNDLED,
            ) ?: return
            Log.i(logTag, "seeded bundled '$id' v${parsed.version}")
        } else {
            val refreshed = existing.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
            )
            repo.registry.updateCoreFields(refreshed)
            repo.files.writeSkillMd(refreshed)
            repo.replaceInState(refreshed)
            Log.i(logTag, "upgraded bundled '$id' to v${parsed.version}")
        }
        copyAssetTree(assetDir, repo.files.dirOf(id))
    }

    /**
     * assets 目录 → 技能目录。FILES.txt（每行一个相对路径，# 注释）优先；
     * SKILL.md 由注册表路径统一写，清单与列举都跳过它。
     */
    private fun copyAssetTree(assetPath: String, dest: File) {
        dest.mkdirs()
        val manifest = runCatching {
            context.assets.open("$assetPath/FILES.txt").bufferedReader().use { reader ->
                reader.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        }.getOrNull()
        if (manifest != null) {
            for (relative in manifest) {
                if (relative.contains("..") || relative.startsWith("/")) continue
                if (relative.equals(SkillMarkdown.FILE_NAME, true) || relative.equals("FILES.txt", true)) continue
                copyAssetFile("$assetPath/$relative", File(dest, relative))
            }
            return
        }
        val children = context.assets.list(assetPath).orEmpty()
        for (child in children) {
            val childPath = "$assetPath/$child"
            val isFolder = context.assets.list(childPath).orEmpty().isNotEmpty()
            if (isFolder) {
                copyAssetTree(childPath, File(dest, child))
            } else if (!child.equals(SkillMarkdown.FILE_NAME, true)) {
                copyAssetFile(childPath, File(dest, child))
            }
        }
    }

    private fun copyAssetFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(logTag, "bundled file copy failed for $assetPath: ${e.message}")
        }
    }
}
