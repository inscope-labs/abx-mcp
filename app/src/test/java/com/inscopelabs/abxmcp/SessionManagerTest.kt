package com.inscopelabs.abxmcp

import com.inscopelabs.abxmcp.core.session.SessionManager
import com.inscopelabs.abxmcp.core.session.SessionManagerImpl
import com.inscopelabs.abxmcp.core.session.SessionState
import com.inscopelabs.abxmcp.core.session.UserGesture
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Constructor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionManagerTest {

    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        sessionManager = SessionManagerImpl()
    }

    @Test
    fun testInitialStateIsInactive() {
        assertEquals(SessionState.INACTIVE, sessionManager.getState())
    }

    @Test
    fun testLegalTransition_InactiveToActive() {
        val started = sessionManager.startSession(UserGesture.LocalButtonPress)
        assertTrue("Session should start successfully with a valid LocalButtonPress", started)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())
    }

    @Test
    fun testLegalTransition_ActiveToInactive() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        val stopped = sessionManager.stopSession()
        assertTrue("Session should stop successfully", stopped)
        assertEquals(SessionState.INACTIVE, sessionManager.getState())
    }

    @Test
    fun testLegalTransition_ActiveToExpired() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        val expired = sessionManager.expireSession()
        assertTrue("Session should expire successfully", expired)
        assertEquals(SessionState.EXPIRED, sessionManager.getState())
    }

    @Test
    fun testLegalTransition_ActiveToRevoked() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        val revoked = sessionManager.revokeSession()
        assertTrue("Session should revoke successfully", revoked)
        assertEquals(SessionState.REVOKED, sessionManager.getState())
    }

    @Test
    fun testLegalTransition_ExpiredToActive() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        sessionManager.expireSession()
        assertEquals(SessionState.EXPIRED, sessionManager.getState())

        // Reactivate with a new gesture
        val restarted = sessionManager.startSession(UserGesture.LocalButtonPress)
        assertTrue("Expired session should reactivate with a valid LocalButtonPress gesture", restarted)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())
    }

    @Test
    fun testIllegalTransition_InactiveToInactive() {
        assertThrows(IllegalStateException::class.java) {
            sessionManager.stopSession()
        }
    }

    @Test
    fun testIllegalTransition_InactiveToExpired() {
        assertThrows(IllegalStateException::class.java) {
            sessionManager.expireSession()
        }
    }

    @Test
    fun testIllegalTransition_InactiveToRevoked() {
        assertThrows(IllegalStateException::class.java) {
            sessionManager.revokeSession()
        }
    }

    @Test
    fun testIllegalTransition_ActiveToActive() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertThrows(IllegalStateException::class.java) {
            sessionManager.startSession(UserGesture.LocalButtonPress)
        }
    }

    @Test
    fun testIllegalTransition_ExpiredToActive_WithoutNewGesture() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        sessionManager.expireSession()

        // Attempting to start with a non-LocalButtonPress gesture (e.g. NotificationAction)
        val started = sessionManager.startSession(UserGesture.NotificationAction)
        assertFalse("Should fail to start session from EXPIRED with NotificationAction", started)
        assertEquals(SessionState.EXPIRED, sessionManager.getState())
    }

    @Test
    fun testReactivateExpiredSessionResetsTtl() {
        // Start session and set TTL to 0 to simulate expiration
        sessionManager.startSession(UserGesture.LocalButtonPress)
        sessionManager.setSessionTtl(0)
        sessionManager.expireSession()

        // Start session again after prior EXPIRED session
        val started = sessionManager.startSession(UserGesture.LocalButtonPress)
        assertTrue(started)
        
        // Stale TTL would be 0 or inherited from prior session. Default TTL should be 300.
        assertEquals("Reactivated session must reset the TTL to the default value (300)", 300, sessionManager.getSessionTtl())
    }

    @Test
    fun testNotificationActionExtendsActiveSessionButCannotStartSession() {
        // Confirm NotificationAction cannot start a session from INACTIVE
        val startedFromInactive = sessionManager.startSession(UserGesture.NotificationAction)
        assertFalse("NotificationAction cannot start a session from INACTIVE", startedFromInactive)

        // Start session normally with LocalButtonPress
        sessionManager.startSession(UserGesture.LocalButtonPress)
        sessionManager.setSessionTtl(100)

        // Try extending with an invalid or incorrect gesture (like local press if restricted, or we check notification)
        // Let's extend with NotificationAction
        val extended = (sessionManager as SessionManagerImpl).extendSession(UserGesture.NotificationAction, 150)
        assertTrue("NotificationAction must successfully extend an ACTIVE session", extended)
        assertEquals("TTL should be increased by extension duration", 250, sessionManager.getSessionTtl())

        // Stop session and confirm NotificationAction cannot extend inactive session
        sessionManager.stopSession()
        val extendedInactive = (sessionManager as SessionManagerImpl).extendSession(UserGesture.NotificationAction, 150)
        assertFalse("NotificationAction cannot extend an inactive session", extendedInactive)
    }

    @Test
    fun testTerminalState_RevokedCannotBeReactivated() {
        sessionManager.startSession(UserGesture.LocalButtonPress)
        sessionManager.revokeSession()
        assertEquals(SessionState.REVOKED, sessionManager.getState())

        assertThrows(IllegalStateException::class.java) {
            sessionManager.startSession(UserGesture.LocalButtonPress)
        }
    }

    /**
     * Simulated Remote Message/Mock/Adversary Rejection Test.
     * We attempt to bypass security by creating a fake or mocked UserGesture
     * (or simulating deserialization / reflection), and try to transition the state
     * to ACTIVE. The SessionManager must reject it and remain INACTIVE.
     */
    @Test
    fun testRemoteTriggerRejection_MockAndReflection() {
        // 1. Reflection attempt to instantiate a copy of LocalButtonPress
        val reflectedGesture = try {
            val constructor = UserGesture.LocalButtonPress::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance() as UserGesture
        } catch (e: Exception) {
            null
        }

        if (reflectedGesture != null) {
            val startedWithReflection = sessionManager.startSession(reflectedGesture)
            assertFalse("SessionManager must reject reflected/re-created LocalButtonPress object", startedWithReflection)
            assertEquals("State must remain INACTIVE when trigger is reflected", SessionState.INACTIVE, sessionManager.getState())
        }

        // 2. Unsafe allocation attempt to bypass constructor and create a duplicate instance of LocalButtonPress
        val unsafeGesture = try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            allocateMethod.invoke(unsafe, UserGesture.LocalButtonPress::class.java) as UserGesture
        } catch (e: Exception) {
            null
        }

        if (unsafeGesture != null) {
            val startedWithUnsafe = sessionManager.startSession(unsafeGesture)
            assertFalse("SessionManager must reject Unsafe-allocated duplicate of LocalButtonPress", startedWithUnsafe)
            assertEquals("State must remain INACTIVE when trigger is Unsafe-allocated", SessionState.INACTIVE, sessionManager.getState())
        }
    }
}
