package com.inscopelabs.abxmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.core.audit.ReasonCode
import com.inscopelabs.abxmcp.core.mcp.FileSystemReaderImpl
import com.inscopelabs.abxmcp.core.mcp.McpExecutor
import com.inscopelabs.abxmcp.core.policy.Capability
import com.inscopelabs.abxmcp.core.policy.PolicyEngineImpl
import com.inscopelabs.abxmcp.core.session.SessionState
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
import androidx.documentfile.provider.MockDocumentFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrossPhaseInteractionTest {

    private lateinit var context: Context
    private lateinit var tempDir: Path
    private lateinit var policyEngine: PolicyEngineImpl
    private lateinit var fileSystemReader: FileSystemReaderImpl
    private lateinit var mcpExecutor: McpExecutor
    private lateinit var staleButUnexpiredToken: Capability
    private lateinit var testFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policyEngine = PolicyEngineImpl()
        fileSystemReader = FileSystemReaderImpl(context)
        mcpExecutor = McpExecutor(policyEngine, fileSystemReader)

        // 1. Setup temporary directory structure (SAF root simulator)
        tempDir = Files.createTempDirectory("saf_cross_phase_tree")
        testFile = File(tempDir.toFile(), "session_data.txt").apply {
            writeText("initial session state", Charsets.UTF_8)
        }

        // Initialize AuditLog
        val keyStoreManager = com.inscopelabs.abxmcp.core.keystore.KeyStoreManager(
            context,
            com.inscopelabs.abxmcp.core.keystore.KeyStoreEnvironment.TEST_FALLBACK
        )
        AuditLog.initialize(context, keyStoreManager)
        AuditLog.clear()

        // 2. Set up a capability token that is stale (representing a previously issued token) but unexpired
        // Expiry set 10 minutes in the future, allowedRoots is empty (as SAF mode will override it)
        staleButUnexpiredToken = Capability(
            sessionId = "stale_session_1234",
            expiry = System.currentTimeMillis() + 600_000,
            allowedOperations = listOf("read_file", "write_file", "append_file"),
            allowedRoots = emptyList(),
            nonceSeed = "stale_seed_987"
        )
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testSafGrantRevokedMidSession_WithUnexpiredToken_RejectsWriteAndLogsAudit() {
        // 1. Activate SAF mode on the policy engine
        policyEngine.isSafModeActive = true
        policyEngine.context = context
        policyEngine.safTreeUri = "content://com.android.externalstorage.documents/tree/primary%3Asaf_cross_phase_tree"
        policyEngine.overrideRootPath = tempDir.toFile().absolutePath

        // Initial state: SAF grant permits both reads and writes
        val mockDocFile = MockDocumentFile(canReadVal = true, canWriteVal = true)
        policyEngine.documentFileResolver = { _, _ -> mockDocFile }

        // Setup a write request
        val writeReq = JSONObject().apply {
            put("method", "write_file")
            put("params", JSONObject().apply {
                put("path", testFile.absolutePath)
                put("content", "updated session content")
            })
        }.toString()

        // Confirm writing works initially with active SAF grant and unexpired token
        val initialRespStr = mcpExecutor.execute(writeReq, staleButUnexpiredToken, SessionState.ACTIVE)
        val initialResp = JSONObject(initialRespStr)
        assertFalse("Initial write should succeed", initialResp.getBoolean("isError"))
        assertEquals("updated session content", testFile.readText())

        // 2. Simulate live mid-session revocation of SAF write permission
        mockDocFile.canWriteVal = false

        // 3. Attempt a second write operation with the unexpired capability token
        val secondRespStr = mcpExecutor.execute(writeReq, staleButUnexpiredToken, SessionState.ACTIVE)
        val secondResp = JSONObject(secondRespStr)

        // Assert that the request is rejected correctly
        assertTrue("Write must be rejected after SAF revocation", secondResp.getBoolean("isError"))
        val contentArr = secondResp.getJSONArray("content")
        val errorText = contentArr.getJSONObject(0).getString("text")
        assertTrue("Error text must contain Authorization rejected", errorText.contains("Authorization rejected"))

        // Confirm that the file content remains unchanged after the rejected attempt
        assertEquals("updated session content", testFile.readText())

        // 4. Verify that the rejection is properly audited with ReasonCode.SAF_REVOKED
        val auditEntries = AuditLog.getEntries()
        assertFalse("Audit log must not be empty", auditEntries.isEmpty())
        
        // Find the SAF_REVOKED audit entry
        val revokedEntry = auditEntries.firstOrNull { it.getString("reasonCode") == "SAF_REVOKED" }
        assertNotNull("Must record an audit entry with reason code SAF_REVOKED", revokedEntry)
        assertEquals("stale_session_1234", revokedEntry?.getString("sessionId"))
        assertTrue(revokedEntry?.getString("details")?.contains("SAF tree does not have write permissions") == true)
    }

    @Test
    fun testSafGrantRevokedMidSession_WithUnexpiredToken_RejectsReadAndLogsAudit() {
        // 1. Activate SAF mode on the policy engine
        policyEngine.isSafModeActive = true
        policyEngine.context = context
        policyEngine.safTreeUri = "content://com.android.externalstorage.documents/tree/primary%3Asaf_cross_phase_tree"
        policyEngine.overrideRootPath = tempDir.toFile().absolutePath

        // Initial state: SAF grant permits both reads and writes
        val mockDocFile = MockDocumentFile(canReadVal = true, canWriteVal = true)
        policyEngine.documentFileResolver = { _, _ -> mockDocFile }

        // Setup a read request
        val readReq = JSONObject().apply {
            put("method", "read_file")
            put("params", JSONObject().apply {
                put("path", testFile.absolutePath)
            })
        }.toString()

        // Confirm reading works initially
        val initialRespStr = mcpExecutor.execute(readReq, staleButUnexpiredToken, SessionState.ACTIVE)
        val initialResp = JSONObject(initialRespStr)
        assertFalse("Initial read should succeed", initialResp.getBoolean("isError"))

        // 2. Simulate live mid-session revocation of SAF read permission
        mockDocFile.canReadVal = false

        // 3. Attempt to read again with the unexpired capability token
        val secondRespStr = mcpExecutor.execute(readReq, staleButUnexpiredToken, SessionState.ACTIVE)
        val secondResp = JSONObject(secondRespStr)

        // Assert that the request is rejected correctly
        assertTrue("Read must be rejected after SAF revocation", secondResp.getBoolean("isError"))
        val contentArr = secondResp.getJSONArray("content")
        val errorText = contentArr.getJSONObject(0).getString("text")
        assertTrue("Error text must contain Authorization rejected", errorText.contains("Authorization rejected"))

        // 4. Verify SAF_REVOKED is audited
        val auditEntries = AuditLog.getEntries()
        val revokedEntry = auditEntries.firstOrNull { it.getString("reasonCode") == "SAF_REVOKED" }
        assertNotNull("Must record an audit entry with reason code SAF_REVOKED", revokedEntry)
        assertTrue(revokedEntry?.getString("details")?.contains("SAF tree does not have read permissions") == true)
    }
}
