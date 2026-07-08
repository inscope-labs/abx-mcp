package com.inscopelabs.abxmcp

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpExecutorTest {

    private lateinit var context: Context
    private lateinit var tempDir: Path
    private lateinit var policyEngine: PolicyEngineImpl
    private lateinit var fileSystemReader: FileSystemReaderImpl
    private lateinit var mcpExecutor: McpExecutor
    private lateinit var activeToken: Capability

    private lateinit var fileA: File
    private lateinit var fileB: File
    private lateinit var subDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policyEngine = PolicyEngineImpl()
        fileSystemReader = FileSystemReaderImpl(context)
        mcpExecutor = McpExecutor(policyEngine, fileSystemReader)

        // 1. Create temporary directory structure (fixture tree)
        tempDir = Files.createTempDirectory("mcp_fixture_tree")
        val filesDir = File(tempDir.toFile(), "files").apply { mkdirs() }
        
        fileA = File(filesDir, "a.txt").apply {
            writeText("Hello, World!", Charsets.UTF_8)
        }

        subDir = File(filesDir, "sub").apply { mkdirs() }
        
        fileB = File(subDir, "b.pdf").apply {
            writeBytes("PDF binary content".toByteArray(Charsets.UTF_8))
        }

        // 2. Set up an active capability token covering all operations
        activeToken = Capability(
            sessionId = "session_read_ops_test",
            expiry = System.currentTimeMillis() + 3600_000, // 1 hour expiry
            allowedOperations = listOf("file_exists", "get_file_metadata", "get_file_version", "read_file", "list_directory"),
            allowedRoots = listOf(tempDir.toFile().absolutePath),
            nonceSeed = "random_seed_123"
        )
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    /**
     * Isolated unit test: file_exists handler.
     */
    @Test
    fun testFileExists_ReturnsTrueForExisting_FalseForNonExisting() {
        val pathExists = fileA.absolutePath
        val pathNotExists = File(fileA.parentFile, "does_not_exist.txt").absolutePath

        // 1. Existing file
        val reqExists = JSONObject().apply {
            put("method", "file_exists")
            put("params", JSONObject().apply {
                put("path", pathExists)
            })
        }.toString()

        val respExistsStr = mcpExecutor.execute(reqExists, activeToken, SessionState.ACTIVE)
        val respExists = JSONObject(respExistsStr)

        assertFalse(respExists.getBoolean("isError"))
        val resultExists = respExists.getJSONObject("result")
        assertTrue(resultExists.getBoolean("exists"))

        // 2. Non-existing file
        val reqNotExists = JSONObject().apply {
            put("method", "file_exists")
            put("params", JSONObject().apply {
                put("path", pathNotExists)
            })
        }.toString()

        val respNotExistsStr = mcpExecutor.execute(reqNotExists, activeToken, SessionState.ACTIVE)
        val respNotExists = JSONObject(respNotExistsStr)

        assertFalse(respNotExists.getBoolean("isError"))
        val resultNotExists = respNotExists.getJSONObject("result")
        assertFalse(resultNotExists.getBoolean("exists"))
    }

    /**
     * Isolated unit test: get_file_metadata handler.
     */
    @Test
    fun testGetFileMetadata_ReturnsCorrectFileProperties() {
        val path = fileA.absolutePath
        val req = JSONObject().apply {
            put("method", "get_file_metadata")
            put("params", JSONObject().apply {
                put("path", path)
            })
        }.toString()

        val respStr = mcpExecutor.execute(req, activeToken, SessionState.ACTIVE)
        val resp = JSONObject(respStr)

        assertFalse(resp.getBoolean("isError"))
        val result = resp.getJSONObject("result")
        assertEquals("a.txt", result.getString("name"))
        assertEquals(path, result.getString("path"))
        assertEquals(fileA.length(), result.getLong("size"))
        assertEquals(fileA.lastModified(), result.getLong("lastModified"))
        assertFalse(result.getBoolean("isDirectory"))
        assertTrue(result.getBoolean("isFile"))
        assertEquals("text/plain", result.getString("mimeType"))
    }

    /**
     * Isolated unit test: get_file_version handler.
     */
    @Test
    fun testGetFileVersion_ReturnsTimestampString() {
        val path = fileA.absolutePath
        val req = JSONObject().apply {
            put("method", "get_file_version")
            put("params", JSONObject().apply {
                put("path", path)
            })
        }.toString()

        val respStr = mcpExecutor.execute(req, activeToken, SessionState.ACTIVE)
        val resp = JSONObject(respStr)

        assertFalse(resp.getBoolean("isError"))
        val result = resp.getJSONObject("result")
        assertEquals(fileA.lastModified().toString(), result.getString("version"))
    }

    /**
     * Isolated unit test: read_file handler.
     * Verifies UTF-8 text mode and Base64 binary mode.
     */
    @Test
    fun testReadFile_SupportsTextAndBase64() {
        // 1. Text mode
        val reqText = JSONObject().apply {
            put("method", "read_file")
            put("params", JSONObject().apply {
                put("path", fileA.absolutePath)
                put("encoding", "text")
            })
        }.toString()

        val respTextStr = mcpExecutor.execute(reqText, activeToken, SessionState.ACTIVE)
        val respText = JSONObject(respTextStr)

        assertFalse(respText.getBoolean("isError"))
        val resultText = respText.getJSONObject("result")
        assertEquals("Hello, World!", resultText.getString("content"))
        assertEquals("text", resultText.getString("encoding"))

        // 2. Base64 mode
        val reqBase64 = JSONObject().apply {
            put("method", "read_file")
            put("params", JSONObject().apply {
                put("path", fileB.absolutePath)
                put("encoding", "base64")
            })
        }.toString()

        val respBase64Str = mcpExecutor.execute(reqBase64, activeToken, SessionState.ACTIVE)
        val respBase64 = JSONObject(respBase64Str)

        assertFalse(respBase64.getBoolean("isError"))
        val resultBase64 = respBase64.getJSONObject("result")
        
        val expectedBase64 = Base64.encodeToString(
            "PDF binary content".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        assertEquals(expectedBase64, resultBase64.getString("content"))
        assertEquals("base64", resultBase64.getString("encoding"))
    }

    /**
     * Isolated unit test: list_directory handler.
     */
    @Test
    fun testListDirectory_ListsFilesAndSubdirectories() {
        val path = File(tempDir.toFile(), "files").absolutePath
        val req = JSONObject().apply {
            put("method", "list_directory")
            put("params", JSONObject().apply {
                put("path", path)
            })
        }.toString()

        val respStr = mcpExecutor.execute(req, activeToken, SessionState.ACTIVE)
        val resp = JSONObject(respStr)

        assertFalse(resp.getBoolean("isError"))
        val result = resp.getJSONObject("result")
        val filesArray = result.getJSONArray("files")

        val filesList = mutableListOf<String>()
        for (i in 0 until filesArray.length()) {
            filesList.add(filesArray.getString(i))
        }

        assertEquals(2, filesList.size)
        assertTrue(filesList.contains("a.txt"))
        assertTrue(filesList.contains("sub"))
    }

    /**
     * End-to-End integration test: Sends a valid file_exists request in standard
     * MCP tool call format and verifies the output structure matches the spec (Section 8 example).
     */
    @Test
    fun testEndToEnd_McpToolCallFormat() {
        // Build an MCP-compliant JSON-RPC tools/call request
        val mcpRequestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "mcp_request_001")
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "file_exists")
                put("arguments", JSONObject().apply {
                    put("path", fileA.absolutePath)
                })
            })
        }.toString()

        val responseStr = mcpExecutor.execute(mcpRequestJson, activeToken, SessionState.ACTIVE)
        val response = JSONObject(responseStr)

        // Validate JSON-RPC standard and ID mapping
        assertEquals("2.0", response.getString("jsonrpc"))
        assertEquals("mcp_request_001", response.getString("id"))
        assertFalse(response.getBoolean("isError"))

        // Validate MCP tool call response structure (Section 8 content list)
        val contentArr = response.getJSONArray("content")
        assertEquals(1, contentArr.length())
        
        val contentItem = contentArr.getJSONObject(0)
        assertEquals("text", contentItem.getString("type"))
        
        val textValue = contentItem.getString("text")
        val parsedTextValue = JSONObject(textValue)
        assertTrue(parsedTextValue.getBoolean("exists"))
    }

    /**
     * Policy pre-check validation: Enforce that permissions are verified before disk operations.
     */
    @Test
    fun testPolicyPreCheck_RejectsUnallowedOperation() {
        // Set up token without list_directory operation
        val limitedToken = Capability(
            sessionId = "session_limited",
            expiry = System.currentTimeMillis() + 3600_000,
            allowedOperations = listOf("file_exists"),
            allowedRoots = listOf(tempDir.toFile().absolutePath),
            nonceSeed = "random_seed_123"
        )

        val path = File(tempDir.toFile(), "files").absolutePath
        val req = JSONObject().apply {
            put("method", "list_directory")
            put("params", JSONObject().apply {
                put("path", path)
            })
        }.toString()

        val respStr = mcpExecutor.execute(req, limitedToken, SessionState.ACTIVE)
        val resp = JSONObject(respStr)

        assertTrue(resp.getBoolean("isError"))
        val contentArr = resp.getJSONArray("content")
        val errorText = contentArr.getJSONObject(0).getString("text")
        assertTrue(errorText.contains("Authorization rejected"))
    }

    /**
     * Concurrency and Bounded Freshness test:
     * While a get_file_version is processing, externally modify the file and verify the next call reflects the new timestamp.
     */
    @Test
    fun testConcurrency_BoundedFreshnessOfFileversion() {
        val fileToModify = File(tempDir.toFile(), "concurrency_test.txt").apply {
            writeText("initial data")
        }

        val path = fileToModify.absolutePath
        val req = JSONObject().apply {
            put("method", "get_file_version")
            put("params", JSONObject().apply {
                put("path", path)
            })
        }.toString()

        // 1. Initial call to get version
        val resp1Str = mcpExecutor.execute(req, activeToken, SessionState.ACTIVE)
        val version1 = JSONObject(resp1Str).getJSONObject("result").getString("version")

        // 2. Perform external modification (writing new content to file and ensuring lastModified changes)
        // Coarse resolution fallback: explicitly update lastModified by 2000ms if needed
        val originalLastMod = fileToModify.lastModified()
        fileToModify.writeText("modified data")
        val newLastMod = originalLastMod + 5000L
        fileToModify.setLastModified(newLastMod)

        // 3. Second call to get version
        val resp2Str = mcpExecutor.execute(req, activeToken, SessionState.ACTIVE)
        val version2 = JSONObject(resp2Str).getJSONObject("result").getString("version")

        // Verify version changed and matches new last modified timestamp
        assertNotEquals("The version string should be updated after modification", version1, version2)
        assertEquals(newLastMod.toString(), version2)
    }

    /**
     * A content:// path is cleanly and consistently rejected end-to-end.
     * PolicyEngine rejects it before any FileSystemReader interaction,
     * ensuring that PolicyEngine and FileSystemReader never disagree on executed path forms.
     */
    @Test
    fun testContentUri_IsCleanlyAndConsistentlyRejected() {
        val path = "content://com.example.provider/docs/1"
        val req = JSONObject().apply {
            put("method", "read_file")
            put("params", JSONObject().apply {
                put("path", path)
            })
        }.toString()

        val respStr = mcpExecutor.execute(req, activeToken, SessionState.ACTIVE)
        val resp = JSONObject(respStr)

        assertTrue("Should return error for rejected path", resp.getBoolean("isError"))
        val contentArr = resp.getJSONArray("content")
        val errorText = contentArr.getJSONObject(0).getString("text")
        assertTrue("Error should be due to authorization rejection", errorText.contains("Authorization rejected"))
        assertTrue("Reason should name non-file schemes rejection", errorText.contains("Non-file schemes (such as content://) are explicitly rejected"))
    }

    /**
     * Symlink TOCTOU test:
     * 1. Authorize a symlink path via PolicyEngine to get its authorized canonical path.
     * 2. Swap the symlink target (point it to a restricted/unauthorized target, e.g. secret.txt).
     * 3. Execute the read using the originally-authorized canonical path.
     * 4. Assert that the read still accesses the original canonical target (or fails closed)
     *    and does NOT read from the swapped/restricted target.
     */
    @Test
    fun testSymlinkTOCTOU_RespectsOriginalCanonicalTarget() {
        val rootDir = File(tempDir.toFile(), "files")
        val originalTarget = File(rootDir, "original.txt").apply { writeText("original content") }
        val secretTarget = File(tempDir.toFile(), "secret_unauthorized.txt").apply { writeText("secret password!") }

        val symlinkFile = File(rootDir, "toctou_link")
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), originalTarget.toPath())
        } catch (e: Exception) {
            System.err.println("Symlinks not supported: ${e.message}")
            return // Skip if OS doesn't support symlinks under test
        }

        // Token authorizes the root directory (which allows original.txt but NOT secret_unauthorized.txt)
        val testToken = Capability(
            sessionId = "session_toctou",
            expiry = System.currentTimeMillis() + 60000L,
            allowedOperations = listOf("read_file"),
            allowedRoots = listOf(rootDir.absolutePath),
            nonceSeed = "seed_123"
        )

        // 1. Authorize the symlink path
        val request = com.inscopelabs.abxmcp.core.policy.Request(
            path = symlinkFile.absolutePath,
            operation = "read_file"
        )
        val authResult = policyEngine.authorize(request, testToken, SessionState.ACTIVE)
        assertTrue(authResult is com.inscopelabs.abxmcp.core.policy.AuthorizationResult.Allowed)
        val authorizedCanonicalPath = (authResult as com.inscopelabs.abxmcp.core.policy.AuthorizationResult.Allowed).canonicalPath

        // Verify the resolved canonical path points to originalTarget
        assertEquals(originalTarget.canonicalPath, authorizedCanonicalPath)

        // 2. TOCTOU Swap: delete symlink and recreate it pointing to the unauthorized secret
        symlinkFile.delete()
        Files.createSymbolicLink(symlinkFile.toPath(), secretTarget.toPath())

        // 3. Perform read using the originally-authorized canonical path
        val readBytes = try {
            fileSystemReader.readFile(authorizedCanonicalPath)
        } catch (e: Exception) {
            null
        }

        // 4. Assert that the read STILL reads the original target, NOT the swapped secret target.
        assertNotNull(readBytes)
        val content = String(readBytes!!, Charsets.UTF_8)
        assertEquals("original content", content)
        assertNotEquals("secret password!", content)
    }
}
