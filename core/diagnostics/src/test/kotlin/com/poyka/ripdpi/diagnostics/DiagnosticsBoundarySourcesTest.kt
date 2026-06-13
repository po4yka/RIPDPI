package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsBoundarySourcesTest {
    private val mapper = DiagnosticsBoundaryMapper(Json { ignoreUnknownKeys = true })

    @Test
    fun `remembered policy source clearAll clears remembered policies and dns path preferences`() =
        runTest {
            val rememberedStore = CountingRememberedNetworkPolicyStore()
            val dnsPathStore = CountingNetworkDnsPathPreferenceStore()
            val blockedStore = CountingNetworkDnsBlockedPathStore()
            val edgeStore = CountingNetworkEdgePreferenceRecordStore()
            val source =
                DefaultDiagnosticsRememberedPolicySource(
                    rememberedNetworkPolicyStore = rememberedStore,
                    networkDnsPathPreferenceStore = dnsPathStore,
                    networkDnsBlockedPathStore = blockedStore,
                    networkEdgePreferenceRecordStore = edgeStore,
                    mapper = mapper,
                )

            source.clearAll()

            assertEquals(1, rememberedStore.clearCalls)
            assertEquals(1, dnsPathStore.clearCalls)
            assertEquals(1, blockedStore.clearAllCalls)
            assertEquals(1, edgeStore.clearNetworkEdgeCalls)
        }

    @Test
    fun `deletePolicy purges companion per-network state when fingerprint has no remaining policies`() =
        runTest {
            val rememberedStore = CountingRememberedNetworkPolicyStore().apply { remainingForFingerprint = 0 }
            val dnsPathStore = CountingNetworkDnsPathPreferenceStore()
            val blockedStore = CountingNetworkDnsBlockedPathStore()
            val edgeStore = CountingNetworkEdgePreferenceRecordStore()
            val source =
                DefaultDiagnosticsRememberedPolicySource(
                    rememberedNetworkPolicyStore = rememberedStore,
                    networkDnsPathPreferenceStore = dnsPathStore,
                    networkDnsBlockedPathStore = blockedStore,
                    networkEdgePreferenceRecordStore = edgeStore,
                    mapper = mapper,
                )

            source.deletePolicy(diagnosticsRememberedPolicy(id = 9L, fingerprintHash = "scope-a"))

            assertEquals(listOf(9L), rememberedStore.deletedIds)
            assertEquals(listOf("scope-a"), dnsPathStore.deletedFingerprints)
            assertEquals(listOf("scope-a"), blockedStore.clearedFingerprints)
            assertEquals(listOf("scope-a"), edgeStore.deletedFingerprints)
        }

    @Test
    fun `deletePolicy keeps companion state when another mode still references the fingerprint`() =
        runTest {
            val rememberedStore = CountingRememberedNetworkPolicyStore().apply { remainingForFingerprint = 1 }
            val dnsPathStore = CountingNetworkDnsPathPreferenceStore()
            val blockedStore = CountingNetworkDnsBlockedPathStore()
            val edgeStore = CountingNetworkEdgePreferenceRecordStore()
            val source =
                DefaultDiagnosticsRememberedPolicySource(
                    rememberedNetworkPolicyStore = rememberedStore,
                    networkDnsPathPreferenceStore = dnsPathStore,
                    networkDnsBlockedPathStore = blockedStore,
                    networkEdgePreferenceRecordStore = edgeStore,
                    mapper = mapper,
                )

            source.deletePolicy(diagnosticsRememberedPolicy(id = 9L, fingerprintHash = "scope-a"))

            assertEquals(listOf(9L), rememberedStore.deletedIds)
            assertEquals(emptyList<String>(), dnsPathStore.deletedFingerprints)
            assertEquals(emptyList<String>(), blockedStore.clearedFingerprints)
            assertEquals(emptyList<String>(), edgeStore.deletedFingerprints)
        }

    @Test
    fun `remembered policy source maps unexpected storage values to unknown`() =
        runTest {
            val rememberedStore =
                CountingRememberedNetworkPolicyStore().apply {
                    policies.value =
                        listOf(
                            RememberedNetworkPolicyEntity(
                                id = 1L,
                                fingerprintHash = "fingerprint",
                                mode = Mode.VPN.preferenceValue,
                                summaryJson = "{}",
                                proxyConfigJson = "{}",
                                source = "unexpected_source",
                                status = "observed",
                                firstObservedAt = 1L,
                                updatedAt = 1L,
                            ),
                        )
                }
            val source =
                DefaultDiagnosticsRememberedPolicySource(
                    rememberedNetworkPolicyStore = rememberedStore,
                    networkDnsPathPreferenceStore = CountingNetworkDnsPathPreferenceStore(),
                    networkDnsBlockedPathStore = CountingNetworkDnsBlockedPathStore(),
                    networkEdgePreferenceRecordStore = CountingNetworkEdgePreferenceRecordStore(),
                    mapper = mapper,
                )

            val policy = source.observePolicies(limit = 1).first().single()

            assertEquals(RememberedNetworkPolicySource.UNKNOWN, policy.source)
        }

    @Test
    fun `active connection policy source retains policy store without external subscribers`() =
        runTest {
            val activePolicyStore = CountingActiveConnectionPolicyStore()
            val source =
                DefaultDiagnosticsActiveConnectionPolicySource(
                    activeConnectionPolicyStore = activePolicyStore,
                    mapper = mapper,
                    scope = backgroundScope,
                )

            runCurrent()

            assertEquals(1, activePolicyStore.subscriptionCount)

            activePolicyStore.policies.value =
                mapOf(Mode.VPN to activeConnectionPolicy(mode = Mode.VPN, fingerprintHash = "vpn-fp"))
            runCurrent()

            val activePolicy =
                source
                    .activePolicies
                    .value
                    .getValue(Mode.VPN)

            assertEquals("vpn-fp", activePolicy.policy.fingerprintHash)
        }

    @Test
    fun `active connection policy source releases policy store when owning scope is cancelled`() =
        runTest {
            val activePolicyStore = CountingActiveConnectionPolicyStore()
            val sourceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            DefaultDiagnosticsActiveConnectionPolicySource(
                activeConnectionPolicyStore = activePolicyStore,
                mapper = mapper,
                scope = sourceScope,
            )
            runCurrent()

            assertEquals(1, activePolicyStore.subscriptionCount)

            sourceScope.cancel()
            runCurrent()

            assertEquals(0, activePolicyStore.subscriptionCount)
        }

    @Test
    fun `scan session mapper decodes persisted automatic background launch metadata`() {
        val session =
            mapper.toDiagnosticScanSession(
                ScanSessionEntity(
                    id = "scan-1",
                    profileId = "automatic-probing",
                    pathMode = "RAW_PATH",
                    serviceMode = "VPN",
                    status = "completed",
                    summary = "Automatic probing finished",
                    reportJson = null,
                    startedAt = 10L,
                    finishedAt = 20L,
                    launchOrigin = DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND.storageValue,
                    triggerType = DiagnosticsScanTriggerType.POLICY_HANDOVER.storageValue,
                    triggerClassification = "transport_switch",
                    triggerOccurredAt = 9L,
                    triggerPreviousFingerprintHash = "fingerprint-a",
                    triggerCurrentFingerprintHash = "fingerprint-b",
                ),
            )

        assertEquals(DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND, session.launchOrigin)
        requireNotNull(session.launchTrigger).also { trigger ->
            assertEquals(DiagnosticsScanTriggerType.POLICY_HANDOVER, trigger.type)
            assertEquals("transport_switch", trigger.classification)
            assertEquals(9L, trigger.occurredAt)
            assertEquals("fingerprint-a", trigger.previousFingerprintHash)
            assertEquals("fingerprint-b", trigger.currentFingerprintHash)
        }
    }

    @Test
    fun `scan session mapper maps legacy null launch metadata to unknown`() {
        val session =
            mapper.toDiagnosticScanSession(
                ScanSessionEntity(
                    id = "scan-legacy",
                    profileId = "default",
                    pathMode = "RAW_PATH",
                    serviceMode = "VPN",
                    status = "completed",
                    summary = "Legacy scan",
                    reportJson = null,
                    startedAt = 10L,
                    finishedAt = 20L,
                ),
            )

        assertEquals(DiagnosticsScanLaunchOrigin.UNKNOWN, session.launchOrigin)
        assertEquals(null, session.launchTrigger)
    }

    @Test
    fun `connection session mapper decodes remembered policy audit and unexpected source safely`() {
        val session =
            mapper.toDiagnosticConnectionSession(
                BypassUsageSessionEntity(
                    id = "connection-1",
                    startedAt = 10L,
                    finishedAt = 20L,
                    updatedAt = 20L,
                    serviceMode = "VPN",
                    connectionState = "Stopped",
                    health = "healthy",
                    approachProfileId = "profile-1",
                    approachProfileName = "Profile 1",
                    strategyId = "strategy-1",
                    strategyLabel = "Strategy 1",
                    strategyJson = "{}",
                    networkType = "wifi",
                    txBytes = 100L,
                    rxBytes = 200L,
                    totalErrors = 0L,
                    routeChanges = 0L,
                    restartCount = 0,
                    endedReason = "stopped",
                    rememberedPolicyMatchedFingerprintHash = "fp-match",
                    rememberedPolicySource = "unexpected_source",
                    rememberedPolicyAppliedByExactMatch = true,
                    rememberedPolicyPreviousSuccessCount = 4,
                    rememberedPolicyPreviousFailureCount = 1,
                    rememberedPolicyPreviousConsecutiveFailureCount = 0,
                ),
            )

        requireNotNull(session.rememberedPolicyAudit).also { audit ->
            assertEquals("fp-match", audit.matchedFingerprintHash)
            assertEquals(RememberedNetworkPolicySource.UNKNOWN, audit.source)
            assertEquals(true, audit.appliedByExactMatch)
            assertEquals(4, audit.previousSuccessCount)
            assertEquals(1, audit.previousFailureCount)
            assertEquals(0, audit.previousConsecutiveFailureCount)
        }
    }
}

