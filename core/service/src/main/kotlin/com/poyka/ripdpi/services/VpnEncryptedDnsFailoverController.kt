package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.buildEncryptedDnsCandidatePlan
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.toEncryptedDnsPathCandidate
import com.poyka.ripdpi.data.toTemporaryResolverOverride

private const val FailoverThreshold = 2
private const val EagerFailoverMaxQueries = 3
private const val ExhaustedRetryAfterMs = 30_000L
private const val AutoFailoverReasonPrefix = "vpn_encrypted_dns_auto_failover"

internal class VpnEncryptedDnsFailoverState {
    var networkScopeKey: String? = null
    var preferredPath: EncryptedDnsPathCandidate? = null
    var currentPathKey: String? = null
    var currentDnsSignature: String? = null
    var expectedPathKey: String? = null
    var pathStartQueries: Long = 0
    var pathStartFailures: Long = 0
    var nativeGeneration: Long? = null
    var lastObservedDnsQueriesTotal: Long = 0
    var lastObservedDnsFailuresTotal: Long = 0
    var lastObservedResolverFailuresTotal: Long = 0
    var consecutiveFailureEvents: Int = 0
    var exhausted: Boolean = false
    var exhaustedAt: Long = 0
    var currentPathSelectedByFailover: Boolean = false
    var currentPathPersisted: Boolean = false
    val attemptedPathKeys: LinkedHashSet<String> = linkedSetOf()
    var blockedPathKeys: Set<String> = emptySet()

    fun resetAll() {
        networkScopeKey = null
        preferredPath = null
        blockedPathKeys = emptySet()
        resetTracking()
    }

    fun resetTracking() {
        currentPathKey = null
        currentDnsSignature = null
        expectedPathKey = null
        pathStartQueries = 0
        pathStartFailures = 0
        lastObservedDnsQueriesTotal = 0
        lastObservedDnsFailuresTotal = 0
        lastObservedResolverFailuresTotal = 0
        consecutiveFailureEvents = 0
        exhausted = false
        currentPathSelectedByFailover = false
        currentPathPersisted = false
        attemptedPathKeys.clear()
    }
}

internal data class DnsTelemetrySource(
    val usesSharedProxy: Boolean = false,
    val generation: Long? = null,
)

