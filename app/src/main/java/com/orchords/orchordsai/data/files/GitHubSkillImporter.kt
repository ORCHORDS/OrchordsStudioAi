package com.orchords.orchordsai.data.files

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class SkillGitMedia(val accept: String) {
    JSON("application/vnd.github+json"), SHA("application/vnd.github.sha"),
}
internal data class SkillHttpResponse(val status: Int, val bytes: ByteArray)
internal fun interface SkillImportHttp {
    fun fetch(uri: URI, media: SkillGitMedia, maxBytes: Int, timeoutMillis: Long, checkpoint: () -> Unit): SkillHttpResponse
}

/** Public package acquisition only: no account credentials, cookies, redirects or hidden retries. */
internal object PublicGitHubSkillHttp : SkillImportHttp {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun fetch(uri: URI, media: SkillGitMedia, maxBytes: Int, timeoutMillis: Long, checkpoint: () -> Unit): SkillHttpResponse {
        requireGitHubSkillOrigin(uri)
        require(maxBytes > 0 && timeoutMillis > 0)
        checkpoint()
        val request = Request.Builder().url(uri.toString()).header("Accept", media.accept)
            .header("X-GitHub-Api-Version", "2026-03-10").build()
        val call = client.newCall(request)
        call.timeout().timeout(minOf(timeoutMillis, 30_000), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            checkpoint()
            if (response.code != 200) return SkillHttpResponse(response.code, byteArrayOf())
            val body = requireNotNull(response.body) { "GitHub returned no response body" }
            require(body.contentLength() <= maxBytes.toLong()) { "GitHub skill response exceeds the byte limit" }
            val bytes = body.byteStream().use { input ->
                readBoundedSkillBytes(SkillAcquisitionInput(input, maxBytes.toLong(), checkpoint), maxBytes)
            }
            return SkillHttpResponse(response.code, bytes)
        }
    }
}

