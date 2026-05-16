package com.poyka.ripdpi.diagnostics.ech

/**
 * Dispatches HTTPS DNS record queries for ECH config retrieval.
 *
 * When a domain's [EchMode] is [EchMode.Enabled] or [EchMode.Opportunistic]
 * the [httpsRrQuery] is invoked so that ECH config bytes can be fetched.
 * When the mode is [EchMode.Disabled] no query is issued.
 */
class EchHttpsRrDispatcher(
    private val httpsRrQuery: suspend (String) -> Unit,
) {
    /**
     * Issues an HTTPS RR query for [domain] if [mode] is not [EchMode.Disabled].
     */
    suspend fun dispatch(
        domain: String,
        mode: EchMode,
    ) {
        if (mode != EchMode.Disabled) {
            httpsRrQuery(domain)
        }
    }

    /**
     * Dispatches queries for all domains in [domainModes] whose mode is not
     * [EchMode.Disabled]. Queries are issued sequentially; callers may wrap
     * in a coroutine scope for concurrency.
     */
    suspend fun dispatchAll(domainModes: Map<String, EchMode>) {
        for ((domain, mode) in domainModes) {
            dispatch(domain, mode)
        }
    }
}
