package com.inscopelabs.abxmcp.core.policy

import com.inscopelabs.abxmcp.core.session.SessionState
import java.io.File
import java.text.Normalizer

class PolicyEngineImpl(private val isDebug: Boolean = isRunningInTest()) : PolicyEngine {

    companion object {
        private fun isRunningInTest(): Boolean {
            return try {
                Class.forName("org.junit.Test") != null
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun authorize(
        request: Request,
        token: Capability,
        currentState: SessionState
    ): AuthorizationResult {
        // 1. Check if the session is ACTIVE
        if (currentState !is SessionState.ACTIVE) {
            return AuthorizationResult.Rejected(
                if (isDebug) "Session is not active (current state: $currentState)"
                else "Authorization rejected: Access denied"
            )
        }

        // 2. Reject any non-file schemes (e.g. content://)
        if (request.path.startsWith("content://") || request.path.contains("://")) {
            return AuthorizationResult.Rejected(
                if (isDebug) "Non-file schemes (such as content://) are explicitly rejected by PolicyEngine"
                else "Authorization rejected: Access denied"
            )
        }

        // 3. Check if the capability token itself has expired based on system clock
        if (System.currentTimeMillis() > token.expiry) {
            return AuthorizationResult.Rejected(
                if (isDebug) "Capability token has expired"
                else "Authorization rejected: Access denied"
            )
        }

        // 4. Check operation granularity
        if (!token.allowedOperations.contains(request.operation)) {
            return AuthorizationResult.Rejected(
                if (isDebug) "Operation '${request.operation}' is not in allowed operations: ${token.allowedOperations}"
                else "Authorization rejected: Access denied"
            )
        }

        // 5. Resolve the requested path canonical form
        val canonicalReqPath = try {
            File(request.path).canonicalPath
        } catch (e: Exception) {
            return AuthorizationResult.Rejected(
                if (isDebug) "Failed to canonicalize requested path: ${e.message}"
                else "Authorization rejected: Access denied"
            )
        }

        val normalizedReqPath = Normalizer.normalize(canonicalReqPath, Normalizer.Form.NFC)
        val cleanReq = if (normalizedReqPath.endsWith(File.separator) && normalizedReqPath != File.separator) {
            normalizedReqPath.dropLast(1)
        } else {
            normalizedReqPath
        }

        // 6. Compare against allowed roots
        var isAllowedPath = false
        for (root in token.allowedRoots) {
            val canonicalRootPath = try {
                File(root).canonicalPath
            } catch (e: Exception) {
                continue
            }

            val normalizedRootPath = Normalizer.normalize(canonicalRootPath, Normalizer.Form.NFC)
            val cleanRoot = if (normalizedRootPath.endsWith(File.separator) && normalizedRootPath != File.separator) {
                normalizedRootPath.dropLast(1)
            } else {
                normalizedRootPath
            }

            // Segment-aware match helper to avoid prefix traversal bypasses
            if (cleanReq == cleanRoot || cleanReq.startsWith(cleanRoot + File.separator)) {
                isAllowedPath = true
                break
            }
        }

        if (!isAllowedPath) {
            return AuthorizationResult.Rejected(
                if (isDebug) "Path '${request.path}' (canonicalized: '$canonicalReqPath') is outside allowed roots: ${token.allowedRoots}"
                else "Authorization rejected: Access denied"
            )
        }

        return AuthorizationResult.Allowed(cleanReq)
    }
}
