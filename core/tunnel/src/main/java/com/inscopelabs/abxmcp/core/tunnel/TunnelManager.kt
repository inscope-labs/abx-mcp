package com.inscopelabs.abxmcp.core.tunnel

import kotlinx.coroutines.flow.StateFlow

interface TunnelManager {
    val isRunningFlow: StateFlow<Boolean>
    fun startTunnel(): Boolean
    fun stopTunnel()
    fun isTunnelRunning(): Boolean
}
