package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceManualSession
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.RememberedNetworkPolicySuppressionDurationMs
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.toActiveDnsSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface RememberedNetworkPolicyStore {
    fun observePolicies(limit: Int = 64): Flow<List<RememberedNetworkPolicyEntity>>

    suspend fun findValidatedMatch(
        fingerprintHash: String,
        mode: Mode,
    ): RememberedNetworkPolicyEntity?

    suspend fun upsertObservedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: String = RememberedNetworkPolicySourceManualSession,
        observedAt: Long? = null,
    ): RememberedNetworkPolicyEntity

    suspend fun rememberValidatedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: String,
        validatedAt: Long? = null,
    ): RememberedNetworkPolicyEntity

    suspend fun recordApplied(
        policy: RememberedNetworkPolicyEntity,
        appliedAt: Long? = null,
    ): RememberedNetworkPolicyEntity

    suspend fun recordSuccess(
        policy: RememberedNetworkPolicyEntity,
        validated: Boolean = false,
        strategySignatureJson: String? = null,
        completedAt: Long? = null,
    ): RememberedNetworkPolicyEntity

    suspend fun recordFailure(
        policy: RememberedNetworkPolicyEntity,
        failedAt: Long? = null,
        allowSuppression: Boolean = true,
    ): RememberedNetworkPolicyEntity

    suspend fun clearAll()
}

@Singleton
class DefaultRememberedNetworkPolicyStore
    @Inject
    constructor(
        private val recordStore: RememberedNetworkPolicyRecordStore,
        private val clock: DiagnosticsHistoryClock,
    ) : RememberedNetworkPolicyStore {
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        override fun observePolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
            recordStore.observeRememberedNetworkPolicies(limit)

        override suspend fun findValidatedMatch(
            fingerprintHash: String,
            mode: Mode,
        ): RememberedNetworkPolicyEntity? =
            recordStore.findValidatedRememberedNetworkPolicy(
                fingerprintHash = fingerprintHash,
                mode = mode.preferenceValue,
            )

        override suspend fun upsertObservedPolicy(
            policy: RememberedNetworkPolicyJson,
            source: String,
            observedAt: Long?,
        ): RememberedNetworkPolicyEntity =
            upsertInternal(
                existing =
                    recordStore.getRememberedNetworkPolicy(
                        fingerprintHash = policy.fingerprintHash,
                        mode = policy.mode,
                    ),
                policy = policy,
                source = source,
                status = RememberedNetworkPolicyStatusObserved,
                validatedAt = null,
                now = observedAt ?: clock.now(),
            )

        override suspend fun rememberValidatedPolicy(
            policy: RememberedNetworkPolicyJson,
            source: String,
            validatedAt: Long?,
        ): RememberedNetworkPolicyEntity {
            val effectiveValidatedAt = validatedAt ?: clock.now()
            return upsertInternal(
                existing =
                    recordStore.getRememberedNetworkPolicy(
                        fingerprintHash = policy.fingerprintHash,
                        mode = policy.mode,
                    ),
                policy = policy,
                source = source,
                status = RememberedNetworkPolicyStatusValidated,
                validatedAt = effectiveValidatedAt,
                now = effectiveValidatedAt,
            )
        }

        override suspend fun recordApplied(
            policy: RememberedNetworkPolicyEntity,
            appliedAt: Long?,
        ): RememberedNetworkPolicyEntity =
            (appliedAt ?: clock.now()).let { effectiveAppliedAt ->
                persist(policy.copy(lastAppliedAt = effectiveAppliedAt, updatedAt = effectiveAppliedAt))
            }

        override suspend fun recordSuccess(
            policy: RememberedNetworkPolicyEntity,
            validated: Boolean,
            strategySignatureJson: String?,
            completedAt: Long?,
        ): RememberedNetworkPolicyEntity {
            val effectiveCompletedAt = completedAt ?: clock.now()
            val shouldValidate = validated || policy.status == RememberedNetworkPolicyStatusValidated
            return persist(
                policy.copy(
                    status = if (shouldValidate) RememberedNetworkPolicyStatusValidated else policy.status,
                    strategySignatureJson = strategySignatureJson ?: policy.strategySignatureJson,
                    successCount = policy.successCount + 1,
                    consecutiveFailureCount = 0,
                    suppressedUntil = null,
                    lastValidatedAt = if (shouldValidate) effectiveCompletedAt else policy.lastValidatedAt,
                    updatedAt = effectiveCompletedAt,
                ),
            )
        }

        override suspend fun recordFailure(
            policy: RememberedNetworkPolicyEntity,
            failedAt: Long?,
            allowSuppression: Boolean,
        ): RememberedNetworkPolicyEntity {
            val effectiveFailedAt = failedAt ?: clock.now()
            val nextConsecutiveFailures = policy.consecutiveFailureCount + 1
            val shouldSuppress =
                allowSuppression &&
                    policy.status == RememberedNetworkPolicyStatusValidated &&
                    nextConsecutiveFailures >= 2
            return persist(
                policy.copy(
                    status = if (shouldSuppress) RememberedNetworkPolicyStatusSuppressed else policy.status,
                    failureCount = policy.failureCount + 1,
                    consecutiveFailureCount = nextConsecutiveFailures,
                    suppressedUntil =
                        if (shouldSuppress) {
                            effectiveFailedAt + RememberedNetworkPolicySuppressionDurationMs
                        } else {
                            policy.suppressedUntil
                        },
                    updatedAt = effectiveFailedAt,
                ),
            )
        }

        override suspend fun clearAll() {
            recordStore.clearRememberedNetworkPolicies()
        }

        private suspend fun upsertInternal(
            existing: RememberedNetworkPolicyEntity?,
            policy: RememberedNetworkPolicyJson,
            source: String,
            status: String,
            validatedAt: Long?,
            now: Long,
        ): RememberedNetworkPolicyEntity {
            val contentChanged = existing?.matches(policy) == false
            val base =
                if (existing == null || contentChanged) {
                    RememberedNetworkPolicyEntity(
                        id = existing?.id ?: 0L,
                        fingerprintHash = policy.fingerprintHash,
                        mode = policy.mode,
                        summaryJson = json.encodeToString(NetworkFingerprintSummary.serializer(), policy.summary),
                        proxyConfigJson = policy.proxyConfigJson,
                        vpnDnsPolicyJson =
                            policy.vpnDnsPolicy?.let {
                                json.encodeToString(
                                    com.poyka.ripdpi.data.VpnDnsPolicyJson
                                        .serializer(),
                                    it,
                                )
                            },
                        strategySignatureJson = policy.strategySignatureJson,
                        winningTcpStrategyFamily = policy.winningTcpStrategyFamily,
                        winningQuicStrategyFamily = policy.winningQuicStrategyFamily,
                        source = source,
                        status = status,
                        successCount = if (status == RememberedNetworkPolicyStatusValidated) 1 else 0,
                        failureCount = 0,
                        consecutiveFailureCount = 0,
                        firstObservedAt = now,
                        lastValidatedAt = validatedAt,
                        lastAppliedAt = existing?.lastAppliedAt,
                        suppressedUntil = null,
                        updatedAt = now,
                    )
                } else {
                    existing.copy(
                        summaryJson = json.encodeToString(NetworkFingerprintSummary.serializer(), policy.summary),
                        proxyConfigJson = policy.proxyConfigJson,
                        vpnDnsPolicyJson =
                            policy.vpnDnsPolicy?.let {
                                json.encodeToString(
                                    com.poyka.ripdpi.data.VpnDnsPolicyJson
                                        .serializer(),
                                    it,
                                )
                            },
                        strategySignatureJson = policy.strategySignatureJson ?: existing.strategySignatureJson,
                        winningTcpStrategyFamily = policy.winningTcpStrategyFamily ?: existing.winningTcpStrategyFamily,
                        winningQuicStrategyFamily =
                            policy.winningQuicStrategyFamily ?: existing.winningQuicStrategyFamily,
                        source = source,
                        status = status,
                        successCount =
                            if (status == RememberedNetworkPolicyStatusValidated) {
                                existing.successCount + 1
                            } else {
                                existing.successCount
                            },
                        consecutiveFailureCount = 0,
                        lastValidatedAt = validatedAt ?: existing.lastValidatedAt,
                        suppressedUntil = null,
                        updatedAt = now,
                    )
                }
            return persist(base)
        }

        private suspend fun persist(policy: RememberedNetworkPolicyEntity): RememberedNetworkPolicyEntity {
            val id = recordStore.upsertRememberedNetworkPolicy(policy)
            recordStore.pruneRememberedNetworkPolicies()
            return policy.copy(id = if (policy.id > 0L) policy.id else id)
        }

        private fun RememberedNetworkPolicyEntity.matches(policy: RememberedNetworkPolicyJson): Boolean =
            fingerprintHash == policy.fingerprintHash &&
                mode == policy.mode &&
                proxyConfigJson == policy.proxyConfigJson &&
                vpnDnsPolicyJson ==
                policy.vpnDnsPolicy?.let {
                    json.encodeToString(
                        com.poyka.ripdpi.data.VpnDnsPolicyJson
                            .serializer(),
                        it,
                    )
                } &&
                strategySignatureJson == policy.strategySignatureJson &&
                winningTcpStrategyFamily == policy.winningTcpStrategyFamily &&
                winningQuicStrategyFamily == policy.winningQuicStrategyFamily
    }

