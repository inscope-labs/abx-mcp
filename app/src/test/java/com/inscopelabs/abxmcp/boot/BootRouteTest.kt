package com.inscopelabs.abxmcp.boot

import android.app.Activity
import android.content.Intent
import com.inscopelabs.abxmcp.MainActivity
import com.inscopelabs.abxmcp.McpApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BootRouteTest {

    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).get()
        BootGuard.clear(activity.applicationContext)
    }

    @Test
    fun testNoFailureDoesNotRedirect() {
        // Given a healthy system without any registered failure
        assertFalse(BootGuard.hasFailure(activity.applicationContext))

        // When invoking the check
        val redirected = BootRoute.redirectIfNeeded(activity)

        // Then it should return false
        assertFalse(redirected)

        // And the calling activity should not be finished, nor any other intent started
        assertFalse(activity.isFinishing)
        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNull(nextIntent)
    }

    @Test
    fun testFailureTriggersRedirect() {
        // Given an active failure registered in BootGuard
        val testException = RuntimeException("Fatal startup crash")
        BootGuard.recordFailure(activity.applicationContext, "INIT_STAGE", testException)
        assertTrue(BootGuard.hasFailure(activity.applicationContext))

        // When invoking the check
        val redirected = BootRoute.redirectIfNeeded(activity)

        // Then it should return true
        assertTrue(redirected)

        // And the calling activity must be finished
        assertTrue(activity.isFinishing)

        // And an intent to RecoveryActivity must be started with proper flags
        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(RecoveryActivity::class.java.name, nextIntent.component?.className)
        
        val expectedFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
    }

    @Test
    fun testMainActivityDefensiveNullHandling() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val act = controller.get()
        val app = act.application as McpApplication
        
        // Force keyStoreManager to be null
        app.keyStoreManager = null
        BootGuard.clear(app)
        
        // When triggering onCreate
        controller.create()
        
        // Then it should register a failure in BootGuard
        assertTrue(BootGuard.hasFailure(app))
        val failure = BootGuard.currentFailure(app)
        assertNotNull(failure)
        assertTrue(failure!!.stage.contains("keyStoreManager unexpectedly null"))
        
        // And it should redirect to RecoveryActivity and finish MainActivity
        assertTrue(act.isFinishing)
        val nextIntent = shadowOf(act).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(RecoveryActivity::class.java.name, nextIntent.component?.className)
    }
}
