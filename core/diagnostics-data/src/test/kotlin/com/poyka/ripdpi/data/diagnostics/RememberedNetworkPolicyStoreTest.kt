package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceManualSession
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.RememberedNetworkPolicySuppressionDurationMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RememberedNetworkPolicyStoreTest {
    private val clock = FixedRememberedPolicyClock(now = 4_000L)
    private val recordStore = FakeRememberedNetworkPolicyRecordStore()
    private val store = DefaultRememberedNetworkPolicyStore(recordStore, clock)

    @Test
    fun `upsert observed policy uses clock when observedAt omitted`() =
        runTest {
            val result =
                store.upsertObservedPolicy(
                    policy = rememberedPolicyJson(fingerprintHash = "fingerprint-observed"),
                    source = RememberedNetworkPolicySourceManualSession,
                )

            assertEquals(RememberedNetworkPolicyStatusObserved, result.status)
            assertEquals(clock.now(), result.firstObservedAt)
            assertEquals(clock.now(), result.updatedAt)
            assertNull(result.lastValidatedAt)
        }

    @Test
    fun `remember validated policy uses explicit validatedAt when provided`() =
        runTest {
            val result =
                store.rememberValidatedPolicy(
                    policy = rememberedPolicyJson(fingerprintHash = "fingerprint-validated"),
                    source = RememberedNetworkPolicySourceManualSession,
                    validatedAt = 1_234L,
                )

            assertEquals(RememberedNetworkPolicyStatusValidated, result.status)
            assertEquals(1_234L, result.firstObservedAt)
            assertEquals(1_234L, result.lastValidatedAt)
            assertEquals(1_234L, result.updatedAt)
        }

    @Test
    fun `record applied uses clock when appliedAt omitted`() =
        runTest {
            val result =
                store.recordApplied(
                    policy = rememberedPolicyEntity(status = RememberedNetworkPolicyStatusObserved),
                )

            assertEquals(clock.now(), result.lastAppliedAt)
            assertEquals(clock.now(), result.updatedAt)
        }

    @Test
    fun `record success uses explicit completedAt for validation transition`() =
        runTest {
            val result =
                store.recordSuccess(
                    policy = rememberedPolicyEntity(status = RememberedNetworkPolicyStatusObserved),
                    validated = true,
                    strategySignatureJson = "strategy-v2",
                    completedAt = 2_222L,
                )

            assertEquals(RememberedNetworkPolicyStatusValidated, result.status)
            assertEquals("strategy-v2", result.strategySignatureJson)
            assertEquals(2_222L, result.lastValidatedAt)
            assertEquals(2_222L, result.updatedAt)
        }

    @Test
    fun `record failure uses clock for suppression when failedAt omitted`() =
        runTest {
            val result =
                store.recordFailure(
                    policy =
                        rememberedPolicyEntity(
                            status = RememberedNetworkPolicyStatusValidated,
                            consecutiveFailureCount = 1,
                        ),
                )

            assertEquals(clock.now(), result.updatedAt)
            assertEquals(clock.now() + RememberedNetworkPolicySuppressionDurationMs, result.suppressedUntil)
            assertEquals(2, result.consecutiveFailureCount)
        }

    @Test
    fun `record failure uses explicit failedAt when provided`() =
        runTest {
            val result =
                store.recordFailure(
                    policy =
                        rememberedPolicyEntity(
                            status = RememberedNetworkPolicyStatusValidated,
                            consecutiveFailureCount = 1,
                        ),
                    failedAt = 9_999L,
                )

            assertEquals(9_999L, result.updatedAt)
            assertEquals(9_999L + RememberedNetworkPolicySuppressionDurationMs, result.suppressedUntil)
        }
}

private class FakeRememberedNetworkPolicyRecordStore : RememberedNetworkPolicyRecordStore {
    private val state = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())
    private var nextId = 1L

    override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> = state

    override suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        state.value.firstOrNull { it.fingerprintHash == fingerprintHash && it.mode == mode }

    override suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        state.value.firstOrNull {
            it.fingerprintHash == fingerprintHash &&
                it.mode == mode &&
                it.status == RememberedNetworkPolicyStatusValidated &&
                (it.suppressedUntil == null || it.suppressedUntil <= 0L)
        }

    override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long {
        val persisted =
            if (policy.id > 0L) {
                policy
            } else {
                policy.copy(id = nextId++)
            }
        state.value = state.value.filterNot { it.id == persisted.id } + persisted
        return persisted.id
    }

    override suspend fun clearRememberedNetworkPolicies() {
        state.value = emptyList()
    }

    override suspend fun pruneRememberedNetworkPolicies() = Unit
}

private fun rememberedPolicyJson(
    fingerprintHash: String,
    mode: Mode = Mode.VPN,
) = RememberedNetworkPolicyJson(
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
    proxyConfigJson = "{\"socks5Port\":1080}",
)

private fun rememberedPolicyEntity(
    status: String,
    consecutiveFailureCount: Int = 0,
) = RememberedNetworkPolicyEntity(
    id = 1L,
    fingerprintHash = "fingerprint-entity",
    mode = Mode.VPN.preferenceValue,
    summaryJson = "{}",
    proxyConfigJson = "{}",
    source = RememberedNetworkPolicySourceManualSession,
    status = status,
    successCount = if (status == RememberedNetworkPolicyStatusValidated) 1 else 0,
    consecutiveFailureCount = consecutiveFailureCount,
    firstObservedAt = 100L,
    lastValidatedAt = if (status == RememberedNetworkPolicyStatusValidated) 200L else null,
    updatedAt = 300L,
)

private class FixedRememberedPolicyClock(
    private val now: Long,
) : DiagnosticsHistoryClock {
    override fun now(): Long = now
}
