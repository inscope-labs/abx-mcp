package com.inscopelabs.abxmcp.core.tunnel

import android.content.Context
import com.inscopelabs.abxmcp.core.session.SessionManager
import com.inscopelabs.abxmcp.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class TunnelManagerImpl(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val environment: TunnelEnvironment = TunnelEnvironment.PRODUCTION,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : TunnelManager {

    private val _isRunningFlow = MutableStateFlow(false)
    override val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    private val _stateFlow = MutableStateFlow(TunnelState.STOPPED)
    override val stateFlow: StateFlow<TunnelState> = _stateFlow.asStateFlow()

    private var activeProcess: Process? = null

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
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val binary = File(nativeLibDir, "libcloudflared.so")
                if (isValidElfBinary(binary)) {
                    TunnelState.STOPPED
                } else {
                    TunnelState.UNAVAILABLE
                }
            }
        }
    }

    private fun isValidElfBinary(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val bytes = ByteArray(4)
        try {
            file.inputStream().use { it.read(bytes) }
            return bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.toByte() && bytes[2] == 'L'.toByte() && bytes[3] == 'F'.toByte()
        } catch (e: Exception) {
            return false
        }
    }

    override fun isTunnelRunning(): Boolean {
        if (_stateFlow.value == TunnelState.UNAVAILABLE) {
            return false
        }
        val process = activeProcess
        if (process != null) {
            try {
                process.exitValue()
                return false
            } catch (e: IllegalThreadStateException) {
                return true
            }
        }
        return false
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

        val pb = when (environment) {
            TunnelEnvironment.TEST_AVAILABLE -> {
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                if (isWindows) {
                    ProcessBuilder("cmd.exe", "/c", "ping -t 127.0.0.1")
                } else {
                    ProcessBuilder("sleep", "3600")
                }
            }
            TunnelEnvironment.TEST_UNAVAILABLE -> {
                _stateFlow.value = TunnelState.UNAVAILABLE
                return false
            }
            TunnelEnvironment.PRODUCTION -> {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val binary = File(nativeLibDir, "libcloudflared.so")
                if (!isValidElfBinary(binary)) {
                    _stateFlow.value = TunnelState.UNAVAILABLE
                    return false
                }
                try {
                    binary.setExecutable(true)
                } catch (e: Exception) {
                    // Ignore
                }
                ProcessBuilder(binary.absolutePath, "tunnel", "run")
            }
        }

        try {
            val process = pb.start()
            activeProcess = process
            _isRunningFlow.value = true
            _stateFlow.value = TunnelState.RUNNING

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
        } catch (e: IOException) {
            e.printStackTrace()
            _stateFlow.value = TunnelState.STOPPED
            _isRunningFlow.value = false
            return false
        }
    }

    @Synchronized
    override fun stopTunnel() {
        activeProcess?.let { process ->
            try {
                process.destroy()
                process.destroyForcibly()
            } catch (e: Exception) {
                // Ignore
            }
        }
        activeProcess = null
        _isRunningFlow.value = false
        val currentAvail = determineInitialState()
        _stateFlow.value = if (currentAvail == TunnelState.UNAVAILABLE) {
            TunnelState.UNAVAILABLE
        } else {
            TunnelState.STOPPED
        }
    }
}

