package com.inscopelabs.abxmcp.core.tunnel

import kotlinx.coroutines.flow.Flow

interface TransportProvider {
    fun connect(): Boolean
    fun disconnect()
    fun send(message: Message): Boolean
    fun receive(): Flow<Message>
    fun isConnected(): Boolean
}
