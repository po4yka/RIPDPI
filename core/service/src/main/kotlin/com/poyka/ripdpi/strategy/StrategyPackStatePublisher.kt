package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.StrategyPackRefreshFailureCode
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.acceptedSequenceOrNull
import com.poyka.ripdpi.data.resolveSelection

internal class StrategyPackStatePublisher(
    private val stateStore: StrategyPackStateStore,
    private val clock: StrategyPackClock,
) {
    fun publishSelectionForSnapshot(
        snapshot: StrategyPackSnapshot,
        key: StrategyPackRefreshKey,
        lastRefreshAttemptAtEpochMillis: Long? = snapshot.lastFetchedAtEpochMillis,
        lastRefreshError: String?,
        lastRefreshFailureCode: StrategyPackRefreshFailureCode? = null,
        lastRejectedSequence: Long? = null,
        cacheDegradationCode: ControlPlaneCacheDegradationCode? = null,
        cacheDegradationDetail: String? = null,
    ) {
        val selection =
            snapshot.catalog.resolveSelection(
                pinnedPackId = key.pinnedPackId,
                pinnedPackVersion = key.pinnedPackVersion,
            )
        stateStore.update(
            StrategyPackRuntimeState(
                snapshot = snapshot,
                selectedPackId = selection.pack?.id,
                selectedPackVersion = selection.pack?.version,
                tlsProfileSetId = selection.tlsProfileSet?.id,
                tlsProfileCatalogVersion = selection.tlsProfileSet?.catalogVersion,
                tlsProfileAllowedIds = selection.tlsProfileSet?.allowedProfileIds.orEmpty(),
                tlsRotationEnabled = selection.tlsProfileSet?.rotationEnabled == true,
                tlsProfileEchPolicy = selection.tlsProfileSet?.echPolicy,
                tlsProfileProxyModeNotice = selection.tlsProfileSet?.proxyModeNotice,
                tlsProfileAcceptanceCorpusRef = selection.tlsProfileSet?.acceptanceCorpusRef,
                morphPolicyId = selection.morphPolicy?.id,
                morphPolicy = selection.morphPolicy,
                transportModuleIds = selection.transportModules.map { it.id },
                featureFlags = selection.featureFlags.associate { it.id to it.enabled },
                lastResolvedAtEpochMillis = clock.nowEpochMillis(),
                lastRefreshAttemptAtEpochMillis = lastRefreshAttemptAtEpochMillis,
                lastRefreshError = lastRefreshError,
                lastRefreshFailureCode = lastRefreshFailureCode,
                lastAcceptedSequence = snapshot.acceptedSequenceOrNull(),
                lastRejectedSequence = lastRejectedSequence,
                cacheDegradationCode = cacheDegradationCode,
                cacheDegradationDetail = cacheDegradationDetail,
                refreshPolicy = key.refreshPolicy,
            ),
        )
    }
}
