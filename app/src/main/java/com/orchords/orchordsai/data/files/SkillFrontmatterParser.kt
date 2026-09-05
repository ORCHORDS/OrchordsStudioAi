package com.orchords.orchordsai.data.files

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): SkillFrontmatter {
        if (!content.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(content) ?: return SkillFrontmatter.Empty
        val yamlContent = content.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
            )
        }.getOrDefault(SkillFrontmatter.Empty)
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }
}

class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    internal fun contains(key: String): Boolean = values.containsKey(key)

    /** Missing policy preserves automatic use; a present malformed policy never grants it. */
    fun isModelInvocationDisabled(): Boolean {
        val key = "disable-model-invocation"
        return values.containsKey(key) && getBoolean(key) != false
    }

    /**
     * Read a boolean frontmatter field by [key].
     *
     * The Agent Skills convention (see code.claude.com docs and the
     * agentskills.io proposal tracking disable-model-invocation) accepts the
     * values Claude Code emits: `true` / `false`. We additionally tolerate the
     * YAML-emitted forms `True` / `False` and `TRUE` / `FALSE`, as well as the
     * quoted strings `"true"` / `"false"`, since frontmatter parsers across
     * editors routinely coerce case or stringify booleans.
     */
    fun getBoolean(key: String): Boolean? {
        return when (val raw = values[key]) {
            is Boolean -> raw
            is String -> raw.trim().lowercase().toBooleanStrictOrNull()
                ?: return null
            else -> null
        }
    }

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
