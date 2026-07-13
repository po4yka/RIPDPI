package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Process-local hand-off for credential-bearing profile imports.
 *
 * Security properties:
 *
 * - Navigation and forwarding intents carry only the opaque token returned by [stage].
 * - Profiles are claimed once and never written to Android saved-state surfaces.
 * - Losing the process also loses pending imports; an import can always be initiated again.
 */
internal class PendingProxyImportStore(
    private val capacity: Int = DefaultCapacity,
    private val ttlNanos: Long = DefaultTtlNanos,
    private val nanoTime: () -> Long = System::nanoTime,
    private val expiryScope: CoroutineScope? = null,
) {
    private data class Entry(
        val profile: ProxyProfile,
        val expiresAtNanos: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlNanos > 0) { "ttlNanos must be positive" }
    }

    fun stage(profile: ProxyProfile): String =
        synchronized(lock) {
            val now = nanoTime()
            pruneExpired(now)
            while (entries.size >= capacity) {
                entries.remove(entries.keys.first())
            }
            UUID.randomUUID().toString().also { token ->
                entries[token] = Entry(profile = profile, expiresAtNanos = now + ttlNanos)
                expiryScope?.launch {
                    delay((ttlNanos / NanosPerMillisecond).coerceAtLeast(1L))
                    discardIfExpired(token)
                }
            }
        }

    fun claim(token: String): ProxyProfile? {
        if (!token.isOpaqueImportToken()) return null
        return synchronized(lock) {
            val now = nanoTime()
            pruneExpired(now)
            entries.remove(token)?.profile
        }
    }

    fun contains(token: String): Boolean {
        if (!token.isOpaqueImportToken()) return false
        return synchronized(lock) {
            pruneExpired(nanoTime())
            token in entries
        }
    }

    private fun pruneExpired(nowNanos: Long) {
        entries.entries.removeAll { (_, entry) -> nowNanos >= entry.expiresAtNanos }
    }

    private fun discardIfExpired(token: String) {
        synchronized(lock) {
            entries[token]?.takeIf { nanoTime() >= it.expiresAtNanos }?.let { entries.remove(token) }
        }
    }

    companion object {
        private const val DefaultCapacity = 4
        private const val DefaultTtlNanos = 5L * 60L * 1_000_000_000L
        private const val NanosPerMillisecond = 1_000_000L
        internal const val OpaqueTokenLength = 36
        private val processExpiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Single store shared by activities, navigation, and destination ViewModels. */
        val process: PendingProxyImportStore = PendingProxyImportStore(expiryScope = processExpiryScope)
    }
}

private fun String.isOpaqueImportToken(): Boolean =
    length == PendingProxyImportStore.OpaqueTokenLength && runCatching { UUID.fromString(this) }.isSuccess
