package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsBoundarySourcesTest {
    @Test
    fun `remembered policy source clearAll clears remembered policies and dns path preferences`() =
        runTest {
            val rememberedStore = CountingRememberedNetworkPolicyStore()
            val dnsPathStore = CountingNetworkDnsPathPreferenceStore()
            val source =
                DefaultDiagnosticsRememberedPolicySource(
                    rememberedNetworkPolicyStore = rememberedStore,
                    networkDnsPathPreferenceStore = dnsPathStore,
                    mapper = DiagnosticsBoundaryMapper(Json { ignoreUnknownKeys = true }),
                )

            source.clearAll()

            assertEquals(1, rememberedStore.clearCalls)
            assertEquals(1, dnsPathStore.clearCalls)
        }
}

private class CountingRememberedNetworkPolicyStore : RememberedNetworkPolicyStore {
    var clearCalls = 0

    override fun observePolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> = emptyFlow()

    override suspend fun findValidatedMatch(
        fingerprintHash: String,
        mode: Mode,
    ): RememberedNetworkPolicyEntity? = null

    override suspend fun upsertObservedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: String,
        observedAt: Long?,
    ): RememberedNetworkPolicyEntity = error("unused in test")

    override suspend fun rememberValidatedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: String,
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