private class CountingActiveConnectionPolicyStore : ActiveConnectionPolicyStore {
    val policies = MutableStateFlow<Map<Mode, ActiveConnectionPolicy>>(emptyMap())
    private val countedPolicies = CountingStateFlow(policies)
    val subscriptionCount: Int
        get() = countedPolicies.collectorCount

    override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> = countedPolicies
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class CountingStateFlow<T>(
    private val delegate: StateFlow<T>,
) : StateFlow<T> {
    var collectorCount: Int = 0
        private set

    override val replayCache: List<T>
        get() = delegate.replayCache

    override val value: T
        get() = delegate.value

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        collectorCount += 1
        try {
            delegate.collect(collector)
        } finally {
            collectorCount -= 1
        }
    }
}

private class CountingRememberedNetworkPolicyStore : RememberedNetworkPolicyStore {
    var clearCalls = 0
    val policies = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())

    override fun observePolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> = policies

    override suspend fun findValidatedMatch(
        fingerprintHash: String,
        mode: Mode,
    ): RememberedNetworkPolicyEntity? = null

    override suspend fun upsertObservedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: RememberedNetworkPolicySource,
        observedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("unused in test")

    override suspend fun rememberValidatedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: RememberedNetworkPolicySource,
        validatedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("unused in test")

    override suspend fun recordApplied(
        policy: RememberedNetworkPolicyEntity,
        appliedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("unused in test")

    override suspend fun recordSuccess(
        policy: RememberedNetworkPolicyEntity,
        validated: Boolean,
        strategySignatureJson: String?,
        completedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("unused in test")

    override suspend fun recordFailure(
        policy: RememberedNetworkPolicyEntity,
        failedAt: Long?,
        allowSuppression: Boolean,
    ): RememberedNetworkPolicyEntity = error("unused in test")

    val deletedIds = mutableListOf<Long>()
    var remainingForFingerprint = 0

    override suspend fun deletePolicy(
        id: Long,
        fingerprintHash: String,
    ): Int {
        deletedIds += id
        return remainingForFingerprint
    }

    override suspend fun clearAll() {
        clearCalls += 1
    }
}

private class CountingNetworkDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    var clearCalls = 0
    val deletedFingerprints = mutableListOf<String>()

    override suspend fun getPreferredPath(fingerprintHash: String) = null

    override suspend fun clearAll() {
        clearCalls += 1
    }

    override suspend fun deleteForFingerprint(fingerprintHash: String) {
        deletedFingerprints += fingerprintHash
    }

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: com.poyka.ripdpi.data.EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity = error("unused in test")
}

