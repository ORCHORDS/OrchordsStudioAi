package com.orchords.orchordsai.data.extensions

enum class ConnectorRisk { READ, WRITE, DESTRUCTIVE, PAID }
data class ConnectorActionDefinition(val connectorId: String, val actionId: String, val risk: ConnectorRisk, val requiredCapabilities: Set<String>, val version: Int = 1)
data class ConnectorConnectionSnapshot(val connectionId: String, val connectorId: String, val accountId: String, val revision: Long, val connected: Boolean, val grantedCapabilities: Set<String>)
data class ConnectorInvocation(val requestId: String, val actorId: String, val connectionId: String, val actionId: String, val resourceId: String, val argumentDigest: String)
data class ConnectorAccessSnapshot(val connectionIds: Set<String>, val actionIds: Set<String>, val resourceIds: Set<String>, val runtimeAllowed: Boolean, val actorId: String = "")
data class ConnectorApproval(val approvalId: String, val invocation: ConnectorInvocation, val accountId: String, val connectionRevision: Long, val issuedAtMillis: Long, val expiresAtMillis: Long, val actionVersion: Int = 1, val risk: ConnectorRisk = ConnectorRisk.WRITE)
enum class ConnectorDecision { ALLOW, INVALID_REQUEST, ARGUMENT_MISMATCH, ADAPTER_UNAVAILABLE, CONNECTION_UNAVAILABLE, IDENTITY_MISMATCH, POLICY_DENIED, SCOPE_DENIED, APPROVAL_REQUIRED, APPROVAL_MISMATCH, APPROVAL_EXPIRED }
/**
 * Pure preflight only. All inputs except user action arguments must be resolved by the trusted
 * runtime, never deserialized from model-supplied approval/connection/permission objects.
 * An ALLOW is NOT a reusable execution token. The executor must recheck live policy, consume
 * one-shot approval/idempotency state atomically, and enforce schema/origin/byte/retry bounds.
 * No credentials or HTTP adapter are implemented by this foundational contract.
 */
fun evaluateConnectorInvocation(
    action: ConnectorActionDefinition,
    connection: ConnectorConnectionSnapshot?,
    invocation: ConnectorInvocation,
    access: ConnectorAccessSnapshot,
    approval: ConnectorApproval?,
    adapterAvailable: Boolean,
    nowMillis: Long,
): ConnectorDecision {
    fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 1024 &&
        value.none { it.code < 32 || it.code == 127 }
    if (nowMillis < 0 || action.version < 1 ||
        listOf(action.connectorId, action.actionId, invocation.requestId, invocation.actorId,
            invocation.connectionId, invocation.actionId, invocation.resourceId).any { !validId(it) } ||
        !Regex("[a-f0-9]{64}").matches(invocation.argumentDigest)
    ) return ConnectorDecision.INVALID_REQUEST
    if (!adapterAvailable) return ConnectorDecision.ADAPTER_UNAVAILABLE
    if (connection == null || !connection.connected) return ConnectorDecision.CONNECTION_UNAVAILABLE
    if (!validId(connection.accountId) || connection.revision < 0 ||
        connection.connectorId != action.connectorId ||
        connection.connectionId != invocation.connectionId || action.actionId != invocation.actionId
    ) return ConnectorDecision.IDENTITY_MISMATCH
    if (!access.runtimeAllowed || access.actorId != invocation.actorId ||
        invocation.connectionId !in access.connectionIds || invocation.actionId !in access.actionIds ||
        invocation.resourceId !in access.resourceIds
    ) return ConnectorDecision.POLICY_DENIED
    if (!connection.grantedCapabilities.containsAll(action.requiredCapabilities)) return ConnectorDecision.SCOPE_DENIED
    if (action.risk == ConnectorRisk.READ) return ConnectorDecision.ALLOW
    if (approval == null) return ConnectorDecision.APPROVAL_REQUIRED
    if (!validId(approval.approvalId) || approval.invocation != invocation ||
        approval.accountId != connection.accountId || approval.connectionRevision != connection.revision ||
        approval.actionVersion != action.version || approval.risk != action.risk
    ) return ConnectorDecision.APPROVAL_MISMATCH
    if (approval.issuedAtMillis < 0 || approval.issuedAtMillis > nowMillis ||
        approval.expiresAtMillis <= nowMillis || approval.expiresAtMillis <= approval.issuedAtMillis
    ) return ConnectorDecision.APPROVAL_EXPIRED
    return ConnectorDecision.ALLOW
}
