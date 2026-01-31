package com.nostrtv.android.data.nostr.relay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages EOSE (End Of Stored Events) timeout logic for multi-relay queries.
 *
 * Strategy: Start a short timeout after the FIRST relay sends EOSE,
 * allowing fast relays to drive UI responsiveness while still accepting
 * late-arriving data from slower relays.
 *
 * This prevents slow relays from blocking the UI while ensuring we get
 * data from the fastest available sources.
 */
object EoseTimeoutManager {

    private const val TAG = "EoseTimeoutManager"

    /**
     * Configuration for EOSE timeout behavior.
     *
     * @param eoseTimeoutMs Timeout after first EOSE (ms) - UI will render after this
     * @param maxWaitMs Absolute maximum wait time (ms) - safety net
     * @param minRelaysBeforeTimeout Minimum relays that must respond before timeout can trigger
     */
    data class TimeoutConfig(
        val eoseTimeoutMs: Long = 500L,
        val maxWaitMs: Long = 3000L,
        val minRelaysBeforeTimeout: Int = 1
    )

    /** Preset configs for different query types */
    object Presets {
        /** Stream discovery - balance speed and completeness */
        val DISCOVERY = TimeoutConfig(eoseTimeoutMs = 500, maxWaitMs = 3000)

        /** Chat history - prioritize speed for better UX */
        val CHAT_JOIN = TimeoutConfig(eoseTimeoutMs = 300, maxWaitMs = 2000)

        /** Profile metadata - moderate timeout */
        val PROFILE = TimeoutConfig(eoseTimeoutMs = 400, maxWaitMs = 2500)

        /** Zap receipts - allow more time for payment verification */
        val ZAP_RECEIPTS = TimeoutConfig(eoseTimeoutMs = 600, maxWaitMs = 4000)

        /** Follow lists - may be large, allow more time */
        val FOLLOW_LIST = TimeoutConfig(eoseTimeoutMs = 800, maxWaitMs = 5000)

        /** Exhaustive search - wait longer for completeness */
        val EXHAUSTIVE = TimeoutConfig(eoseTimeoutMs = 1500, maxWaitMs = 8000)
    }

    /**
     * Tracks state for a single multi-relay query.
     *
     * Thread-safe via atomic operations.
     */
    class QueryState(
        private val subscriptionId: String,
        private val config: TimeoutConfig,
        private val totalRelays: Int,
        private val onInitialBatchReady: () -> Unit,
        private val onAllComplete: () -> Unit = {}
    ) {
        private val eoseCount = AtomicInteger(0)
        private val firstEoseReceived = AtomicBoolean(false)
        private val _isInitialBatchReady = AtomicBoolean(false)
        private val _isComplete = AtomicBoolean(false)
        private var eoseTimeoutJob: Job? = null
        private var maxTimeoutJob: Job? = null

        val isInitialBatchReady: Boolean get() = _isInitialBatchReady.get()
        val isComplete: Boolean get() = _isComplete.get()
        val eoseReceivedCount: Int get() = eoseCount.get()

        /**
         * Called when a relay sends EOSE for this subscription.
         * Starts the timeout countdown on first EOSE.
         */
        fun onEoseReceived(relayUrl: String, scope: CoroutineScope) {
            val count = eoseCount.incrementAndGet()
            Log.d(TAG, "[$subscriptionId] EOSE from $relayUrl ($count/$totalRelays relays)")

            // Start timeout on first EOSE (if we have minimum relays)
            if (count >= config.minRelaysBeforeTimeout &&
                firstEoseReceived.compareAndSet(false, true)) {
                Log.d(TAG, "[$subscriptionId] Starting EOSE timeout (${config.eoseTimeoutMs}ms)")
                eoseTimeoutJob = scope.launch {
                    delay(config.eoseTimeoutMs)
                    if (_isInitialBatchReady.compareAndSet(false, true)) {
                        Log.d(TAG, "[$subscriptionId] EOSE timeout fired - initial batch ready")
                        onInitialBatchReady()
                    }
                }
            }

            // Complete immediately if all relays responded
            if (count >= totalRelays) {
                Log.d(TAG, "[$subscriptionId] All $totalRelays relays sent EOSE")
                eoseTimeoutJob?.cancel()
                maxTimeoutJob?.cancel()

                if (_isInitialBatchReady.compareAndSet(false, true)) {
                    onInitialBatchReady()
                }
                if (_isComplete.compareAndSet(false, true)) {
                    onAllComplete()
                }
            }
        }

        /**
         * Start the absolute maximum timeout.
         * Call this when the query begins.
         */
        fun startMaxTimeout(scope: CoroutineScope) {
            maxTimeoutJob = scope.launch {
                delay(config.maxWaitMs)
                Log.d(TAG, "[$subscriptionId] Max timeout (${config.maxWaitMs}ms) reached")

                eoseTimeoutJob?.cancel()

                if (_isInitialBatchReady.compareAndSet(false, true)) {
                    onInitialBatchReady()
                }
                if (_isComplete.compareAndSet(false, true)) {
                    onAllComplete()
                }
            }
        }

        /**
         * Cancel all pending timeouts.
         * Call this when the query is cancelled or cleaned up.
         */
        fun cancel() {
            eoseTimeoutJob?.cancel()
            maxTimeoutJob?.cancel()
            _isComplete.set(true)
        }
    }
}
