package com.inscopelabs.abxmcp.core.tunnel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inscopelabs.abxmcp.core.session.SessionManagerProvider
import com.inscopelabs.abxmcp.core.session.SessionState
import kotlinx.coroutines.delay

class TtlCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val sessionManager = SessionManagerProvider.get(applicationContext)
        val tunnelManager = TunnelManagerProvider.get(applicationContext)

        while (sessionManager.getState() is SessionState.ACTIVE) {
            val remainingTtl = sessionManager.getSessionTtl()
            if (remainingTtl <= 0) {
                try {
                    sessionManager.expireSession()
                } catch (e: Exception) {
                    // Ignore if state has already changed
                }
                tunnelManager.stopTunnel()
                return Result.success()
            }

            delay(1000)
            sessionManager.decrementTtl(1)
        }

        return Result.success()
    }
}
