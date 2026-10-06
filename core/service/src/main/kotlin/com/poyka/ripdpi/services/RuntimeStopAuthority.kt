package com.poyka.ripdpi.services

/** Captured queued-command ownership, rechecked under the native lifecycle mutex. */
internal class RuntimeStopAuthority(
    val guard: RuntimeStopGuard,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RuntimeStopAuthority>
}
