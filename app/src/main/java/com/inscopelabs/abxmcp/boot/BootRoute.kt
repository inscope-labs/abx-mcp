package com.inscopelabs.abxmcp.boot

import android.app.Activity
import android.content.Intent

object BootRoute {
    fun redirectIfNeeded(activity: Activity): Boolean {
        return try {
            if (BootGuard.hasFailure(activity.applicationContext)) {
                val recoveryClass = Class.forName("com.inscopelabs.abxmcp.boot.RecoveryActivity")
                val intent = Intent(activity, recoveryClass).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                activity.finish()
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            // Safe degradation: if anything fails within the routing logic, do not crash the app
            false
        }
    }
}
