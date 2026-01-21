package com.nostrtv.android.data.auth

import android.content.Context

/**
 * Singleton provider for RemoteSignerManager.
 * Ensures only one WebSocket connection to the bunker relay is maintained.
 */
object RemoteSignerManagerProvider {
    @Volatile
    private var instance: RemoteSignerManager? = null

    fun getInstance(context: Context): RemoteSignerManager {
        return instance ?: synchronized(this) {
            instance ?: RemoteSignerManager(
                SessionStore(context.applicationContext)
            ).also { instance = it }
        }
    }

    /**
     * Clear the instance (for testing or logout scenarios).
     */
    fun clear() {
        instance = null
    }
}
