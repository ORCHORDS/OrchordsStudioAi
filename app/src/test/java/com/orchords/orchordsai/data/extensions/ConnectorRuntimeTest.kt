package com.orchords.orchordsai.data.extensions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorRuntimeTest {
    private val action = ConnectorActionDefinition(
        connectorId = "github",
        actionId = "github.issue.comment",
        risk = ConnectorRisk.WRITE,
        requiredCapabilities = setOf("issues.write"),
    )
    private val connection = ConnectorConnectionSnapshot(
        connectionId = "c1",
        connectorId = "github",
        accountId = "a1",
        revision = 2,
        connected = true,
        grantedCapabilities = setOf("issues.write"),
    )
    private val access = ConnectorAccessSnapshot(
        connectionIds = setOf("c1"),
        actionIds = setOf(action.actionId),
        resourceIds = setOf("repo1/issue1"),
        runtimeAllowed = true,
        actorId = "actor1",
    )
    private val arguments = buildJsonObject {
        put("body", "ship it")
        put("issue", 7)
    }

    private fun invocation(args: JsonElement = arguments) = ConnectorInvocation(
        requestId = "r1",
        actorId = "actor1",
        connectionId = "c1",
        actionId = action.actionId,
        resourceId = "repo1/issue1",
        argumentDigest = digestConnectorArguments(args),
    )

    private fun approval(request: ConnectorInvocation) = ConnectorApproval(
        approvalId = "p1",
        invocation = request,
        accountId = "a1",
        connectionRevision = 2,
        issuedAtMillis = 100,
        expiresAtMillis = 300,
        risk = ConnectorRisk.WRITE,
    )

    @Test
    fun `exact approved arguments reach adapter once`() = runBlocking {
        var calls = 0
        val request = invocation()
        val result = executeConnectorInvocation(
            action = action,
            connection = connection,
            invocation = request,
            arguments = arguments,
            access = access,
            approval = approval(request),
            adapter = ConnectorActionAdapter { _, _, _, received ->
                calls += 1
                assertEquals(arguments, received)
                JsonPrimitive("ok")
            },
            nowMillis = 200,
        )

        assertEquals(ConnectorDecision.ALLOW, result.decision)
        assertEquals(JsonPrimitive("ok"), result.payload)
        assertEquals(1, calls)
    }

    @Test
    fun `changed arguments after approval execute zero adapter calls`() = runBlocking {
        var calls = 0
        val approvedRequest = invocation(arguments)
        val changed = buildJsonObject {
            put("body", "different")
            put("issue", 7)
        }

        val result = executeConnectorInvocation(
            action = action,
            connection = connection,
            invocation = approvedRequest,
            arguments = changed,
            access = access,
            approval = approval(approvedRequest),
            adapter = ConnectorActionAdapter { _, _, _, _ ->
                calls += 1
                JsonPrimitive("should-not-run")
            },
            nowMillis = 200,
        )

        assertEquals(ConnectorDecision.ARGUMENT_MISMATCH, result.decision)
        assertEquals(null, result.payload)
        assertEquals(0, calls)
    }

    @Test
    fun `policy denial executes zero adapter calls`() = runBlocking {
        var calls = 0
        val request = invocation()

        val result = executeConnectorInvocation(
            action = action,
            connection = connection,
            invocation = request,
            arguments = arguments,
            access = access.copy(runtimeAllowed = false),
            approval = approval(request),
            adapter = ConnectorActionAdapter { _, _, _, _ ->
                calls += 1
                JsonPrimitive("should-not-run")
            },
            nowMillis = 200,
        )

        assertEquals(ConnectorDecision.POLICY_DENIED, result.decision)
        assertEquals(0, calls)
    }

    @Test
    fun `argument digest is stable across object key order`() {
        val first = buildJsonObject {
            put("z", 1)
            put("a", buildJsonObject {
                put("y", true)
                put("b", "x")
            })
        }
        val second = buildJsonObject {
            put("a", buildJsonObject {
                put("b", "x")
                put("y", true)
            })
            put("z", 1)
        }

        assertEquals(digestConnectorArguments(first), digestConnectorArguments(second))
    }
}