internal class VpnEncryptedDnsFailoverController(
    private val resolverOverrideStore: ResolverOverrideStore,
    private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
    private val networkDnsBlockedPathStore: NetworkDnsBlockedPathStore,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val clock: ServiceClock = SystemServiceClock,
) {
    private companion object {
        private val log = Logger.withTag("DnsFailover")
    }

    suspend fun evaluate(
        state: VpnEncryptedDnsFailoverState,
        activeDns: ActiveDnsSettings?,
        currentDnsSignature: String?,
        networkScopeKey: String?,
        telemetry: NativeRuntimeSnapshot,
        source: DnsTelemetrySource = DnsTelemetrySource(),
    ): Boolean {
        val encryptedDns = activeDns?.takeIf { it.isEncrypted }
        val currentPath = encryptedDns?.toEncryptedDnsPathCandidate()
        if (encryptedDns == null || currentPath == null) {
            state.resetAll()
            return false
        }

        synchronizeNetworkScope(state, networkScopeKey)
        observeResolverChange(state, currentPath, currentDnsSignature, networkScopeKey, telemetry)
        if (source.generation != null && state.nativeGeneration != source.generation) {
            state.nativeGeneration = source.generation
            // A newly installed bridge owns fresh counters, including failures before the first poll.
            resetCounters(state, queries = 0, failures = 0)
        }
        resetRolledBackCounters(state, telemetry)
        val lastDnsError = telemetry.lastDnsError.orEmpty()
        val keepCurrentPath =
            // ODoH fails closed: every built-in candidate would reveal the client address to the resolver.
            encryptedDns.isOdoh ||
                isLocalTunnelDnsFailure(lastDnsError) ||
                // With no network at all, every resolver fails; that says nothing about a path.
                networkFingerprintProvider.capture() == null ||
                (source.usesSharedProxy && isAmbiguousProxyFailure(lastDnsError))
        return if (keepCurrentPath) {
            // These failures cannot identify a blocked DNS endpoint (or failover is not allowed).
            // Consume their counters so they cannot trigger a later endpoint failover.
            state.lastObservedDnsQueriesTotal = telemetry.dnsQueriesTotal
            state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
            state.lastObservedResolverFailuresTotal = telemetry.dnsResolverFailuresTotal
            state.pathStartQueries = telemetry.dnsQueriesTotal
            state.pathStartFailures = telemetry.dnsFailuresTotal
            state.consecutiveFailureEvents = 0
            false
        } else {
            observeSuccessfulPath(state, currentPath, networkScopeKey, telemetry)
            failoverAfterFailure(state, encryptedDns, currentPath, networkScopeKey, telemetry, source.usesSharedProxy)
        }
    }

    private suspend fun synchronizeNetworkScope(
        state: VpnEncryptedDnsFailoverState,
        networkScopeKey: String?,
    ) {
        if (state.networkScopeKey == networkScopeKey) return
        state.resetAll()
        state.networkScopeKey = networkScopeKey
        reloadNetworkPreferences(state, networkScopeKey)
        log.d { "DNS network changed; hasPreferredPath=${state.preferredPath != null}" }
    }

    private suspend fun observeResolverChange(
        state: VpnEncryptedDnsFailoverState,
        currentPath: EncryptedDnsPathCandidate,
        currentDnsSignature: String?,
        networkScopeKey: String?,
        telemetry: NativeRuntimeSnapshot,
    ) {
        val currentPathKey = currentPath.pathKey()
        val resolverChanged =
            state.currentPathKey != currentPathKey || state.currentDnsSignature != currentDnsSignature
        if (!resolverChanged) return

        val controllerActivatedPath = state.expectedPathKey == currentPathKey
        if (!controllerActivatedPath) {
            state.resetTracking()
            state.networkScopeKey = networkScopeKey
            reloadNetworkPreferences(state, networkScopeKey)
        }
        state.currentPathKey = currentPathKey
        state.currentDnsSignature = currentDnsSignature
        resetCounters(state, telemetry.dnsQueriesTotal, telemetry.dnsFailuresTotal, telemetry.dnsResolverFailuresTotal)
        state.currentPathSelectedByFailover = controllerActivatedPath
        state.currentPathPersisted = false
        state.attemptedPathKeys += currentPathKey
        state.expectedPathKey = null
        log.d { "DNS resolver changed; failover=$controllerActivatedPath attempts=${state.attemptedPathKeys.size}" }
    }

    private fun resetRolledBackCounters(
        state: VpnEncryptedDnsFailoverState,
        telemetry: NativeRuntimeSnapshot,
    ) {
        val countersRolledBack =
            telemetry.dnsQueriesTotal < state.lastObservedDnsQueriesTotal ||
                telemetry.dnsFailuresTotal < state.lastObservedDnsFailuresTotal ||
                telemetry.dnsResolverFailuresTotal < state.lastObservedResolverFailuresTotal
        if (!countersRolledBack) return

        log.i {
            "dns counters restarted at queries=${telemetry.dnsQueriesTotal}, failures=${telemetry.dnsFailuresTotal}"
        }
        resetCounters(state, telemetry.dnsQueriesTotal, telemetry.dnsFailuresTotal, telemetry.dnsResolverFailuresTotal)
    }

    private fun resetCounters(
        state: VpnEncryptedDnsFailoverState,
        queries: Long,
        failures: Long,
        resolverFailures: Long = 0,
    ) {
        state.pathStartQueries = queries
        state.pathStartFailures = failures
        state.lastObservedDnsQueriesTotal = queries
        state.lastObservedDnsFailuresTotal = failures
        state.lastObservedResolverFailuresTotal = resolverFailures
        state.consecutiveFailureEvents = 0
    }

    private suspend fun reloadNetworkPreferences(
        state: VpnEncryptedDnsFailoverState,
        networkScopeKey: String?,
    ) {
        state.preferredPath = networkScopeKey?.let { networkDnsPathPreferenceStore.getPreferredPath(it) }
        state.blockedPathKeys = networkScopeKey?.let { networkDnsBlockedPathStore.getBlockedPathKeys(it) }.orEmpty()
    }

    private suspend fun observeSuccessfulPath(
        state: VpnEncryptedDnsFailoverState,
        currentPath: EncryptedDnsPathCandidate,
        networkScopeKey: String?,
        telemetry: NativeRuntimeSnapshot,
    ) {
        val successfulQueries =
            (telemetry.dnsQueriesTotal - state.pathStartQueries) -
                (telemetry.dnsFailuresTotal - state.pathStartFailures)
        if (successfulQueries <= 0 || !telemetry.lastDnsError.isNullOrBlank()) return

        if (state.consecutiveFailureEvents > 0) {
            log.d { "DNS recovered after ${state.consecutiveFailureEvents} failures" }
        }
        state.consecutiveFailureEvents = 0
        if (state.exhausted) {
            state.exhausted = false
            state.attemptedPathKeys.clear()
            state.attemptedPathKeys += currentPath.pathKey()
        }
        if (!state.currentPathSelectedByFailover || state.currentPathPersisted) return

        val fingerprint = networkFingerprintProvider.capture()
        if (fingerprint != null && fingerprint.scopeKey() == networkScopeKey) {
            networkDnsPathPreferenceStore.rememberPreferredPath(fingerprint, currentPath)
            state.preferredPath = currentPath
            state.currentPathPersisted = true
            log.i { "Stored the successful DNS failover path" }
        }
    }

    private suspend fun failoverAfterFailure(
        state: VpnEncryptedDnsFailoverState,
        encryptedDns: ActiveDnsSettings,
        currentPath: EncryptedDnsPathCandidate,
        networkScopeKey: String?,
        telemetry: NativeRuntimeSnapshot,
        dnsUsesSharedProxy: Boolean,
    ): Boolean {
        val failureObserved = observeFailureThreshold(state, telemetry, dnsUsesSharedProxy)
        val retryExhausted =
            state.exhausted && state.consecutiveFailureEvents >= FailoverThreshold &&
                !telemetry.lastDnsError.isNullOrBlank() &&
                clock.nowMillis() - state.exhaustedAt >= ExhaustedRetryAfterMs
        if (retryExhausted) {
            // The candidates may have failed during an outage that is over now; try them again.
            state.attemptedPathKeys.clear()
            state.attemptedPathKeys += currentPath.pathKey()
            state.exhausted = false
            reloadNetworkPreferences(state, networkScopeKey)
        }
        if ((!failureObserved && !retryExhausted) || state.exhausted) return false

        // The session scope lags a deferred (captive-portal) handover; use the live network.
        val liveScopeKey = networkFingerprintProvider.capture()?.scopeKey()
        if (liveScopeKey == networkScopeKey) {
            recordBlockedPath(state, currentPath.pathKey(), networkScopeKey, telemetry.lastDnsError.orEmpty())
        }
        val nextPath = selectNextPath(state, encryptedDns, currentPath.pathKey())
        return if (nextPath == null) {
            log.w { "all candidates exhausted after ${state.attemptedPathKeys.size} attempts" }
            state.exhausted = true
            state.exhaustedAt = clock.nowMillis()
            false
        } else {
            activatePath(state, nextPath, telemetry.lastDnsError.orEmpty(), liveScopeKey)
            true
        }
    }

    private fun observeFailureThreshold(
        state: VpnEncryptedDnsFailoverState,
        telemetry: NativeRuntimeSnapshot,
        dnsUsesSharedProxy: Boolean,
    ): Boolean {
        val queryDelta = telemetry.dnsQueriesTotal - state.lastObservedDnsQueriesTotal
        val resolverDelta = telemetry.dnsResolverFailuresTotal - state.lastObservedResolverFailuresTotal
        val failureDelta = telemetry.dnsFailuresTotal - state.lastObservedDnsFailuresTotal
        state.lastObservedDnsQueriesTotal = telemetry.dnsQueriesTotal
        state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
        state.lastObservedResolverFailuresTotal = telemetry.dnsResolverFailuresTotal
        if (failureDelta <= 0L || telemetry.lastDnsError.isNullOrBlank()) return false

        val observedSuccess = queryDelta > failureDelta
        if (observedSuccess) state.consecutiveFailureEvents = 0
        // Mixed samples and shared-proxy samples can only attribute the latest failure.
        val count =
            if (dnsUsesSharedProxy || queryDelta != failureDelta) {
                1
            } else {
                resolverDelta.coerceIn(1, minOf(failureDelta, FailoverThreshold.toLong())).toInt()
            }
        state.consecutiveFailureEvents =
            (state.consecutiveFailureEvents + count)
                .coerceAtMost(FailoverThreshold)
        val failureClass = classifyBlockReason(telemetry.lastDnsError.orEmpty()) ?: "other"
        log.w { "DNS failure count=${state.consecutiveFailureEvents} failureClass=$failureClass" }
        val queriesSincePathStart = telemetry.dnsQueriesTotal - state.pathStartQueries
        if (!observedSuccess && queriesSincePathStart <= EagerFailoverMaxQueries &&
            isCatastrophicDnsError(telemetry.lastDnsError.orEmpty())
        ) {
            log.w { "catastrophic error on bootstrap, eager failover triggered (queries=$queriesSincePathStart)" }
            state.consecutiveFailureEvents = FailoverThreshold
        }
        val thresholdReached = state.consecutiveFailureEvents >= FailoverThreshold
        if (!thresholdReached) {
            log.d { "failure #${state.consecutiveFailureEvents} < threshold $FailoverThreshold, waiting" }
        }
        return thresholdReached
    }

    private suspend fun recordBlockedPath(
        state: VpnEncryptedDnsFailoverState,
        currentPathKey: String,
        networkScopeKey: String?,
        error: String,
    ) {
        val blockReason = classifyBlockReason(error) ?: return
        val fingerprintHash = networkScopeKey ?: return
        networkDnsBlockedPathStore.recordBlockedPath(fingerprintHash, currentPathKey, blockReason)
        state.blockedPathKeys = state.blockedPathKeys + currentPathKey
        log.w { "DNS path blocked; reason=$blockReason" }
    }

    private fun selectNextPath(
        state: VpnEncryptedDnsFailoverState,
        encryptedDns: ActiveDnsSettings,
        currentPathKey: String,
    ): EncryptedDnsPathCandidate? =
        buildEncryptedDnsCandidatePlan(encryptedDns, state.preferredPath, state.blockedPathKeys)
            .firstOrNull { candidate ->
                val candidatePathKey = candidate.pathKey()
                candidatePathKey != currentPathKey && candidatePathKey !in state.attemptedPathKeys
            }

    private suspend fun activatePath(
        state: VpnEncryptedDnsFailoverState,
        nextPath: EncryptedDnsPathCandidate,
        lastDnsError: String,
        networkScopeKey: String?,
    ) {
        resolverOverrideStore.setTemporaryOverride(
            nextPath.toTemporaryResolverOverride(
                reason = buildAutoFailoverReason(lastDnsError),
                appliedAt = clock.nowMillis(),
                networkScopeKey = networkScopeKey,
            ),
        )
        state.expectedPathKey = nextPath.pathKey()
        log.i { "Switching DNS path; attempt=${state.attemptedPathKeys.size}" }
        state.attemptedPathKeys += state.expectedPathKey.orEmpty()
        state.consecutiveFailureEvents = 0
        state.exhausted = false
        state.currentPathPersisted = false
    }

    internal fun isAmbiguousProxyFailure(error: String): Boolean {
        val lower = error.lowercase()
        if ("certificate" in lower || lower.startsWith("http:") || lower.startsWith("decode:")) return false
        return lower.startsWith("connect:") || lower.startsWith("timeout:") || lower.startsWith("sniblocked:") ||
            lower.startsWith("tls:") ||
            isCatastrophicDnsError(error) || "socks5" in lower || "timeout" in lower || "timed out" in lower ||
            "unexpected eof" in lower || "end of file" in lower || "connection closed" in lower
    }

    /**
     * Transient failures that the tunnel produces locally say nothing about the resolver path.
     * The strings match `ripdpi-tunnel-core` `DnsCacheError` and its SERVFAIL reasons.
     * A used-up address pool is recovered by a tunnel rebuild in `VpnTelemetryCoordinator`.
     * shortcut: a dead DNS worker still fails over, because the resulting rebuild is its only
     * recovery; upgrade when the telemetry reports that state distinctly.
     */
    internal fun isLocalTunnelDnsFailure(error: String): Boolean {
        val lower = error.lowercase()
        return isSyntheticDnsPoolUsedUp(error) ||
            "synthetic dns mappings are leased by active flows" in lower ||
            "dns worker queue full" in lower || "response buffer smaller than request" in lower
    }

    internal fun buildAutoFailoverReason(lastDnsError: String): String {
        val failureClass = classifyBlockReason(lastDnsError) ?: "unknown_error"
        return "$AutoFailoverReasonPrefix: $failureClass"
    }

    internal fun classifyBlockReason(error: String): String? {
        val lower = error.lowercase()
        return when {
            "connection reset" in lower || "broken pipe" in lower || "connection abort" in lower -> "sni_blocked"
            "invalid peer certificate" in lower || "certificate" in lower -> "sni_blocked"
            "timed out" in lower || "timeout" in lower -> "timeout"
            "tls" in lower && ("handshake" in lower || "alert" in lower) -> "tls_error"
            else -> null
        }
    }

    internal fun isCatastrophicDnsError(error: String): Boolean {
        val lower = error.lowercase()
        return "connection reset" in lower ||
            "connection refused" in lower ||
            "connection abort" in lower ||
            "operation not permitted" in lower ||
            "broken pipe" in lower
    }
}

/** Matches `ripdpi-tunnel-core` `DnsCacheError::SyntheticNetworkExhausted`. */
internal fun isSyntheticDnsPoolUsedUp(error: String): Boolean =
    "all synthetic network addresses have already been issued" in error.lowercase()
