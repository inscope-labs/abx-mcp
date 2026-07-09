package com.inscopelabs.abxmcp.core.tunnel

import android.content.Context
import com.inscopelabs.abxmcp.core.session.SessionManager
import com.inscopelabs.abxmcp.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TunnelManagerImpl(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val environment: TunnelEnvironment = TunnelEnvironment.PRODUCTION,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val relayUrl: String? = null,
    @Volatile private var transportProvider: TransportProvider? = null
) : TunnelManager {

    private val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)

    private val _isRunningFlow = MutableStateFlow(false)
    override val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    private val _stateFlow = MutableStateFlow(TunnelState.STOPPED)
    override val stateFlow: StateFlow<TunnelState> = _stateFlow.asStateFlow()

    private var monitoringJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        _stateFlow.value = determineInitialState()

        // Observe SessionState
        scope.launch {
            sessionManager.stateFlow.collect { state ->
                if (state is SessionState.ACTIVE) {
                    startTunnel()
                } else {
                    stopTunnel()
                }
            }
        }
    }

    private fun determineInitialState(): TunnelState {
        return when (environment) {
            TunnelEnvironment.TEST_UNAVAILABLE -> TunnelState.UNAVAILABLE
            TunnelEnvironment.TEST_AVAILABLE -> TunnelState.STOPPED
            TunnelEnvironment.PRODUCTION -> {
                if (relayUrl.isNullOrBlank()) {
                    TunnelState.UNAVAILABLE
                } else {
                    TunnelState.STOPPED
                }
            }
        }
    }

    override fun isTunnelRunning(): Boolean {
        if (_stateFlow.value == TunnelState.UNAVAILABLE) {
            return false
        }
        return transportProvider?.isConnected() ?: false
    }

    @Synchronized
    override fun startTunnel(): Boolean {
        val currentAvail = determineInitialState()
        if (currentAvail == TunnelState.UNAVAILABLE) {
            _stateFlow.value = TunnelState.UNAVAILABLE
            _isRunningFlow.value = false
            return false
        }

        if (sessionManager.getState() !is SessionState.ACTIVE) {
            return false
        }
        if (isTunnelRunning()) {
            _stateFlow.value = TunnelState.RUNNING
            _isRunningFlow.value = true
            return true
        }

        val provider = transportProvider ?: when (environment) {
            TunnelEnvironment.TEST_AVAILABLE -> {
                FakeTransportProvider(initialConnected = true)
            }
            TunnelEnvironment.TEST_UNAVAILABLE -> {
                _stateFlow.value = TunnelState.UNAVAILABLE
                return false
            }
            TunnelEnvironment.PRODUCTION -> {
                val url = relayUrl
                if (url.isNullOrBlank()) {
                    _stateFlow.value = TunnelState.UNAVAILABLE
                    return false
                }
                WebSocketTransport(url)
            }
        }
        transportProvider = provider

        val connected = provider.connect()
        if (connected) {
            _isRunningFlow.value = true
            _stateFlow.value = TunnelState.RUNNING

            // Start connection monitoring
            startConnectionMonitoring()

            // Enqueue unique WorkManager TTL check
            try {
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<TtlCheckWorker>()
                    .addTag("TTL_CHECK")
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    "TTL_CHECK",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return true
        } else {
            _stateFlow.value = TunnelState.STOPPED
            _isRunningFlow.value = false
            return false
        }
    }

    @Synchronized
    override fun stopTunnel() {
        monitoringJob?.cancel()
        monitoringJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        
        transportProvider?.disconnect()
        _isRunningFlow.value = false
        val currentAvail = determineInitialState()
        _stateFlow.value = if (currentAvail == TunnelState.UNAVAILABLE) {
            TunnelState.UNAVAILABLE
        } else {
            TunnelState.STOPPED
        }
    }

    private fun startConnectionMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isTunnelRunning()) {
                delay(1000)
            }
            // If we exited the loop and state is still RUNNING, handle disconnect
            if (_stateFlow.value == TunnelState.RUNNING) {
                handleConnectionDrop()
            }
        }
    }

    private fun handleConnectionDrop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val reconnected = transportProvider?.connect() ?: false
            if (reconnected) {
                startConnectionMonitoring()
            } else {
                _isRunningFlow.value = false
                val currentAvail = determineInitialState()
                _stateFlow.value = if (currentAvail == TunnelState.UNAVAILABLE) {
                    TunnelState.UNAVAILABLE
                } else {
                    TunnelState.STOPPED
                }
            }
        }
    }

    // Seam to inject or retrieve transport provider in tests
    fun getTransportProvider(): TransportProvider? {
        return transportProvider
    }

    fun setTransportProvider(provider: TransportProvider?) {
        transportProvider = provider
    }

    fun cancel() {
        supervisorJob.cancel()
    }
}
