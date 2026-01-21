package com.nostrtv.android.data.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RelayConnection(
    val url: String,
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "RelayConnection"
        private const val NORMAL_CLOSURE_STATUS = 1000
    }

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _messages = Channel<RelayMessage>(Channel.BUFFERED)
    val messages: Flow<RelayMessage> = _messages.receiveAsFlow()

    private var isConnected = false

    fun connect() {
        if (isConnected) {
            Log.d(TAG, "Already connected to $url")
            return
        }

        Log.d(TAG, "Connecting to relay: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
                isConnected = true
                scope.launch {
                    _messages.send(RelayMessage.Connected(url))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "Message from $url: ${text.take(200)}")
                scope.launch {
                    _messages.send(RelayMessage.Text(url, text))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing connection to $url: $code $reason")
                webSocket.close(NORMAL_CLOSURE_STATUS, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed connection to $url: $code $reason")
                isConnected = false
                scope.launch {
                    _messages.send(RelayMessage.Disconnected(url, reason))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failure to $url: ${t.message}", t)
                isConnected = false
                scope.launch {
                    _messages.send(RelayMessage.Error(url, t.message ?: "Unknown error"))
                }
            }
        })
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false.also {
            Log.w(TAG, "Cannot send message, not connected to $url")
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from $url")
        // Cancel all running coroutines to prevent leaks
        scope.cancel()
        // Close the channel to stop any pending receivers
        _messages.close()
        webSocket?.close(NORMAL_CLOSURE_STATUS, "Client closing")
        webSocket = null
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected
}

sealed class RelayMessage {
    data class Connected(val url: String) : RelayMessage()
    data class Disconnected(val url: String, val reason: String) : RelayMessage()
    data class Text(val url: String, val content: String) : RelayMessage()
    data class Error(val url: String, val error: String) : RelayMessage()
}

object RelayConnectionFactory {
    fun create(url: String): RelayConnection {
        return RelayConnection(url, com.nostrtv.android.data.network.NetworkModule.httpClient)
    }
}
