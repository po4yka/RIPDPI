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
private const val AutoFailoverReasonPrefix = "vpn_encrypted_dns_auto_failover"

internal class VpnEncryptedDnsFailoverState {
    var networkScopeKey: String? = null
    var preferredPath: EncryptedDnsPathCandidate? = null
    var currentPathKey: String? = null
    var currentDnsSignature: String? = null
    var expectedPathKey: String? = null
    var pathStartQueries: Long = 0
    var pathStartFailures: Long = 0
    var lastObservedDnsQueriesTotal: Long = 0
    var lastObservedDnsFailuresTotal: Long = 0
    var consecutiveFailureEvents: Int = 0
    var exhausted: Boolean = false
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
        consecutiveFailureEvents = 0
        exhausted = false
        currentPathSelectedByFailover = false
        currentPathPersisted = false
        attemptedPathKeys.clear()
    }
}

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
    ): Boolean {
        val encryptedDns = activeDns?.takeIf { it.isEncrypted }
        val currentPath = encryptedDns?.toEncryptedDnsPathCandidate()
        if (encryptedDns == null || currentPath == null) {
            state.resetAll()
            return false
        }

        synchronizeNetworkScope(state, networkScopeKey)
        observeResolverChange(state, currentPath, currentDnsSignature, networkScopeKey, telemetry)
        resetRolledBackCounters(state, telemetry)
        observeSuccessfulPath(state, currentPath, networkScopeKey, telemetry)
        return failoverAfterFailure(state, encryptedDns, currentPath, networkScopeKey, telemetry)
    }

    private suspend fun synchronizeNetworkScope(
        state: VpnEncryptedDnsFailoverState,
        networkScopeKey: String?,
    ) {
        if (state.networkScopeKey == networkScopeKey) return
        state.resetAll()
        state.networkScopeKey = networkScopeKey
        reloadNetworkPreferences(state, networkScopeKey)
        log.d { "network scope changed to $networkScopeKey, preferred=${state.preferredPath?.pathKey()}" }
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
        state.pathStartQueries = telemetry.dnsQueriesTotal
        state.pathStartFailures = telemetry.dnsFailuresTotal
        state.lastObservedDnsQueriesTotal = telemetry.dnsQueriesTotal
        state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
        state.consecutiveFailureEvents = 0
        state.currentPathSelectedByFailover = controllerActivatedPath
        state.currentPathPersisted = false
        state.attemptedPathKeys += currentPathKey
        state.expectedPathKey = null
        log.d { "resolver changed pathKey=$currentPathKey attempts=${state.attemptedPathKeys.size}" }
    }

    private fun resetRolledBackCounters(
        state: VpnEncryptedDnsFailoverState,
        telemetry: NativeRuntimeSnapshot,
    ) {
        val countersRolledBack =
            telemetry.dnsQueriesTotal < state.lastObservedDnsQueriesTotal ||
                telemetry.dnsFailuresTotal < state.lastObservedDnsFailuresTotal
        state.lastObservedDnsQueriesTotal = telemetry.dnsQueriesTotal
        if (!countersRolledBack) return

        log.i {
            "dns counters restarted at queries=${telemetry.dnsQueriesTotal}, failures=${telemetry.dnsFailuresTotal}"
        }
        state.pathStartQueries = telemetry.dnsQueriesTotal
        state.pathStartFailures = telemetry.dnsFailuresTotal
        state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
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

        log.d { "success on path ${state.currentPathKey}, resetting failure counter" }
        state.consecutiveFailureEvents = 0
        if (!state.currentPathSelectedByFailover || state.currentPathPersisted) return

        val fingerprint = networkFingerprintProvider.capture()
        if (fingerprint != null && fingerprint.scopeKey() == networkScopeKey) {
            networkDnsPathPreferenceStore.rememberPreferredPath(fingerprint, currentPath)
            state.preferredPath = currentPath
            state.currentPathPersisted = true
            log.i { "persisted preferred path ${state.currentPathKey} for $networkScopeKey" }
        }
    }

    private suspend fun failoverAfterFailure(
        state: VpnEncryptedDnsFailoverState,
        encryptedDns: ActiveDnsSettings,
        currentPath: EncryptedDnsPathCandidate,
        networkScopeKey: String?,
        telemetry: NativeRuntimeSnapshot,
    ): Boolean {
        val failureObserved = observeFailureThreshold(state, telemetry)
        if (!failureObserved || state.exhausted) return false

        recordBlockedPath(state, currentPath.pathKey(), networkScopeKey, telemetry.lastDnsError.orEmpty())
        val nextPath = selectNextPath(state, encryptedDns, currentPath.pathKey())
        return if (nextPath == null) {
            log.w { "all candidates exhausted after ${state.attemptedPathKeys.size} attempts" }
            state.exhausted = true
            false
        } else {
            activatePath(state, nextPath, telemetry.lastDnsError.orEmpty())
            true
        }
    }

    private fun observeFailureThreshold(
        state: VpnEncryptedDnsFailoverState,
        telemetry: NativeRuntimeSnapshot,
    ): Boolean {
        val failureEvent =
            telemetry.dnsFailuresTotal > state.lastObservedDnsFailuresTotal &&
                !telemetry.lastDnsError.isNullOrBlank()
        state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
        if (!failureEvent) return false

        state.consecutiveFailureEvents += 1
        log.w { "failure #${state.consecutiveFailureEvents} error=${telemetry.lastDnsError}" }
        val queriesSincePathStart = telemetry.dnsQueriesTotal - state.pathStartQueries
        if (queriesSincePathStart <= EagerFailoverMaxQueries &&
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
        log.w { "path $currentPathKey blocked reason=$blockReason" }
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
    ) {
        resolverOverrideStore.setTemporaryOverride(
            nextPath.toTemporaryResolverOverride(buildAutoFailoverReason(lastDnsError), clock.nowMillis()),
        )
        state.expectedPathKey = nextPath.pathKey()
        log.i { "switching to ${nextPath.pathKey()} (attempt #${state.attemptedPathKeys.size})" }
        state.attemptedPathKeys += state.expectedPathKey.orEmpty()
        state.consecutiveFailureEvents = 0
        state.exhausted = false
        state.currentPathPersisted = false
    }

    internal fun buildAutoFailoverReason(lastDnsError: String): String {
        val normalizedError = lastDnsError.trim().ifEmpty { "unknown_error" }
        return "$AutoFailoverReasonPrefix: $normalizedError"
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
