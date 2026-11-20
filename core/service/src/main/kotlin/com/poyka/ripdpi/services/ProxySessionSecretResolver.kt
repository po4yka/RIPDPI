package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.resolveTransport
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxySessionSecretResolver
    @Inject
    constructor(
        private val credentialStore: WsTunnelWorkerCredentialStore,
    ) {
        suspend fun currentBearer(settings: AppSettings): String? =
            credentialStore.resolveTransport(settings)?.authBearer?.value
    }
