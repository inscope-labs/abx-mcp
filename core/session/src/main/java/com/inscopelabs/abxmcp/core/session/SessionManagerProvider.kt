package com.inscopelabs.abxmcp.core.session

import android.content.Context

object SessionManagerProvider {
    private var instance: SessionManager? = null

    @Synchronized
    fun get(context: Context): SessionManager {
        val current = instance
        if (current != null) return current
        val newInstance = SessionManagerImpl()
        instance = newInstance
        return newInstance
    }

    @Synchronized
    fun setForTesting(sessionManager: SessionManager?) {
        instance = sessionManager
    }
}
