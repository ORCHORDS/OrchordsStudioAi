package com.orchords.orchordsai.data.files

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class GitHubSkillSourceTest {
    @Test fun `repository and pinned subtree links retain exact scope`() {
        val sha = "a".repeat(40)
        assertEquals(emptyList<String>(), GitHubSkillSource.parse("https://github.com/owner/repo").tail)
        val source = GitHubSkillSource.parse("https://github.com/owner/repo/tree/$sha/skills/my-skill")
        assertEquals(listOf(sha, "skills", "my-skill"), source.tail)
        assertEquals("https://api.github.com/repos/owner/repo/commits/feature%2Fone", source.api("commits", "feature/one").toString())
    }
    @Test fun `foreign origins userinfo traversal and query tricks are rejected before fetch`() {
        listOf("http://github.com/a/b", "https://github.com.evil.test/a/b", "https://github.com@evil.test/a/b",
            "https://user@github.com/a/b", "https://github.com/a/b?next=evil", "https://github.com/a/b/tree/main/../secret",
            "https://github.com/a/b/tree/main/%2e%2e/secret", "https://github.com/a/b/blob/main/SKILL.md").forEach {
            assertThrows(IllegalArgumentException::class.java) { GitHubSkillSource.parse(it) }
        }
    }
    @Test fun `public API transport is restricted to exact HTTPS origin`() {
        requireGitHubSkillOrigin(URI("https://api.github.com/repos/a/b/git/trees/" + "a".repeat(40)))
        listOf("https://api.github.com.evil.test/x", "http://api.github.com/x", "https://user@api.github.com/x",
            "https://api.github.com:444/x", "https://api.github.com/x?token=secret", "https://api.github.com/x#fragment").forEach {
            assertThrows(IllegalArgumentException::class.java) { requireGitHubSkillOrigin(URI(it)) }
        }
    }
}
