package com.inscopelabs.abxmcp.boot

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BootGuardTest {

    private lateinit var realContext: Context

    class FailingContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
            throw RuntimeException("Simulated SharedPreferences failure")
        }
    }

    @Before
    fun setUp() {
        realContext = ApplicationProvider.getApplicationContext()
        BootGuard.clear(realContext)
    }

    @Test
    fun testStageSuccessFlow() {
        BootGuard.stageStart("INIT")
        assertFalse(BootGuard.hasFailure(realContext))
        assertNull(BootGuard.currentFailure(realContext))
        BootGuard.stageSuccess("INIT")
        assertFalse(BootGuard.hasFailure(realContext))
    }

    @Test
    fun testRecordAndRetrieveFailure() {
        val testException = RuntimeException("Crash test")
        BootGuard.recordFailure(realContext, "MEMBER_INIT", testException)

        assertTrue(BootGuard.hasFailure(realContext))
        val failure = BootGuard.currentFailure(realContext)
        assertNotNull(failure)
        assertEquals("MEMBER_INIT", failure?.stage)
        assertEquals("Crash test", failure?.message)
        assertTrue(failure?.stackTrace?.contains("RuntimeException") == true)
        assertNotNull(failure?.timestamp)

        // Reset
        BootGuard.clear(realContext)
        assertFalse(BootGuard.hasFailure(realContext))
        assertNull(BootGuard.currentFailure(realContext))
    }

    @Test
    fun testExceptionSwallowingOnRecordFailure() {
        val brokenContext = FailingContext(realContext)

        // recordFailure should swallow SharedPreferences exception and not crash
        val testException = RuntimeException("App crash")
        BootGuard.recordFailure(brokenContext, "STAGE_BROKEN", testException)

        // Should still hold it in memory even if storage failed
        assertTrue(BootGuard.hasFailure(brokenContext))
        val failure = BootGuard.currentFailure(brokenContext)
        assertNotNull(failure)
        assertEquals("STAGE_BROKEN", failure?.stage)
    }

    @Test
    fun testExceptionSwallowingOnHasFailure() {
        val brokenContext = FailingContext(realContext)

        // First, clear memory state by clearing
        BootGuard.clear(null)

        // hasFailure should swallow exception and return false
        val hasFailed = BootGuard.hasFailure(brokenContext)
        assertFalse(hasFailed)
    }

    @Test
    fun testExceptionSwallowingOnCurrentFailure() {
        val brokenContext = FailingContext(realContext)

        // First, clear memory state by clearing
        BootGuard.clear(null)

        // currentFailure should swallow exception and return null
        val failure = BootGuard.currentFailure(brokenContext)
        assertNull(failure)
    }

    @Test
    fun testExceptionSwallowingOnClear() {
        val brokenContext = FailingContext(realContext)

        // clear should swallow exception and not crash
        BootGuard.clear(brokenContext)
    }
}
