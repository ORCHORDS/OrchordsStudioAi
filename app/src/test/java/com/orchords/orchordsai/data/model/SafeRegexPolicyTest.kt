package com.orchords.orchordsai.data.model

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRegexPolicyTest {
    @Test
    fun `unsupported lookbehind replacement is rejected without mutating input`() {
        val assistant = assistantWithRegex("(?<=a)b", "X")

        assertEquals(
            "ab",
            "ab".replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `unsupported pattern backreference cannot trigger lorebook`() {
        val entry = PromptInjection.RegexInjection(
            keywords = listOf("(a)\\1"),
            useRegex = true,
        )

        assertFalse(entry.isTriggered("aa"))
    }

    @Test
    fun `numbered capture replacement keeps Kotlin replacement semantics`() {
        val assistant = assistantWithRegex("(hello) (world)", "$2, $1")

        assertEquals(
            "world, hello",
            "hello world".replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `named capture replacement keeps Kotlin replacement semantics`() {
        val assistant = assistantWithRegex(
            "(?<key>[a-z]+)=(?<value>[0-9]+)",
            "${'$'}{value}:${'$'}{key}",
        )

        assertEquals(
            "42:answer",
            "answer=42".replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `invalid replacement group fails closed to unchanged text`() {
        val assistant = assistantWithRegex("(hello)", "$2")

        assertEquals(
            "hello",
            "hello".replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `catastrophic backtracking shape completes safely as no match`() {
        val assistant = assistantWithRegex("(a+)+$", "X")
        val input = "a".repeat(20_000) + "!"

        assertEquals(
            input,
            input.replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `overlong pattern fails closed`() {
        val assistant = assistantWithRegex("a".repeat(SafeRegexPolicy.MAX_PATTERN_CHARS + 1), "X")

        assertEquals(
            "aaa",
            "aaa".replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `overlong input fails closed`() {
        val assistant = assistantWithRegex("a", "X")
        val input = "a".repeat(SafeRegexPolicy.MAX_INPUT_CHARS + 1)

        assertEquals(
            input,
            input.replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `replacement expansion over output budget fails closed`() {
        val assistant = assistantWithRegex(".", "x".repeat(SafeRegexPolicy.MAX_REPLACEMENT_CHARS))
        val input = "a".repeat(100)

        assertEquals(
            input,
            input.replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `only bounded number of assistant transforms execute`() {
        val regexes = (0..SafeRegexPolicy.MAX_TRANSFORMS).map {
            AssistantRegex(
                id = Uuid.random(),
                findRegex = "^",
                replaceString = "a",
                affectingScope = setOf(AssistantAffectScope.USER),
            )
        }
        val assistant = Assistant(regexes = regexes)

        assertEquals(
            "a".repeat(SafeRegexPolicy.MAX_TRANSFORMS) + "x",
            "x".replaceRegexes(assistant, AssistantAffectScope.USER),
        )
    }

    @Test
    fun `lorebook regex respects case insensitive configuration`() {
        val entry = PromptInjection.RegexInjection(
            keywords = listOf("hello [a-z]+"),
            useRegex = true,
            caseSensitive = false,
        )

        assertTrue(entry.isTriggered("HELLO WORLD"))
    }

    @Test
    fun `literal lorebook keyword stays outside regex semantics`() {
        val entry = PromptInjection.RegexInjection(
            keywords = listOf("a+b"),
            useRegex = false,
            caseSensitive = true,
        )

        assertTrue(entry.isTriggered("literal a+b text"))
        assertFalse(entry.isTriggered("aaab"))
    }

    private fun assistantWithRegex(pattern: String, replacement: String): Assistant = Assistant(
        regexes = listOf(
            AssistantRegex(
                id = Uuid.random(),
                findRegex = pattern,
                replaceString = replacement,
                affectingScope = setOf(AssistantAffectScope.USER),
            )
        )
    )
}
