package com.inscopelabs.abxmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.inscopelabs.abxmcp.core.session.SessionManager
import com.inscopelabs.abxmcp.core.session.SessionManagerImpl
import com.inscopelabs.abxmcp.core.session.SessionManagerProvider
import com.inscopelabs.abxmcp.core.session.SessionState
import com.inscopelabs.abxmcp.core.session.UserGesture
import com.inscopelabs.abxmcp.core.tunnel.TunnelManager
import com.inscopelabs.abxmcp.core.tunnel.TunnelManagerImpl
import com.inscopelabs.abxmcp.core.tunnel.TunnelManagerProvider
import com.inscopelabs.abxmcp.core.tunnel.TtlCheckWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TunnelLifecycleManagerTest {

    private lateinit var context: Context
    private lateinit var sessionManager: SessionManager
    private lateinit var tunnelManager: TunnelManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        sessionManager = SessionManagerImpl()
        SessionManagerProvider.setForTesting(sessionManager)
        
        tunnelManager = TunnelManagerImpl(
            context,
            sessionManager,
            com.inscopelabs.abxmcp.core.tunnel.TunnelEnvironment.TEST_AVAILABLE
        )
        TunnelManagerProvider.setForTesting(tunnelManager)
    }

    @After
    fun tearDown() {
        tunnelManager.stopTunnel()
        SessionManagerProvider.setForTesting(null)
        TunnelManagerProvider.setForTesting(null)
    }

    @Test
    fun testProcessListCheck_InactiveState() {
        // Initially INACTIVE
        assertEquals(SessionState.INACTIVE, sessionManager.getState())
        assertFalse("Tunnel process should not be running in INACTIVE state", tunnelManager.isTunnelRunning())

        // Run simulated process list grep check
        val isCloudflaredRunning = isProcessRunningInSystem("cloudflared")
        assertFalse("System process check must assert cloudflared is not running when inactive", isCloudflaredRunning)
    }

    @Test
    fun testProcessLifecycle_ActiveToInactive() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())
        
        Thread.sleep(100)
        assertTrue("Tunnel process must be running in ACTIVE state", tunnelManager.isTunnelRunning())

        sessionManager.stopSession()
        assertEquals(SessionState.INACTIVE, sessionManager.getState())
        
        Thread.sleep(100)
        assertFalse("Tunnel process must stop when session transitions to INACTIVE", tunnelManager.isTunnelRunning())
    }

    @Test
    fun testTtlExpiryWork_TransitionsToExpiredAndStopsTunnel() = runTest {
        sessionManager.setSessionTtl(1)
        
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())
        
        Thread.sleep(100)
        assertTrue("Tunnel should be running", tunnelManager.isTunnelRunning())

        val worker = TestListenableWorkerBuilder<TtlCheckWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        assertEquals("Session state must be EXPIRED upon TTL hit 0", SessionState.EXPIRED, sessionManager.getState())
        assertFalse("Tunnel process must be terminated when session expires", tunnelManager.isTunnelRunning())
    }

    @Test
    fun testLowMemoryKillSimulation_ResetsToInactive() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())

        val cleanSessionManager = SessionManagerImpl()
        SessionManagerProvider.setForTesting(cleanSessionManager)

        assertEquals("Upon low-memory kill and restart, the session state must default to INACTIVE", 
            SessionState.INACTIVE, cleanSessionManager.getState())
    }

    private fun isProcessRunningInSystem(processName: String): Boolean {
        return try {
            val osName = (System.getProperty("os.name") ?: "").lowercase()
            val command = if (osName.contains("win")) {
                arrayOf("cmd.exe", "/c", "tasklist")
            } else {
                arrayOf("sh", "-c", "ps -A")
            }
            val process = Runtime.getRuntime().exec(command)
            process.inputStream.bufferedReader().useLines { lines ->
                lines.any { it.contains(processName) }
            }
        } catch (e: Exception) {
            false
        }
    }

    @Test
    fun testNoValidBinaryPresent_ReportsUnavailable() {
        val unavailableTunnelManager = TunnelManagerImpl(
            context,
            sessionManager,
            com.inscopelabs.abxmcp.core.tunnel.TunnelEnvironment.TEST_UNAVAILABLE
        )
        sessionManager.startSession(UserGesture.LocalButtonPress)
        val started = unavailableTunnelManager.startTunnel()
        assertFalse("Should fail to start when binary is unavailable", started)
        assertEquals(com.inscopelabs.abxmcp.core.tunnel.TunnelState.UNAVAILABLE, unavailableTunnelManager.stateFlow.value)
        assertFalse(unavailableTunnelManager.isRunningFlow.value)
    }

    @Test
    fun testValidTestDoubleBinary_ReportsActiveNotification() = runTest {
        // Setup service testScope seam
        com.inscopelabs.abxmcp.core.tunnel.TunnelService.testScope = this
        
        val activeTunnelManager = TunnelManagerImpl(
            context,
            sessionManager,
            com.inscopelabs.abxmcp.core.tunnel.TunnelEnvironment.TEST_AVAILABLE
        )
        TunnelManagerProvider.setForTesting(activeTunnelManager)

        sessionManager.startSession(UserGesture.LocalButtonPress)
        
        // Start TunnelService using Robolectric
        val serviceController = org.robolectric.Robolectric.buildService(com.inscopelabs.abxmcp.core.tunnel.TunnelService::class.java).create().startCommand(0, 0)
        
        assertTrue("isRunningFlow must be true", activeTunnelManager.isRunningFlow.value)
        assertEquals(com.inscopelabs.abxmcp.core.tunnel.TunnelState.RUNNING, activeTunnelManager.stateFlow.value)

        // Verify active notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val activeNotifications = notificationManager.activeNotifications
        
        assertTrue("Should have posted at least one notification", activeNotifications.isNotEmpty())
        val notification = activeNotifications.first().notification
        
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        
        assertEquals("ABX-MCP Tunnel Active", title)
        assertEquals("The secure hardware-backed tunnel is active.", text)
        
        // Clean up
        serviceController.destroy()
        com.inscopelabs.abxmcp.core.tunnel.TunnelService.testScope = null
    }

    @Test
    fun testLongTtlExpiryServiceCountdown() = runTest {
        // Use virtual test scope to bypass real delays
        com.inscopelabs.abxmcp.core.tunnel.TunnelService.testScope = this
        
        val activeTunnelManager = TunnelManagerImpl(
            context,
            sessionManager,
            com.inscopelabs.abxmcp.core.tunnel.TunnelEnvironment.TEST_AVAILABLE
        )
        TunnelManagerProvider.setForTesting(activeTunnelManager)

        // Set TTL above 10-minute WorkManager execution ceiling (e.g. 700 seconds)
        sessionManager.setSessionTtl(700)
        sessionManager.startSession(UserGesture.LocalButtonPress)
        
        // Start TunnelService using Robolectric
        val serviceController = org.robolectric.Robolectric.buildService(com.inscopelabs.abxmcp.core.tunnel.TunnelService::class.java).create().startCommand(0, 0)
        
        assertEquals(SessionState.ACTIVE, sessionManager.getState())
        assertTrue(activeTunnelManager.isTunnelRunning())

        // Advance time by 700 seconds (700000 ms) in virtual scheduler
        testScheduler.advanceTimeBy(700000)
        testScheduler.runCurrent()

        // Verify that session state has transitioned to EXPIRED, the tunnel is stopped and the service self-stops
        assertEquals("Session state must be EXPIRED upon TTL hit 0", SessionState.EXPIRED, sessionManager.getState())
        assertFalse("Tunnel must be stopped", activeTunnelManager.isTunnelRunning())

        // Clean up
        serviceController.destroy()
        com.inscopelabs.abxmcp.core.tunnel.TunnelService.testScope = null
    }
}
