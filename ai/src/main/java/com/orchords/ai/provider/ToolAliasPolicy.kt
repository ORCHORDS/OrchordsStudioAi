package com.orchords.ai.provider

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Provider-route constraints for function/tool names. */
data class ToolNamePolicy(
    val maxLength: Int,
) {
    init {
        require(maxLength >= MIN_ALIAS_LENGTH) { "Tool name limit is too small for collision-safe aliases" }
    }

    companion object {
        val OPENAI_CHAT = ToolNamePolicy(maxLength = 64)
        val OPENAI_RESPONSES = ToolNamePolicy(maxLength = 64)
        /** Conservative ORCHORDS compatibility policy for unknown OpenAI-compatible endpoints. */
        val OPENAI_COMPATIBLE = ToolNamePolicy(maxLength = 64)
        val DEEPSEEK_CHAT = ToolNamePolicy(maxLength = 64)
        val DEEPSEEK_RESPONSES = ToolNamePolicy(maxLength = 128)
        val GEMINI = ToolNamePolicy(maxLength = 128)
        val CLAUDE = ToolNamePolicy(maxLength = 64)

        private const val MIN_ALIAS_LENGTH = 12
    }
}

/**
 * Builds a deterministic request-scoped canonical-name -> provider-alias map.
 *
 * Already-valid names are reserved first so compatibility mangling cannot steal a canonical
 * provider-safe name. Names requiring normalization or truncation receive a stable hash suffix.
 * A deterministic salt is used only if a full-request collision still occurs.
 */
fun buildProviderToolAliases(
    canonicalNames: List<String>,
    policy: ToolNamePolicy,
): Map<String, String> {
    val names = canonicalNames.distinct().sorted()
    val result = linkedMapOf<String, String>()
    val usedAliases = mutableSetOf<String>()

    names.filter { isAlreadyProviderSafe(it, policy) }.forEach { canonical ->
        check(usedAliases.add(canonical))
        result[canonical] = canonical
    }

    names.filterNot { isAlreadyProviderSafe(it, policy) }.forEach { canonical ->
        var collisionSalt = 0
        while (true) {
            val candidate = aliasForCanonicalName(canonical, policy, collisionSalt)
            if (usedAliases.add(candidate)) {
                result[canonical] = candidate
                break
            }
            collisionSalt++
        }
    }

    return names.associateWith { result.getValue(it) }
}

private fun aliasForCanonicalName(
    canonical: String,
    policy: ToolNamePolicy,
    collisionSalt: Int,
): String {
    val normalized = buildString(canonical.length.coerceAtMost(policy.maxLength)) {
        var previousUnderscore = false
        canonical.forEach { ch ->
            val out = if (isProviderSafeToolNameChar(ch)) ch else '_'
            if (out == '_' && previousUnderscore) return@forEach
            append(out)
            previousUnderscore = out == '_'
        }
    }.trim('_', '-')

    val digestSeed = if (collisionSalt == 0) canonical else "$canonical\u0000$collisionSalt"
    val digest = sha256Hex(digestSeed).take(HASH_CHARS)
    val suffix = "_$digest"
    val prefixBudget = policy.maxLength - suffix.length
    val prefix = normalized
        .take(prefixBudget)
        .trimEnd('_', '-')
        .ifEmpty { "tool".take(prefixBudget) }

    return (prefix + suffix).take(policy.maxLength)
}

private fun isAlreadyProviderSafe(name: String, policy: ToolNamePolicy): Boolean =
    name.isNotEmpty() &&
        name.length <= policy.maxLength &&
        name.all(::isProviderSafeToolNameChar)

private fun isProviderSafeToolNameChar(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val HASH_CHARS = 10
