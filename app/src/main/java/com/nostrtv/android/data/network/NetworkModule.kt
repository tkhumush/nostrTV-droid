package com.nostrtv.android.data.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared network module providing a singleton OkHttpClient.
 * OkHttp is designed to be shared - multiple clients waste memory and connections.
 */
object NetworkModule {

    /**
     * Shared OkHttpClient for all HTTP/WebSocket operations.
     * - Connection pooling: 5 idle connections, 5 minute keep-alive
     * - Timeouts: 30 seconds for connect, read, write
     * - Ping interval: 30 seconds to keep WebSocket connections alive
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }
}
