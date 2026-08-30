package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore

internal object EmptyWsTunnelWorkerCredentialStore : WsTunnelWorkerCredentialStore {
    override suspend fun load(credentialRef: String): String? = null

    override suspend fun save(
        credentialRef: String,
        bearer: String,
    ) = Unit

    override suspend fun clear(credentialRef: String) = Unit

    override suspend fun clearAll() = Unit
}
