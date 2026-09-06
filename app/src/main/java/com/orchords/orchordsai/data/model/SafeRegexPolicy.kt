package com.orchords.orchordsai.data.model

import com.google.re2j.Pattern
import com.orchords.orchordsai.utils.SimpleCache
import java.util.concurrent.TimeUnit

/**
 * Bounded profile for user-configurable regular expressions.
 *
 * RE2/J supplies linear-time matching. ORCHORDS additionally bounds pattern, input,
 * compiled-program, replacement, output, and match counts so a valid expression cannot
 * create unbounded work or allocation through configuration alone.
 */
internal object SafeRegexPolicy {
    const val MAX_PATTERN_CHARS = 1_024
    const val MAX_INPUT_CHARS = 65_536
    const val MAX_REPLACEMENT_CHARS = 4_096
    const val MAX_PROGRAM_SIZE = 4_096
    const val MAX_MATCHES = 4_096
    const val MAX_OUTPUT_CHARS = 131_072
    const val MAX_TRANSFORMS = 64

    private data class CacheKey(val pattern: String, val caseSensitive: Boolean)

    private val cache = SimpleCache.builder<CacheKey, Result<Pattern>>()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build()

    fun containsMatch(
        pattern: String,
        input: String,
        caseSensitive: Boolean,
    ): Boolean {
        if (input.length > MAX_INPUT_CHARS) return false
        val compiled = compile(pattern, caseSensitive) ?: return false
        return runCatching { compiled.matcher(input).find() }.getOrDefault(false)
    }

    fun replaceAll(
        pattern: String,
        input: String,
        replacement: String,
    ): String {
        if (input.length > MAX_INPUT_CHARS) return input
        if (replacement.length > MAX_REPLACEMENT_CHARS) return input

        val compiled = compile(pattern, caseSensitive = true) ?: return input
        val replacementTokens = parseReplacement(replacement, compiled) ?: return input
        val matcher = compiled.matcher(input)
        val output = StringBuilder(input.length.coerceAtMost(MAX_OUTPUT_CHARS))
        var lastEnd = 0
        var matchCount = 0

        return runCatching {
            while (matcher.find()) {
                matchCount++
                if (matchCount > MAX_MATCHES) return input

                if (!appendBounded(output, input, lastEnd, matcher.start())) return input
                for (token in replacementTokens) {
                    when (token) {
                        is ReplacementToken.Literal -> {
                            if (!appendBounded(output, token.value)) return input
                        }

                        is ReplacementToken.NumberedGroup -> {
                            val value = matcher.group(token.index) ?: ""
                            if (!appendBounded(output, value)) return input
                        }

                        is ReplacementToken.NamedGroup -> {
                            val value = matcher.group(token.name) ?: ""
                            if (!appendBounded(output, value)) return input
                        }
                    }
                }
                lastEnd = matcher.end()
            }

            if (!appendBounded(output, input, lastEnd, input.length)) return input
            output.toString()
        }.getOrDefault(input)
    }

    private fun compile(pattern: String, caseSensitive: Boolean): Pattern? {
        if (pattern.length > MAX_PATTERN_CHARS) return null
        val key = CacheKey(pattern, caseSensitive)
        cache.getIfPresent(key)?.let { return it.getOrNull() }

        val result = runCatching {
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            Pattern.compile(pattern, flags).also { compiled ->
                require(compiled.programSize() <= MAX_PROGRAM_SIZE) {
                    "Regular expression exceeds the compiled-program limit"
                }
            }
        }
        cache.put(key, result)
        return result.getOrNull()
    }

    private sealed interface ReplacementToken {
        data class Literal(val value: String) : ReplacementToken
        data class NumberedGroup(val index: Int) : ReplacementToken
        data class NamedGroup(val name: String) : ReplacementToken
    }

    private fun parseReplacement(
        replacement: String,
        pattern: Pattern,
    ): List<ReplacementToken>? {
        val tokens = mutableListOf<ReplacementToken>()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                tokens += ReplacementToken.Literal(literal.toString())
                literal.setLength(0)
            }
        }

        var index = 0
        while (index < replacement.length) {
            when (val ch = replacement[index]) {
                '\\' -> {
                    if (index + 1 >= replacement.length) return null
                    literal.append(replacement[index + 1])
                    index += 2
                }

                '$' -> {
                    flushLiteral()
                    if (index + 1 >= replacement.length) return null
                    val next = replacement[index + 1]
                    if (next == '{') {
                        val end = replacement.indexOf('}', startIndex = index + 2)
                        if (end < 0) return null
                        val name = replacement.substring(index + 2, end)
                        if (name.isEmpty() || !pattern.namedGroups().containsKey(name)) return null
                        tokens += ReplacementToken.NamedGroup(name)
                        index = end + 1
                    } else if (next.isDigit()) {
                        var group = next.digitToInt()
                        if (group > pattern.groupCount()) return null
                        var cursor = index + 2
                        while (cursor < replacement.length && replacement[cursor].isDigit()) {
                            val candidate = group * 10 + replacement[cursor].digitToInt()
                            if (candidate > pattern.groupCount()) break
                            group = candidate
                            cursor++
                        }
                        tokens += ReplacementToken.NumberedGroup(group)
                        index = cursor
                    } else {
                        return null
                    }
                }

                else -> {
                    literal.append(ch)
                    index++
                }
            }
        }
        flushLiteral()
        return tokens
    }

    private fun appendBounded(builder: StringBuilder, value: String): Boolean {
        if (builder.length + value.length > MAX_OUTPUT_CHARS) return false
        builder.append(value)
        return true
    }

    private fun appendBounded(
        builder: StringBuilder,
        value: String,
        start: Int,
        end: Int,
    ): Boolean {
        val length = end - start
        if (builder.length + length > MAX_OUTPUT_CHARS) return false
        builder.append(value, start, end)
        return true
    }
}
