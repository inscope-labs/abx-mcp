package com.inscopelabs.abxmcp

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

class LightweightWebSocketServer {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    private var thread: Thread? = null

    fun start(): Int {
        val server = ServerSocket(0) // Bind to any free port
        serverSocket = server
        thread = Thread {
            try {
                val client = server.accept()
                clientSocket = client
                val input = client.getInputStream()
                val output = client.getOutputStream()
                
                val reader = input.bufferedReader()
                var line: String?
                var key = ""
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                    if (line!!.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                        key = line!!.substringAfter(":").trim()
                    }
                }
                
                if (key.isNotEmpty()) {
                    val acceptVal = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest(
                            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()
                        )
                    )
                    val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $acceptVal\r\n\r\n"
                    output.write(response.toByteArray())
                    output.flush()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        thread?.start()
        return server.localPort
    }

    fun stop() {
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        thread?.interrupt()
    }
    
    fun disconnectClient() {
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
    }
}
