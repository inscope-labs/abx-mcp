package com.inscopelabs.abxmcp.core.policy

import com.inscopelabs.abxmcp.core.session.SessionState
import java.io.File
import java.text.Normalizer

class PolicyEngineImpl : PolicyEngine {

    override fun authorize(
        request: Request,
        token: Capability,
        currentState: SessionState
    ): AuthorizationResult {
        // 1. Check if the session is ACTIVE
        if (currentState !is SessionState.ACTIVE) {
            return AuthorizationResult.Rejected("Session is not active (current state: $currentState)")
        }

        // 2. Check if the capability token itself has expired based on system clock
        if (System.currentTimeMillis() > token.expiry) {
            return AuthorizationResult.Rejected("Capability token has expired")
        }

        // 3. Check operation granularity
        if (!token.allowedOperations.contains(request.operation)) {
            return AuthorizationResult.Rejected("Operation '${request.operation}' is not in allowed operations: ${token.allowedOperations}")
        }

        // 4. Resolve the requested path canonical form
        val canonicalReqPath = try {
            File(request.path).canonicalPath
        } catch (e: Exception) {
            return AuthorizationResult.Rejected("Failed to canonicalize requested path: ${e.message}")
        }

        val normalizedReqPath = Normalizer.normalize(canonicalReqPath, Normalizer.Form.NFC)
        val cleanReq = if (normalizedReqPath.endsWith(File.separator)) {
            normalizedReqPath.dropLast(1)
        } else {
            normalizedReqPath
        }

        // 5. Compare against allowed roots
        var isAllowedPath = false
        for (root in token.allowedRoots) {
            val canonicalRootPath = try {
                File(root).canonicalPath
            } catch (e: Exception) {
                continue
            }

            val normalizedRootPath = Normalizer.normalize(canonicalRootPath, Normalizer.Form.NFC)
            val cleanRoot = if (normalizedRootPath.endsWith(File.separator)) {
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
            return AuthorizationResult.Rejected("Path '${request.path}' (canonicalized: '$canonicalReqPath') is outside allowed roots: ${token.allowedRoots}")
        }

        return AuthorizationResult.Allowed
    }
}
