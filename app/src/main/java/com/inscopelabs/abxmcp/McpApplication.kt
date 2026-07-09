package com.inscopelabs.abxmcp

import android.app.Application
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.core.audit.AuditLog

class McpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val keyStoreManager = KeyStoreManager(this)
        AuditLog.initialize(this, keyStoreManager)
    }
}
