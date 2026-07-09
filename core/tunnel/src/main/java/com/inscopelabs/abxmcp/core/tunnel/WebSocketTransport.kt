package com.inscopelabs.abxmcp.core.tunnel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketTransport(
    private val url: String,
    private val client: OkHttpClient = OkHttpClient()
) : TransportProvider {

    private var webSocket: WebSocket? = null
    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 100)
    @Volatile
    private var connected = false

    override fun connect(): Boolean {
        if (connected) return true
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            return false
        }
        val latch = CountDownLatch(1)
        var connectResult = false

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                connectResult = true
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _messageFlow.tryEmit(Message(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                latch.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
            }
        })

        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // Ignore
        }

        return connectResult
    }

    override fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
        connected = false
    }

    override fun send(message: Message): Boolean {
        val ws = webSocket ?: return false
        return ws.send(message.content)
    }

    override fun receive(): Flow<Message> {
        return _messageFlow.asSharedFlow()
    }

    override fun isConnected(): Boolean {
        return connected
    }
}
