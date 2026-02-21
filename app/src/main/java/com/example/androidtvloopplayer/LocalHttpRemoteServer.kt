package com.example.androidtvloopplayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LocalHttpRemoteServer(
    private val scope: CoroutineScope,
    private val pin: String,
    private val onCommand: (RemoteCommand) -> Unit,
    private val onServerReady: (Int) -> Unit,
    private val onServerStopped: () -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    fun start() {
        if (serverJob != null) {
            return
        }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket(REMOTE_CONTROL_PORT, 20, InetAddress.getByName("0.0.0.0"))
                serverSocket = socket
                onServerReady(socket.localPort)

                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }

                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start remote server", e)
            } finally {
                closeSocketQuietly()
                serverJob = null
                onServerStopped()
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        closeSocketQuietly()
        serverJob = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val remoteAddress = client.inetAddress
            if (!remoteAddress.isLoopbackAddress && !remoteAddress.isSiteLocalAddress) {
                writeResponse(client, 403, "Forbidden")
                return
            }

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: run {
                writeResponse(client, 400, "Bad Request")
                return
            }

            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) {
                writeResponse(client, 400, "Bad Request")
                return
            }

            val method = requestParts[0]
            val path = requestParts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isBlank()) {
                    break
                }
                val separatorIndex = headerLine.indexOf(':')
                if (separatorIndex > 0) {
                    val name = headerLine.substring(0, separatorIndex).trim().lowercase()
                    val value = headerLine.substring(separatorIndex + 1).trim()
                    headers[name] = value
                }
            }

            if (headers[PIN_HEADER_NAME.lowercase()] != pin) {
                writeResponse(client, 401, "Unauthorized")
                return
            }

            if (method != "POST") {
                writeResponse(client, 405, "Method Not Allowed")
                return
            }

            val command = when (path) {
                "/reload" -> RemoteCommand.RELOAD
                "/next" -> RemoteCommand.NEXT
                "/prev" -> RemoteCommand.PREV
                "/pause" -> RemoteCommand.PAUSE
                "/play" -> RemoteCommand.PLAY
                else -> null
            }

            if (command == null) {
                writeResponse(client, 404, "Not Found")
                return
            }

            onCommand(command)
            writeResponse(client, 200, "OK")
        }
    }

    private fun writeResponse(socket: Socket, statusCode: Int, message: String) {
        val writer = OutputStreamWriter(socket.getOutputStream())
        writer.write("HTTP/1.1 $statusCode $message\r\n")
        writer.write("Content-Type: text/plain\r\n")
        writer.write("Content-Length: ${message.length}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(message)
        writer.flush()
    }

    private fun closeSocketQuietly() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    companion object {
        private const val TAG = "LocalHttpRemoteServer"
        private const val REMOTE_CONTROL_PORT = 8080
        private const val PIN_HEADER_NAME = "X-PIN"
    }
}

enum class RemoteCommand {
    RELOAD,
    NEXT,
    PREV,
    PAUSE,
    PLAY
}
