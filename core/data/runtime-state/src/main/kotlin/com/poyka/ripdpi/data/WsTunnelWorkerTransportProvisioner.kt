package com.poyka.ripdpi.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsTunnelWorkerTransportProvisioner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WsTunnelWorkerCredentialStore,
    ) {
        private val mutex = Mutex()

        @Suppress("TooGenericExceptionCaught")
        suspend fun provision(
            workerUrl: String,
            credentialRef: String,
            bearer: SecretString,
        ): CloudflareWorkerTransportConfig =
            mutex.withLock {
                val config = CloudflareWorkerTransportConfig(workerUrl.trim(), credentialRef.trim(), bearer)
                val previousSettings = appSettingsRepository.snapshot()
                val previousSecret = credentialStore.load(config.credentialRef)
                val previousRef = previousSettings.wsTunnelWorkerCredentialRef.trim()
                val previousRefSecret =
                    previousRef
                        .takeIf { it.isNotEmpty() && it != config.credentialRef }
                        ?.let { credentialStore.load(it) }

                credentialStore.save(config.credentialRef, config.authBearer.value)
                try {
                    if (previousRef.isNotEmpty() && previousRef != config.credentialRef) {
                        credentialStore.clear(previousRef)
                    }
                    appSettingsRepository.update {
                        setWsTunnelWorkerUrl(config.workerUrl)
                        setWsTunnelWorkerCredentialRef(config.credentialRef)
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        restoreCredential(config.credentialRef, previousSecret)
                        if (previousRef.isNotEmpty() && previousRef != config.credentialRef) {
                            restoreCredential(previousRef, previousRefSecret)
                        }
                    }
                    throw failure
                }
                config
            }

        @Suppress("TooGenericExceptionCaught")
        suspend fun clear() {
            mutex.withLock {
                val previousRef = appSettingsRepository.snapshot().wsTunnelWorkerCredentialRef.trim()
                val previousSecret = previousRef.takeIf(String::isNotEmpty)?.let { credentialStore.load(it) }
                if (previousRef.isNotEmpty()) credentialStore.clear(previousRef)
                try {
                    appSettingsRepository.update {
                        setWsTunnelWorkerUrl("")
                        setWsTunnelWorkerCredentialRef("")
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        if (previousRef.isNotEmpty()) restoreCredential(previousRef, previousSecret)
                    }
                    throw failure
                }
            }
        }

        private suspend fun restoreCredential(
            credentialRef: String,
            previousSecret: String?,
        ) {
            if (previousSecret == null) {
                credentialStore.clear(credentialRef)
            } else {
                credentialStore.save(credentialRef, previousSecret)
            }
        }
    }
