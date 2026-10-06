package com.poyka.ripdpi.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Serializes group persistence with profile journals; a reset scope consumes its existing receipt. */
internal class ProfileUtilityCatalogTransactions(
    private val mutex: Mutex,
    private val authority: PauseIntentAuthority,
    private val catalog: ProfileUtilityCatalogPublisher,
    private val mutations: ProfileMutationGenerationPublisher,
    private val recover: suspend () -> Unit,
) {
    suspend fun <T> mutate(
        preparation: ProfileMutationPreparation,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            recover()
            val outcome =
                authority.invalidateForMutation(
                    preparation.origin,
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    preparation.expectedPauseAuthority,
                )
            check(outcome != ProfileMutationOutcome.Superseded) { "Catalog mutation was superseded" }
            write(block)
        }

    suspend fun <T> replace(
        receipt: DurableCommandReceipt,
        block: suspend () -> T,
    ): T {
        val nested = coroutineContext[ResetContext]
        return if (nested?.owner === this && nested.receipt === receipt && nested.active) {
            check(authority.isCurrent(receipt)) { "Catalog reset was superseded" }
            write(block)
        } else {
            mutex.withLock {
                recover()
                check(authority.isCurrent(receipt)) { "Catalog replacement was superseded" }
                write(block)
            }
        }
    }

    suspend fun withinReset(
        receipt: DurableCommandReceipt,
        block: suspend () -> Unit,
    ) {
        val reset = ResetContext(this, receipt)
        try {
            withContext(reset) { block() }
        } finally {
            reset.active = false
        }
    }

    private suspend fun <T> write(block: suspend () -> T): T {
        catalog.invalidate()
        val result = block()
        catalog.publish()
        mutations.completed()
        return result
    }

    private class ResetContext(
        val owner: ProfileUtilityCatalogTransactions,
        val receipt: DurableCommandReceipt,
    ) : AbstractCoroutineContextElement(Key) {
        var active = true

        companion object Key : CoroutineContext.Key<ResetContext>
    }
}
