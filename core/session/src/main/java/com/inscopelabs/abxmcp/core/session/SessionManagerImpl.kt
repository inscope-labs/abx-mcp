package com.inscopelabs.abxmcp.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManagerImpl : SessionManager {
    private val _state = MutableStateFlow<SessionState>(SessionState.INACTIVE)
    override val stateFlow: StateFlow<SessionState> = _state.asStateFlow()

    override fun getState(): SessionState = _state.value

    @Synchronized
    override fun startSession(trigger: UserGesture): Boolean {
        // STRICT Identity Check to reject remote mocks or custom subclasses
        if (trigger !== UserGesture.LocalButtonPress) {
            // Keep state unchanged
            return false
        }

        val currentState = _state.value
        if (currentState is SessionState.ACTIVE) {
            throw IllegalStateException("Cannot start session: already ACTIVE")
        }
        if (currentState is SessionState.REVOKED) {
            throw IllegalStateException("Cannot start session: session is REVOKED")
        }

        // Legal transitions: INACTIVE -> ACTIVE, EXPIRED -> ACTIVE
        if (currentState is SessionState.INACTIVE || currentState is SessionState.EXPIRED) {
            _state.value = SessionState.ACTIVE
            return true
        }

        return false
    }

    @Synchronized
    override fun stopSession(): Boolean {
        val currentState = _state.value
        if (currentState !is SessionState.ACTIVE) {
            throw IllegalStateException("Cannot stop session: session is not ACTIVE (current: ${currentState::class.simpleName})")
        }
        _state.value = SessionState.INACTIVE
        return true
    }

    @Synchronized
    override fun expireSession(): Boolean {
        val currentState = _state.value
        if (currentState !is SessionState.ACTIVE) {
            throw IllegalStateException("Cannot expire session: session is not ACTIVE (current: ${currentState::class.simpleName})")
        }
        _state.value = SessionState.EXPIRED
        return true
    }

    @Synchronized
    override fun revokeSession(): Boolean {
        val currentState = _state.value
        if (currentState !is SessionState.ACTIVE) {
            throw IllegalStateException("Cannot revoke session: session is not ACTIVE (current: ${currentState::class.simpleName})")
        }
        _state.value = SessionState.REVOKED
        return true
    }
}
