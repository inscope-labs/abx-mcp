package com.inscopelabs.abxmcp.core.policy

import com.inscopelabs.abxmcp.core.session.SessionState

data class Request(
    val path: String,
    val operation: String
)

data class Capability(
    val sessionId: String,
    val expiry: Long,
    val allowedOperations: List<String>,
    val allowedRoots: List<String>,
    val nonceSeed: String
)

sealed class AuthorizationResult {
    data class Allowed(val canonicalPath: String) : AuthorizationResult()
    data class Rejected(val reason: String) : AuthorizationResult()
}

interface PolicyEngine {
    fun authorize(
        request: Request,
        token: Capability,
        currentState: SessionState
    ): AuthorizationResult
}
