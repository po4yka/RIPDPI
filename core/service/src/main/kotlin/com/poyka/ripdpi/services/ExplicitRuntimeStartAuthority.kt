package com.poyka.ripdpi.services

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the accepted user-intent fence through framework dispatch and suspended readiness. */
internal class ExplicitRuntimeStartAuthority(
    val guard: ExplicitUserStartGuard,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExplicitRuntimeStartAuthority>
}
