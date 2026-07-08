package com.inscopelabs.abxmcp.core.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.inscopelabs.abxmcp.core.session.SessionManagerProvider
import com.inscopelabs.abxmcp.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TunnelService : Service() {

    private val defaultScope = CoroutineScope(Dispatchers.Default)
    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private lateinit var serviceScope: CoroutineScope
    private lateinit var tunnelManager: TunnelManager

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tunnel_service_channel"
        private const val CHANNEL_NAME = "Tunnel Service Channel"

        var testScope: CoroutineScope? = null

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TunnelService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val baseScope = testScope ?: defaultScope
        serviceScope = CoroutineScope(baseScope.coroutineContext + serviceJob)

        tunnelManager = TunnelManagerProvider.get(this)
        createNotificationChannel()

        val initialNotification = createNotificationForState(tunnelManager.stateFlow.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Start observing tunnel running state to update notification live
        serviceScope.launch {
            tunnelManager.stateFlow.collect { state ->
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = createNotificationForState(state)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }

        // Start observing session state to self-stop if inactive and run countdown when active
        val sessionManager = SessionManagerProvider.get(this)
        serviceScope.launch {
            sessionManager.stateFlow.collect { state ->
                if (state !is SessionState.ACTIVE) {
                    stopSelf()
                } else {
                    startCountdown(sessionManager)
                }
            }
        }
    }

    private var countdownJob: kotlinx.coroutines.Job? = null

    private fun startCountdown(sessionManager: com.inscopelabs.abxmcp.core.session.SessionManager) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (sessionManager.getState() is SessionState.ACTIVE) {
                val remainingTtl = sessionManager.getSessionTtl()
                if (remainingTtl <= 0) {
                    try {
                        sessionManager.expireSession()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    tunnelManager.stopTunnel()
                    stopSelf()
                    break
                }
                kotlinx.coroutines.delay(1000)
                sessionManager.decrementTtl(1)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tunnelManager.startTunnel()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelManager.stopTunnel()
        serviceJob.cancel()
        if (testScope == null) {
            defaultScope.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ABX-MCP Tunnel Status Notification"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotificationForState(state: TunnelState): Notification {
        val (title, text) = when (state) {
            TunnelState.RUNNING -> Pair("ABX-MCP Tunnel Active", "The secure hardware-backed tunnel is active.")
            TunnelState.UNAVAILABLE -> Pair("ABX-MCP Tunnel Unavailable", "The secure tunnel is unavailable on this device.")
            TunnelState.STOPPED -> Pair("ABX-MCP Tunnel Stopped", "The secure tunnel is stopped.")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
