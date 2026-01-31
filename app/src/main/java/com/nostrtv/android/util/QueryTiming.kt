package com.nostrtv.android.util

import android.util.Log

/**
 * Utility for measuring and logging query performance.
 * Helps track the effectiveness of EOSE timeout optimizations.
 */
object QueryTiming {
    private const val TAG = "QueryTiming"

    /**
     * Metrics for a single multi-relay query.
     */
    data class QueryMetrics(
        val queryType: String,
        val subscriptionId: String,
        val totalTimeMs: Long,
        val timeToFirstEventMs: Long?,
        val timeToInitialBatchMs: Long?,
        val totalEventsReceived: Int,
        val eventsAtInitialBatch: Int,
        val relayCount: Int,
        val relaysRespondedAtInitialBatch: Int
    ) {
        val percentEventsAtBatch: Int
            get() = if (totalEventsReceived > 0) {
                (eventsAtInitialBatch * 100) / totalEventsReceived
            } else 0
    }

    /**
     * Log query metrics in a readable format.
     */
    fun log(metrics: QueryMetrics) {
        Log.i(TAG, buildString {
            appendLine("=== Query Performance: ${metrics.queryType} ===")
            appendLine("  Subscription: ${metrics.subscriptionId}")
            appendLine("  Total time: ${metrics.totalTimeMs}ms")
            metrics.timeToFirstEventMs?.let {
                appendLine("  First event: ${it}ms")
            }
            metrics.timeToInitialBatchMs?.let {
                appendLine("  Initial batch ready: ${it}ms")
            }
            appendLine("  Events at batch: ${metrics.eventsAtInitialBatch}/${metrics.totalEventsReceived} (${metrics.percentEventsAtBatch}%)")
            appendLine("  Relays at batch: ${metrics.relaysRespondedAtInitialBatch}/${metrics.relayCount}")
            appendLine("==========================================")
        })
    }

    /**
     * Helper class to track timing during a query.
     */
    class Tracker(
        private val queryType: String,
        private val subscriptionId: String,
        private val relayCount: Int
    ) {
        private val startTime = System.currentTimeMillis()
        private var firstEventTime: Long? = null
        private var initialBatchTime: Long? = null
        private var eventCount = 0
        private var eventsAtInitialBatch = 0
        private var relaysAtInitialBatch = 0

        fun onEventReceived() {
            eventCount++
            if (firstEventTime == null) {
                firstEventTime = System.currentTimeMillis() - startTime
            }
        }

        fun onInitialBatchReady(relaysResponded: Int) {
            if (initialBatchTime == null) {
                initialBatchTime = System.currentTimeMillis() - startTime
                eventsAtInitialBatch = eventCount
                relaysAtInitialBatch = relaysResponded
            }
        }

        fun finish(): QueryMetrics {
            val totalTime = System.currentTimeMillis() - startTime

            // If initial batch wasn't explicitly marked, mark it now
            if (initialBatchTime == null) {
                initialBatchTime = totalTime
                eventsAtInitialBatch = eventCount
                relaysAtInitialBatch = relayCount
            }

            return QueryMetrics(
                queryType = queryType,
                subscriptionId = subscriptionId,
                totalTimeMs = totalTime,
                timeToFirstEventMs = firstEventTime,
                timeToInitialBatchMs = initialBatchTime,
                totalEventsReceived = eventCount,
                eventsAtInitialBatch = eventsAtInitialBatch,
                relayCount = relayCount,
                relaysRespondedAtInitialBatch = relaysAtInitialBatch
            )
        }
    }
}
