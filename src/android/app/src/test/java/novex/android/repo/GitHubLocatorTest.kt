package novex.android.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GitHubLocator 的地址形态覆盖：仓库根 / tree / blob / raw 四种用户
 * 常粘贴的形态都要认，不认识的域直接拒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GitHubLocatorTest {

    @Test fun repoRootUrlResolvesThroughDefaultBranch() {
        val raw = GitHubLocator.rawSkillMdUrl("https://github.com/user/skill-repo")
        assertEquals(
            "https://raw.githubusercontent.com/user/skill-repo/HEAD/SKILL.md",
            raw,
        )
        val target = GitHubLocator.parse("github.com/user/skill-repo")
        assertEquals("HEAD", target?.branch)
        assertEquals("", target?.dirPath)
    }

    @Test fun treeUrlMapsToRawSkillMd() {
        val raw = GitHubLocator.rawSkillMdUrl(
            "https://github.com/user/repo/tree/main/skills/foo",
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/skills/foo/SKILL.md",
            raw,
        )
    }

    @Test fun blobUrlPointingAtSkillMdKeepsItsPath() {
        val raw = GitHubLocator.rawSkillMdUrl(
            "https://github.com/user/repo/blob/main/SKILL.md",
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/SKILL.md",
            raw,
        )
    }

    @Test fun rawUrlWithoutSkillMdGetsItAppended() {
        val raw = GitHubLocator.rawSkillMdUrl(
            "https://raw.githubusercontent.com/user/repo/main/dir",
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/dir/SKILL.md",
            raw,
        )
    }

    @Test fun foreignHostsAndMalformedUrlsAreRejected() {
        assertNull(GitHubLocator.rawSkillMdUrl("https://gitlab.com/u/r"))
        assertNull(GitHubLocator.parse("https://github.com/user"))
        assertNull(GitHubLocator.parse("not a url at all"))
    }
}
