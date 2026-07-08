package com.inscopelabs.abxmcp.core.session

import android.content.Context

object ReplayProtectionProvider {
    private var instance: ReplayProtection? = null

    @Synchronized
    fun get(context: Context): ReplayProtection {
        val current = instance
        if (current != null) return current
        val newInstance = ReplayProtectionImpl(SessionManagerProvider.get(context))
        instance = newInstance
        return newInstance
    }

    @Synchronized
    fun setForTesting(replayProtection: ReplayProtection?) {
        instance = replayProtection
    }
}
