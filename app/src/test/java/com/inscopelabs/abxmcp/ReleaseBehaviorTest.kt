package com.inscopelabs.abxmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abxmcp.core.keystore.KeyStoreEnvironment
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.core.mcp.FileSystemReader
import com.inscopelabs.abxmcp.core.mcp.McpExecutor
import com.inscopelabs.abxmcp.core.policy.AuthorizationResult
import com.inscopelabs.abxmcp.core.policy.Capability
import com.inscopelabs.abxmcp.core.policy.PolicyEngineImpl
import com.inscopelabs.abxmcp.core.policy.Request as PolicyRequest
import com.inscopelabs.abxmcp.core.session.SessionState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseBehaviorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPolicyEngineImplReleaseSanitization() {
        // Instantiate PolicyEngineImpl in release mode (isDebug = false)
        val policyEngine = PolicyEngineImpl(isDebug = false)
        
        val request = PolicyRequest("/any/path", "read_file")
        val token = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 100000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf("/allowed"),
            nonceSeed = "nonce_123"
        )
        
        // When session is INACTIVE, authorize should return generic message
        val inactiveResult = policyEngine.authorize(request, token, SessionState.INACTIVE)
        assertTrue(inactiveResult is AuthorizationResult.Rejected)
        val inactiveReason = (inactiveResult as AuthorizationResult.Rejected).reason
        assertEquals("Authorization rejected: Access denied", inactiveReason)

        // When path is outside allowed roots, authorize should return generic message
        val disallowedResult = policyEngine.authorize(request, token, SessionState.ACTIVE)
        assertTrue(disallowedResult is AuthorizationResult.Rejected)
        val disallowedReason = (disallowedResult as AuthorizationResult.Rejected).reason
        assertEquals("Authorization rejected: Access denied", disallowedReason)
    }

    @Test
    fun testMcpExecutorReleaseSanitization() {
        val policyEngine = PolicyEngineImpl(isDebug = false)
        val mockReader = object : FileSystemReader {
            override fun exists(path: String): Boolean = false
            override fun getMetadata(path: String): com.inscopelabs.abxmcp.core.mcp.FileMetadata? = null
            override fun getLastModified(path: String): Long = 0L
            override fun readFile(path: String): ByteArray = ByteArray(0)
            override fun listDirectory(path: String): List<String> = emptyList()
        }
        
        // Instantiate McpExecutor in release mode (isDebug = false)
        val mcpExecutor = McpExecutor(policyEngine, mockReader, isDebug = false)
        
        val reqStr = JSONObject().apply {
            put("id", 123)
            put("jsonrpc", "2.0")
            put("method", "read_file")
            put("params", JSONObject().apply {
                put("path", "/forbidden/file.txt")
            })
        }.toString()

        val token = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 100000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf("/allowed"),
            nonceSeed = "nonce_123"
        )

        val responseStr = mcpExecutor.execute(reqStr, token, SessionState.ACTIVE)
        val responseJson = JSONObject(responseStr)
        
        assertTrue(responseJson.has("error"))
        val errorObj = responseJson.getJSONObject("error")
        val errorMessage = errorObj.getString("message")
        
        // Assert that the error message is completely sanitized and generic
        assertEquals("Authorization rejected: Access denied", errorMessage)
    }

    @Test
    fun testKeyStoreManagerProductionRejectsJvmFallback() {
        val keyStoreManager = KeyStoreManager(context, KeyStoreEnvironment.PRODUCTION)
        
        // If the device does not have AndroidKeyStore (or Robolectric does not support it),
        // KeyStoreManager must reject falling back to JVM and throw an IllegalStateException
        if (!keyStoreManager.isAndroidKeyStore) {
            try {
                keyStoreManager.generateKeyPair("test_alias")
                fail("Expected IllegalStateException to be thrown when falling back to JVM in production")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("Secure hardware-backed keystore is unavailable in production") == true)
            }
        }
    }
}
