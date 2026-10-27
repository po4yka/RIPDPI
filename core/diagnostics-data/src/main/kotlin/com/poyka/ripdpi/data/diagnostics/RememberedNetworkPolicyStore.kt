package com.poyka.ripdpi.data.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedConnectionConcurrencyPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.RememberedNetworkPolicySuppressionDurationMs
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.toActiveDnsSettings
import com.poyka.ripdpi.serialization.RipDpiContractJson
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        source: RememberedNetworkPolicySource = RememberedNetworkPolicySource.MANUAL_SESSION,
        observedAt: Long? = null,
    ): RememberedNetworkPolicyEntity

    suspend fun rememberValidatedPolicy(
        policy: RememberedNetworkPolicyJson,
        source: RememberedNetworkPolicySource,
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

    /**
     * Deletes the remembered policy row identified by [id]. Returns the number of remaining
     * remembered policy rows that still reference [fingerprintHash] (across other modes).
     * A return value of `0` means the network scope is now fully forgotten and any companion
     * per-network learned state keyed by the same fingerprint can be safely purged.
     */
    suspend fun deletePolicy(
        id: Long,
        fingerprintHash: String,
    ): Int

    suspend fun clearAll()
}

/**
 * Serializes the read-modify-writes of `remembered_network_policies` made by
 * [DefaultRememberedNetworkPolicyStore], the terminal outbox policy checkpoint, the history reset and
 * the retention delete. Callers hold entity snapshots from different coroutines, so each mutation
 * re-reads the row under this lock, and a delete cannot fall between that read and the write.
 * Lock order: this lock, then a Room transaction.
 */
internal val rememberedNetworkPolicyWriteLock = Mutex()

