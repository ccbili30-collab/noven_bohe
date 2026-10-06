package com.openminis.app.data.repository

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SkillRepositoryBundledAssetSkillTest {

    private fun newRepository(): SkillRepository = SkillRepository(RuntimeEnvironment.getApplication())

    @Test
    fun bundledDeslopSkillsInstallWithUpstreamContentIntact() {
        val repo = newRepository()

        val humanizer = repo.skills.value.firstOrNull { it.id == "humanizer-zh" }
        assertNotNull("humanizer-zh should be installed on first init", humanizer)
        assertTrue(
            "upstream description survives (block-scalar frontmatter)",
            humanizer!!.description.contains("AI 生成痕迹"),
        )
        assertTrue(
            "upstream body survives",
            humanizer.body.contains("AI 写作特征") || humanizer.body.isNotBlank(),
        )

        val humanWriting = repo.skills.value.firstOrNull { it.id == "human-writing" }
        assertNotNull("human-writing should be installed on first init", humanWriting)
        assertTrue(
            "upstream trigger description survives",
            humanWriting!!.description.contains("改稿"),
        )

        val refs = repo.listSkillFiles("human-writing")
        assertTrue("references on disk: $refs", refs.contains("references/fiction.md"))
        assertTrue("references on disk: $refs", refs.contains("references/forum-prose.md"))
        val fiction = repo.readSkillFile("human-writing", "references/fiction.md")
        assertTrue("reference content intact", fiction.orEmpty().isNotEmpty())
        assertTrue(
            "LICENSE attribution kept",
            repo.listSkillFiles("humanizer-zh").contains("LICENSE"),
        )
    }

    @Test
    fun bundledIslandCraftSkillInstallsWithReferences() {
        val repo = newRepository()

        val islandCraft = repo.skills.value.firstOrNull { it.id == "island-craft" }
        assertNotNull("island-craft should be installed on first init", islandCraft)
        assertTrue(
            "trigger description must name the html island",
            islandCraft!!.description.contains("界面岛"),
        )
        assertTrue(
            "SKILL.md body survives",
            islandCraft.body.contains("NOVEN_FRAME"),
        )

        val refs = repo.listSkillFiles("island-craft")
        assertTrue("references on disk: $refs", refs.contains("references/canvas.md"))
        assertTrue("references on disk: $refs", refs.contains("references/design.md"))
        val design = repo.readSkillFile("island-craft", "references/design.md")
        assertTrue("design reference content intact", design.orEmpty().contains("状态 HUD"))
    }

    @Test
    fun sameVersionReinstallDoesNotClobberUserEdits() {
        newRepository()
        val skillDir = File(
            File(RuntimeEnvironment.getApplication().filesDir, "minis-global/skills"),
            "human-writing",
        )
        val edited = File(skillDir, "references/fiction.md")
        edited.writeText("用户改过的参考文本")

        // Second init runs the seed again; same version + files present means
        // the installer must skip entirely.
        val second = newRepository()
        assertEquals("用户改过的参考文本", edited.readText())
        val skill = second.skills.value.first { it.id == "human-writing" }
        assertTrue(skill.version.isNotBlank())
    }

    @Test
    fun halfInstalledSkillSelfHealsWithoutVersionBump() {
        newRepository()
        val skillDir = File(
            File(RuntimeEnvironment.getApplication().filesDir, "minis-global/skills"),
            "human-writing",
        )
        // Simulate a half install: registry row exists (init created it) but the
        // directory was wiped before sibling files landed.
        skillDir.deleteRecursively()

        val second = newRepository()
        val files = second.listSkillFiles("human-writing")
        assertTrue("references restored without a version bump: $files", files.contains("references/fiction.md"))
    }

    @Test
    fun skillPromptFragmentDisclosesBundledSkillsWhenEnabled() {
        val repo = newRepository()
        val fragment = repo.skillPromptFragment(sessionId = "session-a")
        assertNotNull("bundled skills are enabled by default, fragment must exist", fragment)
        assertTrue(
            "fragment should disclose humanizer-zh",
            fragment!!.contains("humanizer-zh"),
        )
        assertTrue(
            "fragment should point at the readable SKILL.md path",
            fragment.contains("/var/minis/skills/humanizer-zh/SKILL.md"),
        )
    }

    @Test
    fun retiredBundledSkillsAreRemovedOnNextInit() {
        val first = newRepository()
        // Simulate a stale bundled install from an older release.
        first.add(
            name = "wenyou-maker",
            description = "stale",
            body = "stale",
            source = SkillRepository.ImportSource.BUNDLED,
        )
        assertNotNull(first.skills.value.firstOrNull { it.id == "wenyou-maker" })

        val second = newRepository()
        assertNull(
            "retired BUNDLED skill must be pruned on re-init",
            second.skills.value.firstOrNull { it.id == "wenyou-maker" },
        )
    }
}
