package com.inscopelabs.abxmcp.core.policy

import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.core.audit.ReasonCode
import com.inscopelabs.abxmcp.core.session.SessionState
import java.io.File
import java.text.Normalizer

class PolicyEngineImpl(private val isDebug: Boolean = isRunningInTest()) : PolicyEngine {

    @Volatile
    var isSafModeActive: Boolean = false

    @Volatile
    var safTreeUri: String? = null

    @Volatile
    var context: android.content.Context? = null

    @Volatile
    var overrideRootPath: String? = null

    @Volatile
    var documentFileResolver: (android.content.Context, android.net.Uri) -> androidx.documentfile.provider.DocumentFile? = { ctx, uri ->
        androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)
    }

    companion object {
        fun getRootPathFromTreeUri(context: android.content.Context, uri: android.net.Uri): String? {
            try {
                if (uri.scheme == "file") {
                    return uri.path
                }
                val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return null
                val parts = documentId.split(":")
                val relativePath = if (parts.size >= 2) parts[1] else ""
                val type = if (parts.isNotEmpty()) parts[0] else ""

                if ("primary".equals(type, ignoreCase = true)) {
                    val base = android.os.Environment.getExternalStorageDirectory().absolutePath
                    return if (relativePath.isEmpty()) base else java.io.File(base, relativePath).absolutePath
                } else {
                    if (java.io.File(type).isAbsolute) {
                        return if (relativePath.isEmpty()) type else java.io.File(type, relativePath).absolutePath
                    }
                    return "/storage/$type/$relativePath"
                }
            } catch (e: Exception) {
                val path = uri.path ?: return null
                if (path.contains("/tree/")) {
                    val docId = path.substringAfter("/tree/")
                    val parts = docId.split(":")
                    if (parts.size >= 2) {
                        return parts[1]
                    }
                }
                return path
            }
        }

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
            AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, token.sessionId, "Session is not active (state: $currentState)")
            return AuthorizationResult.Rejected(
                if (isDebug) "Session is not active (current state: $currentState)"
                else "Authorization rejected: Access denied"
            )
        }

        // 2. Reject any non-file schemes (e.g. content://)
        if (request.path.startsWith("content://") || request.path.contains("://")) {
            AuditLog.recordRejection(ReasonCode.PATH_OUT_OF_BOUNDS, token.sessionId, "Non-file scheme requested: ${request.path}")
            return AuthorizationResult.Rejected(
                if (isDebug) "Non-file schemes (such as content://) are explicitly rejected by PolicyEngine"
                else "Authorization rejected: Access denied"
            )
        }

        // 3. Check if the capability token itself has expired based on system clock
        if (System.currentTimeMillis() > token.expiry) {
            AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, token.sessionId, "Capability token has expired")
            return AuthorizationResult.Rejected(
                if (isDebug) "Capability token has expired"
                else "Authorization rejected: Access denied"
            )
        }

        // 4. Check operation granularity
        if (!token.allowedOperations.contains(request.operation)) {
            val isTierViolation = (request.operation == "delete_file" && !token.allowedOperations.contains("delete_file"))
            val code = if (isTierViolation) ReasonCode.TIER_VIOLATION else ReasonCode.OP_NOT_ALLOWED
            AuditLog.recordRejection(code, token.sessionId, "Operation '${request.operation}' is not in allowed operations: ${token.allowedOperations}")
            return AuthorizationResult.Rejected(
                if (isDebug) "Operation '${request.operation}' is not in allowed operations: ${token.allowedOperations}"
                else "Authorization rejected: Access denied"
            )
        }

        // 5. Resolve the requested path canonical form
        val canonicalReqPath = try {
            File(request.path).canonicalPath
        } catch (e: Exception) {
            AuditLog.recordRejection(ReasonCode.PATH_OUT_OF_BOUNDS, token.sessionId, "Failed to canonicalize requested path: ${e.message}")
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

        // 6. Compare against allowed roots / SAF derived root
        if (isSafModeActive) {
            val ctx = context ?: run {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "Context is null for SAF mode")
                return AuthorizationResult.Rejected(
                    if (isDebug) "Context is null for SAF mode"
                    else "Authorization rejected: Access denied"
                )
            }
            val uriStr = safTreeUri ?: run {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "SAF tree URI is null")
                return AuthorizationResult.Rejected(
                    if (isDebug) "SAF tree URI is null"
                    else "Authorization rejected: Access denied"
                )
            }
            val treeUri = android.net.Uri.parse(uriStr)
            
            // Call live to derive the root and check permissions (no caching beyond current request)
            val documentFile = documentFileResolver(ctx, treeUri) ?: run {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "Failed to resolve SAF DocumentFile")
                return AuthorizationResult.Rejected(
                    if (isDebug) "Failed to resolve SAF DocumentFile"
                    else "Authorization rejected: Access denied"
                )
            }

            val isReadOp = listOf("read_file", "list_directory", "file_exists", "get_file_metadata", "get_file_version")
                .contains(request.operation)
            val isWriteOp = listOf("write_file", "append_file").contains(request.operation)
            val isDeleteOp = (request.operation == "delete_file")

            if (isReadOp && !documentFile.canRead()) {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "SAF tree does not have read permissions")
                return AuthorizationResult.Rejected(
                    if (isDebug) "SAF tree does not have read permissions"
                    else "Authorization rejected: Access denied"
                )
            }
            if (isWriteOp && !documentFile.canWrite()) {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "SAF tree does not have write permissions")
                return AuthorizationResult.Rejected(
                    if (isDebug) "SAF tree does not have write permissions"
                    else "Authorization rejected: Access denied"
                )
            }
            if (isDeleteOp && !documentFile.canWrite()) {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "SAF tree does not have write permissions needed for delete")
                return AuthorizationResult.Rejected(
                    if (isDebug) "SAF tree does not have write permissions needed for delete"
                    else "Authorization rejected: Access denied"
                )
            }

            val derivedRoot = overrideRootPath ?: getRootPathFromTreeUri(ctx, treeUri) ?: run {
                AuditLog.recordRejection(ReasonCode.SAF_REVOKED, token.sessionId, "Failed to derive file system path from SAF tree URI")
                return AuthorizationResult.Rejected(
                    if (isDebug) "Failed to derive file system path from SAF tree URI"
                    else "Authorization rejected: Access denied"
                )
            }

            val canonicalRootPath = try {
                File(derivedRoot).canonicalPath
            } catch (e: Exception) {
                AuditLog.recordRejection(ReasonCode.PATH_OUT_OF_BOUNDS, token.sessionId, "Failed to canonicalize derived root path: ${e.message}")
                return AuthorizationResult.Rejected(
                    if (isDebug) "Failed to canonicalize derived root path: ${e.message}"
                    else "Authorization rejected: Access denied"
                )
            }

            val normalizedRootPath = Normalizer.normalize(canonicalRootPath, Normalizer.Form.NFC)
            val cleanRoot = if (normalizedRootPath.endsWith(File.separator) && normalizedRootPath != File.separator) {
                normalizedRootPath.dropLast(1)
            } else {
                normalizedRootPath
            }

            if (cleanReq == cleanRoot || cleanReq.startsWith(cleanRoot + File.separator)) {
                return AuthorizationResult.Allowed(cleanReq)
            } else {
                AuditLog.recordRejection(ReasonCode.PATH_OUT_OF_BOUNDS, token.sessionId, "Path '${request.path}' is outside SAF derived root: $cleanRoot")
                return AuthorizationResult.Rejected(
                    if (isDebug) "Path '${request.path}' (canonicalized: '$canonicalReqPath') is outside SAF derived root: $cleanRoot"
                    else "Authorization rejected: Access denied"
                )
            }
        } else {
            // Raw allowlist
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
                AuditLog.recordRejection(ReasonCode.PATH_OUT_OF_BOUNDS, token.sessionId, "Path '${request.path}' is outside allowed roots: ${token.allowedRoots}")
                return AuthorizationResult.Rejected(
                    if (isDebug) "Path '${request.path}' (canonicalized: '$canonicalReqPath') is outside allowed roots: ${token.allowedRoots}"
                    else "Authorization rejected: Access denied"
                )
            }
        }

        return AuthorizationResult.Allowed(cleanReq)
    }
}
