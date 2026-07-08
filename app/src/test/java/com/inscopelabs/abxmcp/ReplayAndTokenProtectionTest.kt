package com.inscopelabs.abxmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.core.keystore.TokenIssuer
import com.inscopelabs.abxmcp.core.keystore.TokenIssuerImpl
import com.inscopelabs.abxmcp.core.session.Nonce
import com.inscopelabs.abxmcp.core.session.ReplayProtection
import com.inscopelabs.abxmcp.core.session.ReplayProtectionImpl
import com.inscopelabs.abxmcp.core.session.SessionManager
import com.inscopelabs.abxmcp.core.session.SessionManagerImpl
import com.inscopelabs.abxmcp.core.session.SessionState
import com.inscopelabs.abxmcp.core.session.UserGesture
import com.inscopelabs.abxmcp.core.session.ValidationResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReplayAndTokenProtectionTest {

    private lateinit var context: Context
    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var tokenIssuer: TokenIssuer
    private lateinit var sessionManager: SessionManager
    private lateinit var replayProtection: ReplayProtection

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyStoreManager = KeyStoreManager(context)
        tokenIssuer = TokenIssuerImpl(keyStoreManager)
        
        sessionManager = SessionManagerImpl()
        replayProtection = ReplayProtectionImpl(sessionManager, windowSizeMs = 30000L) // 30 seconds window
    }

    /**
     * Unit test: Tamper with a generated token (flip a byte/character). Verification must fail.
     */
    @Test
    fun testTokenTampering_SignatureVerificationFails() {
        val sessionId = "session_abc_123"
        val expiry = System.currentTimeMillis() + 600000L
        val allowedOperations = listOf("read", "write")
        val allowedRoots = listOf("/foo", "/bar")
        val nonceSeed = "seed_xyz"

        val originalToken = tokenIssuer.issueToken(
            sessionId = sessionId,
            expiry = expiry,
            allowedOperations = allowedOperations,
            allowedRoots = allowedRoots,
            nonceSeed = nonceSeed
        )

        // Verify the original token is completely valid
        val parsedOriginal = tokenIssuer.verifyAndParseToken(originalToken)
        assertNotNull("Original issued token must be valid", parsedOriginal)
        assertEquals(sessionId, parsedOriginal?.sessionId)
        assertEquals(allowedOperations, parsedOriginal?.allowedOperations)

        // 1. Tamper by changing a single character in the payload part of the token
        val parts = originalToken.split(".")
        assertEquals(2, parts.size)
        val payload = parts[0]
        val signature = parts[1]

        // Modify the first character of the payload (e.g. if it is 'e', change to 'f')
        val tamperedPayload = if (payload.startsWith("a")) "b" + payload.substring(1) else "a" + payload.substring(1)
        val tamperedToken1 = "$tamperedPayload.$signature"

        val parsedTampered1 = tokenIssuer.verifyAndParseToken(tamperedToken1)
        assertNull("Verification must fail when payload is tampered with", parsedTampered1)

        // 2. Tamper by changing a single character in the signature part of the token
        val tamperedSignature = if (signature.startsWith("x")) "y" + signature.substring(1) else "x" + signature.substring(1)
        val tamperedToken2 = "$payload.$tamperedSignature"

        val parsedTampered2 = tokenIssuer.verifyAndParseToken(tamperedToken2)
        assertNull("Verification must fail when signature is tampered with", parsedTampered2)
    }

    /**
     * Instrumented (Adversarial): Replay the exact same request twice within an active session.
     * The second request must be rejected (duplicate nonce).
     */
    @Test
    fun testReplayAttack_DuplicateNonceRejected() {
        // Start session to make it ACTIVE
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())

        val nonce = Nonce("nonce_99999")
        val currentTime = System.currentTimeMillis()

        // First attempt: should succeed
        val result1 = replayProtection.validateRequest(nonce, currentTime, currentTime)
        assertTrue("First request with unique nonce must succeed", result1 is ValidationResult.Success)

        // Second attempt (replay): should be rejected as DuplicateNonce
        val result2 = replayProtection.validateRequest(nonce, currentTime, currentTime)
        assertTrue("Replay attempt with same nonce must be rejected", result2 is ValidationResult.DuplicateNonce)
    }

    /**
     * Instrumented: Replay a valid request immediately after session expiry.
     * It must reject on session-state grounds (EXPIRED) before even hitting the nonce check.
     */
    @Test
    fun testReplayAfterExpiry_RejectsOnSessionStateBeforeNonceCheck() {
        // Start session to make it ACTIVE
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())

        // Expire the session
        sessionManager.expireSession()
        assertEquals(SessionState.EXPIRED, sessionManager.getState())

        val nonce = Nonce("nonce_expired_test")
        val currentTime = System.currentTimeMillis()

        // Validate request: should reject on session state grounds (EXPIRED)
        val result = replayProtection.validateRequest(nonce, currentTime, currentTime)
        
        assertTrue("Must reject with InvalidSessionState", result is ValidationResult.InvalidSessionState)
        val stateResult = result as ValidationResult.InvalidSessionState
        assertEquals(SessionState.EXPIRED, stateResult.state)
    }

    /**
     * Boundary test: Send a request with timestamp exactly 1ms outside the acceptable window (configurable, e.g., 30 seconds).
     * Must reject.
     */
    @Test
    fun testBoundary_TimestampOutsideAcceptableWindow_Rejects() {
        // Start session to make it ACTIVE
        sessionManager.startSession(UserGesture.LocalButtonPress)
        assertEquals(SessionState.ACTIVE, sessionManager.getState())

        // Set acceptable window to 30,000ms (30 seconds)
        replayProtection.setWindowSizeMs(30000L)

        val nonceVal = "nonce_boundary_test"
        val currentTime = 1000000L

        // 1. Exactly at the positive boundary: currentTime + 30,000ms (Should succeed)
        val resultInUpperBoundary = replayProtection.validateRequest(Nonce("${nonceVal}_1"), currentTime + 30000L, currentTime)
        assertTrue("Exactly on 30s upper boundary must succeed", resultInUpperBoundary is ValidationResult.Success)

        // 2. Exactly 1ms outside positive boundary: currentTime + 30,001ms (Must reject)
        val resultOutUpperBoundary = replayProtection.validateRequest(Nonce("${nonceVal}_2"), currentTime + 30001L, currentTime)
        assertTrue("1ms outside upper boundary must be rejected", resultOutUpperBoundary is ValidationResult.OutsideTimestampWindow)

        // 3. Exactly at the negative boundary: currentTime - 30,000ms (Should succeed)
        val resultInLowerBoundary = replayProtection.validateRequest(Nonce("${nonceVal}_3"), currentTime - 30000L, currentTime)
        assertTrue("Exactly on 30s lower boundary must succeed", resultInLowerBoundary is ValidationResult.Success)

        // 4. Exactly 1ms outside negative boundary: currentTime - 30,001ms (Must reject)
        val resultOutLowerBoundary = replayProtection.validateRequest(Nonce("${nonceVal}_4"), currentTime - 30001L, currentTime)
        assertTrue("1ms outside lower boundary must be rejected", resultOutLowerBoundary is ValidationResult.OutsideTimestampWindow)
    }
}
