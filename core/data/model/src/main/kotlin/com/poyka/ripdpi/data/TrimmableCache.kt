package com.poyka.ripdpi.data

/**
 * A cache that can voluntarily release its in-memory footprint when the app is
 * backgrounded, in response to Android's memory-pressure callbacks.
 *
 * Implementations are invoked from
 * [android.content.ComponentCallbacks2.onTrimMemory] for the
 * `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_BACKGROUND` levels -- the only levels
 * Android still delivers as of Android 14/15. Android 17 introduces a per-app
 * memory cap, so shedding regenerable allocations while the UI is not visible
 * directly lowers the risk of the app being the process that gets capped.
 *
 * Contract for implementations:
 * - Drop only state that can be cheaply regenerated on demand (re-read from
 *   disk, recomputed). NEVER discard persistent state or in-flight,
 *   connection-critical state (e.g. an active resolver/route cache).
 * - Must be non-blocking and safe to call from the main thread.
 * - Must be thread-safe: it may run concurrently with normal cache access.
 */
interface TrimmableCache {
    fun trimToBackground()
}
