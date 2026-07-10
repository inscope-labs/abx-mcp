package com.inscopelabs.abxmcp.core.policy

import com.inscopelabs.abxmcp.core.session.SessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PolicyEngineTest {

    private lateinit var policyEngine: PolicyEngine
    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        policyEngine = PolicyEngineImpl()
        // Create a temporary directory structure for filesystem operations (symlink resolution)
        tempDir = Files.createTempDirectory("policy_engine_test")
    }

    @After
    fun tearDown() {
        // Clean up temporary files recursively
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testPathTraversal_RejectsEscapingPath() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val capability = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1"
        )

        // Path inside directory
        val validRequest = Request(
            path = rootDir.resolve("report.txt").toString(),
            operation = "read_file"
        )
        val validResult = policyEngine.authorize(validRequest, capability, SessionState.ACTIVE)
        assertTrue("Valid path inside root should be allowed", validResult is AuthorizationResult.Allowed)

        // Traversal request pointing to a path outside allowed root
        val traversalPath = rootDir.resolve("../system/etc/hosts").toString()
        val traversalRequest = Request(
            path = traversalPath,
            operation = "read_file"
        )
        val traversalResult = policyEngine.authorize(traversalRequest, capability, SessionState.ACTIVE)
        assertTrue("Traversal path escaping allowed root must be rejected", traversalResult is AuthorizationResult.Rejected)
    }

    @Test
    fun testSymlinkEscape_RejectsSymlinkTargetOutsideRoot() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val secretDir = tempDir.resolve("secret_data")
        Files.createDirectories(secretDir)
        val secretFile = secretDir.resolve("passwords.txt")
        Files.write(secretFile, listOf("supersecret"))

        // Create a symlink inside the allowed rootDir pointing to secretFile outside rootDir
        val symlinkPath = rootDir.resolve("passwords_link")
        try {
            Files.createSymbolicLink(symlinkPath, secretFile)
        } catch (e: Exception) {
            // If the host platform does not support symlinks (e.g. windows without developer mode), skip gracefully
            System.err.println("Warning: Symbolic links not supported on this platform: ${e.message}")
            return
        }

        val capability = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1"
        )

        // Try to request reading through the symlink
        val symlinkRequest = Request(
            path = symlinkPath.toString(),
            operation = "read_file"
        )

        val symlinkResult = policyEngine.authorize(symlinkRequest, capability, SessionState.ACTIVE)
        assertTrue("Symlink escape attempting to read target outside allowed root must be rejected", symlinkResult is AuthorizationResult.Rejected)
    }

    @Test
    fun testUnicodeNormalization_AllowsBothNfcAndNfdVariations() {
        // café in NFC form (é is \u00e9)
        val nfcRootName = "caf\u00e9"
        val rootDir = tempDir.resolve(nfcRootName)
        Files.createDirectories(rootDir)

        val testFile = rootDir.resolve("test.txt")
        Files.write(testFile, listOf("unicode content"))

        // Create a capability with NFC root path
        val capability = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1"
        )

        // café in NFD form (é decomposed to e + \u0301)
        val nfdFileName = "cafe\u0301"
        val nfdPath = tempDir.resolve(nfdFileName).resolve("test.txt").toString()

        // Validate using NFD path against NFC registered root
        val request = Request(
            path = nfdPath,
            operation = "read_file"
        )

        val result = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue("NFD path variation must match NFC allowed root and be allowed", result is AuthorizationResult.Allowed)
    }

    @Test
    fun testOperationGranularity_RejectsUnsupportedOperation() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val capability = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("get_file_metadata"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1"
        )

        // Allowed operation (get_file_metadata)
        val validRequest = Request(
            path = rootDir.resolve("test.txt").toString(),
            operation = "get_file_metadata"
        )
        val validResult = policyEngine.authorize(validRequest, capability, SessionState.ACTIVE)
        assertTrue("Allowed operation must succeed", validResult is AuthorizationResult.Allowed)

        // Disallowed operation (read_file) on the exact same path
        val invalidRequest = Request(
            path = rootDir.resolve("test.txt").toString(),
            operation = "read_file"
        )
        val invalidResult = policyEngine.authorize(invalidRequest, capability, SessionState.ACTIVE)
        assertTrue("Disallowed operation must be rejected even if path is correct", invalidResult is AuthorizationResult.Rejected)
    }

    @Test
    fun testSessionStatePreCheck_RejectsWhenNotActive() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val capability = Capability(
            sessionId = "session_123",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1"
        )

        val request = Request(
            path = rootDir.resolve("test.txt").toString(),
            operation = "read_file"
        )

        // Try when EXPIRED
        val expiredResult = policyEngine.authorize(request, capability, SessionState.EXPIRED)
        assertTrue("Must be rejected when session is expired", expiredResult is AuthorizationResult.Rejected)

        // Try when INACTIVE
        val inactiveResult = policyEngine.authorize(request, capability, SessionState.INACTIVE)
        assertTrue("Must be rejected when session is inactive", inactiveResult is AuthorizationResult.Rejected)
    }

    @Test
    fun testMaxRequestCountEnforcement() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val capability = Capability(
            sessionId = "session_max_req",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1",
            maxRequestCount = 2
        )

        val request = Request(
            path = rootDir.resolve("test.txt").toString(),
            operation = "read_file"
        )

        // First request: Allowed
        val result1 = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue("First request should be allowed", result1 is AuthorizationResult.Allowed)

        // Second request: Allowed
        val result2 = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue("Second request should be allowed", result2 is AuthorizationResult.Allowed)

        // Third request: Rejected
        val result3 = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue("Third request should be rejected as limit is 2", result3 is AuthorizationResult.Rejected)
    }

    @Test
    fun testMaxRequestCountResetOnNonActiveState() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val capability = Capability(
            sessionId = "session_reset_req",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1",
            maxRequestCount = 1
        )

        val request = Request(
            path = rootDir.resolve("test.txt").toString(),
            operation = "read_file"
        )

        // First request: Allowed
        val result1 = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue(result1 is AuthorizationResult.Allowed)

        // Second request: Rejected
        val result2 = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue(result2 is AuthorizationResult.Rejected)

        // End session (transition to INACTIVE)
        val inactiveResult = policyEngine.authorize(request, capability, SessionState.INACTIVE)
        assertTrue(inactiveResult is AuthorizationResult.Rejected)

        // Try active session again - count should be reset and allowed once more
        val result3 = policyEngine.authorize(request, capability, SessionState.ACTIVE)
        assertTrue("Should be allowed again because state transition to INACTIVE reset the count", result3 is AuthorizationResult.Allowed)
    }

    @Test
    fun testMaxRequestCountUnboundedWhenZeroOrUnset() {
        val rootDir = tempDir.resolve("Documents")
        Files.createDirectories(rootDir)

        val capability = Capability(
            sessionId = "session_unbounded_req",
            expiry = System.currentTimeMillis() + 60000,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.toString()),
            nonceSeed = "seed_1",
            maxRequestCount = 0 // unbounded
        )

        val request = Request(
            path = rootDir.resolve("test.txt").toString(),
            operation = "read_file"
        )

        for (i in 1..10) {
            val result = policyEngine.authorize(request, capability, SessionState.ACTIVE)
            assertTrue("Request $i should be allowed since maxRequestCount is 0", result is AuthorizationResult.Allowed)
        }
    }
}