/** Resolves mutable refs once, then acquires bounded, identity-checked Git objects from that commit. */
internal class GitHubSkillImporter(
    private val http: SkillImportHttp = PublicGitHubSkillHttp,
    private val limits: SkillPackageLimits = SkillPackageLimits(),
    private val maxRequests: Int = 128,
    private val maxWireBytes: Long = 40L * 1024 * 1024,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val shaPattern = Regex("[0-9a-f]{40}")

    fun acquire(link: String, checkpoint: () -> Unit = {}): PreparedSkillPackage {
        require(maxRequests > 0 && maxWireBytes > 0)
        val source = GitHubSkillSource.parse(link)
        val started = nowNanos()
        var requests = 0
        var wireBytes = 0L
        fun checkActive() {
            checkpoint()
            require(nowNanos() - started < TimeUnit.MINUTES.toNanos(2)) { "Skill import exceeded its time limit" }
        }
        fun fetch(uri: URI, media: SkillGitMedia = SkillGitMedia.JSON, maxBytes: Int = 1024 * 1024, optional: Boolean = false): ByteArray? {
            checkActive()
            requireGitHubSkillOrigin(uri)
            require(++requests <= maxRequests) { "Skill import exceeded its request limit" }
            val budget = minOf(maxBytes.toLong(), maxWireBytes - wireBytes).toInt()
            require(budget > 0) { "Skill import exceeded its network byte limit" }
            val remaining = TimeUnit.NANOSECONDS.toMillis(TimeUnit.MINUTES.toNanos(2) - (nowNanos() - started)).coerceAtLeast(1)
            val result = http.fetch(uri, media, budget, remaining, ::checkActive)
            checkActive()
            require(result.bytes.size <= budget) { "GitHub skill response exceeds the byte limit" }
            wireBytes += result.bytes.size
            if (optional && result.status == 404) return null
            require(result.status == 200) { "GitHub skill download failed (HTTP ${result.status}); no package was installed" }
            return result.bytes
        }
        fun objectAt(uri: URI, budget: Int = 1024 * 1024): JsonObject =
            Json.parseToJsonElement(decodeJson(requireNotNull(fetch(uri, maxBytes = budget)))) as? JsonObject
                ?: throw IllegalArgumentException("Invalid GitHub object response")
        fun resolveRef(ref: String, optional: Boolean = false): String? {
            val bytes = fetch(source.api("commits", ref), SkillGitMedia.SHA, 128, optional) ?: return null
            val sha = bytes.toString(Charsets.US_ASCII).trim()
            require(shaPattern.matches(sha)) { "GitHub returned an invalid commit identity" }
            return sha
        }
        val (commit, directory) = when {
            source.tail.isEmpty() -> requireNotNull(resolveRef("HEAD")) to emptyList()
            shaPattern.matches(source.tail.first()) -> source.tail.first() to source.tail.drop(1)
            else -> {
                // A slash can belong to a ref or a directory. Never silently select another branch.
                val candidates = (1..source.tail.size).mapNotNull { count ->
                    resolveRef(source.tail.take(count).joinToString("/"), optional = true)?.let { it to source.tail.drop(count) }
                }
                require(candidates.size == 1) { "GitHub ref is missing or ambiguous; use a tree link pinned to a full commit SHA" }
                candidates.single()
            }
        }
        val commitObject = objectAt(source.api("git", "commits", commit))
        require(commitObject.string("sha") == commit) { "GitHub commit identity changed" }
        var tree = (commitObject["tree"] as? JsonObject)?.string("sha")
            ?: throw IllegalArgumentException("GitHub commit is missing its tree")
        fun readTree(sha: String): List<JsonObject> {
            require(shaPattern.matches(sha)) { "Invalid Git tree identity" }
            val obj = objectAt(source.api("git", "trees", sha))
            require(obj.string("sha") == sha && (obj["truncated"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == false) {
                "GitHub returned an incomplete or mismatched skill directory"
            }
            val entries = obj["tree"] as? JsonArray ?: throw IllegalArgumentException("Invalid Git tree")
            require(entries.size <= 1024) { "Skill directory has too many entries" }
            return entries.map { it as? JsonObject ?: throw IllegalArgumentException("Invalid Git tree entry") }
        }
        directory.forEach { segment ->
            checkActive()
            val entry = readTree(tree).singleOrNull { it.string("path") == segment }
                ?: throw IllegalArgumentException("Skill directory not found at selected commit")
            require(entry.string("type") == "tree" && entry.string("mode") in setOf("040000", "40000")) { "Skill path is not a directory" }
            tree = entry.string("sha")
        }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        var visitedEntries = 0
        fun visit(sha: String, prefix: String) {
            for (entry in readTree(sha)) {
                checkActive()
                require(++visitedEntries <= 1024) { "Skill directory has too many entries" }
                val name = entry.string("path")
                require('/' !in name) { "Git tree returned a non-child path" }
                val path = prefix + name
                SkillPackageValidator.requirePath(path)
                val id = entry.string("sha")
                require(shaPattern.matches(id)) { "Invalid Git object identity" }
                when (entry.string("mode")) {
                    "040000", "40000" -> {
                        require(entry.string("type") == "tree") { "Invalid skill directory type" }
                        visit(id, "$path/")
                    }
                    "100644", "100755" -> {
                        require(entry.string("type") == "blob") { "Invalid skill file type" }
                        require(files.size < limits.maxFiles) { "Skill package has too many files" }
                        val size = entry.number("size")
                        val perFile = if (path == "SKILL.md") minOf(MAX_SKILL_CONTENT_BYTES, limits.maxFileBytes) else limits.maxFileBytes
                        require(size in 0..minOf(perFile.toLong(), limits.maxTotalBytes - total)) { "Skill file exceeds the byte limit" }
                        // Base64 expands bytes; include a bounded JSON envelope, never an unbounded response.
                        val jsonBudget = minOf(Int.MAX_VALUE.toLong() - 1, ((size + 2) / 3) * 4 * 11 / 10 + 64 * 1024).toInt()
                        val blob = objectAt(source.api("git", "blobs", id), jsonBudget)
                        require(blob.string("sha") == id && blob.number("size") == size && blob.string("encoding") == "base64") {
                            "GitHub skill blob metadata does not match its tree"
                        }
                        val encoded = blob.string("content").replace("\n", "").replace("\r", "")
                        require(encoded.length.toLong() <= ((size + 2) / 3) * 4) { "GitHub skill blob exceeds its declared size" }
                        val bytes = Base64.getDecoder().decode(encoded)
                        require(bytes.size.toLong() == size && gitBlobId(bytes) == id) { "GitHub skill blob failed its integrity check" }
                        require(files.put(path, bytes) == null) { "GitHub skill files contain duplicate paths" }
                        total += size
                    }
                    else -> throw IllegalArgumentException("Symlinks, submodules and non-regular skill files are not supported")
                }
            }
        }
        visit(tree, "")
        checkActive()
        return SkillImportReader.prepareSingle(files, limits).copy(sourceRevision = commit)
    }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Invalid GitHub field: $key")
    private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("Invalid GitHub field: $key")
    private fun decodeJson(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

/** Git's object identity is an integrity check, not a signature or a trust grant. */
internal fun gitBlobId(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
    return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
