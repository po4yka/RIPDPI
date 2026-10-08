package com.poyka.ripdpi.integration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest

/** Wait for startup callbacks to settle. The caller owns the readiness deadline. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T : Any> awaitStableNetworkEpoch(
    changes: Flow<Long>,
    capture: () -> T?,
    quietMillis: Long = 500L,
): T {
    require(quietMillis > 0)
    return changes
        .transformLatest {
            val token = capture() ?: return@transformLatest
            delay(quietMillis)
            // Compare the full event token: A -> B -> A must also restart the wait.
            if (token == capture()) emit(token)
        }.first()
}