@Singleton
class DefaultRememberedNetworkPolicyStore
    @Inject
    constructor(
        private val recordStore: RememberedNetworkPolicyRecordStore,
        private val clock: DiagnosticsHistoryClock,
    ) : RememberedNetworkPolicyStore {
        private val json =
            RipDpiContractJson

        private val writeLock = rememberedNetworkPolicyWriteLock

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
            source: RememberedNetworkPolicySource,
            observedAt: Long?,
        ): RememberedNetworkPolicyEntity =
            writeLock.withLock {
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
            }

        override suspend fun rememberValidatedPolicy(
            policy: RememberedNetworkPolicyJson,
            source: RememberedNetworkPolicySource,
            validatedAt: Long?,
        ): RememberedNetworkPolicyEntity {
            val effectiveValidatedAt = validatedAt ?: clock.now()
            return writeLock.withLock {
                upsertInternal(
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
        }

        override suspend fun recordApplied(
            policy: RememberedNetworkPolicyEntity,
            appliedAt: Long?,
        ): RememberedNetworkPolicyEntity =
            (appliedAt ?: clock.now()).let { effectiveAppliedAt ->
                mutateCurrent(policy) { current ->
                    current.copy(lastAppliedAt = effectiveAppliedAt, updatedAt = effectiveAppliedAt)
                }
            }

        override suspend fun recordSuccess(
            policy: RememberedNetworkPolicyEntity,
            validated: Boolean,
            strategySignatureJson: String?,
            completedAt: Long?,
        ): RememberedNetworkPolicyEntity {
            val effectiveCompletedAt = completedAt ?: clock.now()
            return mutateCurrent(policy) { current ->
                val shouldValidate = validated || current.status == RememberedNetworkPolicyStatusValidated
                current.copy(
                    status = if (shouldValidate) RememberedNetworkPolicyStatusValidated else current.status,
                    strategySignatureJson = strategySignatureJson ?: current.strategySignatureJson,
                    successCount = current.successCount + 1,
                    consecutiveFailureCount = 0,
                    suppressedUntil = null,
                    lastValidatedAt = if (shouldValidate) effectiveCompletedAt else current.lastValidatedAt,
                    updatedAt = effectiveCompletedAt,
                )
            }
        }

        override suspend fun recordFailure(
            policy: RememberedNetworkPolicyEntity,
            failedAt: Long?,
            allowSuppression: Boolean,
        ): RememberedNetworkPolicyEntity {
            val effectiveFailedAt = failedAt ?: clock.now()
            return mutateCurrent(policy) { current ->
                val nextConsecutiveFailures = current.consecutiveFailureCount + 1
                val shouldSuppress =
                    allowSuppression &&
                        current.status == RememberedNetworkPolicyStatusValidated &&
                        nextConsecutiveFailures >= 2
                current.copy(
                    status = if (shouldSuppress) RememberedNetworkPolicyStatusSuppressed else current.status,
                    failureCount = current.failureCount + 1,
                    consecutiveFailureCount = nextConsecutiveFailures,
                    suppressedUntil =
                        if (shouldSuppress) {
                            effectiveFailedAt + RememberedNetworkPolicySuppressionDurationMs
                        } else {
                            current.suppressedUntil
                        },
                    updatedAt = effectiveFailedAt,
                )
            }
        }

        override suspend fun deletePolicy(
            id: Long,
            fingerprintHash: String,
        ): Int =
            writeLock.withLock {
                recordStore.deleteRememberedNetworkPolicy(id)
                recordStore.countRememberedNetworkPoliciesForFingerprint(fingerprintHash)
            }

        override suspend fun clearAll() {
            writeLock.withLock { recordStore.clearRememberedNetworkPolicies() }
        }

        /**
         * Applies [transform] to the stored row, not to the caller's snapshot, so concurrent updates
         * are not lost. Without a stored row nothing is written and the snapshot is returned: only
         * the remember* calls create a policy, so a missing row was deleted (by the user or a clear),
         * and writing the snapshot would bring it back or REPLACE a newer row of the same network.
         */
        private suspend fun mutateCurrent(
            policy: RememberedNetworkPolicyEntity,
            transform: (RememberedNetworkPolicyEntity) -> RememberedNetworkPolicyEntity,
        ): RememberedNetworkPolicyEntity =
            writeLock.withLock {
                val current =
                    if (policy.id > 0L) {
                        recordStore.getRememberedNetworkPolicyById(policy.id)
                    } else {
                        recordStore.getRememberedNetworkPolicy(policy.fingerprintHash, policy.mode)
                    }
                if (current == null) policy else persist(transform(current))
            }

        private suspend fun upsertInternal(
            existing: RememberedNetworkPolicyEntity?,
            policy: RememberedNetworkPolicyJson,
            source: RememberedNetworkPolicySource,
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
                        connectionConcurrencyPolicyJson =
                            policy.connectionConcurrencyPolicy?.let {
                                json.encodeToString(RememberedConnectionConcurrencyPolicyJson.serializer(), it)
                            },
                        source = source.encodeStorageValue(),
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
                        connectionConcurrencyPolicyJson =
                            policy.connectionConcurrencyPolicy?.let {
                                json.encodeToString(RememberedConnectionConcurrencyPolicyJson.serializer(), it)
                            } ?: existing.connectionConcurrencyPolicyJson,
                        source = source.encodeStorageValue(),
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
                winningQuicStrategyFamily == policy.winningQuicStrategyFamily &&
                connectionConcurrencyPolicyJson ==
                policy.connectionConcurrencyPolicy?.let {
                    json.encodeToString(RememberedConnectionConcurrencyPolicyJson.serializer(), it)
                }
    }

fun RememberedNetworkPolicyEntity.decodedSource(): RememberedNetworkPolicySource =
    RememberedNetworkPolicySource.fromStorageValue(source)

fun RememberedNetworkPolicyEntity.decodeSummary(
    json: Json =
        RipDpiJson,
): NetworkFingerprintSummary? =
    runCatching {
        json.decodeFromString(NetworkFingerprintSummary.serializer(), summaryJson)
    }.onFailure { Logger.w(it) { "Failed to decode network fingerprint summary" } }.getOrNull()

fun RememberedNetworkPolicyEntity.toPolicyJson(json: Json = RipDpiJson): RememberedNetworkPolicyJson? {
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
                }.onFailure { Logger.w(it) { "Failed to decode VPN DNS policy" } }.getOrNull()
            }
    val connectionConcurrencyPolicy =
        connectionConcurrencyPolicyJson?.takeIf(String::isNotBlank)?.let { payload ->
            runCatching {
                json.decodeFromString(RememberedConnectionConcurrencyPolicyJson.serializer(), payload)
            }.onFailure { Logger.w(it) { "Failed to decode connection concurrency policy" } }.getOrNull()
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
        connectionConcurrencyPolicy = connectionConcurrencyPolicy,
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
