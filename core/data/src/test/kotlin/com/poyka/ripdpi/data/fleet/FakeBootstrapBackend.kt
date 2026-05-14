package com.poyka.ripdpi.data.fleet

/**
 * A faked, in-memory stand-in for the deployer's `/bootstrap/<token>` HTTP
 * endpoint. The bootstrap delivery plane is one-shot: a token serves its
 * payload exactly once, then is consumed.
 *
 * No network, no Android — this is pure test infrastructure for the
 * `bootstrap-bundle` fleet-compat scenario.
 */
class FakeBootstrapBackend(
    token: String,
    payload: String,
) {
    private val store = HashMap<String, String>().apply { put(token, payload) }

    /**
     * Consumes [token]: returns its payload on the first call, `null` on every
     * call after that (the token is single-use).
     */
    fun consume(token: String): String? = store.remove(token)
}
