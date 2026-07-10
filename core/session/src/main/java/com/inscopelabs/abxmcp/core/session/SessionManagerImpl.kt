package com.inscopelabs.abxmcp.core.session

import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.core.audit.ReasonCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManagerImpl : SessionManager {
    private val _state = MutableStateFlow<SessionState>(SessionState.INACTIVE)
    override val stateFlow: StateFlow<SessionState> = _state.asStateFlow()

    private var _sessionId: String? = null
    override val sessionId: String?
        get() = _sessionId

    private var defaultTtlSeconds: Int = 300
    private var ttlSeconds: Int = 300

    override fun getState(): SessionState = _state.value

    @Synchronized
    override fun getSessionTtl(): Int = ttlSeconds

    @Synchronized
    override fun setSessionTtl(seconds: Int) {
        if (_state.value !is SessionState.ACTIVE) {
            defaultTtlSeconds = seconds
        }
        ttlSeconds = seconds
    }

    @Synchronized
    override fun decrementTtl(amountSeconds: Int): Int {
        ttlSeconds = (ttlSeconds - amountSeconds).coerceAtLeast(0)
        return ttlSeconds
    }

    @Synchronized
    override fun startSession(trigger: UserGesture): Boolean {
        // STRICT Identity Check to reject remote mocks or custom subclasses
        if (trigger !== UserGesture.LocalButtonPress) {
            AuditLog.recordRejection(ReasonCode.OP_NOT_ALLOWED, "unknown", "Session start rejected: invalid trigger")
            // Keep state unchanged
            return false
        }

        val currentState = _state.value
        if (currentState is SessionState.ACTIVE) {
            AuditLog.recordRejection(ReasonCode.OP_NOT_ALLOWED, "unknown", "Cannot start session: already ACTIVE")
            throw IllegalStateException("Cannot start session: already ACTIVE")
        }
        if (currentState is SessionState.REVOKED) {
            AuditLog.recordRejection(ReasonCode.OP_NOT_ALLOWED, "unknown", "Cannot start session: session is REVOKED")
            throw IllegalStateException("Cannot start session: session is REVOKED")
        }

        // Legal transitions: INACTIVE -> ACTIVE, EXPIRED -> ACTIVE
        if (currentState is SessionState.INACTIVE || currentState is SessionState.EXPIRED) {
            _sessionId = "sess_" + java.util.UUID.randomUUID().toString().take(12)
            ttlSeconds = defaultTtlSeconds // Reset TTL to the configured default or explicitly supplied value
            _state.value = SessionState.ACTIVE
            AuditLog.recordSessionApproval(_sessionId!!, "default_enrolled_agent")
            return true
        }

        return false
    }

    @Synchronized
    override fun extendSession(trigger: UserGesture, extensionSeconds: Int): Boolean {
        if (_state.value !is SessionState.ACTIVE) {
            AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, "unknown", "Cannot extend session: state is not ACTIVE")
            return false
        }
        if (trigger !== UserGesture.NotificationAction) {
            AuditLog.recordRejection(ReasonCode.OP_NOT_ALLOWED, "unknown", "Cannot extend session: invalid gesture trigger")
            return false
        }
        ttlSeconds += extensionSeconds
        return true
    }

    @Synchronized
    override fun stopSession(): Boolean {
        val currentState = _state.value
        if (currentState !is SessionState.ACTIVE) {
            AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, "unknown", "Cannot stop session: session is not ACTIVE")
            throw IllegalStateException("Cannot stop session: session is not ACTIVE (current: ${currentState::class.simpleName})")
        }
        _state.value = SessionState.INACTIVE
        return true
    }

    @Synchronized
    override fun expireSession(): Boolean {
        val currentState = _state.value
        if (currentState !is SessionState.ACTIVE) {
            AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, "unknown", "Cannot expire session: session is not ACTIVE")
            throw IllegalStateException("Cannot expire session: session is not ACTIVE (current: ${currentState::class.simpleName})")
        }
        _state.value = SessionState.EXPIRED
        return true
    }

    @Synchronized
    override fun revokeSession(): Boolean {
        val currentState = _state.value
        if (currentState !is SessionState.ACTIVE) {
            AuditLog.recordRejection(ReasonCode.SESSION_EXPIRED, "unknown", "Cannot revoke session: session is not ACTIVE")
            throw IllegalStateException("Cannot revoke session: session is not ACTIVE (current: ${currentState::class.simpleName})")
        }
        _state.value = SessionState.REVOKED
        return true
    }
}
