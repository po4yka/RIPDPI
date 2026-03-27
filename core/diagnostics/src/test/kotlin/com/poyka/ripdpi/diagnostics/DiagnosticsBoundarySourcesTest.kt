package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsBoundarySourcesTest {
    private val mapper = DiagnosticsBoundaryMapper(Json { ignoreUnknownKeys = true })

    @Test
    fun `remembered policy source clearAll clears remembered policies and dns path preferences`() =
        runTest {
            val rememberedStore = CountingRememberedNetworkPolicyStore()
            val dnsPathStore = CountingNetworkDnsPathPreferenceStore()
            val source =
                DefaultDiagnosticsRememberedPolicySource(
                    rememberedNetworkPolicyStore = rememberedStore,
                    networkDnsPathPreferenceStore = dnsPathStore,
                    mapper = mapper,
                )

            source.clearAll()

            assertEquals(1, rememberedStore.clearCalls)
            assertEquals(1, dnsPathStore.clearCalls)
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
                    mapper = mapper,
                )

            val policy = source.observePolicies(limit = 1).first().single()

            assertEquals(RememberedNetworkPolicySource.UNKNOWN, policy.source)
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

    override suspend fun clearAll() {
        clearCalls += 1
    }
}

private class CountingNetworkDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    var clearCalls = 0

    override suspend fun getPreferredPath(fingerprintHash: String) = null

    override suspend fun clearAll() {
        clearCalls += 1
    }

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: com.poyka.ripdpi.data.EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity = error("unused in test")
}
