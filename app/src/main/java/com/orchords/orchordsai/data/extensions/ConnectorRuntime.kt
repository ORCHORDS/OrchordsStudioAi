package com.orchords.orchordsai.data.extensions

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

fun interface ConnectorActionAdapter {
    suspend fun execute(
        action: ConnectorActionDefinition,
        connection: ConnectorConnectionSnapshot,
        invocation: ConnectorInvocation,
        arguments: JsonElement,
    ): JsonElement
}

data class ConnectorExecutionResult(
    val decision: ConnectorDecision,
    val payload: JsonElement? = null,
)

/** Stable digest for approval binding; object-key order cannot change the digest. */
fun digestConnectorArguments(arguments: JsonElement): String {
    val canonical = canonicalConnectorJson(arguments).toString().encodeToByteArray()
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

suspend fun executeConnectorInvocation(
    action: ConnectorActionDefinition,
    connection: ConnectorConnectionSnapshot?,
    invocation: ConnectorInvocation,
    arguments: JsonElement,
    access: ConnectorAccessSnapshot,
    approval: ConnectorApproval?,
    adapter: ConnectorActionAdapter,
    nowMillis: Long,
): ConnectorExecutionResult {
    if (digestConnectorArguments(arguments) != invocation.argumentDigest) {
        return ConnectorExecutionResult(ConnectorDecision.ARGUMENT_MISMATCH)
    }

    val decision = evaluateConnectorInvocation(
        action = action,
        connection = connection,
        invocation = invocation,
        access = access,
        approval = approval,
        adapterAvailable = true,
        nowMillis = nowMillis,
    )
    if (decision != ConnectorDecision.ALLOW || connection == null) {
        return ConnectorExecutionResult(decision)
    }

    return ConnectorExecutionResult(
        decision = ConnectorDecision.ALLOW,
        payload = adapter.execute(action, connection, invocation, arguments),
    )
}

private fun canonicalConnectorJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.keys.sorted().forEach { key ->
            put(key, canonicalConnectorJson(element.getValue(key)))
        }
    }
    is JsonArray -> buildJsonArray {
        element.forEach { add(canonicalConnectorJson(it)) }
    }
    is JsonPrimitive -> element
}
