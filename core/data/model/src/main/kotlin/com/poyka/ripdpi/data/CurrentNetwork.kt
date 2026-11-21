package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce

/**
 * The scope key of the network the device is on: the provider's reading first, then each handover. Both come from
 * the same [NetworkFingerprintProvider.capture]. A moment without a network keeps the last known key, so a
 * handover gap does not read as a network change.
 */
fun currentNetworkScopeKey(
    handovers: NetworkHandoverMonitor,
    fingerprints: NetworkFingerprintProvider,
): Flow<String?> =
    handovers
        .observe()
        .map { handover -> handover.currentFingerprint?.scopeKey() }
        .onStart { emit(runCatching { fingerprints.capture() }.getOrNull()?.scopeKey()) }
        .runningReduce { last, next -> next ?: last }

/** A result measured under [sessionKey] belongs to the current network only when both keys are known and equal. */
fun isMeasuredOn(
    sessionKey: String?,
    currentKey: String?,
): Boolean = sessionKey != null && sessionKey == currentKey
