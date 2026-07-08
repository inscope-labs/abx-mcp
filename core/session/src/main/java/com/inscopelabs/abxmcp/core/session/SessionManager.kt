package com.inscopelabs.abxmcp.core.session

import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val stateFlow: StateFlow<SessionState>
    
    fun startSession(trigger: UserGesture): Boolean
    fun stopSession(): Boolean
    fun expireSession(): Boolean
    fun revokeSession(): Boolean
    fun getState(): SessionState
    
    fun getSessionTtl(): Int
    fun setSessionTtl(seconds: Int)
    fun decrementTtl(amountSeconds: Int = 1): Int
    fun extendSession(trigger: UserGesture, extensionSeconds: Int = 300): Boolean
}
