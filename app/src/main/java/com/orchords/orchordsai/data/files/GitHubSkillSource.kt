package com.orchords.orchordsai.data.files

import java.net.URI
import java.net.URLEncoder

internal data class GitHubSkillSource(val owner: String, val repository: String, val tail: List<String>) {
    fun api(vararg segments: String): URI = URI("https://api.github.com/repos/$owner/$repository/" +
        segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") })

    companion object {
        fun parse(value: String): GitHubSkillSource {
            require(value.length <= 2048) { "GitHub skill link is too long" }
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("Invalid GitHub repository link") }
            require(uri.scheme == "https" && uri.host.equals("github.com", true) && uri.rawUserInfo == null &&
                uri.port in listOf(-1, 443) && uri.rawQuery == null && uri.rawFragment == null) { "Use an HTTPS GitHub repository or tree link" }
            val segments = uri.path.removePrefix("/").trimEnd('/').split('/')
            require(segments.size >= 2 && segments.all { it.isNotEmpty() }) { "Invalid GitHub repository link" }
            val owner = segments[0]
            val repository = segments[1].removeSuffix(".git")
            require(Regex("[A-Za-z0-9-]{1,39}").matches(owner) &&
                Regex("[A-Za-z0-9_.-]{1,100}").matches(repository) && repository !in setOf(".", "..")) { "Invalid GitHub repository identity" }
            val tail = if (segments.size == 2) emptyList() else {
                require(segments.size >= 4 && segments[2] == "tree") { "Use a repository or directory tree link" }
                segments.drop(3)
            }
            if (tail.isNotEmpty()) SkillPackageValidator.requirePath(tail.joinToString("/"))
            return GitHubSkillSource(owner, repository, tail)
        }
    }
}

internal fun requireGitHubSkillOrigin(uri: URI) {
    require(uri.scheme == "https" && uri.host.equals("api.github.com", true) && uri.rawUserInfo == null &&
        uri.port in listOf(-1, 443) && uri.rawQuery == null && uri.rawFragment == null) {
        "Skill download origin is not permitted"
    }
}
