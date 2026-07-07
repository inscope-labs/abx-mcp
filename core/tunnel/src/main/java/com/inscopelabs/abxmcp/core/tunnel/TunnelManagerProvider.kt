package com.inscopelabs.abxmcp.core.tunnel

import android.content.Context
import com.inscopelabs.abxmcp.core.session.SessionManagerProvider

object TunnelManagerProvider {
    private var instance: TunnelManager? = null

    @Synchronized
    fun get(context: Context): TunnelManager {
        val current = instance
        if (current != null) return current
        val newInstance = TunnelManagerImpl(
            context.applicationContext,
            SessionManagerProvider.get(context.applicationContext)
        )
        instance = newInstance
        return newInstance
    }

    @Synchronized
    fun setForTesting(tunnelManager: TunnelManager?) {
        instance = tunnelManager
    }
}
