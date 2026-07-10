package com.inscopelabs.abxmcp.core.keystore

interface TokenIssuer {
    /**
     * Generates a signed token string (JWT-like format: Base64(Payload) + "." + Base64(Signature))
     */
    fun issueToken(
        sessionId: String,
        expiry: Long,
        allowedOperations: List<String>,
        allowedRoots: List<String>,
        nonceSeed: String,
        issuedTime: Long = System.currentTimeMillis(),
        maxRequestCount: Int = 0,
        alias: String = "ABX_MCP_TOKEN_KEY"
    ): String

    /**
     * Verifies the signature of a token and parses the payload.
     * Returns the parsed token data if valid, or null if invalid or tampered with.
     */
    fun verifyAndParseToken(
        token: String,
        alias: String = "ABX_MCP_TOKEN_KEY"
    ): ParsedToken?
}

data class ParsedToken(
    val sessionId: String,
    val expiry: Long,
    val allowedOperations: List<String>,
    val allowedRoots: List<String>,
    val nonceSeed: String,
    val issuedTime: Long = System.currentTimeMillis(),
    val maxRequestCount: Int = 0
)
