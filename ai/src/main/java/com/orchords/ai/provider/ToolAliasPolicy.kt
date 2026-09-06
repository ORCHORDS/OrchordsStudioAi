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
        val DEEPSEEK_CHAT = ToolNamePolicy(maxLength = 64)
        val DEEPSEEK_RESPONSES = ToolNamePolicy(maxLength = 128)

        private const val MIN_ALIAS_LENGTH = 12
    }
}

/**
 * Builds a deterministic request-scoped canonical-name -> provider-alias map.
 *
 * Already-valid names are preserved. Names requiring normalization or truncation receive a
 * stable hash suffix, which prevents collisions without making aliases depend on input order.
 */
fun buildProviderToolAliases(
    canonicalNames: List<String>,
    policy: ToolNamePolicy,
): Map<String, String> {
    return canonicalNames
        .distinct()
        .sorted()
        .associateWith { canonical -> aliasForCanonicalName(canonical, policy) }
}

private fun aliasForCanonicalName(
    canonical: String,
    policy: ToolNamePolicy,
): String {
    val safeAlready = canonical.isNotEmpty() &&
        canonical.length <= policy.maxLength &&
        canonical.all(::isProviderSafeToolNameChar)
    if (safeAlready) return canonical

    val normalized = buildString(canonical.length.coerceAtMost(policy.maxLength)) {
        var previousUnderscore = false
        canonical.forEach { ch ->
            val out = if (isProviderSafeToolNameChar(ch)) ch else '_'
            if (out == '_' && previousUnderscore) return@forEach
            append(out)
            previousUnderscore = out == '_'
        }
    }.trim('_', '-')

    val digest = sha256Hex(canonical).take(HASH_CHARS)
    val suffix = "_$digest"
    val prefixBudget = policy.maxLength - suffix.length
    val prefix = normalized
        .take(prefixBudget)
        .trimEnd('_', '-')
        .ifEmpty { "tool".take(prefixBudget) }

    return (prefix + suffix).take(policy.maxLength)
}

private fun isProviderSafeToolNameChar(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val HASH_CHARS = 10
