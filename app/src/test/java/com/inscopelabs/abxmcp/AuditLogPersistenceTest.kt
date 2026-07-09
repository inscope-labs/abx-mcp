package com.inscopelabs.abxmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.core.audit.ReasonCode
import com.inscopelabs.abxmcp.core.keystore.KeyStoreEnvironment
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditLogPersistenceTest {

    @Test
    fun testAuditLogSurvivesProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyStoreManager = KeyStoreManager(context, KeyStoreEnvironment.TEST_FALLBACK)

        // 1. Initialize and clear AuditLog
        AuditLog.initialize(context, keyStoreManager)
        AuditLog.clear()

        // Verify it starts empty
        assertTrue(AuditLog.getEntries().isEmpty())

        // 2. Record simulated rejections
        AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, "session_first_123", "Session was expired")
        AuditLog.recordRejection(ReasonCode.PATH_OUT_OF_BOUNDS, "session_first_123", "Disallowed directory access")

        // 3. Confirm items are correctly saved and fetched
        val originalEntries = AuditLog.getEntries()
        assertEquals(2, originalEntries.size)
        assertEquals("SESSION_EXPIRED", originalEntries[0].getString("reasonCode"))
        assertEquals("PATH_OUT_OF_BOUNDS", originalEntries[1].getString("reasonCode"))

        // Print physical evidence of file path as requested by User Goal 3
        val fileDir = context.filesDir
        val physicalFile = File(fileDir, "audit_log.jsonl")
        println("[EVIDENCE] Physical Audit Log JSONL File Location: ${physicalFile.absolutePath}")
        println("[EVIDENCE] Log File Contents:\n${physicalFile.readText()}")
        assertTrue(physicalFile.exists())

        // 4. Discard in-memory state simulating sudden process death/restart
        AuditLog.simulateProcessDeathForTest()

        // 5. Verify the state in AuditLog is cleared after process death
        assertTrue(AuditLog.getEntries().isEmpty())

        // 6. Re-initialize from the same persistence context
        AuditLog.initialize(context, keyStoreManager)

        // 7. Verify entries are correctly read back from disk persistence
        val loadedEntries = AuditLog.getEntries()
        assertEquals(2, loadedEntries.size)
        assertEquals("SESSION_EXPIRED", loadedEntries[0].getString("reasonCode"))
        assertEquals("PATH_OUT_OF_BOUNDS", loadedEntries[1].getString("reasonCode"))
        assertEquals("session_first_123", loadedEntries[0].getString("sessionId"))
        assertEquals("session_first_123", loadedEntries[1].getString("sessionId"))
    }
}
