package com.inscopelabs.abxmcp.core.mcp

import android.util.Base64
import com.inscopelabs.abxmcp.core.policy.Capability
import com.inscopelabs.abxmcp.core.policy.PolicyEngine
import com.inscopelabs.abxmcp.core.policy.Request as PolicyRequest
import com.inscopelabs.abxmcp.core.policy.AuthorizationResult
import com.inscopelabs.abxmcp.core.session.SessionState
import org.json.JSONArray
import org.json.JSONObject

class McpExecutor(
    private val policyEngine: PolicyEngine,
    private val fileSystemReader: FileSystemReader,
    private val isDebug: Boolean = isRunningInTest()
) {

    companion object {
        private fun isRunningInTest(): Boolean {
            return try {
                Class.forName("org.junit.Test") != null
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Executes an MCP read command encoded as a JSON request.
     * Enforces the PolicyEngine pre-check.
     * Returns a JSON-RPC and MCP tool-call compliant response.
     */
    fun execute(jsonRequestStr: String, token: Capability, currentState: SessionState): String {
        val responseJson = JSONObject()
        var reqId: Any? = null
        try {
            val reqObj = JSONObject(jsonRequestStr)
            
            // Extract JSON-RPC ID if present
            reqId = if (reqObj.has("id")) reqObj.get("id") else null
            if (reqId != null) {
                responseJson.put("id", reqId)
            }
            responseJson.put("jsonrpc", "2.0")

            var method = reqObj.optString("method", "")
            var paramsObj = reqObj.optJSONObject("params") ?: JSONObject()

            // If it's a standard MCP tools/call request
            if (method == "tools/call" || method == "call_tool") {
                val name = paramsObj.optString("name", "")
                val arguments = paramsObj.optJSONObject("arguments") ?: JSONObject()
                method = name
                paramsObj = arguments
            }

            val path = paramsObj.optString("path", "")
            if (path.isEmpty()) {
                return errorResponse(reqId, "Invalid request: 'path' parameter is missing", isMcpStyle = true)
            }

            // Map method to our handled operations
            val operation = when (method) {
                "file_exists", "exists" -> "file_exists"
                "get_file_metadata", "metadata" -> "get_file_metadata"
                "get_file_version", "version" -> "get_file_version"
                "read_file", "read" -> "read_file"
                "list_directory", "list" -> "list_directory"
                else -> {
                    return errorResponse(reqId, "Unknown method or operation: $method", isMcpStyle = true)
                }
            }

            // 1. Policy Engine Authorization pre-check!
            val policyReq = PolicyRequest(path = path, operation = operation)
            val authResult = policyEngine.authorize(policyReq, token, currentState)
            val authorizedPath = when (authResult) {
                is AuthorizationResult.Allowed -> authResult.canonicalPath
                is AuthorizationResult.Rejected -> {
                    val message = if (isDebug) "Authorization rejected: ${authResult.reason}" else "Authorization rejected: Access denied"
                    return errorResponse(reqId, message, isMcpStyle = true)
                }
            }

            // 2. Execute requested operation
            val resultObj = JSONObject()
            when (operation) {
                "file_exists" -> {
                    val exists = fileSystemReader.exists(authorizedPath)
                    resultObj.put("exists", exists)
                }
                "get_file_metadata" -> {
                    val metadata = fileSystemReader.getMetadata(authorizedPath)
                    if (metadata != null) {
                        resultObj.put("name", metadata.name)
                        resultObj.put("path", metadata.path)
                        resultObj.put("size", metadata.size)
                        resultObj.put("lastModified", metadata.lastModified)
                        resultObj.put("isDirectory", metadata.isDirectory)
                        resultObj.put("isFile", metadata.isFile)
                        resultObj.put("mimeType", metadata.mimeType)
                    } else {
                        return errorResponse(reqId, "File not found: $authorizedPath", isMcpStyle = true)
                    }
                }
                "get_file_version" -> {
                    val lastMod = fileSystemReader.getLastModified(authorizedPath)
                    resultObj.put("version", lastMod.toString())
                }
                "read_file" -> {
                    val encoding = paramsObj.optString("encoding", "text")
                    val bytes = fileSystemReader.readFile(authorizedPath)
                    if (encoding == "base64" || encoding == "binary") {
                        val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        resultObj.put("content", base64Str)
                        resultObj.put("encoding", "base64")
                    } else {
                        val textStr = String(bytes, Charsets.UTF_8)
                        resultObj.put("content", textStr)
                        resultObj.put("encoding", "text")
                    }
                }
                "list_directory" -> {
                    val files = fileSystemReader.listDirectory(authorizedPath)
                    val filesArray = JSONArray(files)
                    resultObj.put("files", filesArray)
                }
            }

            // Build standard MCP structured ToolResponse:
            // { "content": [ { "type": "text", "text": "<json-result>" } ], "isError": false }
            val mcpContentArr = JSONArray()
            val mcpTextObj = JSONObject()
            mcpTextObj.put("type", "text")
            mcpTextObj.put("text", resultObj.toString())
            mcpContentArr.put(mcpTextObj)

            responseJson.put("content", mcpContentArr)
            responseJson.put("isError", false)
            responseJson.put("result", resultObj)

            return responseJson.toString()

        } catch (e: Exception) {
            return errorResponse(reqId, "Exception during execution: ${e.message}", isMcpStyle = true)
        }
    }

    private fun errorResponse(id: Any?, message: String, isMcpStyle: Boolean): String {
        val errObj = JSONObject()
        errObj.put("jsonrpc", "2.0")
        if (id != null) {
            errObj.put("id", id)
        }
        
        val errorDetail = JSONObject()
        errorDetail.put("code", -32000)
        errorDetail.put("message", message)
        errObj.put("error", errorDetail)

        if (isMcpStyle) {
            val mcpContentArr = JSONArray()
            val mcpTextObj = JSONObject()
            mcpTextObj.put("type", "text")
            mcpTextObj.put("text", "Error: $message")
            mcpContentArr.put(mcpTextObj)
            errObj.put("content", mcpContentArr)
            errObj.put("isError", true)
        }
        return errObj.toString()
    }
}
