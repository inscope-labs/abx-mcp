package com.inscopelabs.abxmcp

import android.app.Application
import com.inscopelabs.abxmcp.core.keystore.KeyStoreManager
import com.inscopelabs.abxmcp.core.audit.AuditLog
import com.inscopelabs.abxmcp.boot.BootGuard

class McpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            BootGuard.stageStart("KeyStoreManager")
            val keyStoreManager = KeyStoreManager(this)
            BootGuard.stageSuccess("KeyStoreManager")

            BootGuard.stageStart("AuditLog")
            AuditLog.initialize(this, keyStoreManager)
            BootGuard.stageSuccess("AuditLog")
        } catch (t: Throwable) {
            BootGuard.recordFailure(this, "Application.onCreate", t)
        }
    }
}
