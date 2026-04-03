package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.buildEncryptedDnsCandidatePlan
import com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.toEncryptedDnsPathCandidate
import com.poyka.ripdpi.data.toTemporaryResolverOverride

private const val FailoverThreshold = 2
private const val AutoFailoverReasonPrefix = "vpn_encrypted_dns_auto_failover"

internal class VpnEncryptedDnsFailoverState {
    var networkScopeKey: String? = null
    var preferredPath: com.poyka.ripdpi.data.EncryptedDnsPathCandidate? = null
    var currentPathKey: String? = null
    var currentDnsSignature: String? = null
    var expectedPathKey: String? = null
    var pathStartQueries: Long = 0
    var pathStartFailures: Long = 0
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
    suspend fun evaluate(
        state: VpnEncryptedDnsFailoverState,
        activeDns: ActiveDnsSettings?,
        currentDnsSignature: String?,
        networkScopeKey: String?,
        telemetry: NativeRuntimeSnapshot,
    ): Boolean {
        val encryptedDns =
            activeDns?.takeIf { it.isEncrypted } ?: run {
                state.resetAll()
                return false
            }
        val currentPath =
            encryptedDns.toEncryptedDnsPathCandidate() ?: run {
                state.resetAll()
                return false
            }

        if (state.networkScopeKey != networkScopeKey) {
            state.resetAll()
            state.networkScopeKey = networkScopeKey
            state.preferredPath =
                networkScopeKey?.let { fingerprintHash ->
                    networkDnsPathPreferenceStore.getPreferredPath(fingerprintHash)
                }
            state.blockedPathKeys =
                networkScopeKey?.let { fingerprintHash ->
                    networkDnsBlockedPathStore.getBlockedPathKeys(fingerprintHash)
                } ?: emptySet()
        }

        val currentPathKey = currentPath.pathKey()
        val controllerActivatedPath = state.expectedPathKey != null && state.expectedPathKey == currentPathKey
        val resolverChanged =
            state.currentPathKey != currentPathKey ||
                state.currentDnsSignature != currentDnsSignature
        if (resolverChanged) {
            if (!controllerActivatedPath) {
                state.resetTracking()
                state.networkScopeKey = networkScopeKey
                state.preferredPath =
                    networkScopeKey?.let { fingerprintHash ->
                        networkDnsPathPreferenceStore.getPreferredPath(fingerprintHash)
                    }
                state.blockedPathKeys =
                    networkScopeKey?.let { fingerprintHash ->
                        networkDnsBlockedPathStore.getBlockedPathKeys(fingerprintHash)
                    } ?: emptySet()
            }
            state.currentPathKey = currentPathKey
            state.currentDnsSignature = currentDnsSignature
            state.pathStartQueries = telemetry.dnsQueriesTotal
            state.pathStartFailures = telemetry.dnsFailuresTotal
            state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
            state.consecutiveFailureEvents = 0
            state.currentPathSelectedByFailover = controllerActivatedPath
            state.currentPathPersisted = false
            state.attemptedPathKeys += currentPathKey
            state.expectedPathKey = null
        }

        val successfulQueriesSincePathActivated =
            (telemetry.dnsQueriesTotal - state.pathStartQueries) -
                (telemetry.dnsFailuresTotal - state.pathStartFailures)
        val successEvent = successfulQueriesSincePathActivated > 0 && telemetry.lastDnsError.isNullOrBlank()
        if (successEvent) {
            state.consecutiveFailureEvents = 0
            if (state.currentPathSelectedByFailover && !state.currentPathPersisted) {
                val fingerprint = networkFingerprintProvider.capture()
                if (fingerprint != null && fingerprint.scopeKey() == networkScopeKey) {
                    networkDnsPathPreferenceStore.rememberPreferredPath(
                        fingerprint = fingerprint,
                        path = currentPath,
                    )
                    state.preferredPath = currentPath
                    state.currentPathPersisted = true
                }
            }
        }

        val failureEvent =
            telemetry.dnsFailuresTotal > state.lastObservedDnsFailuresTotal &&
                !telemetry.lastDnsError.isNullOrBlank()
        state.lastObservedDnsFailuresTotal = telemetry.dnsFailuresTotal
        if (!failureEvent || state.exhausted) {
            return false
        }

        state.consecutiveFailureEvents += 1

        // Eager failover: when very few queries have been attempted and the error
        // is catastrophic (connection reset, refused, etc.), skip the threshold and
        // failover immediately.  This prevents the service from halting before the
        // failover controller gets a second chance.
        val queriesSincePathStart = telemetry.dnsQueriesTotal - state.pathStartQueries
        if (queriesSincePathStart <= 3 && isCatastrophicDnsError(telemetry.lastDnsError.orEmpty())) {
            state.consecutiveFailureEvents = FailoverThreshold
        }

        if (state.consecutiveFailureEvents < FailoverThreshold) {
            return false
        }

        val blockReason = classifyBlockReason(telemetry.lastDnsError.orEmpty())
        if (blockReason != null && networkScopeKey != null) {
            networkDnsBlockedPathStore.recordBlockedPath(
                fingerprintHash = networkScopeKey,
                pathKey = currentPathKey,
                blockReason = blockReason,
            )
            state.blockedPathKeys = state.blockedPathKeys + currentPathKey
        }

        val nextPath =
            buildEncryptedDnsCandidatePlan(
                activeDns = encryptedDns,
                preferredPath = state.preferredPath,
                blockedPathKeys = state.blockedPathKeys,
            ).firstOrNull { candidate ->
                val candidatePathKey = candidate.pathKey()
                candidatePathKey != currentPathKey && candidatePathKey !in state.attemptedPathKeys
            }

        if (nextPath == null) {
            state.exhausted = true
            return false
        }

        resolverOverrideStore.setTemporaryOverride(
            nextPath.toTemporaryResolverOverride(
                reason = buildAutoFailoverReason(telemetry.lastDnsError.orEmpty()),
                appliedAt = clock.nowMillis(),
            ),
        )
        state.expectedPathKey = nextPath.pathKey()
        state.attemptedPathKeys += state.expectedPathKey.orEmpty()
        state.consecutiveFailureEvents = 0
        state.exhausted = false
        state.currentPathPersisted = false
        return true
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