private class CountingNetworkDnsBlockedPathStore : com.poyka.ripdpi.data.diagnostics.NetworkDnsBlockedPathStore {
    var clearAllCalls = 0
    val clearedFingerprints = mutableListOf<String>()

    override suspend fun getBlockedPathKeys(fingerprintHash: String): Set<String> = emptySet()

    override suspend fun recordBlockedPath(
        fingerprintHash: String,
        pathKey: String,
        blockReason: String,
    ) = Unit

    override suspend fun clearAll() {
        clearAllCalls += 1
    }

    override suspend fun clearForFingerprint(fingerprintHash: String) {
        clearedFingerprints += fingerprintHash
    }
}

private class CountingNetworkEdgePreferenceRecordStore :
    com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceRecordStore {
    val deletedFingerprints = mutableListOf<String>()
    var clearNetworkEdgeCalls = 0

    override suspend fun getNetworkEdgePreference(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceEntity? = null

    override suspend fun getNetworkEdgePreferencesForFingerprint(
        fingerprintHash: String,
    ): List<com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceEntity> = emptyList()

    override suspend fun upsertNetworkEdgePreference(
        preference: com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceEntity,
    ): Long = 0L

    override suspend fun clearNetworkEdgePreferences() {
        clearNetworkEdgeCalls += 1
    }

    override suspend fun deleteNetworkEdgePreferencesForFingerprint(fingerprintHash: String) {
        deletedFingerprints += fingerprintHash
    }

    override suspend fun pruneNetworkEdgePreferences() = Unit
}

private fun diagnosticsRememberedPolicy(
    id: Long,
    fingerprintHash: String,
): DiagnosticsRememberedPolicy =
    DiagnosticsRememberedPolicy(
        id = id,
        fingerprintHash = fingerprintHash,
        mode = Mode.VPN.preferenceValue,
        summary =
            NetworkFingerprintSummary(
                transport = "wifi",
                networkState = "validated",
                identityKind = "wifi",
                privateDnsMode = "system",
                dnsServerCount = 2,
            ),
        proxyConfigJson = "{}",
        source = RememberedNetworkPolicySource.MANUAL_SESSION,
        status = "validated",
        firstObservedAt = 1L,
        updatedAt = 1L,
    )

private fun activeConnectionPolicy(
    mode: Mode,
    fingerprintHash: String,
): ActiveConnectionPolicy =
    ActiveConnectionPolicy(
        mode = mode,
        policy =
            RememberedNetworkPolicyJson(
                fingerprintHash = fingerprintHash,
                mode = mode.preferenceValue,
                summary =
                    NetworkFingerprintSummary(
                        transport = "wifi",
                        networkState = "validated",
                        identityKind = "wifi",
                        privateDnsMode = "system",
                        dnsServerCount = 2,
                    ),
                proxyConfigJson = "{}",
            ),
        policySignature = "${mode.preferenceValue}::$fingerprintHash",
        appliedAt = 123L,
    )
