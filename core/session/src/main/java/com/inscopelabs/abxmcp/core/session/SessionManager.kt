package com.inscopelabs.abxmcp.core.session

import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val stateFlow: StateFlow<SessionState>
    
    fun startSession(trigger: UserGesture): Boolean
    fun stopSession(): Boolean
    fun expireSession(): Boolean
    fun revokeSession(): Boolean
    fun getState(): SessionState
}
