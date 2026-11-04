package com.poyka.ripdpi.activities

import android.os.SystemClock

private const val RecentDnsFailureWindowMillis = 60_000L

/**
 * The tunnel reports only a session total of DNS failures, so recency comes from when that total last grew. A total
 * already above zero at the first reading has an unknown age, and it does not count as recent.
 */
internal class RecentDnsFailureTracker(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastTotal: Long? = null
    private var lastIncreaseAtMs: Long? = null

    fun isRecent(total: Long): Boolean {
        val time = now()
        val previous = lastTotal
        when {
            // A lower total means a new session, whose failures are new only if there are any.
            previous != null && total < previous -> lastIncreaseAtMs = time.takeIf { total > 0L }

            previous != null && total > previous -> lastIncreaseAtMs = time
        }
        lastTotal = total
        return lastIncreaseAtMs?.let { time - it < RecentDnsFailureWindowMillis } == true
    }
}
