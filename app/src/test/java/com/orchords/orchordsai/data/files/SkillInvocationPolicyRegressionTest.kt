package com.orchords.orchordsai.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInvocationPolicyRegressionTest {
    private fun parse(policy: String): SkillFrontmatter = SkillFrontmatterParser.parse(
        "---\nname: policy-test\ndescription: Policy regression\n$policy\n---\nInstructions"
    )

    @Test
    fun `absent policy and valid false preserve automatic invocation`() {
        assertFalse(parse("").isModelInvocationDisabled())
        listOf("false", "False", "FALSE", "\"false\"", "\" FALSE \"").forEach { value ->
            val metadata = parse("disable-model-invocation: $value")
            assertEquals("policy-test", metadata["name"])
            assertFalse("Valid false should remain automatic: $value", metadata.isModelInvocationDisabled())
        }
    }

    @Test
    fun `valid true variants disable automatic invocation`() {
        listOf("true", "True", "TRUE", "\"true\"", "\" TRUE \"").forEach { value ->
            val metadata = parse("disable-model-invocation: $value")
            assertEquals("policy-test", metadata["name"])
            assertTrue(metadata.isModelInvocationDisabled())
        }
    }

    @Test
    fun `present null blank misspelled numeric and structured policies fail closed`() {
        listOf("", "null", "~", "\"\"", "\"  \"", "truee", "automatic", "0", "1", "[]", "[false]", "{value: false}").forEach { value ->
            val metadata = parse("disable-model-invocation: $value")
            // Prove the policy was not confused with an entirely failed frontmatter parse.
            assertEquals("policy-test", metadata["name"])
            assertTrue("Malformed policy must not grant automatic use: $value", metadata.isModelInvocationDisabled())
        }
    }

    @Test
    fun `policy evaluation does not change unrelated boolean parsing`() {
        val metadata = parse("other: true\ndisable-model-invocation: malformed")
        assertEquals(true, metadata.getBoolean("other"))
        assertEquals(null, metadata.getBoolean("disable-model-invocation"))
        assertTrue(metadata.isModelInvocationDisabled())
    }
}
