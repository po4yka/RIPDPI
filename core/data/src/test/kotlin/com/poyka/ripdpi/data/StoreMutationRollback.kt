package com.poyka.ripdpi.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs every rollback action without cancellation and rethrows the original store failure. */
suspend fun Throwable.rollbackStoreMutation(vararg actions: suspend () -> Unit): Nothing {
    withContext(NonCancellable) {
        actions.forEach { action ->
            runCatching { action() }
                .exceptionOrNull()
                ?.let(this@rollbackStoreMutation::addSuppressed)
        }
    }
    throw this
}
