package com.inscopelabs.abxmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abxmcp.core.mcp.FileSystemReaderImpl
import com.inscopelabs.abxmcp.core.mcp.McpExecutor
import com.inscopelabs.abxmcp.core.policy.Capability
import com.inscopelabs.abxmcp.core.policy.PolicyEngineImpl
import com.inscopelabs.abxmcp.core.session.SessionManagerImpl
import com.inscopelabs.abxmcp.core.session.SessionState
import com.inscopelabs.abxmcp.core.session.UserGesture
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalBridgeTest {

    private lateinit var context: Context
    private lateinit var tempDir: Path
    private lateinit var policyEngine: PolicyEngineImpl
    private lateinit var fileSystemReader: FileSystemReaderImpl
    private lateinit var mcpExecutor: McpExecutor
    private lateinit var sessionManager: SessionManagerImpl
    private lateinit var testFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policyEngine = PolicyEngineImpl()
        fileSystemReader = FileSystemReaderImpl(context)
        mcpExecutor = McpExecutor(policyEngine, fileSystemReader)
        sessionManager = SessionManagerImpl()
        
        tempDir = Files.createTempDirectory("local_bridge_test")
        testFile = File(tempDir.toFile(), "example.txt").apply {
            writeText("Hello from Local Bridge Test!", Charsets.UTF_8)
        }
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testParseSharedRequest_JSONFormat() {
        val input = "{\"method\": \"read_file\", \"params\": {\"path\": \"/Documents/notes.txt\"}}"
        val parsed = parseSharedRequest(input)
        assertNotNull(parsed)
        assertEquals("read_file", parsed!!.operation)
        assertEquals("/Documents/notes.txt", parsed.path)
    }

    @Test
    fun testParseSharedRequest_KeyValueFormat() {
        val input = "operation: read_file\npath: /Documents/notes.txt"
        val parsed = parseSharedRequest(input)
        assertNotNull(parsed)
        assertEquals("read_file", parsed!!.operation)
        assertEquals("/Documents/notes.txt", parsed.path)
    }

    @Test
    fun testParseSharedRequest_ColonFormat() {
        val input = "read_file: /Documents/notes.txt"
        val parsed = parseSharedRequest(input)
        assertNotNull(parsed)
        assertEquals("read_file", parsed!!.operation)
        assertEquals("/Documents/notes.txt", parsed.path)
    }

    @Test
    fun testParseSharedRequest_Malformed() {
        val input = "invalid_input_without_colon_or_json"
        val parsed = parseSharedRequest(input)
        assertNull(parsed)
    }

    @Test
    fun testExecuteLocalBridgeRequest_ActiveSessionSuccess() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        val activeState = sessionManager.stateFlow.value
        assertTrue(activeState is SessionState.ACTIVE)

        val input = "read_file: ${testFile.absolutePath}"
        var successResult: String? = null
        var failureReason: String? = null

        executeLocalBridgeRequest(
            inputText = input,
            sessionState = activeState,
            sessionManager = sessionManager,
            mcpExecutor = mcpExecutor,
            allowedRootsStr = tempDir.toFile().absolutePath,
            allowedOpsStr = "read_file",
            maxReqStr = "0",
            onSuccess = { successResult = it },
            onFailure = { failureReason = it }
        )

        assertNull(failureReason)
        assertNotNull(successResult)
        
        val displayResult = extractBridgeDisplayResult(successResult!!)
        assertEquals("Hello from Local Bridge Test!", displayResult)
    }

    @Test
    fun testExecuteLocalBridgeRequest_InactiveSessionRejected() {
        // Session is INACTIVE initially
        val inactiveState = sessionManager.stateFlow.value
        assertTrue(inactiveState is SessionState.INACTIVE)

        val input = "read_file: ${testFile.absolutePath}"
        var successResult: String? = null
        var failureReason: String? = null

        executeLocalBridgeRequest(
            inputText = input,
            sessionState = inactiveState,
            sessionManager = sessionManager,
            mcpExecutor = mcpExecutor,
            allowedRootsStr = tempDir.toFile().absolutePath,
            allowedOpsStr = "read_file",
            maxReqStr = "0",
            onSuccess = { successResult = it },
            onFailure = { failureReason = it }
        )

        assertNotNull(failureReason)
        assertEquals("Blocked: session had ended", failureReason)
        assertNull(successResult)
    }

    @Test
    fun testExecuteLocalBridgeRequest_MaxRequestExceeded() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        val activeState = sessionManager.stateFlow.value
        assertTrue(activeState is SessionState.ACTIVE)

        val input = "read_file: ${testFile.absolutePath}"
        
        // Execute first time (with max limit = 1)
        var success1: String? = null
        var failure1: String? = null
        executeLocalBridgeRequest(
            inputText = input,
            sessionState = activeState,
            sessionManager = sessionManager,
            mcpExecutor = mcpExecutor,
            allowedRootsStr = tempDir.toFile().absolutePath,
            allowedOpsStr = "read_file",
            maxReqStr = "1", // limit to 1 request
            onSuccess = { success1 = it },
            onFailure = { failure1 = it }
        )
        assertNull(failure1)
        assertNotNull(success1)

        // Execute second time (limit should be exceeded)
        var success2: String? = null
        var failure2: String? = null
        executeLocalBridgeRequest(
            inputText = input,
            sessionState = activeState,
            sessionManager = sessionManager,
            mcpExecutor = mcpExecutor,
            allowedRootsStr = tempDir.toFile().absolutePath,
            allowedOpsStr = "read_file",
            maxReqStr = "1",
            onSuccess = { success2 = it },
            onFailure = { failure2 = it }
        )
        assertNotNull(failure2)
        assertEquals("Blocked: request limit exceeded", failure2)
        assertNull(success2)
    }
}
