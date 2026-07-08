package com.inscopelabs.abxmcp.core.tunnel

import kotlinx.coroutines.flow.StateFlow

enum class TunnelState {
    UNAVAILABLE,
    STOPPED,
    RUNNING
}

enum class TunnelEnvironment {
    PRODUCTION,
    TEST_UNAVAILABLE,
    TEST_AVAILABLE
}

interface TunnelManager {
    val isRunningFlow: StateFlow<Boolean>
    val stateFlow: StateFlow<TunnelState>
    fun startTunnel(): Boolean
    fun stopTunnel()
    fun isTunnelRunning(): Boolean
}