fun RememberedNetworkPolicyEntity.decodeSummary(
    json: Json =
        Json {
            ignoreUnknownKeys = true
        },
): NetworkFingerprintSummary? =
    runCatching { json.decodeFromString(NetworkFingerprintSummary.serializer(), summaryJson) }.getOrNull()

fun RememberedNetworkPolicyEntity.toPolicyJson(
    json: Json = Json { ignoreUnknownKeys = true },
): RememberedNetworkPolicyJson? {
    val summary = decodeSummary(json) ?: return null
    val dnsPolicy =
        vpnDnsPolicyJson
            ?.takeIf { it.isNotBlank() }
            ?.let { payload ->
                runCatching {
                    json.decodeFromString(
                        com.poyka.ripdpi.data.VpnDnsPolicyJson
                            .serializer(),
                        payload,
                    )
                }.getOrNull()
            }
    return RememberedNetworkPolicyJson(
        fingerprintHash = fingerprintHash,
        mode = mode,
        summary = summary,
        proxyConfigJson = proxyConfigJson,
        vpnDnsPolicy = dnsPolicy,
        strategySignatureJson = strategySignatureJson,
        winningTcpStrategyFamily = winningTcpStrategyFamily,
        winningQuicStrategyFamily = winningQuicStrategyFamily,
        winningDnsStrategyFamily = dnsPolicy?.toActiveDnsSettings()?.strategyFamily(),
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RememberedNetworkPolicyStoreModule {
    @Binds
    @Singleton
    abstract fun bindRememberedNetworkPolicyStore(
        store: DefaultRememberedNetworkPolicyStore,
    ): RememberedNetworkPolicyStore
}
