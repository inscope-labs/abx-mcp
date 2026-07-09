package com.inscopelabs.abxmcp.core.tunnel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeTransportProvider(
    private var initialConnected: Boolean = true
) : TransportProvider {
    @Volatile
    private var connected = false
    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 100)

    override fun connect(): Boolean {
        connected = initialConnected
        return connected
    }

    override fun disconnect() {
        connected = false
    }

    override fun send(message: Message): Boolean {
        return connected
    }

    override fun receive(): Flow<Message> {
        return _messageFlow.asSharedFlow()
    }

    override fun isConnected(): Boolean {
        return connected
    }

    fun simulateDrop() {
        connected = false
    }

    fun simulateMessage(text: String) {
        _messageFlow.tryEmit(Message(text))
    }
}
