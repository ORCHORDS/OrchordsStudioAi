package com.orchords.orchordsai.data.files

import java.net.URI
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitHubSkillImporterTest {
    private val commit = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val nested = "c".repeat(40)
    private val manifest = "---\nname: example\ndescription: A useful skill\n---\nInstructions".toByteArray()
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, -1)
    private val base = "https://api.github.com/repos/owner/repo"
    private val pinned = "https://github.com/owner/repo/tree/$commit"
    private fun json(build: JsonObjectBuilder.() -> Unit): ByteArray = buildJsonObject(build).toString().toByteArray()
    private fun entry(path: String, id: String, size: Int, mode: String = "100644") = buildJsonObject {
        put("path", path); put("sha", id); put("mode", mode); put("type", if (mode == "040000") "tree" else "blob"); put("size", size)
    }
    private fun treeResponse(id: String, entries: List<JsonObject>, truncated: Boolean = false) = json {
        put("sha", id); put("truncated", truncated); put("tree", JsonArray(entries))
    }
    private fun blob(bytes: ByteArray, claimed: String = gitBlobId(bytes)): ByteArray = json {
        put("sha", claimed); put("size", bytes.size); put("encoding", "base64")
        put("content", Base64.getMimeEncoder(60, byteArrayOf(10)).encodeToString(bytes))
    }
    private class Wire : SkillImportHttp {
        val replies = linkedMapOf<String, SkillHttpResponse>()
        val calls = mutableListOf<Pair<URI, SkillGitMedia>>()
        override fun fetch(uri: URI, media: SkillGitMedia, maxBytes: Int, timeoutMillis: Long, checkpoint: () -> Unit): SkillHttpResponse {
            requireGitHubSkillOrigin(uri)
            assertTrue(maxBytes > 0 && timeoutMillis in 1..120_000)
            checkpoint()
            calls += uri to media
            return replies[uri.toString()] ?: SkillHttpResponse(404, byteArrayOf())
        }
        fun add(uri: String, bytes: ByteArray, status: Int = 200) { replies[uri] = SkillHttpResponse(status, bytes) }
    }
    private fun wire(): Wire = Wire().apply {
        add("$base/commits/HEAD", commit.toByteArray())
        add("$base/git/commits/$commit", json { put("sha", commit); put("tree", buildJsonObject { put("sha", tree) }) })
        add("$base/git/trees/$tree", treeResponse(tree, listOf(entry("SKILL.md", gitBlobId(manifest), manifest.size), entry("image.png", gitBlobId(image), image.size))))
        add("$base/git/blobs/${gitBlobId(manifest)}", blob(manifest))
        add("$base/git/blobs/${gitBlobId(image)}", blob(image))
    }

    @Test fun `mutable HEAD is resolved once and binary blobs retain exact bytes`() {
        val wire = wire()
        val result = GitHubSkillImporter(wire).acquire("https://github.com/owner/repo")
        assertEquals("example", result.name)
        assertEquals(commit, result.sourceRevision)
        assertArrayEquals(image, result.files["image.png"])
        assertFalse(result.preserveAssets)
        assertEquals(1, wire.calls.count { it.second == SkillGitMedia.SHA })
        assertTrue(wire.calls.none { it.first.toString().contains("download_url") })
    }

    @Test fun `full commit links do not resolve mutable refs and honor selected subtree`() {
        val wire = wire()
        wire.add("$base/git/trees/$tree", treeResponse(tree, listOf(entry("selected", nested, 0, "040000"))))
        wire.add("$base/git/trees/$nested", treeResponse(nested, listOf(entry("SKILL.md", gitBlobId(manifest), manifest.size))))
        val result = GitHubSkillImporter(wire).acquire("$pinned/selected")
        assertEquals(setOf("SKILL.md"), result.files.keys)
        assertTrue(wire.calls.none { it.second == SkillGitMedia.SHA })
        assertFalse(wire.calls.any { it.first.toString().endsWith(gitBlobId(image)) })
    }

    @Test fun `ambiguous slash refs reject instead of silently choosing a different branch`() {
        val wire = wire()
        wire.add("$base/commits/feature", commit.toByteArray())
        wire.add("$base/commits/feature%2Fone", commit.toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            GitHubSkillImporter(wire).acquire("https://github.com/owner/repo/tree/feature/one")
        }
        assertTrue(wire.calls.all { it.second == SkillGitMedia.SHA })
    }

    @Test fun `truncated trees wrong blob identities and symlinks cannot prepare a skill`() {
        val truncated = wire().apply { add("$base/git/trees/$tree", treeResponse(tree, emptyList(), true)) }
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(truncated).acquire(pinned) }
        val corrupt = wire().apply { add("$base/git/blobs/${gitBlobId(manifest)}", blob(manifest + byteArrayOf(1), gitBlobId(manifest))) }
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(corrupt).acquire(pinned) }
        val symbolic = wire().apply { add("$base/git/trees/$tree", treeResponse(tree, listOf(entry("SKILL.md", gitBlobId(manifest), manifest.size, "120000")))) }
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(symbolic).acquire(pinned) }
        assertTrue(symbolic.calls.none { it.first.path.contains("/blobs/") })
    }

    @Test fun `blob bytes are checked against tree object hash even when sizes match`() {
        val changed = manifest.copyOf().apply { this[lastIndex] = 65 }
        val wire = wire().apply { add("$base/git/blobs/${gitBlobId(manifest)}", blob(changed, gitBlobId(manifest))) }
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(wire).acquire(pinned) }
    }

    @Test fun `declared oversized files stop before blob acquisition and request limits are exact`() {
        val wire = wire()
        assertThrows(IllegalArgumentException::class.java) {
            GitHubSkillImporter(wire, SkillPackageLimits(maxFileBytes = 8)).acquire(pinned)
        }
        assertTrue(wire.calls.none { it.first.path.contains("/blobs/") })
        val bounded = wire()
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(bounded, maxRequests = 1).acquire(pinned) }
        assertEquals(1, bounded.calls.size)
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(wire(), maxWireBytes = 1).acquire(pinned) }
    }

    @Test fun `HTTP redirects and rate limits are errors without retries`() {
        listOf(301, 403, 429, 500).forEach { status ->
            val wire = wire().apply { add("$base/git/commits/$commit", byteArrayOf(), status) }
            assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(wire).acquire(pinned) }
            assertEquals(1, wire.calls.size)
        }
    }

    @Test fun `cancellation deadline and invalid origins stop before network use`() {
        val cancelled = wire()
        assertThrows(CancellationException::class.java) { GitHubSkillImporter(cancelled).acquire(pinned) { throw CancellationException() } }
        assertTrue(cancelled.calls.isEmpty())
        val expired = wire()
        var tick = 0
        assertThrows(IllegalArgumentException::class.java) {
            GitHubSkillImporter(expired, nowNanos = { if (tick++ == 0) 0 else TimeUnit.MINUTES.toNanos(3) }).acquire(pinned)
        }
        assertTrue(expired.calls.isEmpty())
        val unsafe = wire()
        assertThrows(IllegalArgumentException::class.java) { GitHubSkillImporter(unsafe).acquire("https://evil.test/owner/repo") }
        assertTrue(unsafe.calls.isEmpty())
    }
}
